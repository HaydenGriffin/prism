#!/usr/bin/env python3
"""Draw the README hero and the README screenshots from real screenshots of the app on an Android TV box.

    docs/images/hero.png                         3200 × 1640: LG Settings over the launcher on a TV, Quick TV
                                                 settings and the "LG apps" row beside it, with the lockup and
                                                 tagline
    docs/images/screenshots/launcher.png         the launcher
    docs/images/screenshots/quick-settings.png   Quick TV settings
    docs/images/screenshots/home-rows.png        the "LG apps" row in Projectivy, cropped to the row

The screenshots are 1920 × 1080 PNGs taken on the box with `adb exec-out screencap -p > launcher.png`:

    pip install pillow
    python3 tools/hero.py SCREENS_DIR    # holds launcher.png, quick.png and rows.png

The TV on the hero shows docs/images/tv/lg-settings.jpg, a screenshot the LG took of itself while LG Settings was
open over Prism on the box. On a rooted LG, webOS's capture service saves whatever is on screen, the box's input
included:

    luna-send -n 1 luna://com.webos.service.capture/executeOneShot \\
        '{"path": "/tmp/capture.jpg", "method": "DISPLAY", "width": 1920, "height": 1080, "format": "JPEG"}'
"""
import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from brand import BRAND, ROOT, hex_rgb, render, save_png

OUT = ROOT / 'docs' / 'images'
TV = OUT / 'tv'
FONTS = ROOT / 'res' / 'font'

NIGHT = hex_rgb('#0e0f12')
NIGHT_RAISED = hex_rgb('#17191e')
WARM_WHITE = hex_rgb('#f4f2ee')
MUTED = hex_rgb('#b9b4ac')
SKY = hex_rgb('#80d8ff')
AMBER = hex_rgb('#ff9d5c')

WIDTH, HEIGHT = 3200, 1640
MARGIN = 160
TAGLINE = [  # (text, font, colour, line height)
    ('One remote for your Android TV box and your LG TV.', ('semibold', 54), WARM_WHITE, 70),
    ('A launcher for the LG, quick TV settings and LG rows on your home screen.', ('regular', 42), MUTED, 58),
]

# Regions of the 1920 × 1080 screenshots (left, top, right, bottom).
ROWS_CROP = (40, 650, 1880, 1048)     # the "LG apps" row; below it Projectivy starts the next row's title
ROWS_HERO_CROP = (40, 740, 1880, 1048)  # the row's cards, for the hero (its caption names the row)
QUICK_CROP = (40, 222, 1880, 772)     # the first four settings, for the hero (its caption is the title)


def font(weight, size):
    return ImageFont.truetype(str(FONTS / f'atkinson_{weight}.ttf'), size)


def rounded_mask(size, radius):
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def glow(canvas, centre, radius, colour, strength):
    """A soft coloured light on the background, centred on a point."""
    layer = Image.new('L', canvas.size, 0)
    x, y = centre
    ImageDraw.Draw(layer).ellipse((x - radius, y - radius, x + radius, y + radius), fill=int(255 * strength))
    layer = layer.filter(ImageFilter.GaussianBlur(radius * 0.6))
    canvas.paste(Image.new('RGB', canvas.size, colour), (0, 0), layer)


def shadow(canvas, box, radius, opacity=0.55, blur=60, offset=30):
    left, top, right, bottom = box
    layer = Image.new('L', canvas.size, 0)
    ImageDraw.Draw(layer).rounded_rectangle((left, top + offset, right, bottom + offset), radius,
                                            fill=int(255 * opacity))
    canvas.paste(Image.new('RGB', canvas.size, (0, 0, 0)), (0, 0), layer.filter(ImageFilter.GaussianBlur(blur)))


def edge(canvas, box, radius, alpha=26):
    """A hairline on a panel's edge so it lifts off the night ground."""
    layer = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(box, radius, outline=WARM_WHITE + (alpha,), width=2)
    canvas.paste(layer, (0, 0), layer)


def place(canvas, image, box, radius):
    left, top, right, bottom = box
    size = (right - left, bottom - top)
    canvas.paste(image.resize(size, Image.LANCZOS), (left, top), rounded_mask(size, radius))


def tv(canvas, screen, left, top, width):
    """The screenshot on a thin-bezelled TV with a low stand."""
    bezel, radius = 18, 22
    height = round(width * 9 / 16)
    frame = (left - bezel, top - bezel, left + width + bezel, top + height + bezel)
    stand_w, stand_h = width // 4, 22
    stand_x = left + (width - stand_w) // 2
    stand = (stand_x, frame[3], stand_x + stand_w, frame[3] + stand_h)
    shadow(canvas, frame, radius + bezel)
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(stand, 8, fill=NIGHT_RAISED)
    draw.rounded_rectangle(frame, radius + bezel // 2, fill=(26, 28, 33))
    edge(canvas, frame, radius + bezel // 2, alpha=30)
    place(canvas, screen, (left, top, left + width, top + height), 6)
    return frame


def panel(canvas, image, left, top, width, radius=28):
    height = round(width * image.height / image.width)
    box = (left, top, left + width, top + height)
    shadow(canvas, box, radius, opacity=0.5, blur=44, offset=22)
    place(canvas, image, box, radius)
    edge(canvas, box, radius)
    return box


def wrap(draw, text, face, width):
    lines, line = [], ''
    for word in text.split():
        trial = f'{line} {word}'.strip()
        if line and draw.textlength(trial, font=face) > width:
            lines.append(line)
            line = word
        else:
            line = trial
    return lines + [line]


def text_column(canvas, left, top, width):
    """The lockup and the tagline; returns the y below them."""
    lockup_h = 120
    lockup = render(BRAND / 'prism-lockup.svg', round(lockup_h * 543 / 150), lockup_h)
    canvas.paste(lockup, (left, top), lockup)
    draw = ImageDraw.Draw(canvas)
    y = top + lockup_h + 64
    for text, (weight, size), colour, leading in TAGLINE:
        face = font(weight, size)
        for line in wrap(draw, text, face, width):
            draw.text((left, y), line, font=face, fill=colour)
            y += leading
        y += 18
    return y


def caption(canvas, text, left, bottom):
    """A small spaced-out label that sits just above a panel."""
    face = font('semibold', 30)
    draw = ImageDraw.Draw(canvas)
    x = left
    for char in text.upper():
        draw.text((x, bottom), char, font=face, fill=MUTED, anchor='ls')
        x += draw.textlength(char, font=face) + 5


def captioned_panel(canvas, image, label, left, bottom, width):
    """A panel whose bottom edge is at `bottom`, with its caption above it; returns the caption's top."""
    height = round(width * image.height / image.width)
    top = bottom - height
    panel(canvas, image, left, top, width)
    caption(canvas, label, left + 4, top - 26)
    return top - 26 - 30


def hero(settings, quick, rows):
    canvas = Image.new('RGB', (WIDTH, HEIGHT), NIGHT)
    glow(canvas, (2100, 820), 1100, SKY, 0.07)
    glow(canvas, (420, 1400), 700, AMBER, 0.045)

    column_w = 820
    gap = 120
    screen_w = WIDTH - 2 * MARGIN - column_w - gap - 36
    screen_h = round(screen_w * 9 / 16)
    top = (HEIGHT - screen_h) // 2 - 10
    screen_left = MARGIN + column_w + gap + 18
    tv(canvas, settings, screen_left, top, screen_w)
    caption(canvas, 'LG Settings over Prism, driven by the Shield remote', screen_left - 14, top - 50)

    below_text = text_column(canvas, MARGIN, top - 18, column_w)
    bottom = top + screen_h + 18
    rows_top = captioned_panel(canvas, rows, 'LG apps row in Projectivy', MARGIN, bottom, column_w)
    quick_top = captioned_panel(canvas, quick, 'Quick TV settings', MARGIN, rows_top - 64, column_w)
    assert quick_top > below_text + 40, f'the panels run into the tagline ({quick_top} <= {below_text + 40})'
    return canvas


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('screens', type=Path, help='folder with launcher.png, quick.png and rows.png')
    args = parser.parse_args()
    launcher = Image.open(args.screens / 'launcher.png').convert('RGB')
    quick = Image.open(args.screens / 'quick.png').convert('RGB')
    rows = Image.open(args.screens / 'rows.png').convert('RGB')
    settings = Image.open(TV / 'lg-settings.jpg').convert('RGB')

    save_png(hero(settings, quick.crop(QUICK_CROP), rows.crop(ROWS_HERO_CROP)), OUT / 'hero.png')
    shots = OUT / 'screenshots'
    save_png(launcher, shots / 'launcher.png')
    save_png(quick, shots / 'quick-settings.png')
    save_png(rows.crop(ROWS_CROP), shots / 'home-rows.png')


if __name__ == '__main__':
    main()
