# Images

`social.png` (1280 × 640) is the GitHub social preview, drawn by `tools/brand.py`; upload it under the
repository's Settings → Social preview.

`hero.png` (3200 × 1640) and `screenshots/` are drawn by `tools/hero.py` from 1920 × 1080 screenshots taken on an
NVIDIA Shield (`adb exec-out screencap -p > launcher.png`): the launcher, Quick TV settings, and Projectivy's home
screen with the "LG apps" row. Use made-up favourites and no real Home Assistant address, and keep the Home
Assistant page (it blocks screenshots anyway) out of them.

`tv/` holds screenshots the LG took of itself, so they show its own menus over the box: `tv/lg-settings.jpg` is LG
Settings opened from Prism's launcher. They come from webOS's capture service on a rooted LG (see `tools/hero.py`);
the hero's TV shows `tv/lg-settings.jpg`.
