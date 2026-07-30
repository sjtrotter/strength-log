# strength.log — Session share card (issue #103)

Sharing is warranted, and it is **strictly user-initiated**: a visible SHARE
affordance the user taps. Nothing is ever shared, uploaded, or prepared for
sharing on the app's own initiative. The share sheet is the boundary — the app
has no network permission and gains none here.

Scope: `:app` only. Builds on the journal (docs/briefs/journal.md, merged as
#106); the affordance lives on its session rows.

## 1. The affordance

On an **expanded** session row in the journal: one quiet text button, caps,
`SHARE`, in the row's action register (tertiary, pressed = day accent). No
icons elsewhere, no share on collapsed rows, nothing on the day screen in v1.

## 2. The card

Tapping SHARE renders a PNG on-device and hands it to the system chooser:

- Canvas: 1080×1350 (4:5). `android.graphics.Canvas` drawing — deterministic,
  no off-screen Compose. Barlow Condensed via `ResourcesCompat.getFont`;
  colors via the existing tokens/`DayAccentColors` (no new literals).
- Layout, top to bottom, near-black `#0D0D0F` ground, hairline border inset:
  - date, caps, secondary (`WEDNESDAY · JUL 30`)
  - `DAY A · LOWER` in the day's bright accent (`accentBright`, added by the
    journal), caps
  - the lifts: one line each — name caps secondary, then its heaviest done
    set in condensed numerals primary (`235 × 5`; timed: `0:45`; reps-only:
    `×12`). Cap at 6 lines; more lifts → last line `+2 MORE` faint.
  - footer strip: `21 SETS · 38 MIN · 12,450 LB` condensed numerals, then a
    small `strength.log` wordmark, faint.
- **Never on the card**: bodyweight, goals, notes, anything not listed above.
  The card describes the workout, not the person.

## 3. Plumbing

- FileProvider (manifest `provider`, `exported=false`,
  `grantUriPermissions=true`, paths limited to `cache/shares/`), one file per
  render (`share-<sessionId>.png`, overwrite), delete stale files in that dir
  on each render.
- `ACTION_SEND`, `image/png`, `EXTRA_STREAM` + `ClipData` with
  `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser`.
- Rendering happens on tap, off the main thread, in the ViewModel/use-case
  layer; the card *content model* (lines, strings) is a pure function with
  unit tests (weighted/timed/reps lifts, 6+ lift overflow, volume/duration
  formatting). The Canvas painter stays dumb.

## 4. Acceptance

1. Share requires a tap on SHARE, every time; no other code path constructs a
   share intent.
2. The card contains exactly §2's content — verified by the content-model
   tests; bodyweight nowhere.
3. Provider is unexported, scoped to `cache/shares/`, and no permission was
   added to the manifest.
4. Journal/session-list behavior otherwise unchanged; spec §11 untouched.
