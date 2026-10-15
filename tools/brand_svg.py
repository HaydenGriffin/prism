#!/usr/bin/env python3
"""Write Prism's brand SVG sources into docs/brand/: the mark, its colour and one-colour versions, lockups,
the icon tile, the adaptive-icon foreground, the Android TV banner and the social card.

The mark is a P whose counter is a prism. A 30° wedge opens from the prism through the bowl, so the bowl
leaves the stem as two arms: one stem (the box's remote), two arms (sky for the LG, amber for the box).

Words are outlined from the app's own Atkinson Hyperlegible Next, so the SVGs need no fonts. Run this only to
change the artwork; `tools/brand.py` turns these SVGs into the app's PNGs and vector drawables.

    pip install fonttools
    python3 tools/brand_svg.py
"""
import math
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs' / 'brand'
SEMIBOLD = ROOT / 'res' / 'font' / 'atkinson_semibold.ttf'
REGULAR = ROOT / 'res' / 'font' / 'atkinson_regular.ttf'

# The app's colours (res/values/styles.xml), plus darker sky and amber for light grounds.
NIGHT, PANEL, WARM, MUTED = '#0e0f12', '#17191e', '#f4f2ee', '#b9b4ac'
SKY, AMBER = '#80d8ff', '#ff9d5c'
SKY_DEEP, AMBER_DEEP = '#1d8ac0', '#d4632a'
BLACK, WHITE = '#0e0f12', '#ffffff'

ON_DARK = {'stem': WARM, 'top': SKY, 'bottom': AMBER, 'word': WARM}
ON_LIGHT = {'stem': NIGHT, 'top': SKY_DEEP, 'bottom': AMBER_DEEP, 'word': NIGHT}
SQ3 = math.sqrt(3)



def num(v):
    return f'{v:.2f}'.rstrip('0').rstrip('.')


def pt(p):
    return f'{num(p[0])} {num(p[1])}'


# ---- the mark ----

def ray_circle(p, ang, c, r):
    dx, dy = math.cos(ang), math.sin(ang)
    fx, fy = p[0] - c[0], p[1] - c[1]
    b = 2 * (fx * dx + fy * dy)
    t = (-b + math.sqrt(b * b - 4 * (fx * fx + fy * fy - r * r))) / 2
    return p[0] + dx * t, p[1] + dy * t


def ray_line(p, ang, q1, q2):
    dx, dy = math.cos(ang), math.sin(ang)
    ex, ey = q2[0] - q1[0], q2[1] - q1[1]
    t = ((q1[0] - p[0]) * ey - (q1[1] - p[1]) * ex) / (dx * ey - dy * ex)
    return p[0] + dx * t, p[1] + dy * t


def symbol_parts(stem_width=40):
    """Stem 40 wide (x 58–98, y 32–224); bowl r 72; counter an equilateral triangle (side 80) pointing right;
    the wedge starts 20 inside the counter's tip at ±15°. The arms overlap the stem by 2 so no seam shows."""
    sx1, top, bot, r, a = 98, 32, 224, 72, 40
    sx0 = sx1 - stem_width
    cy = top + r
    centre = (sx1 + 32, cy)
    c_top, c_bot, tip = (sx1, cy - a), (sx1, cy + a), (sx1 + a * SQ3, cy)
    origin = (tip[0] - 20, cy)
    th = math.radians(15)
    rim_t, rim_b = ray_circle(origin, -th, centre, r), ray_circle(origin, th, centre, r)
    cut_t, cut_b = ray_line(origin, -th, c_top, tip), ray_line(origin, th, c_bot, tip)
    j = sx1 - 2
    top_arm = (f'M{num(j)} {top} H{num(centre[0])} A{r} {r} 0 0 1 {pt(rim_t)} L{pt(cut_t)} L{pt(c_top)} '
               f'H{num(j)} Z')
    bottom_arm = (f'M{num(j)} {top + 2 * r} H{num(centre[0])} A{r} {r} 0 0 0 {pt(rim_b)} L{pt(cut_b)} '
                  f'L{pt(c_bot)} H{num(j)} Z')
    stem = f'M{sx0} {top} H{sx1} V{bot} H{sx0} Z'
    return {'stem': stem, 'top': top_arm, 'bottom': bottom_arm, 'right': rim_t[0]}


# The symbol's box inside its 256 × 256 drawing grid: the wedge cuts the bowl's rightmost point, so the right
# edge is where the wedge meets the rim.
SYM_X0, SYM_Y0, SYM_X1, SYM_Y1 = 58, 32, symbol_parts()['right'], 224
SYM_W, SYM_H = SYM_X1 - SYM_X0, SYM_Y1 - SYM_Y0


def symbol_group(colours, x, y, height, ident='symbol', stem_width=40):
    """The symbol with its box's top-left at (x, y), scaled to the given height."""
    k = height / SYM_H
    tx, ty = x - (SYM_X1 - SYM_W - stem_width + 40) * k, y - SYM_Y0 * k
    parts = symbol_parts(stem_width)
    body = '\n'.join(f'    <path fill="{colours[name]}" d="{parts[name]}"/>' for name in ('top', 'bottom', 'stem'))
    return f'  <g id="{ident}" transform="translate({num(tx)} {num(ty)}) scale({k:.4f})">\n{body}\n  </g>'


def symbol_width(height):
    return SYM_W * height / SYM_H


# ---- words ----

class Words:
    """Outlined text from a TrueType font, sized by cap height, without kerning (the font's advances)."""

    def __init__(self, path):
        self.font = TTFont(path)
        self.glyphs = self.font.getGlyphSet()
        self.cmap = self.font.getBestCmap()
        self.cap = self.font['OS/2'].sCapHeight

    def width(self, text, cap):
        """Ink width: from the first glyph's left edge to the last glyph's right edge."""
        names = [self.cmap[ord(ch)] for ch in text]
        k = cap / self.cap
        adv = sum(self.font['hmtx'][n][0] for n in names[:-1])
        first, last = self.bounds(names[0]), self.bounds(names[-1])
        return (adv + last[1] - first[0]) * k

    def bounds(self, name):
        glyph = self.font['glyf'][name]
        return (glyph.xMin, glyph.xMax) if hasattr(glyph, 'xMin') else (0, self.font['hmtx'][name][0])

    def path(self, text, cap, x, baseline):
        """Path data with the ink's left edge at x and the baseline at y = baseline."""
        names = [self.cmap[ord(ch)] for ch in text]
        k = cap / self.cap
        pen = SVGPathPen(self.glyphs, ntos=num)
        pen_x = x - self.bounds(names[0])[0] * k
        for name in names:
            self.glyphs[name].draw(TransformPen(pen, (k, 0, 0, -k, pen_x, baseline)))
            pen_x += self.font['hmtx'][name][0] * k
        return pen.getCommands()


SEMI = None
REG = None


def word_path(colour, text, cap, x, baseline, regular=False, ident='wordmark'):
    face = REG if regular else SEMI
    return f'  <path id="{ident}" fill="{colour}" d="{face.path(text, cap, x, baseline)}"/>'


# ---- files ----

def svg(width, height, body, title, ground=None, radius=0):
    width, height = math.ceil(width - 0.01), math.ceil(height - 0.01)
    rect = f'  <rect width="{num(width)}" height="{num(height)}" rx="{num(radius)}" fill="{ground}"/>\n' if ground else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {num(width)} {num(height)}" width="{num(width)}" '
            f'height="{num(height)}" role="img" aria-labelledby="title">\n  <title id="title">{title}</title>\n'
            f'{rect}{body}\n</svg>\n')


def save(name, text):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / name).write_text(text)
    print(f'docs/brand/{name}')


def mono(colour):
    return {'stem': colour, 'top': colour, 'bottom': colour, 'word': colour}


def symbols():
    """The symbol alone, cropped to its box."""
    for suffix, colours in (('', ON_DARK), ('-light', ON_LIGHT), ('-black', mono(BLACK)), ('-white', mono(WHITE))):
        body = symbol_group(colours, 0, 0, SYM_H)
        save(f'prism-symbol{suffix}.svg', svg(SYM_W, SYM_H, body, 'Prism'))


# Horizontal lockup: symbol 150 tall; wordmark caps 104 tall, centred on the symbol; gap 40 (one stem width ×
# the lockup scale, rounded). Cropped tight; the clear space is in docs/brand/README.md.
LOCK_SYM, LOCK_CAP, LOCK_GAP = 150, 104, 40


def lockups():
    word_w = SEMI.width('Prism', LOCK_CAP)
    width = symbol_width(LOCK_SYM) + LOCK_GAP + word_w
    baseline = LOCK_SYM / 2 + LOCK_CAP / 2
    for suffix, colours in (('', ON_DARK), ('-light', ON_LIGHT), ('-black', mono(BLACK)), ('-white', mono(WHITE))):
        body = (symbol_group(colours, 0, 0, LOCK_SYM) + '\n'
                + word_path(colours['word'], 'Prism', LOCK_CAP, symbol_width(LOCK_SYM) + LOCK_GAP, baseline))
        save(f'prism-lockup{suffix}.svg', svg(width, LOCK_SYM, body, 'Prism'))
    # Stacked: symbol above the wordmark, both centred.
    cap, gap = 72, 40
    word_w = SEMI.width('Prism', cap)
    width = max(word_w, symbol_width(LOCK_SYM))
    body = (symbol_group(ON_DARK, (width - symbol_width(LOCK_SYM)) / 2, 0, LOCK_SYM) + '\n'
            + word_path(WARM, 'Prism', cap, (width - word_w) / 2, LOCK_SYM + gap + cap))
    save('prism-stacked.svg', svg(width, LOCK_SYM + gap + cap, body, 'Prism'))


def icon_tile():
    """The launcher-style tile: the symbol on a night rounded square (radius 22 %)."""
    h = 156
    body = symbol_group(ON_DARK, (256 - symbol_width(h)) / 2, (256 - h) / 2, h)
    save('prism-icon.svg', svg(256, 256, body, 'Prism', NIGHT, 56))


def icon_tile_small():
    """Small-size cut for 16 and 32 px: the symbol at full grid scale (192 tall, 12 px at 16 px) with a 48-wide
    stem, its edges on the 16-px pixel grid (x 48–96, y 32–224)."""
    body = symbol_group(ON_DARK, 48, 32, 192, stem_width=48)
    save('prism-icon-small.svg', svg(256, 256, body, 'Prism', NIGHT, 48))


def adaptive_foreground():
    """Adaptive-icon foreground on Android's 108 × 108 grid: the symbol 50 tall, centred, so its corners stay
    inside the 66-unit safe circle that every launcher mask keeps."""
    h = 50
    body = symbol_group(ON_DARK, (108 - symbol_width(h)) / 2, (108 - h) / 2, h)
    save('prism-adaptive-foreground.svg', svg(108, 108, body, 'Prism'))


def banner():
    """Android TV banner, 320 × 180: the symbol and the name, centred as one group."""
    w, h, sym, cap, gap = 320, 180, 92, 50, 22
    word_w = SEMI.width('Prism', cap)
    x = (w - (symbol_width(sym) + gap + word_w)) / 2
    body = (symbol_group(ON_DARK, x, (h - sym) / 2, sym) + '\n'
            + word_path(WARM, 'Prism', cap, x + symbol_width(sym) + gap, h / 2 + cap / 2))
    save('prism-banner.svg', svg(w, h, body, 'Prism', NIGHT))


TAGLINE = 'One remote for your Android TV box and your LG TV'


def social():
    """GitHub social preview and README hero, 1280 × 640 (2:1): the lockup centred, the tagline under it."""
    w, h, sym, tag_cap, tag_gap = 1280, 640, 180, 28, 84
    cap, gap = LOCK_CAP * sym / LOCK_SYM, LOCK_GAP * sym / LOCK_SYM
    word_w, tag_w = SEMI.width('Prism', cap), REG.width(TAGLINE, tag_cap)
    lock_w = symbol_width(sym) + gap + word_w
    y = (h - (sym + tag_gap + tag_cap)) / 2
    x = (w - lock_w) / 2
    body = (symbol_group(ON_DARK, x, y, sym) + '\n'
            + word_path(WARM, 'Prism', cap, x + symbol_width(sym) + gap, y + sym / 2 + cap / 2) + '\n'
            + word_path(MUTED, TAGLINE, tag_cap, (w - tag_w) / 2, y + sym + tag_gap + tag_cap, regular=True,
                        ident='tagline'))
    save('prism-social.svg', svg(w, h, body, 'Prism: one remote for your Android TV box and your LG TV', NIGHT))


def main():
    global SEMI, REG
    SEMI, REG = Words(SEMIBOLD), Words(REGULAR)
    symbols()
    lockups()
    icon_tile()
    icon_tile_small()
    adaptive_foreground()
    banner()
    social()


if __name__ == '__main__':
    main()
