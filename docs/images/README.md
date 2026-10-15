# Images

`social.png` (1280 × 640) is the GitHub social preview, drawn by `tools/brand.py`; upload it under the
repository's Settings → Social preview.

`hero.png` is a placeholder drawn by `tools/brand.py` from the same artwork. Replace it with a real screenshot
from an Android TV box (2400 × 1200 or larger, 2:1), or a composite of three:

1. **The launcher**: the Continue tile, a full row of favourites with their LG icons, and the live line reading
   "On the LG · …".
2. **Quick TV settings**: Input on the box, picture mode and OLED brightness in view.
3. **The home-screen rows in Projectivy**: "LG apps" and "LG commands" with a card focused.

On the box: `adb exec-out screencap -p > launcher.png`. Use made-up favourites and no real Home Assistant address,
and keep the Home Assistant page (it blocks screenshots anyway) out of it.
