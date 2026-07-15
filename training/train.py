"""
Trains the Lazy Reader keyword-spotting model (go / backward / stop / background)
as a small CNN over log-mel spectrograms, trained end-to-end, with the
spectrogram frontend INSIDE the model graph using only TFLite-builtin-
compatible ops (STFT->RFFT, mel matmul, log). Exports waveform-in ->
probabilities-out .tflite for MediaPipe tasks-audio.

Why not transfer learning?
  - mediapipe-model-maker's audio_classifier: documented but doesn't exist in
    the published package.
  - YAMNet embeddings: only ~61% test accuracy (audio-event model; lumps all
    speech together, embeddings carry little word-level information).
  - speech_embedding (TF Hub): 93% test accuracy, but its graph contains TF1
    TensorArrayV3 ops (map_fn over batch) that TFLite builtins can't express,
    and MediaPipe tasks-audio can't run flex ops on-device.
An end-to-end CNN on log-mel features is the standard Speech Commands
architecture, uses only convertible ops, and allows waveform-level data
augmentation (random time shift + gain) during training.

Metadata is attached by attach_metadata.py, run as a SEPARATE process —
tflite_support and tensorflow cannot be imported into the same process here
(pybind "StatusCode already defined" clash).

Run inside the lazyreader-voice-training container with training/data at /work:
  python /work/train.py && python /work/attach_metadata.py
"""
import random
from pathlib import Path

import numpy as np
import tensorflow as tf

DATA_DIR = Path("/work/dataset")
EXPORT_DIR = Path("/work/exported_model")

# Alphabetical; must match the model's output order and the exported label file.
CLASSES = ["background", "backward", "go", "stop"]

SAMPLE_RATE = 16000
# Fixed 0.975s @ 16kHz input so the exported model has the fixed tensor size
# MediaPipe's AudioClassifier expects.
NUM_SAMPLES = 15600

# Log-mel frontend: 25ms window / 10ms hop -> 96 frames x 40 mel bins.
FRAME_LENGTH = 400
FRAME_STEP = 160
FFT_LENGTH = 512
MEL_BINS = 40
MEL_LOW_HZ = 20.0
MEL_HIGH_HZ = 7600.0

MAX_SHIFT = 1600  # augmentation: random time shift up to +/-100ms

SEED = 0
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)


def load_clip(path: Path) -> np.ndarray:
    audio = tf.io.read_file(str(path))
    waveform, sr = tf.audio.decode_wav(audio, desired_channels=1)
    sr = int(sr)
    if sr != SAMPLE_RATE:
        raise ValueError(f"{path} has sample rate {sr}, expected {SAMPLE_RATE}")
    waveform = tf.squeeze(waveform, axis=-1)
    n = tf.shape(waveform)[0]
    waveform = tf.cond(
        n >= NUM_SAMPLES,
        lambda: waveform[:NUM_SAMPLES],
        lambda: tf.pad(waveform, [[0, NUM_SAMPLES - n]]),
    )
    return waveform.numpy()


def split_files():
    """Per-class shuffled 80/10/10 split -> (train, val, test) lists of (path, label_idx)."""
    splits = {"train": [], "val": [], "test": []}
    for label_idx, cls in enumerate(CLASSES):
        files = sorted((DATA_DIR / cls).glob("*.wav"))
        random.shuffle(files)
        n = len(files)
        n_train, n_val = int(n * 0.8), int(n * 0.1)
        splits["train"] += [(f, label_idx) for f in files[:n_train]]
        splits["val"] += [(f, label_idx) for f in files[n_train:n_train + n_val]]
        splits["test"] += [(f, label_idx) for f in files[n_train + n_val:]]
    for part in splits.values():
        random.shuffle(part)
    return splits["train"], splits["val"], splits["test"]


def load_split(items, name):
    xs = np.stack([load_clip(p) for p, _ in items])
    ys = np.array([label for _, label in items])
    print(f"  loaded {name}: {xs.shape}")
    return xs, ys


def augment(waveform, label):
    """Random time shift (pad+crop, +/-100ms) and random gain (0.8-1.2x)."""
    padded = tf.pad(waveform, [[MAX_SHIFT, MAX_SHIFT]])
    start = tf.random.uniform([], 0, 2 * MAX_SHIFT + 1, dtype=tf.int32)
    shifted = padded[start:start + NUM_SAMPLES]
    gain = tf.random.uniform([], 0.8, 1.2)
    return shifted * gain, label


def log_mel_layer():
    """Waveform [batch, NUM_SAMPLES] -> log-mel [batch, frames, MEL_BINS, 1].

    Only TFLite-builtin-expressible ops: frame/RFFT (via tf.signal.stft),
    ComplexAbs, MatMul with a constant mel matrix, Log.
    """
    mel_matrix = tf.signal.linear_to_mel_weight_matrix(
        num_mel_bins=MEL_BINS,
        num_spectrogram_bins=FFT_LENGTH // 2 + 1,
        sample_rate=SAMPLE_RATE,
        lower_edge_hertz=MEL_LOW_HZ,
        upper_edge_hertz=MEL_HIGH_HZ,
    )

    def fn(waveform):
        stft = tf.signal.stft(
            waveform,
            frame_length=FRAME_LENGTH,
            frame_step=FRAME_STEP,
            fft_length=FFT_LENGTH,
        )
        power = tf.square(tf.abs(stft))
        mel = tf.matmul(power, mel_matrix)
        log_mel = tf.math.log(mel + 1e-6)
        # tf.expand_dims, not [..., tf.newaxis]: the latter becomes a
        # StridedSlice with new_axis_mask, which TFLite cannot legalize.
        return tf.expand_dims(log_mel, -1)

    return tf.keras.layers.Lambda(fn, name="log_mel")


def build_model():
    inp = tf.keras.layers.Input(shape=(NUM_SAMPLES,), dtype=tf.float32, name="audio")
    x = log_mel_layer()(inp)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.Conv2D(32, 3, strides=2, padding="same", activation="relu")(x)
    x = tf.keras.layers.Conv2D(64, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.MaxPooling2D(2)(x)
    x = tf.keras.layers.Conv2D(64, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.MaxPooling2D(2)(x)
    x = tf.keras.layers.Conv2D(128, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    x = tf.keras.layers.Dropout(0.3)(x)
    x = tf.keras.layers.Dense(128, activation="relu")(x)
    x = tf.keras.layers.Dropout(0.3)(x)
    out = tf.keras.layers.Dense(len(CLASSES), name="logits")(x)
    return tf.keras.Model(inputs=inp, outputs=out)


def per_class_accuracy(model, x_test, y_test):
    probs = model.predict(x_test, verbose=0)
    preds = probs.argmax(axis=1)
    for idx, cls in enumerate(CLASSES):
        mask = y_test == idx
        acc = (preds[mask] == idx).mean()
        print(f"  {cls}: {acc:.4f} ({mask.sum()} clips)")


def main():
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)

    train_items, val_items, test_items = split_files()
    print(f"Split sizes: train={len(train_items)} val={len(val_items)} test={len(test_items)}")

    print("Loading waveforms...")
    x_train, y_train = load_split(train_items, "train")
    x_val, y_val = load_split(val_items, "val")
    x_test, y_test = load_split(test_items, "test")

    train_ds = (
        tf.data.Dataset.from_tensor_slices((x_train, y_train))
        .shuffle(len(x_train), seed=SEED)
        .map(augment, num_parallel_calls=tf.data.AUTOTUNE)
        .batch(64)
        .prefetch(tf.data.AUTOTUNE)
    )
    val_ds = tf.data.Dataset.from_tensor_slices((x_val, y_val)).batch(64)

    model = build_model()
    model.summary()
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=["accuracy"],
    )
    model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=60,
        callbacks=[
            tf.keras.callbacks.EarlyStopping(
                patience=10, restore_best_weights=True, monitor="val_accuracy"
            ),
            tf.keras.callbacks.ReduceLROnPlateau(
                patience=4, factor=0.5, monitor="val_loss"
            ),
        ],
    )

    loss, acc = model.evaluate(x_test, y_test, verbose=0)
    print(f"TEST loss={loss:.4f} accuracy={acc:.4f}")
    print("Per-class test accuracy:")
    per_class_accuracy(model, x_test, y_test)

    print("Building export model (adding softmax)...")
    probs = tf.keras.layers.Softmax(name="probabilities")(model.outputs[0])
    export_model = tf.keras.Model(inputs=model.inputs, outputs=probs)

    converter = tf.lite.TFLiteConverter.from_keras_model(export_model)
    tflite_bytes = converter.convert()
    tflite_path = EXPORT_DIR / "voice_commands.tflite"
    tflite_path.write_bytes(tflite_bytes)
    print(f"Wrote {tflite_path} ({len(tflite_bytes) / 1e6:.2f} MB)")

    label_path = EXPORT_DIR / "labels.txt"
    label_path.write_text("\n".join(CLASSES) + "\n")
    print("Wrote labels.txt — now run attach_metadata.py in a fresh process.")


if __name__ == "__main__":
    main()
