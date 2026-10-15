# Changelog

## 1.0.0

The first public release.

- The remote follows the TV: while an LG app, LG Home or LG Settings is in front (even above the box's HDMI input),
  arrows, OK, Back, play/pause, rewind and fast-forward go to the LG through Home Assistant over one websocket.
  Hold Back for 0.8 s, or press Home, to go back to the box's input.
- A launcher for the LG: a Continue tile for the last LG app, favourites with the apps' own LG icons, recently used
  apps, all apps, LG Home, LG Settings.
- Quick TV settings: input, picture mode, OLED brightness, energy saving, sound mode, sleep timer, logo dimming.
- Two Android TV home-screen rows, "LG apps" and "LG commands", for launchers that show channels (such as
  Projectivy).
- The LG on-screen keyboard is driven by the remote while it's up (needs a Glasshouse change not upstream yet).
- Setup → Remotes: every input device seen pressing keys, with a switch for each. Physical remotes on, virtual
  devices off until chosen; the TV's own remote over HDMI-CEC is always off, since it would loop back to the TV.
- The box is called by its own name from Android's settings ("On the SHIELD"), or "this box".
- Setup → Monitoring: an optional heartbeat into an `input_text` of your choice, off by default.
- Shortcuts for button mappers, and an optional on-TV cue through Glasshouse's screen notification.
- Builds without Gradle: `tools/build.py` for debug builds, `--release` signs with a key from `PRISM_*`
  environment variables and writes `SHA256SUMS`. Host tests in `tools/test.py`.
