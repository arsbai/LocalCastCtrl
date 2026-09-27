"""Generate pre-Android-8 launcher PNGs matching the adaptive vector icon."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT.parent / "icon-preview.png"
SCALE = 8
CANVAS = 108 * SCALE


def sc(value):
    return round(value * SCALE)


im = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
draw = ImageDraw.Draw(im)
draw.rounded_rectangle((0, 0, CANVAS - 1, CANVAS - 1), radius=sc(24), fill="#6750A4")
for x0, x1 in ((20, 43), (65, 88)):
    draw.rounded_rectangle(
        (sc(x0), sc(22), sc(x1), sc(86)),
        radius=sc(4),
        outline="#FFFFFF",
        width=sc(3.6),
    )
    draw.rounded_rectangle(
        (sc(x0 + 7), sc(28), sc(x1 - 7), sc(30)),
        radius=sc(1),
        fill="#FFFFFF",
    )
    cx = (x0 + x1) / 2
    draw.ellipse((sc(cx - 2.5), sc(76.5), sc(cx + 2.5), sc(81.5)), fill="#FFFFFF")

for points in (
    [(48, 39), (58, 39), (58, 35), (65, 43), (58, 51), (58, 47), (48, 47)],
    [(60, 61), (50, 61), (50, 57), (43, 65), (50, 73), (50, 69), (60, 69)],
):
    draw.polygon([(sc(x), sc(y)) for x, y in points], fill="#D0BCFF")

for density, size in (
    ("mdpi", 48),
    ("hdpi", 72),
    ("xhdpi", 96),
    ("xxhdpi", 144),
    ("xxxhdpi", 192),
):
    folder = ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    im.resize((size, size), Image.Resampling.LANCZOS).save(folder / "ic_launcher.png")

im.resize((512, 512), Image.Resampling.LANCZOS).save(OUT)
print(OUT)
