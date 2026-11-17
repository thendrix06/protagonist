"""Adds a caption bar to each RecordingTest frame and writes an ffmpeg concat list using real frame timing."""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

d = sys.argv[1]
rows = [line.rstrip("\n").split("\t", 2) for line in open(os.path.join(d, "manifest.tsv")) if line.strip()]
out = os.path.join(d, "captioned")
os.makedirs(out, exist_ok=True)
font = ImageFont.load_default(size=22)
concat = []
for i, (name, ms, caption) in enumerate(rows):
    img = Image.open(os.path.join(d, name)).convert("RGB")
    bar = 44
    canvas = Image.new("RGB", (img.width, img.height + bar), (0, 0, 0))
    canvas.paste(img, (0, bar))
    ImageDraw.Draw(canvas).text((12, 10), caption, fill=(0, 229, 255), font=font)
    path = os.path.abspath(os.path.join(out, name))
    canvas.save(path)
    nxt = int(rows[i + 1][1]) if i + 1 < len(rows) else int(ms) + 1500
    concat.append(f"file '{path}'\nduration {max(nxt - int(ms), 1) / 1000:.3f}\n")
concat.append(f"file '{path}'\n")  # concat demuxer needs the last file repeated
open(os.path.join(d, "concat.txt"), "w").write("".join(concat))
