#!/usr/bin/env python3
"""Make the app's static TTFs (res/font) from the variable fonts (Android resources can't hold variable woff2).

Atkinson Hyperlegible Next (Braille Institute) for words, Lexend for numerals; both SIL OFL 1.1 (FONTS-LICENSE.txt).
Get the variable fonts from Google Fonts (TTF or woff2), then:

    pip install fonttools brotli
    python3 tools/fonts.py --atkinson AtkinsonHyperlegibleNext-VariableFont_wght.ttf --lexend Lexend-VariableFont_wght.ttf
"""
import argparse
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'res' / 'font'
# Android resource names: lowercase letters, digits and underscores only.
FACES = [
    ('atkinson', 400, 'atkinson_regular.ttf'),
    ('atkinson', 600, 'atkinson_semibold.ttf'),
    ('lexend', 400, 'lexend_regular.ttf'),
    ('lexend', 600, 'lexend_semibold.ttf'),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--atkinson', type=Path, required=True, help='Atkinson Hyperlegible Next variable font')
    parser.add_argument('--lexend', type=Path, required=True, help='Lexend variable font')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for family, weight, name in FACES:
        source = getattr(args, family)
        font = TTFont(source)
        if 'fvar' in font:
            font = instantiateVariableFont(font, {'wght': weight})
        font.flavor = None  # woff2 to plain TrueType
        font.save(OUT / name)
        print(f'{name}: {source.name} at weight {weight}')


if __name__ == '__main__':
    main()
