"""Prepare genuine, uncensored screen crops for the v2 Remotion cut."""
from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FOOTAGE = ROOT / "user-footage"
PUBLIC = ROOT / "production" / "remotion" / "public" / "media"
FFMPEG = Path(r"C:\Users\GuanXingRen\Miniconda3\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe")

HIRES_SOURCES = {
    "shelf-hires.mp4": "04_shelf.mp4",
    "comic-open-hires.mp4": "05_comic_open.mp4",
    "comic-glass-hires.mp4": "07_comic_glass.mp4",
    "glass-ui-hires.mp4": "11_glass_ui.mp4",
}


def crop_clip(source: str, output: str, start: float, duration: float, crop: tuple[int, int, int, int]) -> None:
    x, y, w, h = crop
    vf = f"fps=30,crop={w}:{h}:{x}:{y}"
    subprocess.run([
        str(FFMPEG), "-y", "-hide_banner", "-loglevel", "error", "-ss", str(start),
        "-i", str(FOOTAGE / source), "-t", str(duration), "-vf", vf, "-an",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "21", "-pix_fmt", "yuv420p",
        "-movflags", "+faststart", str(PUBLIC / output),
    ], check=True)


def main() -> None:
    PUBLIC.mkdir(parents=True, exist_ok=True)
    # Final-render sources: preserve the original 1224px screen width and
    # convert only to a constant 30fps stream. The old 540px proxies remain
    # available for the earlier review render.
    for target, source in HIRES_SOURCES.items():
        subprocess.run([
            str(FFMPEG), "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(FOOTAGE / source), "-vf", "fps=30", "-an",
            "-c:v", "libx264", "-preset", "fast", "-crf", "18",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(PUBLIC / target),
        ], check=True)
    # Pure screen crops. They preserve the original pixels and contain no masks,
    # blur, mosaic, overlays, or replacement UI.
    # Full search row, with the real category label, text field and button intact.
    # The input progresses from placeholder to typed query during this clean window.
    crop_clip("02_search.mp4", "search-field.mp4", 1.0, 2.8, (0, 230, 1224, 215))
    crop_clip("03_detail_download.mp4", "download-panel.mp4", 18.0, 3.2, (0, 2020, 1224, 680))
    crop_clip("09_novel_settings.mp4", "novel-panel.mp4", 8.0, 6.0, (0, 1740, 1224, 920))
    # Clean comic page windows from the real reader recording. The source's
    # 4–7s transition contains a visibly broken loading animation, so use the
    # clear early page and a later, genuinely different page instead.
    crop_clip("06_comic_read.mp4", "comic-page-early.mp4", 1.2, 1.8, (0, 410, 1224, 1940))
    crop_clip("06_comic_read.mp4", "comic-page-later.mp4", 8.9, 2.8, (0, 410, 1224, 1940))
    subprocess.run([
        str(FFMPEG), "-y", "-hide_banner", "-loglevel", "error", "-ss", "2.2",
        "-i", str(FOOTAGE / "06_comic_read.mp4"), "-frames:v", "1",
        str(PUBLIC / "comic-climax-hires.png"),
    ], check=True)
    print("Prepared uncensored source crops and a high-resolution still from genuine footage.")


if __name__ == "__main__":
    main()
