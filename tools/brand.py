#!/usr/bin/env python3
"""Turn the brand SVG sources in docs/brand/ into the files the app and the repo use:

    res/drawable-xhdpi/banner.png                   Android TV banner, 320 × 180
    res/drawable/icon.xml                           the icon tile as a vector (home-screen channel logo)
    res/drawable/ic_launcher_foreground.xml         adaptive launcher icon foreground (and monochrome layer)
    docs/images/social.png                          GitHub social preview, 1280 × 640
    docs/images/hero.png                            README hero placeholder, 2400 × 1200
    docs/brand/png/                                 icon PNGs (512, 192, 180) and favicon.ico (16, 32, 48)

The SVGs come from tools/brand_svg.py. They use a small subset of SVG (rect, g and path with absolute
M L H V C Q A Z, fills, translate/scale transforms), which this script rasterises itself with Pillow, so no
SVG renderer is needed.

    pip install pillow
    python3 tools/brand.py
"""
import math
import re
from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / 'docs' / 'brand'
NS = '{http://www.w3.org/2000/svg}'
SUPERSAMPLE = 4


# ---- SVG geometry ----

def parse_transform(text):
    """'translate(a b) scale(k)' (either part optional) → (scale_x, scale_y, translate_x, translate_y)."""
    sx = sy = 1.0
    tx = ty = 0.0
    for name, args in re.findall(r'(\w+)\(([^)]*)\)', text or ''):
        values = [float(v) for v in re.split(r'[\s,]+', args.strip())]
        if name == 'translate':
            tx, ty = values[0], values[1] if len(values) > 1 else 0.0
        elif name == 'scale':
            sx, sy = values[0], values[1] if len(values) > 1 else values[0]
        else:
            raise ValueError(f'unsupported transform {name}')
    return sx, sy, tx, ty


def compose(outer, inner):
    """outer ∘ inner for (sx, sy, tx, ty) transforms."""
    osx, osy, otx, oty = outer
    isx, isy, itx, ity = inner
    return osx * isx, osy * isy, osx * itx + otx, osy * ity + oty


def tokens(d):
    for match in re.finditer(r'[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?', d):
        yield match.group(0)


def contours(d, steps=24):
    """Flatten absolute path data into closed polylines."""
    out, current, pos, start = [], [], (0.0, 0.0), (0.0, 0.0)
    stream = list(tokens(d))
    i, cmd = 0, None

    def take(n):
        nonlocal i
        values = [float(v) for v in stream[i:i + n]]
        i += n
        return values

    while i < len(stream):
        if stream[i].isalpha():
            cmd = stream[i]
            i += 1
            if cmd in 'Zz':
                if current:
                    out.append(current)
                current, pos = [], start
                continue
        if cmd != cmd.upper():
            raise ValueError(f'relative path command {cmd} is not supported')
        if cmd == 'M':
            if current:
                out.append(current)
            pos = start = tuple(take(2))
            current = [pos]
            cmd = 'L'
        elif cmd == 'L':
            pos = tuple(take(2))
            current.append(pos)
        elif cmd == 'H':
            pos = (take(1)[0], pos[1])
            current.append(pos)
        elif cmd == 'V':
            pos = (pos[0], take(1)[0])
            current.append(pos)
        elif cmd == 'Q':
            x1, y1, x, y = take(4)
            p0 = pos
            current += [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * x1 + t * t * x,
                         (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * y1 + t * t * y)
                        for t in (k / steps for k in range(1, steps + 1))]
            pos = (x, y)
        elif cmd == 'C':
            x1, y1, x2, y2, x, y = take(6)
            p0 = pos
            current += [((1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3 * x,
                         (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t * t * y2 + t ** 3 * y)
                        for t in (k / steps for k in range(1, steps + 1))]
            pos = (x, y)
        elif cmd == 'A':
            rx, ry, rot, large, sweep, x, y = take(7)
            current += arc(pos, rx, ry, rot, int(large), int(sweep), (x, y), steps * 2)
            pos = (x, y)
        else:
            raise ValueError(f'unsupported path command {cmd}')
    if current:
        out.append(current)
    return out


def arc(p0, rx, ry, rot, large, sweep, p1, steps):
    """SVG elliptical arc (endpoint form) as points after p0."""
    phi = math.radians(rot)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx, dy = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1p, y1p = cos_p * dx + sin_p * dy, -sin_p * dx + cos_p * dy
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / (rx * rx * y1p * y1p + ry * ry * x1p * x1p)))
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (p0[0] + p1[0]) / 2
    cy = sin_p * cxp + cos_p * cyp + (p0[1] + p1[1]) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t0 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(2, int(steps * abs(dt) / math.pi))
    pts = []
    for k in range(1, n + 1):
        t = t0 + dt * k / n
        x, y = rx * math.cos(t), ry * math.sin(t)
        pts.append((cos_p * x - sin_p * y + cx, sin_p * x + cos_p * y + cy))
    pts[-1] = p1
    return pts


def rect_path(el):
    x, y = float(el.get('x', 0)), float(el.get('y', 0))
    w, h = float(el.get('width')), float(el.get('height'))
    r = min(float(el.get('rx', 0)), w / 2, h / 2)
    if not r:
        return f'M{x} {y} H{x + w} V{y + h} H{x} Z'
    return (f'M{x + r} {y} H{x + w - r} A{r} {r} 0 0 1 {x + w} {y + r} V{y + h - r} A{r} {r} 0 0 1 {x + w - r} {y + h} '
            f'H{x + r} A{r} {r} 0 0 1 {x} {y + h - r} V{y + r} A{r} {r} 0 0 1 {x + r} {y} Z')


def shapes(path):
    """The SVG's filled shapes in drawing order: (path data, fill, transform), plus the viewBox."""
    root = ET.parse(path).getroot()
    view = [float(v) for v in root.get('viewBox').split()]
    out = []

    def walk(el, transform):
        tag = el.tag.replace(NS, '')
        here = compose(transform, parse_transform(el.get('transform')))
        if tag == 'path':
            out.append((el.get('d'), el.get('fill'), here))
        elif tag == 'rect':
            out.append((rect_path(el), el.get('fill'), here))
        for child in el:
            walk(child, here)

    walk(root, (1.0, 1.0, 0.0, 0.0))
    return view, out


# ---- raster ----

def hex_rgb(colour):
    c = colour.lstrip('#')
    return int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16)


def signed_area(points):
    return sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(points, points[1:] + points[:1])) / 2


def coverage(d, transform, size):
    """Non-zero fill of the path as an 8-bit mask: each contour adds its winding direction."""
    sx, sy, tx, ty = transform
    plus = Image.new('L', size, 0)
    minus = Image.new('L', size, 0)
    for contour in contours(d):
        pts = [(x * sx + tx, y * sy + ty) for x, y in contour]
        if len(pts) < 3:
            continue
        mask = Image.new('L', size, 0)
        ImageDraw.Draw(mask).polygon(pts, fill=1)
        if signed_area(pts) >= 0:
            plus = ImageChops.add(plus, mask)
        else:
            minus = ImageChops.add(minus, mask)
    return ImageChops.difference(plus, minus).point(lambda v: 255 if v else 0)


def render(svg_path, width, height):
    """Rasterise an SVG source to an RGBA image of the given size (the viewBox is stretched to fit)."""
    view, items = shapes(svg_path)
    ss = SUPERSAMPLE if max(width, height) <= 640 else 2
    big = (width * ss, height * ss)
    fit = (big[0] / view[2], big[1] / view[3], -view[0] * big[0] / view[2], -view[1] * big[1] / view[3])
    image = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    for d, fill, transform in items:
        alpha = coverage(d, compose(fit, transform), big).resize((width, height), Image.LANCZOS)
        layer = Image.new('RGBA', (width, height), hex_rgb(fill) + (0,))
        layer.putalpha(alpha)
        image = Image.alpha_composite(image, layer)
    return image


def save_png(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    print(f'{path.relative_to(ROOT)}  {image.width}×{image.height}')


# ---- vector drawables ----

def vector_drawable(svg_path, size_dp):
    """The SVG as an Android VectorDrawable: transforms become groups, fills become fillColor."""
    view, items = shapes(svg_path)
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<!-- Generated by tools/brand.py from docs/brand/{svg_path.name}. Do not edit. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             f'    android:width="{size_dp}dp" android:height="{size_dp}dp"',
             f'    android:viewportWidth="{view[2]:g}" android:viewportHeight="{view[3]:g}">']
    for d, fill, (sx, sy, tx, ty) in items:
        path = f'<path android:fillColor="{fill}" android:pathData="{d}" />'
        if (sx, sy, tx, ty) == (1.0, 1.0, 0.0, 0.0):
            lines.append(f'    {path}')
        else:
            lines.append(f'    <group android:translateX="{tx:g}" android:translateY="{ty:g}" '
                         f'android:scaleX="{sx:g}" android:scaleY="{sy:g}">')
            lines.append(f'        {path}')
            lines.append('    </group>')
    lines.append('</vector>')
    return '\n'.join(lines) + '\n'


def save_text(text, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    print(path.relative_to(ROOT))


# ---- outputs ----

def app():
    save_png(render(BRAND / 'prism-banner.svg', 320, 180), ROOT / 'res/drawable-xhdpi/banner.png')
    save_text(vector_drawable(BRAND / 'prism-icon.svg', 48), ROOT / 'res/drawable/icon.xml')
    save_text(vector_drawable(BRAND / 'prism-adaptive-foreground.svg', 108),
              ROOT / 'res/drawable/ic_launcher_foreground.xml')


def repo():
    save_png(render(BRAND / 'prism-social.svg', 1280, 640), ROOT / 'docs/images/social.png')
    save_png(render(BRAND / 'prism-social.svg', 2400, 1200), ROOT / 'docs/images/hero.png')
    png = BRAND / 'png'
    for size, name in ((512, 'prism-icon-512.png'), (192, 'prism-icon-192.png'), (180, 'apple-touch-icon.png')):
        save_png(render(BRAND / 'prism-icon.svg', size, size), png / name)
    # 16 and 32 px use the small-size cut (heavier stem on the pixel grid); 48 px uses the full tile.
    small = [render(BRAND / 'prism-icon-small.svg', s, s) for s in (16, 32)]
    render(BRAND / 'prism-icon.svg', 48, 48).save(png / 'favicon.ico', sizes=[(16, 16), (32, 32), (48, 48)],
                                                  append_images=small)
    print(f'{(png / "favicon.ico").relative_to(ROOT)}  16, 32, 48')


if __name__ == '__main__':
    app()
    repo()
