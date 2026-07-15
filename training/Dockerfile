FROM python:3.10-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --default-timeout=120 --upgrade pip \
    && pip install --default-timeout=120 --retries 20 mediapipe-model-maker

# mediapipe-model-maker's audio_classifier module turned out to not exist in
# the published package (or upstream source) — training is done instead by
# fine-tuning a head on YAMNet embeddings (tensorflow-hub) and stamping the
# exported .tflite with audio-classifier metadata (tflite-support) so
# MediaPipe tasks-audio can consume it on-device.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --default-timeout=120 --retries 20 tensorflow-hub tflite-support

WORKDIR /work