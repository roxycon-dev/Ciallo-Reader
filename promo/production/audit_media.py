"""Audit supplied user-footage without altering the originals.

Run from repository root with Python, OpenCV, Pillow, and ffmpeg/ffprobe available.
Produces timestamp-labelled contact sheets and machine-readable stream/blackdetect
metadata under promo/production. Human visual notes belong in promo/media-audit.md.
"""
from __future__ import annotations

import json
import math
import subprocess
import tempfile
from pathlib import Path

import cv2
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
FOOTAGE = ROOT / "user-footage"
OUT = ROOT / "production"
SHEETS = OUT / "contact-sheets"
FFMPEG = Path(r"C:\Users\GuanXingRen\Miniconda3\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe")
FFPROBE = Path(r"C:\Users\GuanXingRen\AppData\Local\Programs\TRAE SOLO CN\resources\app\bin\ffprobe.exe")
CELL_W = 320
LABEL_H = 36
SAMPLE_SECONDS = 1.0
FRAMES_PER_SHEET = 8


def run_json(args: list[str]) -> dict:
    raw = subprocess.check_output(args, text=True, encoding="utf-8", errors="replace")
    return json.loads(raw)


def font(size: int):
    for candidate in (r"C:\Windows\Fonts\arial.ttf", r"C:\Windows\Fonts\segoeui.ttf"):
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def blackdetect(path: Path) -> str:
    proc = subprocess.run(
        [str(FFMPEG), "-hide_banner", "-nostats", "-i", str(path), "-vf", "blackdetect=d=0.10:pix_th=0.10", "-an", "-f", "null", "NUL"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return "\n".join(line.strip() for line in proc.stdout.splitlines() if "black_start:" in line or "black_end:" in line or "black_duration:" in line)


def make_sheets(path: Path, duration: float) -> list[str]:
    # Decode sequentially with FFmpeg's fps filter. OpenCV's random seeks can fail
    # partway through some variable-frame-rate phone recordings.
    with tempfile.TemporaryDirectory(prefix=f"{path.stem}_", dir=OUT) as temp_name:
        temp = Path(temp_name)
        subprocess.run(
            [str(FFMPEG), "-hide_banner", "-loglevel", "error", "-i", str(path), "-vf", "fps=1", "-frames:v", str(math.ceil(duration)), str(temp / "frame_%03d.jpg")],
            check=True,
        )
        frames: list[tuple[float, Image.Image]] = []
        for index, frame_path in enumerate(sorted(temp.glob("frame_*.jpg"))):
            im = Image.open(frame_path).convert("RGB")
            height = round(CELL_W * im.height / im.width)
            frames.append((float(index), im.resize((CELL_W, height), Image.Resampling.LANCZOS)))
    if not frames:
        return []
    cell_h = frames[0][1].height + LABEL_H
    base = path.stem
    out_names: list[str] = []
    for batch_start in range(0, len(frames), FRAMES_PER_SHEET):
        batch = frames[batch_start:batch_start + FRAMES_PER_SHEET]
        cols = 2
        rows = math.ceil(len(batch) / cols)
        sheet = Image.new("RGB", (cols * CELL_W, rows * cell_h), (15, 18, 22))
        draw = ImageDraw.Draw(sheet)
        for i, (sec, im) in enumerate(batch):
            x, y = (i % cols) * CELL_W, (i // cols) * cell_h
            draw.rectangle((x, y, x + CELL_W - 1, y + LABEL_H - 1), fill=(30, 36, 43))
            draw.text((x + 8, y + 7), f"{base}  |  {sec:05.1f}s", fill=(238, 241, 244), font=font(18))
            sheet.paste(im, (x, y + LABEL_H))
        first = int(batch[0][0])
        last = int(batch[-1][0])
        name = f"{base}_{first:02d}-{last:02d}s.jpg"
        target = SHEETS / name
        sheet.save(target, quality=92, optimize=True)
        out_names.append(str(target.relative_to(ROOT)))
    return out_names


def main() -> None:
    if not FFMPEG.exists() or not FFPROBE.exists():
        raise SystemExit(f"Required tools missing: ffmpeg={FFMPEG.exists()} ffprobe={FFPROBE.exists()}")
    SHEETS.mkdir(parents=True, exist_ok=True)
    records = []
    for path in sorted(FOOTAGE.glob("*.mp4")):
        probe = run_json([str(FFPROBE), "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)])
        video = next(s for s in probe["streams"] if s.get("codec_type") == "video")
        audio = next((s for s in probe["streams"] if s.get("codec_type") == "audio"), None)
        duration = float(probe["format"].get("duration") or video.get("duration") or 0)
        rec = {
            "file": path.name,
            "bytes": path.stat().st_size,
            "duration_seconds": duration,
            "video": {key: video.get(key) for key in ("codec_name", "width", "height", "r_frame_rate", "avg_frame_rate", "nb_frames", "pix_fmt")},
            "audio": None if not audio else {key: audio.get(key) for key in ("codec_name", "sample_rate", "channels", "channel_layout")},
            "blackdetect": blackdetect(path),
            "contact_sheets": make_sheets(path, duration),
        }
        records.append(rec)
        print(f"{path.name}: {duration:.3f}s; sheets={len(rec['contact_sheets'])}; black={rec['blackdetect'] or 'none'}")
    dest = OUT / "media-audit.json"
    dest.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {dest.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
