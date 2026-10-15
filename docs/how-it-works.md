# How it works

## Routing

`AutomaticService` is an accessibility service that does key filtering only. It holds one websocket to Home
Assistant with one `subscribe_entities` on two of the TV's Glasshouse entities: the active-app sensor and the
Launch App select. Every change is pushed, including Glasshouse's focused-window id (`control_app_id`), so LG Home,
LG Settings and the LG's quick settings above an HDMI input are routed to the LG too.

- **The app catalog** is the select's pushed `app_ids`. While it's missing or unavailable (the TV is off), nothing
  goes to the LG and the live line says "The TV is off". Prism doesn't reconnect for that.
- **The keyboard.** The same sensor's `keyboard_visible` attribute routes keys to the LG while the LG keyboard is up,
  whatever is in front.
- **Presses** go as `call_service` (`button.press`) on the same socket, with a 2 s limit and no retries. At most
  three presses wait; held arrows repeat at most every 150 ms. Anything other than a confirmed press drops what is
  queued behind it.
- **Which devices.** A press goes to the LG only if its input device is switched on in Setup → Remotes (see the
  README for the defaults). Devices are remembered by Android's input device descriptor.
- **Staying awake.** Each forwarded press counts as user activity (a wake lock acquired and released at once).
  Consumed keys never reach Android, so without it the box would fall asleep in the middle of an LG session.

## The link

A ping every 5 s; 15 s of silence reconnects, backing off 1, 2, 5 and 10 s. Silence is measured on a clock that
counts deep sleep (`elapsedRealtime`), so a link that died while the box slept is never taken for healthy. When the
screen turns on, Prism reconnects at once (or pings at once if the link has been quiet), because Doze cuts the
app's network while the box sleeps.

While the link is healthy, the last pushed target stays valid, so a slow Home Assistant doesn't drop control. A dead
link sends keys to the box.

On every connect Prism re-reads Home Assistant's entity and device registries for the saved TV and follows added,
removed or renamed entities. The box's input stays as chosen.

The Home Assistant user needs only entity services and `subscribe_entities`. It never needs `mqtt.publish`, which
Home Assistant keeps for admins.

## Monitoring

When Setup → Monitoring is on, the service sets the chosen `input_text` to `<mode> · <version>` (for example
`lg · 1.0.0`) at once on connect and reconnect, on every mode change, and every 60 s, over the same socket but off
the key path. A value counts as sent only once Home Assistant accepts it, so a failed write is retried on the next
5 s tick. A refusal (the helper doesn't exist) is logged once and changes nothing else.

The modes are `off`, `setup`, `connecting`, `shield` (keys stay on the box), `lg`, `other_input`, `paused`, `tv_off`, `error` and
`no_permission`.

## Home-screen rows

`ChannelSync` keeps two Android TV preview channels in step with the favourites, the last LG app and the TV's
inputs. It's idempotent (rows and cards are matched by their internal provider id, so repeated runs write
nothing new), runs on its own low-priority thread, and logs a failure once without ever touching the remote. Card
images are drawn on the box and served read-only by `ArtProvider`
(`content://io.github.haydengriffin.prism.art/…`).

## The look

Native Android TV views for a 960 × 540 dp screen read from the sofa, driven with a D-pad only. A solid night
ground, panel tiles with rounded corners, a sky focus ring with a small lift, warm-white words and muted labels.
Atkinson Hyperlegible Next for words and Lexend for every digit, because Atkinson's slashed zero reads as "Ø" from
across a room. Margins are overscan-safe (48 and 28 dp).
