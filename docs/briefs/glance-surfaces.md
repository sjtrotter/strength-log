# strength.log — Glance surfaces (issue #100)

The most common interaction with a training log is not a session — it's the
glance: *what's today, and how far in am I?* Right now that glance costs an app
launch. Three read-only surfaces fix it, all speaking the dial's language:

1. **Phone home-screen widget** (this brief's part 1 — ships first, app module
   only)
2. **Watch-face complication** (part 2 — wear module, after the dial-v2 branch
   merges)
3. **Wear tile** (part 3 — same gate)

Shared rules: read-only, on-device, derived entirely from state that already
exists. No new stored facts, no INTERNET, no reaching into UI internals — each
surface derives from the same sources the app/watch already trust. The
per-day accent comes from `DayAccentColors`; success green only for done.

---

## 1. Phone widget

**Technology: classic `AppWidgetProvider` + RemoteViews XML.** Not Glance —
Glance cannot use font resources, and Barlow Condensed *is* this app's face
(principle 5). XML layouts reference `@font/` directly, need zero new
dependencies, and are the boring solution. One widget, resizable within
reason, default ~4×1.

**Content — mirror of the day screen's header truth:**

- Line 1 (caps, condensed semibold, accent-bright of today's accent):
  `DAY A · LOWER` — day letter + emphasis line.
- Line 2 (condensed bold numerals, TextPrimary, the widget's hero):
  - before any set today: `3 LIFTS · 21 SETS`
  - mid-session: `12 / 21 SETS`
  - day finished: `DONE · 21 SETS` (numerals in success green)
- A 3dp horizontal progress bar under the text, day-accent tint on
  `border`-gray track, only while 0 < progress < total.
- Background: `background` #0D0D0F, hairline `border` corner-rounded per
  system widget radius. No icons, no buttons, no emoji.
- Tap anywhere → launch MainActivity.
- No-program state: `SET UP YOUR PROGRAM` single line, tertiary. Never blank.

**Data + freshness (the SSOT constraint, non-negotiable):** the widget renders
from the *same observed flow* `WearSyncPublisher` already uses to decide the
watch snapshot changed — today's day, its sets, done flags, unit. Factor that
source so both consumers share it (derive, never duplicate: the widget must
not re-implement "which day is today" or progress counting). Delivery: a small
app-scope observer (started where WearSyncPublisher is started) pushes
RemoteViews on content change; `onUpdate`/boot re-renders from a synchronous
read of the same source. No WorkManager, no polling.

**Tests:** the render-model derivation (state → widget lines) is a pure
function with unit tests (before/mid/done/no-program). RemoteViews assembly
stays dumb.

## 2. Watch-face complication (after dial v2 merges)

`ComplicationDataSourceService` in `wear/`, supporting:

- `RANGED_VALUE` (preferred): value = sets done today, max = total sets,
  short text = day letter (`A`). The watch face draws the arc — the dial's
  outer ring, living on the face.
- `SHORT_TEXT` fallback: `A` with `12/21` as title.

Day finished → full range with the letter; no-program → `—` with tap-to-open.
Tap action always opens MainActivity. Data comes from the latest snapshot
DataItem already persisted in the Data Layer (readable without the activity);
freshness via `ComplicationDataSourceUpdateRequester` from the existing
snapshot-received path. Complications render in the face's own style — do not
fight it, send data not decoration.

## 3. Wear tile (after dial v2 merges)

`TileService` + ProtoLayout. A mini dial, one glance, one tap:

- Outer `ArcLine`: day progress in success green over a `border` track
  (matches the real dial's outer-ring vocabulary).
- Center: day letter + emphasis (caps), then `12 / 21 SETS` (or the widget's
  before/done variants — reuse the same line logic contract).
- Single clickable = launch MainActivity. No buttons, no lists.
- System font (renderer limitation — accepted; the ring vocabulary carries
  the identity). Colors from the day accent + tokens.
- Freshness: `TileService.getUpdater().requestUpdate` from the same
  snapshot-received path; tiles also refresh on carousel entry.

New deps (wear only): `androidx.wear.tiles:tiles`, protolayout + material.

## 4. Acceptance

1. Widget shows correct before/mid/done/no-program states from a cold start
   (no app process assumptions) and updates within a second of a set ticked on
   the phone; tap opens the app.
2. Widget derivation shares its "today + progress" source with
   `WearSyncPublisher` — one source, two consumers, verified by construction
   (code review) and unit tests on the shared function.
3. Complication serves RANGED_VALUE + SHORT_TEXT with correct values and tap
   intent; requests an update when a new snapshot lands.
4. Tile renders the mini dial from the latest snapshot, updates on request,
   opens the app on tap.
5. No INTERNET, no new stored state, no polling loops; spec §11 untouched.
