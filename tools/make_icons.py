#!/usr/bin/env python3
"""Generate launcher icons for LUT Darkroom: pastel color-wheel artwork."""
import colorsys
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
S = 2048


def hsv_color(hue, sat=0.66, val=1.0, alpha=255):
    r, g, b = colorsys.hsv_to_rgb((hue % 360) / 360.0, sat, val)
    return int(r * 255), int(g * 255), int(b * 255), alpha


def background(size):
    """Soft four-corner pastel gradient."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    u = x / (size - 1)
    v = y / (size - 1)
    tl = np.array([251, 247, 238], np.float32)
    tr = np.array([251, 234, 240], np.float32)
    bl = np.array([227, 240, 249], np.float32)
    br = np.array([249, 235, 243], np.float32)
    top = tl[None, None, :] * (1 - u)[..., None] + tr[None, None, :] * u[..., None]
    bot = bl[None, None, :] * (1 - u)[..., None] + br[None, None, :] * u[..., None]
    img = top * (1 - v)[..., None] + bot * v[..., None]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def wheel_layer(size):
    cx = cy = size / 2.0
    r_out = size * 0.330
    r_in = size * 0.190

    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    segments = 12
    gap = 1.3
    for i in range(segments):
        hue = 330 + i * (360.0 / segments)
        a0 = -90 + i * (360.0 / segments) + gap
        a1 = -90 + (i + 1) * (360.0 / segments) - gap
        d.pieslice([cx - r_out, cy - r_out, cx + r_out, cy + r_out],
                   start=a0, end=a1, fill=hsv_color(hue, 0.56, 0.99))

    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([cx - r_out, cy - r_out, cx + r_out, cy + r_out], fill=255)
    md.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=0)
    layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", (size, size), 0), mask))

    # pearly centre disc with a diagonal cool -> warm gradient
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    t = np.clip(((x - y) / (2.0 * r_in) + 0.5), 0, 1)[..., None]
    cool = np.array([214, 221, 233], np.float32)
    warm = np.array([253, 238, 226], np.float32)
    disc = (cool[None, None, :] * (1 - t) + warm[None, None, :] * t)
    disc = Image.fromarray(np.clip(disc, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    dmask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(dmask).ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=255)
    disc.putalpha(dmask)
    layer = Image.alpha_composite(layer, disc)

    # soft inner shadow ring on the disc edge
    edge = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ed = ImageDraw.Draw(edge)
    ed.arc([cx - r_in, cy - r_in, cx + r_in, cy + r_in], 20, 200,
           fill=(255, 255, 255, 150), width=int(size * 0.012))
    edge = edge.filter(ImageFilter.GaussianBlur(size * 0.006))
    layer = Image.alpha_composite(layer, edge)
    return layer


def arc_layer(size):
    """Thin rainbow arc with three knobs."""
    cx = cy = size / 2.0
    r = size * 0.418
    width = int(size * 0.016)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    knobs = [(325.0, (240, 156, 172), 1), (205.0, (128, 182, 219), 2), (305.0, (243, 199, 123), 3)]
    gaps = sorted(k[0] for k in knobs)
    starts = gaps
    for idx in range(len(starts)):
        a0 = starts[idx] + 12
        a1 = starts[(idx + 1) % len(starts)] + (360 if idx == len(starts) - 1 else 0) - 12
        steps = max(8, int((a1 - a0) / 2))
        for s in range(steps):
            b0 = a0 + (a1 - a0) * s / steps
            b1 = a0 + (a1 - a0) * (s + 1) / steps + 0.6
            col = hsv_color((b0 - 90) * 0.55 - 20, 0.38, 1.0)
            d.arc([cx - r, cy - r, cx + r, cy + r], b0, b1, fill=col, width=width)

    for deg, col, _ in knobs:
        kx = cx + r * math.cos(math.radians(deg))
        ky = cy + r * math.sin(math.radians(deg))
        kr = size * 0.050
        d.ellipse([kx - kr, ky - kr, kx + kr, ky + kr], fill=col + (255,))
    return layer


def wedge_layer(size):
    cx = cy = size / 2.0
    r = size * 0.418
    deg = math.radians(-35)
    kx = cx + r * math.cos(deg)
    ky = cy + r * math.sin(deg)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.polygon([(cx, cy), (kx, ky), (cx + size * 0.30, cy - size * 0.06)],
              fill=(255, 214, 214, 60))
    return layer


def compose(size, with_bg=True, art_scale=1.0):
    canvas = background(size) if with_bg else Image.new("RGBA", (size, size), (0, 0, 0, 0))
    art = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    art = Image.alpha_composite(art, wedge_layer(size))
    art = Image.alpha_composite(art, wheel_layer(size))
    art = Image.alpha_composite(art, arc_layer(size))
    if art_scale != 1.0:
        small = art.resize((int(size * art_scale), int(size * art_scale)), Image.LANCZOS)
        holder = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        off = (size - small.size[0]) // 2
        holder.paste(small, (off, off), small)
        art = holder
    return Image.alpha_composite(canvas, art)


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG")


def main():
    art = compose(S, with_bg=True)
    square = art.copy()
    square.putalpha(rounded_mask(S, int(S * 0.225)))
    round_icon = art.copy()
    round_icon.putalpha(circle_mask(S))
    fg = compose(S, with_bg=False, art_scale=0.70)
    bg = background(S)

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in densities.items():
        save(square, os.path.join(OUT, "mipmap-" + dpi, "ic_launcher.png"), px)
        save(round_icon, os.path.join(OUT, "mipmap-" + dpi, "ic_launcher_round.png"), px)
        save(fg, os.path.join(OUT, "mipmap-" + dpi, "ic_launcher_fg.png"), int(px * 108 / 48))
        save(bg, os.path.join(OUT, "mipmap-" + dpi, "ic_launcher_bg.png"), int(px * 108 / 48))
    print("icons written to", OUT)


if __name__ == "__main__":
    main()
