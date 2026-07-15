"""
Builds a balanced training dataset for the Lazy Reader keyword-spotting model
from the raw, fully-extracted Google Speech Commands v0.02 corpus.

Classes:
  - go, backward, stop: sampled directly from their word folders.
  - background: a mix of 1-second chopped clips from the corpus's long
    ambient noise recordings (_background_noise_/) and a sample of other
    spoken words (not go/backward/stop), so the model learns to reject
    both silence/noise and unrelated speech.
"""
import random
import shutil
import subprocess
from pathlib import Path

random.seed(0)

RAW = Path("/work/speech_commands_raw/extracted")
OUT = Path("/work/dataset")

COMMAND_WORDS = ["go", "backward", "stop"]
EXCLUDED_FROM_UNKNOWN = set(COMMAND_WORDS) | {"_background_noise_"}

TARGET_PER_CLASS = 1650  # ~= backward's natural count (1664), keeps classes balanced


def reset_out():
    if OUT.exists():
        shutil.rmtree(OUT)
    for name in COMMAND_WORDS + ["background"]:
        (OUT / name).mkdir(parents=True, exist_ok=True)


def sample_command_words():
    for word in COMMAND_WORDS:
        clips = sorted((RAW / word).glob("*.wav"))
        chosen = random.sample(clips, min(TARGET_PER_CLASS, len(clips)))
        for clip in chosen:
            shutil.copy(clip, OUT / word / clip.name)
        print(f"{word}: copied {len(chosen)} clips")


def chop_background_noise(seconds_target):
    noise_dir = RAW / "_background_noise_"
    noise_files = sorted(noise_dir.glob("*.wav"))
    clip_count = 0
    for wav in noise_files:
        out_pattern = str(OUT / "background" / f"noise_{wav.stem}_%03d.wav")
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-i", str(wav),
                "-f", "segment", "-segment_time", "1",
                "-ar", "16000", "-ac", "1",
                out_pattern,
            ],
            check=True,
        )
    clip_count = len(list((OUT / "background").glob("noise_*.wav")))
    # Cap noise clips to roughly half the background budget; the rest comes from
    # other-word speech so the negative class isn't pure silence/hum.
    cap = seconds_target // 2
    all_noise_clips = sorted((OUT / "background").glob("noise_*.wav"))
    if len(all_noise_clips) > cap:
        for clip in random.sample(all_noise_clips, len(all_noise_clips) - cap):
            clip.unlink()
    print(f"background (noise): kept {min(clip_count, cap)} clips")


def sample_other_words_as_unknown(target_total):
    other_word_dirs = [
        d for d in RAW.iterdir()
        if d.is_dir() and d.name not in EXCLUDED_FROM_UNKNOWN
    ]
    per_word = max(1, target_total // len(other_word_dirs))
    copied = 0
    for d in other_word_dirs:
        clips = sorted(d.glob("*.wav"))
        chosen = random.sample(clips, min(per_word, len(clips)))
        for clip in chosen:
            dest = OUT / "background" / f"word_{d.name}_{clip.name}"
            shutil.copy(clip, dest)
            copied += 1
    print(f"background (other words): copied {copied} clips from {len(other_word_dirs)} words")


def main():
    reset_out()
    sample_command_words()
    chop_background_noise(seconds_target=TARGET_PER_CLASS)
    existing_noise = len(list((OUT / "background").glob("noise_*.wav")))
    sample_other_words_as_unknown(target_total=TARGET_PER_CLASS - existing_noise)

    print()
    for name in COMMAND_WORDS + ["background"]:
        n = len(list((OUT / name).glob("*.wav")))
        print(f"FINAL {name}: {n} clips")


if __name__ == "__main__":
    main()
