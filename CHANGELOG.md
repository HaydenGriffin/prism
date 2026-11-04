# Changelog

## Unreleased

- The launcher fits one screen: a big Continue tile, a grid of favourites, recent apps and then the TV's others ending
  in All apps, and a dock with LG Home, LG Settings, Quick TV settings and Setup. Favourites past the grid's seven
  places stay in All apps.
- A failure gets the launcher's fix-this row, with a Check action, and that row is coloured by how serious it is. Allow
  opens the permission page directly.
- All apps shows four whole rows of six, and names wrap rather than get cut.
- The box's input reads its short name whatever the LG calls that port, and a long input name no longer cuts the row's
  label in Quick TV settings.
- A beam on the launcher's status line shows who has the remote, the box or the LG, beside the same thing in words.
- Themes in Setup: Refract (new default; a soft light follows the focused tile in that app's colour), OLED Night, Dusk
  and Daylight. Setup → Dusk after sunset switches to Dusk when Home Assistant's sun has set.
- Quick TV settings is a sheet over the launcher, with all seven settings on screen.
- Setup lists its rows in two columns, so they all show at once. Pages that do outgrow the screen fade at the edge.
- Setup's red adb paragraph is gone: OK on Stay connected in sleep shows the command when you want it.
- "Remote follows the TV" and "Choose your TV" name things more plainly, and Monitoring reads On or Off.
- App tiles keep the app's art on its own colour and the name underneath, so a short tile's initial no longer runs
  into its name.

## 1.0.1

- Prism stays open behind what it opens on the LG, so LG Settings and other LG menus that cover part of the screen
  appear over Prism instead of the box's home screen. Setup → **Close after opening on the LG** brings back the old
  behaviour, where Prism closes once the TV shows what it opened.
- New logo: a P whose counter is a prism, its bowl split into a sky arm (the LG) and an amber arm (the box).
  Adaptive launcher icon with a themed-icon layer, a new TV banner and a GitHub social preview
  ([`docs/brand/`](docs/brand)).

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
