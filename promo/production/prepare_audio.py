"""Generate an original quiet piano-like cue and stage traceable Mixkit SFX."""
from __future__ import annotations

import shutil
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SKILL_AUDIO = Path(r"C:\Users\GuanXingRen\.codex\skills\video-shotcraft\assets\audio")
OUT = ROOT / "production" / "remotion" / "public" / "audio"
RATE = 44_100
DURATION = 72
SFX = {
    "shimmer-sparkle-sweep.mp3": "sfx/light/shimmer-sparkle-sweep.mp3",
    "ui-select-click.mp3": "sfx/ui/ui-select-click.mp3",
    "ui-success-soft.mp3": "sfx/ui/ui-success-soft.mp3",
    "swoosh-quick.mp3": "sfx/transition/swoosh-quick.mp3",
    "glass-plate-slide.mp3": "sfx/glass/glass-plate-slide.mp3",
    "paper-page-turn.mp3": "sfx/paper/paper-page-turn.mp3",
    "transition-soft.mp3": "sfx/transition/transition-soft.mp3",
}


def add_piano_note(track: np.ndarray, at: float, freq: float, strength: float, decay: float) -> None:
    start = max(0, int(at * RATE))
    count = min(int(decay * RATE), track.size - start)
    if count <= 0:
        return
    t = np.arange(count, dtype=np.float32) / RATE
    envelope = (1.0 - np.exp(-t / 0.006)) * np.exp(-t / decay)
    tone = np.zeros(count, dtype=np.float32)
    for partial, amp in enumerate((1.0, 0.42, 0.22, 0.12, 0.065, 0.032), start=1):
        # Slight inharmonicity and a quiet second partial give the pluck a soft piano body.
        f = freq * partial * (1.0 + 0.00008 * partial * partial)
        tone += amp * np.sin(2 * np.pi * f * t + 0.11 * partial)
    track[start:start + count] += (tone * envelope * strength).astype(np.float32)


def make_ambient() -> None:
    samples = RATE * DURATION
    dry = np.zeros(samples, dtype=np.float32)
    bar = 60 / 72 * 4
    chords = [
        (82.41, [164.81, 196.00, 246.94, 293.66, 369.99]),  # Em9
        (65.41, [130.81, 164.81, 196.00, 246.94, 329.63]),  # Cmaj7
        (98.00, [196.00, 246.94, 293.66, 369.99, 440.00]),  # Gmaj9
        (73.42, [146.83, 164.81, 220.00, 293.66, 369.99]),  # Dsus2
    ]
    offsets = (0.12, 0.82, 1.58, 2.34, 2.84)
    for n in range(int(DURATION / bar) + 1):
        root, notes = chords[n % len(chords)]
        at = n * bar
        add_piano_note(dry, at, root, 0.22, 3.25)
        for note, offset in zip(notes, offsets):
            add_piano_note(dry, at + offset, note, 0.105, 2.5 if note < 300 else 2.0)

    # Short, quiet early reflections create space while leaving the notes exposed.
    delay_a, delay_b, delay_c = int(0.085 * RATE), int(0.17 * RATE), int(0.29 * RATE)
    wet = dry * 0.78
    wet[delay_a:] += dry[:-delay_a] * 0.15
    wet[delay_b:] += dry[:-delay_b] * 0.09
    wet[delay_c:] += dry[:-delay_c] * 0.055
    fade = np.ones(samples, dtype=np.float32)
    fade[:int(1.6 * RATE)] = np.linspace(0, 1, int(1.6 * RATE), dtype=np.float32)
    fade[-int(3 * RATE):] = np.linspace(1, 0, int(3 * RATE), dtype=np.float32)
    wet *= fade
    peak = max(float(np.max(np.abs(wet))), 1e-6)
    wet *= 0.19 / peak
    stereo = np.column_stack((wet, wet * 0.94))
    pcm = np.clip(stereo, -1.0, 1.0)
    pcm = (pcm * 32767).astype(np.int16)
    with wave.open(str(OUT / "ambient-piano.wav"), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(pcm.tobytes())
    print(f"Original ambient cue: {DURATION}s, 44.1kHz stereo -> {OUT / 'ambient-piano.wav'}")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    make_ambient()
    for target_name, source_rel in SFX.items():
        shutil.copy2(SKILL_AUDIO / source_rel, OUT / target_name)
        print(f"{source_rel} -> {OUT / target_name}")


if __name__ == "__main__":
    main()
