#!/usr/bin/env python3
"""Draw the Prism mark once and write it out: SVG sources in docs/brand/, the app's icon and TV banner as PNGs in
res/drawable-xhdpi/, and the placeholder README hero in docs/images/.

The mark is a prism splitting one warm beam into two: warm for the box, sky for the LG (the app's own colours for
"on the box" and "the LG has the remote"). Needs Pillow; text uses the app's own Atkinson font.

    pip install pillow
    python3 tools/brand.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / 'res' / 'font' / 'atkinson_semibold.ttf'
FONT_REGULAR = ROOT / 'res' / 'font' / 'atkinson_regular.ttf'

# res/values/styles.xml, plus the warm lamp colour of the look the app shares.
NIGHT = '#0e0f12'
PANEL = '#17191e'
RAISED = '#22252b'
TEXT = '#f4f2ee'
MUTED = '#b9b4ac'
SKY = '#80d8ff'
WARM = '#ffc76b'

# The mark in a 512 × 512 box: (kind, points, colour, width, opacity). Later shapes are drawn on top.
APEX, LEFT, RIGHT = (262, 118), (142, 326), (382, 326)
ENTRY = (202, 222)            # halfway down the left face
EXIT_SKY = (306, 194)         # on the right face
EXIT_WARM = (338, 250)
MARK = [
    ('line', [(30, 262), ENTRY], WARM, 34, 1.0),
    ('polygon', [APEX, RIGHT, LEFT], RAISED, 0, 1.0),
    ('line', [ENTRY, EXIT_SKY], SKY, 22, 0.55),
    ('line', [ENTRY, EXIT_WARM], WARM, 22, 0.55),
    ('line', [EXIT_SKY, (482, 128)], SKY, 30, 1.0),
    ('line', [EXIT_WARM, (482, 318)], WARM, 30, 1.0),
    ('outline', [APEX, RIGHT, LEFT], TEXT, 16, 1.0),
]


def hex_rgba(colour, opacity=1.0):
    c = colour.lstrip('#')
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), round(255 * opacity))


# ---- SVG ----

def svg_mark(scale, dx, dy):
    """The mark as SVG elements, placed at (dx, dy) and scaled from the 512 box."""
    out = [f'<g transform="translate({dx:g} {dy:g}) scale({scale:g})" stroke-linecap="round" stroke-linejoin="round">']
    for kind, points, colour, width, opacity in MARK:
        pts = ' '.join(f'{x},{y}' for x, y in points)
        alpha = '' if opacity == 1 else f' opacity="{opacity:g}"'
        if kind == 'line':
            (x1, y1), (x2, y2) = points
            out.append(f'  <line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{colour}" stroke-width="{width}"{alpha}/>')
        elif kind == 'polygon':
            out.append(f'  <polygon points="{pts}" fill="{colour}"{alpha}/>')
        else:
            out.append(f'  <polygon points="{pts}" fill="none" stroke="{colour}" stroke-width="{width}"{alpha}/>')
    out.append('</g>')
    return '\n'.join(out)


def svg(width, height, body, ground=None, radius=0):
    rect = f'<rect width="{width}" height="{height}" rx="{radius}" fill="{ground}"/>\n' if ground else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">\n'
            f'{rect}{body}\n</svg>\n')


def svg_text(x, y, size, colour, text, weight=600):
    return (f'<text x="{x}" y="{y}" fill="{colour}" font-family="Atkinson Hyperlegible Next, Atkinson Hyperlegible, sans-serif" '
            f'font-weight="{weight}" font-size="{size}">{text}</text>')


# ---- PNG (drawn 4× and scaled down for smooth edges) ----

SS = 4


def draw_mark(image, scale, dx, dy):
    """Each shape on its own layer, so translucent beams blend as they do in the SVG."""
    k = scale * SS
    for kind, points, colour, width, opacity in MARK:
        layer = Image.new('RGBA', image.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        pts = [(dx * SS + x * k, dy * SS + y * k) for x, y in points]
        rgba = hex_rgba(colour, 1.0)
        w = max(1, round(width * k))
        if kind == 'polygon':
            d.polygon(pts, fill=rgba)
        elif kind == 'line':
            round_line(d, pts, rgba, w)
        else:
            for a, b in zip(pts, pts[1:] + pts[:1]):
                round_line(d, [a, b], rgba, w)
        if opacity < 1:
            layer.putalpha(layer.getchannel('A').point(lambda v: round(v * opacity)))
        image.alpha_composite(layer)


def round_line(d, pts, rgba, w):
    d.line(pts, fill=rgba, width=w)
    r = w / 2
    for x, y in pts:
        d.ellipse((x - r, y - r, x + r, y + r), fill=rgba)


def canvas(width, height, ground, radius=0):
    image = Image.new('RGBA', (width * SS, height * SS), (0, 0, 0, 0))
    ImageDraw.Draw(image).rounded_rectangle((0, 0, width * SS - 1, height * SS - 1), radius * SS, fill=hex_rgba(ground))
    return image


def draw_text(image, x, y, size, colour, text, font=FONT):
    face = ImageFont.truetype(str(font), size * SS)
    ImageDraw.Draw(image).text((x * SS, y * SS), text, font=face, fill=hex_rgba(colour), anchor='ls')


def save_png(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    w, h = image.size
    image.resize((w // SS, h // SS), Image.LANCZOS).save(path, optimize=True)
    print(f'{path.relative_to(ROOT)}  {w // SS}×{h // SS}')


def save_svg(text, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    print(path.relative_to(ROOT))


# ---- outputs ----

def mark():
    save_svg(svg(512, 512, svg_mark(1, 0, 0)), ROOT / 'docs/brand/prism-mark.svg')


def icon():
    """Launcher icon: the mark on a rounded night square. 96 px is 48 dp at xhdpi."""
    size, inset = 96, 6
    save_svg(svg(size, size, svg_mark((size - 2 * inset) / 512, inset, inset + 2), NIGHT, 20), ROOT / 'docs/brand/prism-icon.svg')
    image = canvas(size, size, NIGHT, 20)
    draw_mark(image, (size - 2 * inset) / 512, inset, inset + 2)
    save_png(image, ROOT / 'res/drawable-xhdpi/icon.png')


def banner():
    """Android TV banner: 320 × 180 px at xhdpi, the mark and the app's name."""
    w, h, s = 320, 180, 0.28
    dx, dy = 22, (h - 512 * s) / 2 + 2
    body = svg_mark(s, dx, dy) + '\n' + svg_text(176, 106, 46, TEXT, 'Prism')
    save_svg(svg(w, h, body, NIGHT), ROOT / 'docs/brand/prism-banner.svg')
    image = canvas(w, h, NIGHT)
    draw_mark(image, s, dx, dy)
    draw_text(image, 176, 106, 46, TEXT, 'Prism')
    save_png(image, ROOT / 'res/drawable-xhdpi/banner.png')


def hero():
    """Placeholder until a real screenshot replaces it (docs/images/README.md says what to capture)."""
    w, h = 2400, 1200
    image = canvas(w, h, NIGHT)
    ImageDraw.Draw(image).rounded_rectangle((120 * SS, 160 * SS, 1100 * SS, 1040 * SS), 64 * SS, fill=hex_rgba(PANEL))
    draw_mark(image, 1.7, 175, 210)
    draw_text(image, 1240, 560, 200, TEXT, 'Prism')
    draw_text(image, 1250, 680, 64, MUTED, 'One remote for your Android TV box', FONT_REGULAR)
    draw_text(image, 1250, 770, 64, MUTED, 'and your LG TV.', FONT_REGULAR)
    save_png(image, ROOT / 'docs/images/hero.png')


if __name__ == '__main__':
    mark()
    icon()
    banner()
    hero()
