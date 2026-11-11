"""Create small, silent 30fps proxies from the user-supplied recordings."""
from __future__ import annotations

import subprocess
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
FOOTAGE = ROOT / "user-footage"
PUBLIC = ROOT / "production" / "remotion" / "public" / "media"
FFMPEG = Path(r"C:\Users\GuanXingRen\Miniconda3\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe")

SOURCES = {
    "search.mp4": "02_search.mp4",
    "detail.mp4": "03_detail_download.mp4",
    "shelf.mp4": "04_shelf.mp4",
    "comic-open.mp4": "05_comic_open.mp4",
    "comic-read.mp4": "06_comic_read.mp4",
    "comic-glass.mp4": "07_comic_glass.mp4",
    "novel-turn.mp4": "08_novel_turn.mp4",
    "roxy.mp4": "10_roxy.mp4",
    "glass-ui.mp4": "11_glass_ui.mp4",
}


def main() -> None:
    PUBLIC.mkdir(parents=True, exist_ok=True)
    # Five-second opening proxy: the supplied screen recording is 11.24s long;
    # hold its final clean bookshelf frame to complete the 5s shot.
    subprocess.run(
        [str(FFMPEG), "-y", "-hide_banner", "-loglevel", "warning", "-ss", "8.2", "-i", str(FOOTAGE / "01_home.mp4"), "-vf", "fps=30,scale=540:-2,tpad=stop_mode=clone:stop_duration=2", "-t", "5", "-an", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "21", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(PUBLIC / "home-hero.mp4")],
        check=True,
    )
    print("01_home.mp4@8.2s -> media/home-hero.mp4 (5s, final frame held)")

    for target_name, source_name in SOURCES.items():
        src, dst = FOOTAGE / source_name, PUBLIC / target_name
        subprocess.run(
            [str(FFMPEG), "-y", "-hide_banner", "-loglevel", "warning", "-i", str(src), "-vf", "fps=30,scale=540:-2", "-an", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "21", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(dst)],
            check=True,
        )
        print(f"{source_name} -> {dst.relative_to(ROOT)}")

    # Keep the downloader drawer and the safe page-turn gesture. Clone the last
    # genuine frame for the remainder of each shot so later text is never used.
    segments = [
        ("03_detail_download.mp4", 18.0, 3.23, 1.77, "detail.mp4"),
        ("08_novel_turn.mp4", 2.6, 2.6, 6.4, "novel-turn.mp4"),
    ]
    for source_name, start, keep, pad, target_name in segments:
        subprocess.run(
            [str(FFMPEG), "-y", "-hide_banner", "-loglevel", "warning", "-ss", str(start), "-i", str(FOOTAGE / source_name), "-vf", f"fps=30,scale=540:-2,trim=duration={keep},setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration={pad}", "-t", str(keep + pad), "-an", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "21", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(PUBLIC / target_name)],
            check=True,
        )
        print(f"{source_name}@{start:.2f}s for {keep:.2f}s -> {target_name}, final frame held to {keep + pad:.2f}s")

    # Remove Android system status/navigation bars from this genuine app screenshot.
    screenshot = ROOT / "captures" / "real-device" / "roxy-onboarding.png"
    with Image.open(screenshot) as im:
        w, h = im.size
        cropped = im.crop((0, 135, w, h - 150))
        cropped.save(PUBLIC / "roxy-onboarding.png", optimize=True)
    mascot = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "mascot_reading.webp"
    shutil.copy2(mascot, PUBLIC / "mascot_reading.webp")
    print(f"{mascot.relative_to(ROOT)} -> {PUBLIC.relative_to(ROOT) / mascot.name}")

    stills = {
        "search-card.png": ("search.mp4", 0.6),
        "shelf-card.png": ("shelf.mp4", 2.2),
        "comic-card.png": ("comic-read.mp4", 2.2),
        "glass-card.png": ("glass-ui.mp4", 17.0),
    }
    for name, (video_name, at_seconds) in stills.items():
        subprocess.run(
            [str(FFMPEG), "-y", "-hide_banner", "-loglevel", "error", "-ss", str(at_seconds), "-i", str(PUBLIC / video_name), "-frames:v", "1", str(PUBLIC / name)],
            check=True,
        )
        print(f"{video_name}@{at_seconds:.1f}s -> {name}")


if __name__ == "__main__":
    main()
