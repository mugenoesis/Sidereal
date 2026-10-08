#!/usr/bin/env python3
"""Builds the Play Store icon and feature graphic from the app's own icon (no screenshots, so nothing personal is in them).

    python3 store/make_graphics.py

Needs Pillow. Outputs store/graphics/icon-512.png and store/graphics/feature-graphic-1024x500.png.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "store" / "graphics"
FOREGROUND = ROOT / "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"
BOLD = "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
REGULAR = "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"


def icon():
    # The launcher foreground already carries the sky and horizon, so it scales up to the 512 px store icon as it is.
    Image.open(FOREGROUND).convert("RGB").resize((512, 512), Image.LANCZOS).save(OUT / "icon-512.png")


def feature_graphic():
    w, h = 1024, 500
    bg = Image.new("RGB", (w, h))
    px = bg.load()
    for y in range(h):
        for x in range(w):
            t = (x / w) * 0.55 + (y / h) * 0.45
            px[x, y] = (int(15 + 20 * t), int(27 + 40 * t), int(49 + 90 * t))
    draw = ImageDraw.Draw(bg)

    # the app icon, rounded, above the title
    art = Image.open(FOREGROUND).convert("RGB").resize((132, 132), Image.LANCZOS)
    corner = Image.new("L", art.size, 0)
    ImageDraw.Draw(corner).rounded_rectangle([0, 0, 131, 131], radius=30, fill=255)
    bg.paste(art, (56, 92), corner)

    title = ImageFont.truetype(BOLD, 84)
    tag = ImageFont.truetype(REGULAR, 29)
    small = ImageFont.truetype(REGULAR, 23)
    draw.text((52, 232), "Sidereal", font=title, fill=(255, 255, 255))
    draw.text((56, 338), "Camera and gimbal control", font=tag, fill=(205, 222, 250))
    draw.text((56, 378), "for the DJI Osmo Pro", font=tag, fill=(205, 222, 250))
    draw.text((56, 430), "Timelapse  \u00b7  Panorama  \u00b7  Watch remote", font=small, fill=(150, 180, 230))

    # the icon's star-trail art, large, on the right
    big = Image.open(FOREGROUND).convert("RGB").resize((420, 420), Image.LANCZOS)
    frame = Image.new("L", big.size, 0)
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, 419, 419], radius=64, fill=255)
    shadow = Image.new("RGBA", (big.width + 80, big.height + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([40, 48, big.width + 40, big.height + 48], radius=64, fill=(0, 0, 0, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(16))
    sx, sy = w - big.width - 70, (h - big.height) // 2
    bg.paste(shadow, (sx - 40, sy - 40), shadow)
    bg.paste(big, (sx, sy), frame)
    bg.save(OUT / "feature-graphic-1024x500.png")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    icon()
    feature_graphic()
    print("wrote", OUT)
