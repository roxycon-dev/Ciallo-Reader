"""Build a timestamp-labelled contact sheet from v2 preview keyframes."""
from __future__ import annotations

from math import ceil
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
SHEETS = ROOT / "contact-sheets"
files = sorted(SHEETS.glob("v2-key-*.png"))
cell_w, image_h, label_h = 480, 270, 30
cols = 4
rows = ceil(len(files) / cols)
sheet = Image.new("RGB", (cols * cell_w, rows * (image_h + label_h)), "#080b0f")
draw = ImageDraw.Draw(sheet)
for i, path in enumerate(files):
    with Image.open(path) as frame:
        frame = frame.convert("RGB").resize((cell_w, image_h), Image.Resampling.LANCZOS)
        x, y = (i % cols) * cell_w, (i // cols) * (image_h + label_h)
        sheet.paste(frame, (x, y))
        draw.text((x + 12, y + image_h + 6), path.stem.replace("v2-key-", ""), fill="#dce7e2")
sheet.save(SHEETS / "v2-preview-contact.png", optimize=True)
print(f"Wrote {SHEETS / 'v2-preview-contact.png'} from {len(files)} keyframes")
