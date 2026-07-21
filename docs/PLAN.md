# strength-log — Delivery Plan & Spec Review

Reviewed against `STRENGTH_TRACKER_SPEC.md` (v2) on 2026-07-06. Decisions below
were confirmed with Stephen; where this document amends the spec, this document
wins.

## Locked decisions

| Decision | Choice |
|---|---|
| Repo | Public, `sjtrotter/strength-log` |
| Storage/sync (v1) | Local-first (Room/DataStore) + file export/import via share sheet & SAF. No accounts, no INTERNET permission. Automatic cloud backup (Drive appDataFolder) is a v2 milestone. |
| Wear OS | **In scope for v1**, built last (spec build order stands) |
| Exercise library | ~200 curated entries incl. barbell/dumbbell/kettlebell/machine/cable/bodyweight variants, **plus user-created custom exercises** |

## Spec review — verdict

The spec is unusually good: the domain math is pinned with verification numbers,
the behavioral contracts (cascade, superset alignment, per-date checkmarks) are
prototype-proven, and the layering (`:domain` pure Kotlin → `:data` → UI → Wear)
is exactly right. **Nothing in §3–§6 or §8–§9 needs structural change.** The
amendments below are additions the new requirements force, plus gaps found in
review.

## Amendments (A1–A11)

### A1. Session history is now required (biggest change)

Spec §10 excludes history. But CSV export, Health Connect writes, and honest
"what did I actually do" tracking all need per-session records. The current
schema (`exercise_log` keyed by program slot) only holds the *latest* state and
is silently overwritten every session.

**Change:** keep `exercise_log` exactly as specced (it's the live working state
and the GOAL/ACTUAL seed), and additionally append an immutable session record
when the user taps **DONE — advance**:

```
workout_session(id, dayId, dayTitle, startedAt?, completedAt, bodyweightLb)
session_set(sessionId, exerciseId, exerciseName, slot, setIndex, kind,
            weightLb, reps, done)
```

- Written once, never mutated → no sync/consistency headaches.
- Denormalized names so history survives program edits and exercise deletion.
- Trend **charts stay out of v1** (spec §10 stands); history exists as data +
  a plain reverse-chronological list screen ("Log" tab) + the export source.
- Bonus the prototype couldn't do: the day screen can show a subtle
  "last time: 185×8" per exercise from history.

### A2. Export / import (v1)

- **Full backup**: single versioned JSON file (schema version field, forward-
  migratable), containing config, program, live logs, custom exercises, and
  session history. Export via share sheet or SAF "save as"; import restores
  atomically with a confirm-overwrite dialog. This is the transfer mechanism —
  user parks it in Drive/email/wherever they like.
- **CSV history export**: one row per set, **Strong-app-compatible column
  layout** (the de facto interchange format that Hevy/FitNotes/spreadsheets
  understand): Date, Workout Name, Exercise Name, Set Order, Weight, Reps, etc.
- **CSV import**: accept Strong/Hevy-style CSVs into session history (best-
  effort exercise-name matching; unmatched names become custom exercises).
- Validate all imported files defensively (size caps, schema check, no partial
  writes — import into a staging transaction, commit only if fully valid).
- Enable **Android Auto Backup** (`dataExtractionRules`) so Room/DataStore ride
  along with device-to-device transfer and Google One backups for free.

### A3. Health Connect integration (v1)

Health Connect is Android's standard fitness interchange (built into Android
14+, app-provided on 9–13); Google Fit's APIs are deprecated in its favor, and
it's how Fitbit/Samsung Health/Garmin interop now works.

- **Write** on session completion: `ExerciseSessionRecord`
  (STRENGTH_TRAINING) with per-exercise `ExerciseSegment`s + rep counts.
- **Read (import/connect)**: list other apps' strength sessions in the Log
  screen (marked as external); optionally read latest `WeightRecord` and offer
  "bodyweight changed — update your GOALs?" (never silently, per GOAL-vs-ACTUAL
  principle).
- Health Connect is on-device IPC — **no INTERNET permission needed**, so the
  local-first promise holds.
- All HC permissions requested lazily and individually; app fully functional
  when denied. Requires the Play-required privacy policy for health perms —
  trivial since we collect nothing (static page in the repo, GitHub Pages).

### A4. Expanded library + kettlebells + custom exercises

- Add `KETTLEBELL` to `Equipment`. Extend the catalog to ~200 entries by
  filling each `MovementPattern` with its barbell/dumbbell/kettlebell/machine/
  cable/bodyweight variants (KB swing, goblet press, KB front-rack squat, etc.),
  each with pattern, equipment, `perHand`, GOAL source, and `subRank`. The
  spec's 60 entries keep their ids and ranks (verification numbers depend on
  defaults staying default).
- **Custom exercises**: user picks name + pattern + equipment + perHand +
  starting weight → stored in Room, merged with the code catalog behind one
  `ExerciseCatalog` repository (SSOT: code = seed truth, Room = user overlay;
  custom ids prefixed `custom_` to never collide).
- Substitution picker with 200 entries needs **search + equipment filter**
  (spec deferred equipment filtering at 60 entries; at 200 it's necessary).
  Wizard gains one **optional** "what equipment do you have?" step (default:
  everything) that filters generator picks and sorts substitutions —
  skippable so "next-next-next" still works.

### A5. Units: lb and kg

Spec is lb-only; kettlebells and non-US users are kg-native.

- Store canonically in **lb** (all spec math/rounding is lb-calibrated; pinned
  tests stay untouched), display in the user's unit.
- Unit-aware rounding for display/steppers: 5 lb / 2.5 kg standard, 2.5 lb /
  1.25 kg for light isolation. A "unit" DataStore pref + one conversion point
  (SSOT). Exports carry an explicit unit column; Health Connect takes SI.

### A6. Persistence hardening (the reason this rewrite exists)

- Every mutation is a suspend DAO call committing immediately; Room in WAL mode.
- ViewModels hold no unsaved truth: UI state derives from `Flow`s off Room/
  DataStore; ephemeral UI state (collapse overrides, view-day override) via
  `SavedStateHandle` so rotation/process death can't lose anything.
- `:data` gets an instrumented **process-death test** (kill mid-edit, relaunch,
  assert state) and serialization round-trip tests, per spec §11.
- "Today" for checkmark reset = device-local calendar date (document the
  midnight/DST edge in a test).

### A7. Gym-floor usability (small, high-value)

- **Keep-screen-on toggle** during a workout day screen.
- Touch targets ≥48 dp; steppers get content descriptions (TalkBack) and
  long-press-to-repeat.
- Edge-to-edge, predictive back, font-scale tolerance — standard MAD hygiene.

### A8. Wear OS (confirmed in v1)

As specced (§9): `:wear` shares `:domain`, Compose for Wear + Horologist,
Wearable Data Layer (`DataClient` snapshots + `MessageClient` deltas),
last-write-wins, phone is source of truth. Built last. Watch also gets the
keep-screen-on/ambient treatment.

### A9. Release & ops

- **CI (GitHub Actions)**: build + unit tests + lint on every PR; the pinned
  §11 domain tests are a required status check. `main` is protected: PRs only,
  no force push, checks must pass (the CLAUDE.md Fable loop governs merges).
- Signing keys live outside the repo (public repo!). Play listing later:
  one-time price, data-safety form = "no data collected/shared" — the honest
  local-first story is the marketing.
- Versioned DB schema from day one (`exportSchema = true`, migration tests).

### A10. Deferred (v2 backlog, in rough order)

Drive appDataFolder auto-backup → trend charts over session history → plate
calculator → home-screen widget ("today: Day B") → light theme →
tablet layouts → RPE.

(Rest timers left this backlog on 2026-07-20 — now in scope; see A11.)

### A11. Rest timers are now in scope (watch-primary)

Spec §10 lists rest timers as a v1 non-goal, and A10 parked them in the backlog.
On the user's request (2026-07-20) they move into scope as a watch-first
feature: after you tick a set, the watch runs a single-haptic countdown for a
rest tuned to what the set *is*. The resolver and its signed-off defaults live
in `domain/standards/RestPolicy.kt` (the SSOT — its doc comments carry the
design); it rides on the read-only watch-logger rework of the same effort.

- **Source of truth is a pure `:domain` policy, not stored data.** Rest varies
  with the set, and the set already encodes that (`SetKind` + `TrackingType`).
  `domain/standards/RestPolicy.kt` maps every set to one of five `RestCategory`
  buckets (RAMP/TOP/BACKOFF/WORK/LIGHT) and resolves an effective rest through
  a single function; nothing is stored per set/exercise/program.
- **Editable defaults.** The five bucket durations ship with signed-off
  defaults (90/180/120/90/60s) and are user-editable as per-category overrides
  in DataStore (`SettingsStore`), gated by a master "Rest timer on watch"
  toggle (default ON). `0` means "no timer". Per-exercise overrides stay
  deferred behind the same resolver.
- **Device-local for now.** Rest prefs are settings, not workout data, so they
  are deliberately outside the backup payload (revisit in a later backup
  version — additive).
- **Wire is additive.** `WatchSet.restAfterSeconds` is stamped phone-side from
  the resolver; `schemaVersion` stays 1, both directions stay compatible (old
  wire decodes to 0 = no timer). The watch counts a number down, it never
  computes one.

Delivered across PRs W2a (model + wire + settings plumbing, this amendment),
W2b (watch timer engine), W2c (Setup UI), W2d (watch countdown UI).

## Architecture

```
:domain     pure Kotlin — types, catalog seed, goal math, seeding, cascade,
            generator, rotation. Zero Android deps. (spec §3–§6)
:data       Room (program, live logs, sessions, custom exercises), DataStore,
            TrackerRepository, ExerciseCatalog. (spec §7 + A1/A4)
:transfer   backup JSON, CSV export/import, Health Connect client. (A2/A3)
:app        phone UI — Compose, Material3 w/ custom theme, MVVM, Hilt,
            single activity. (spec §8)
:wear       watch UI + Data Layer sync. (spec §9)
```

Kotlin 2.x, AGP current stable, version catalog (`libs.versions.toml`),
kotlinx.serialization, Hilt, JUnit5 + Turbine for flows, Robolectric where an
Android class is unavoidable in `:data` tests. Phone `minSdk 26` (Health
Connect floor), `targetSdk` latest; Wear `minSdk 30`.

## Milestones → PRs (each PR runs the CLAUDE.md Fable loop)

| # | Milestone | PRs (delegation tier) |
|---|---|---|
| M0 | Scaffold | Gradle + modules + version catalog + CI + branch protection (Sonnet) |
| M1 | `:domain` | types & constants + pinned §11 tests (Opus); catalog data entry to ~200 (Sonnet, from a Fable-approved exercise list); generator + rotation + cascade (Opus) |
| M2 | `:data` | Room schema incl. history + DataStore + repository (Opus); process-death & round-trip tests (Sonnet) |
| M3 | Phone UI | theme/design system (Sonnet); wizard (Sonnet); day screen incl. cascade/supersets/collapse (Opus — densest behavioral contract); day-edit sheet + setup screen (Sonnet); custom-exercise flow (Sonnet) |
| M4 | `:transfer` | JSON backup/restore (Opus — atomicity); CSV export/import (Sonnet); Health Connect (Opus) |
| M5 | `:wear` | watch UI (Sonnet); Data Layer sync (Opus); phone-restart survival test (spec §11.4) |
| M6 | Polish/release | icon, keep-screen-on, a11y pass, privacy page, Play prep (Sonnet) |

Manual verification gates: spec §11's numbers after M1; §8.2's behavioral
checklist after M3; watch-edit-survives-restart after M5.

## Risks / watch items

- **Catalog quality at 200 entries** — GOAL sources (`flat`/`frac`) need sane
  values per entry; Fable reviews the full table before data entry (it's the
  SSOT for every suggested weight).
- **Health Connect availability** varies by device/Android version — feature
  must degrade invisibly.
- **Wear Data Layer** is the fiddliest platform surface in the plan; it's last
  deliberately, and `:domain` purity keeps it thin.
- **CSV import name-matching** is inherently fuzzy — best-effort with a
  preview/confirm screen, never silent guessing.
