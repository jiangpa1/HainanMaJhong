#!/usr/bin/env python3
"""Generate static/favicon.ico (multi-size) + static/favicon.svg.

Draws a mahjong tile with a red diamond and the character for "centre" (0x4E2D),
matching frontend/public/favicon.svg and the tile art under static/img/tiles/.

Why this script exists
----------------------
Modern browsers are satisfied by <link rel="icon" href="/favicon.svg">, and
frontend/public/favicon.svg is copied into static/ by the Vite build. But some
clients (old browsers, crawlers, IDE previews) ignore the link tag and request
/favicon.ico directly; without that file every page load logs a 404.

Why Pillow rather than PowerShell + System.Drawing
--------------------------------------------------
The first attempt used System.Drawing from Windows PowerShell 5.1, but that
host cannot resolve the [System.Drawing.Drawing2D] enum (SmoothingMode /
InterpolationMode) even though System.Drawing itself loads. Pillow is already
installed here and gives exact control over supersampling.

Supersampling
-------------
Everything is drawn once at SS=8 (2048px) and then downscaled with LANCZOS.
Drawing each size independently makes the 16px glyph mushy; supersampling keeps
the strokes crisp at every size.

Usage:  python tools/build_favicon.py
"""

import os
import struct
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow is required: python -m pip install Pillow")

# Proportions come from the real tile art (181x238 ~= 60:80).
TILE_W_RATIO = 60.0
TILE_H_RATIO = 80.0
# Tile occupies this fraction of the canvas, leaving room for the border/shadow.
FILL = 0.88
SS = 8  # supersample factor

BODY_TOP = (255, 255, 253, 246)
BODY_BOTTOM = (255, 228, 217, 192)
BORDER = (195, 183, 156, 255)
INNER = (216, 205, 180, 255)
DIAMOND = (192, 39, 45, 255)
GLYPH = (255, 248, 240, 255)

# KaiTi (simkai) is a brush-style face, closest to traditional tile lettering.
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\simkai.ttf",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simsun.ttc",
]


def load_font(px):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, px)
            except OSError:
                continue
    raise RuntimeError("no usable CJK font found; checked: " + ", ".join(FONT_CANDIDATES))


def rounded_rect(draw, box, radius, fill=None, outline=None, width=1):
    """Pillow's rounded_rectangle exists, but we need it to work on older versions too."""
    if hasattr(draw, "rounded_rectangle"):
        draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)
        return
    x0, y0, x1, y1 = box
    draw.rectangle([x0 + radius, y0, x1 - radius, y1], fill=fill)
    draw.rectangle([x0, y0 + radius, x1, y1 - radius], fill=fill)
    for cx, cy in ((x0 + radius, y0 + radius), (x1 - radius, y0 + radius),
                   (x0 + radius, y1 - radius), (x1 - radius, y1 - radius)):
        draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=fill)
    if outline:
        draw.rounded_rectangle(box, radius=radius, outline=outline, width=width)


def draw_tile(size):
    """Render the tile icon at `size` px (already downsampled)."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    # Gradient body: build it small and resize, cheaper and smoother than per-pixel.
    grad = Image.new("RGB", (1, 256))
    for y in range(256):
        t = y / 255.0
        grad.putpixel((0, y), tuple(
            int(BODY_TOP[i] + (BODY_BOTTOM[i] - BODY_TOP[i]) * t) for i in range(3)
        ))
    grad = grad.resize((s, s), Image.BILINEAR).convert("RGBA")

    # Tile geometry
    tw = s * FILL
    th = tw * TILE_H_RATIO / TILE_W_RATIO
    if th > s * 0.96:
        th = s * 0.96
        tw = th * TILE_W_RATIO / TILE_H_RATIO
    tx = (s - tw) / 2.0
    ty = (s - th) / 2.0
    radius = max(1, int(tw * 0.15))
    box = [int(tx), int(ty), int(tx + tw), int(ty + th)]

    # Mask the gradient to the rounded tile shape
    mask = Image.new("L", (s, s), 0)
    md = ImageDraw.Draw(mask)
    rounded_rect(md, box, radius, fill=255)
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)
    stroke = max(1, int(s * 0.012))
    rounded_rect(d, box, radius, outline=BORDER, width=stroke)

    # Inner frame line
    inset = tw * 0.075
    ibox = [int(tx + inset), int(ty + inset), int(tx + tw - inset), int(ty + th - inset)]
    rounded_rect(d, ibox, max(1, int(radius * 0.7)), outline=INNER, width=max(1, int(s * 0.006)))

    # Centre diamond (half-diagonal = 0.30 * tile width)
    cx = tx + tw / 2.0
    cy = ty + th * 0.47
    half = tw * 0.30
    d.polygon([(cx, cy - half), (cx + half, cy), (cx, cy + half), (cx - half, cy)], fill=DIAMOND)

    # Glyph, centred on the diamond
    font = load_font(int(half * 1.5))
    text = "\u4e2d"  # the character for "centre"
    bbox = d.textbbox((0, 0), text, font=font)
    gw = bbox[2] - bbox[0]
    gh = bbox[3] - bbox[1]
    d.text((cx - gw / 2.0 - bbox[0], cy - gh / 2.0 - bbox[1]), text, font=font, fill=GLYPH)

    return img.resize((size, size), Image.LANCZOS)


def png_bytes(img):
    import io

    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def write_ico(path, sizes):
    """Write a multi-size .ico with PNG-compressed payloads (Vista+ accepts these)."""
    payloads = []
    for sz in sizes:
        payloads.append((sz, png_bytes(draw_tile(sz))))

    header = struct.pack("<HHH", 0, 1, len(payloads))
    entries = b""
    offset = 6 + 16 * len(payloads)
    for sz, data in payloads:
        dim = 0 if sz >= 256 else sz
        entries += struct.pack(
            "<BBBBHHII",
            dim,          # width
            dim,          # height
            0,            # palette count
            0,            # reserved
            1,            # colour planes
            32,           # bits per pixel
            len(data),
            offset,
        )
        offset += len(data)

    with open(path, "wb") as f:
        f.write(header)
        f.write(entries)
        for _, data in payloads:
            f.write(data)
    return os.path.getsize(path)


def write_svg(path):
    """Also emit the vector version so the HTML can prefer it (crisper on HiDPI)."""
    svg = """<?xml version="1.0" encoding="UTF-8"?>
<!--
  Site icon: a mahjong tile with a red diamond and the character for "centre".

  Generated by frontend/tools/build_favicon.py -- edit that script, not this file.
  Geometry mirrors the tile art under static/img/tiles/ (181x238 ~= 60:80),
  so the tab icon and the tiles on the table look like the same object.

  Diamond geometry: tile is 64x84, centre x=32; diamond centre (32,38) with
  half-diagonal 13 -> vertices (32,25) (45,38) (32,51) (19,38).
-->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 84" width="64" height="84" role="img" aria-label="Hainan Mahjong">
  <defs>
    <linearGradient id="body" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#fffdf6"/>
      <stop offset="0.62" stop-color="#f6f0e2"/>
      <stop offset="1" stop-color="#e4d9c0"/>
    </linearGradient>
    <linearGradient id="gloss" x1="0" y1="0" x2="0.8" y2="1">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.9"/>
      <stop offset="0.5" stop-color="#ffffff" stop-opacity="0"/>
    </linearGradient>
  </defs>

  <rect x="2" y="2" width="60" height="80" rx="9" fill="url(#body)" stroke="#c3b79c" stroke-width="2"/>
  <rect x="6.5" y="6.5" width="51" height="71" rx="6" fill="none" stroke="#d8cdb4" stroke-width="1.2"/>

  <polygon points="32,25 45,38 32,51 19,38" fill="#c0272d"/>
  <text x="32" y="45.5" text-anchor="middle" font-size="21" font-weight="700"
        fill="#fff8f0" font-family="'KaiTi','STKaiti','SimSun',serif">&#x4E2D;</text>

  <path d="M2 11 A9 9 0 0 1 11 2 H53 A9 9 0 0 1 62 11 Z" fill="url(#gloss)" opacity="0.45"/>
</svg>
"""
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(svg)
    return os.path.getsize(path)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    frontend_dir = os.path.normpath(os.path.join(here, ".."))
    public_dir = os.path.join(frontend_dir, "public")
    os.makedirs(public_dir, exist_ok=True)

    # Both files are written into frontend/public/ so that Vite owns the copy step
    # and a `pnpm build` after `mvn clean` regenerates static/ correctly.
    # Writing them straight into static/ would make them a manual, easily-lost step
    # (and would collide with the public/ copy -- which is exactly the bug this
    # layout avoids: two sources for the same output path).
    ico_path = os.path.join(public_dir, "favicon.ico")
    svg_path = os.path.join(public_dir, "favicon.svg")

    sizes = [16, 32, 48, 64, 128]
    ico_size = write_ico(ico_path, sizes)
    svg_size = write_svg(svg_path)

    print("sizes      : " + ", ".join(str(s) for s in sizes))
    print("written    : " + ico_path + "  (" + str(ico_size) + " bytes)")
    print("written    : " + svg_path + "  (" + str(svg_size) + " bytes)")
    print("next       : pnpm build   (Vite copies public/ into static/)")


if __name__ == "__main__":
    main()
