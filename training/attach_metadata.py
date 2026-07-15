"""
Attaches audio-classifier TFLite metadata (sample rate, channels, labels) to the
model exported by train.py, so MediaPipe tasks-audio can load it on Android.

Must run as its own process, AFTER train.py. Imports go directly through
tensorflow_lite_support: the top-level `tflite_support` package's __init__
imports its task.vision pybind modules, which clash with TensorFlow internals
(pybind "StatusCode already defined") even in a fresh process.
"""
from pathlib import Path

from tensorflow_lite_support.metadata.python.metadata_writers import (
    audio_classifier,
    writer_utils,
)

EXPORT_DIR = Path("/work/exported_model")
SAMPLE_RATE = 16000

tflite_path = EXPORT_DIR / "voice_commands.tflite"
label_path = EXPORT_DIR / "labels.txt"

writer = audio_classifier.MetadataWriter.create_for_inference(
    writer_utils.load_file(str(tflite_path)),
    sample_rate=SAMPLE_RATE,
    channels=1,
    label_file_paths=[str(label_path)],
)
writer_utils.save_file(writer.populate(), str(tflite_path))
print(f"Metadata attached to {tflite_path}")
print(writer.get_metadata_json())
