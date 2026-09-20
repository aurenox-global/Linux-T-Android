#!/usr/bin/env python3
"""make-icon.py — genera el icono estilo Apple (squircle + degradado + prompt)
para la app Linux-T: mipmaps legacy, foreground adaptativo y roundIcon.
"""
import os
from PIL import Image, ImageDraw, ImageFont

BASE = "/home/zota/debian-android/app/app/src/main/res"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"

C_TOP = (10, 132, 255)     # #0A84FF
C_MID = (72, 108, 243)     # intermedio
C_BOT = (122, 78, 233)     # #7A4EE9

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FG_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size, stops):
    """Degradado vertical suave entre varias paradas de color."""
    img = Image.new("RGB", (size, size), stops[0])
    d = ImageDraw.Draw(img)
    n = len(stops) - 1
    for y in range(size):
        t = y / max(1, size - 1) * n
        i = min(int(t), n - 1)
        col = lerp(stops[i], stops[i + 1], t - i)
        d.line([(0, y), (size, y)], fill=col)
    return img


def gloss(size):
    """Brillo sutil superior (aire Apple)."""
    ov = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for y in range(int(size * 0.5)):
        a = int(46 * (1 - y / (size * 0.5)))
        d.line([(0, y), (size, y)], fill=(255, 255, 255, a))
    return ov


def squircle_mask(size, frac=0.225):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * frac), fill=255)
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def draw_glyph(layer, size, scale):
    d = ImageDraw.Draw(layer)
    fs = int(size * scale)
    font = ImageFont.truetype(FONT, fs)
    text = ">_"
    bbox = d.textbbox((0, 0), text, font=font)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (size - w) / 2 - bbox[0]
    y = (size - h) / 2 - bbox[1]
    off = max(1, int(size * 0.012))
    d.text((x + off, y + off), text, font=font, fill=(0, 0, 0, 110))   # sombra
    d.text((x, y), text, font=font, fill=(255, 255, 255, 255))         # glifo


def legacy_icon(size, glyph_scale=0.42):
    base = gradient(size, [C_TOP, C_MID, C_BOT]).convert("RGBA")
    base = Image.alpha_composite(base, gloss(size))
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_glyph(layer, size, glyph_scale)
    base = Image.alpha_composite(base, layer)
    base.putalpha(squircle_mask(size))
    return base


def round_icon(size, glyph_scale=0.40):
    base = gradient(size, [C_TOP, C_MID, C_BOT]).convert("RGBA")
    base = Image.alpha_composite(base, gloss(size))
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_glyph(layer, size, glyph_scale)
    base = Image.alpha_composite(base, layer)
    base.putalpha(circle_mask(size))
    return base


def foreground(size, glyph_scale=0.34):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_glyph(img, size, glyph_scale)
    return img


def save(img, dens, name):
    d = os.path.join(BASE, "mipmap-" + dens)
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name + ".png"))


def main():
    for dens, px in DENSITIES.items():
        save(legacy_icon(px), dens, "ic_launcher")
        save(round_icon(px), dens, "ic_launcher_round")
    for dens, px in FG_DENSITIES.items():
        save(foreground(px), dens, "ic_launcher_foreground")

    # XMLs
    anydpi = os.path.join(BASE, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n'
    )
    for nm in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, nm), "w") as f:
            f.write(adaptive)

    drawable = os.path.join(BASE, "drawable")
    os.makedirs(drawable, exist_ok=True)
    bg = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        '    <gradient\n'
        '        android:angle="270"\n'
        '        android:startColor="#0A84FF"\n'
        '        android:centerColor="#486CF3"\n'
        '        android:endColor="#7A4EE9"\n'
        '        android:type="linear" />\n'
        '</shape>\n'
    )
    with open(os.path.join(drawable, "ic_launcher_background.xml"), "w") as f:
        f.write(bg)

    # Preview 512
    legacy_icon(512).save("/tmp/linux-t-icon-preview.png")
    print("OK: iconos generados en", BASE)


if __name__ == "__main__":
    main()
