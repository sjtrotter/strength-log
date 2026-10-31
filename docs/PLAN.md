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

## Amendments (A1–A13)

### A1. Session history is now required (biggest change)

Spec §10 excludes history. But CSV export, Health Connect writes, and honest
"what did I actually do" tracking all need per-session records. The current
schema (`exercise_log` keyed by program slot) only holds the *latest* state and
is silently overwritten every session.

**Change:** `exercise_log` holds the plan's working values. A workout run holds
today's sets and is archived to `workout_session` at Finish:

```
workout_session(id, dayId, dayTitle, startedAt?, completedAt, bodyweightLb?)
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

The backup carries every saved plan from schema v8 (A13); older backups restore as one plan.

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
- A workout run has no date. It survives midnight and process death, and it ends
  only by Finish or Discard.

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

### A12. Watch UI is the concentric dial (2026-07-28)

The list-based watch screens are replaced wholesale by the dial redesign —
normative spec: `docs/briefs/wear-dial-redesign.md` (#84). One composable
renders the whole workout as concentric rings + a single 176px tap target;
navigation is the crown, not scrolling. Consequences that supersede A8/§9
details:

- **Horologist and `ScalingLazyColumn` are gone** from `:wear`; A8's "Compose
  for Wear + Horologist" is now just Compose for Wear.
- **Wire grew two facts** (additive, `schemaVersion` still 1):
  `SetEditDelta.startedAtMillis`/`completedAtMillis` — wall-clock stamps for a
  set's START and tick, persisted through to archived history (DB v3). The
  phone derives active time and actual rest; the watch sends facts. An untick
  retracts its stamps.
- **The watch's outbound queue settles per field** (a tick delta is
  multi-field: done + both stamps).
- **One tap locks a set in; undo is a deliberate 700ms hold.** No untick-by-tap.
- §6 Swap is deferred until alternates ride the snapshot (#90).

### A13. Saved plans, authored prescriptions, cardio as sets (2026-10-19)

Normative design: `docs/briefs/plans-review-2026-10-19-r2.md` (round 2 of a
two-model review, owner decisions recorded at its head). Supersedes the
spec's single-program model (§7), and in `docs/briefs/cardio-promotion.md`
the rule that no day completes through cardio (§8): a cardio-only day now
finishes like any other day. Finishers stay optional and never gate a
strength day; the shipped cardio vectors stay pinned.

- **Plans.** The app holds any number of named plans; one is selected for
  Today and the watch. A plan owns its days, prescriptions, live working
  values, rotation position, emphasis, equipment and cardio prefs.
  Bodyweight, age, level and units stay global. The wizard always creates a
  new plan; nothing deletes another plan's working values. Sessions record
  the plan they ran under; history from before plans has an unknown plan.
- **Identity on the wire.** Snapshots carry the run: the open run, or the day's next run ID. Deltas carry the run and the exercise. A delta for a finished or unknown run is rejected. A delta with no run (an old watch) only edits a run the phone opened after that day's last close. `schemaVersion` stays 1; a field's presence is the capability.
- **Exercise floor (§8.3 amendment).** Generated days lose the floor of three; every day keeps at least one exercise unless it has a cardio block.
- **Authored prescriptions.** A plan's exercises are generated (spec §4 math,
  seeding and cascade, unchanged) or authored: load × reps, reps, hold,
  reps-in-time, each side, a band described in text, a note. Authored slots
  have no GOAL and never cascade. Authored days may hold one exercise and may
  repeat in place instead of rotating.
- **Timed rep events.** The clock runs with the screen kept on (no dimming,
  no lock), and when time ends the count entry (a stepper) comes up by
  itself. The lifter records the count.
- **Before production:** plans, run identity, authored prescriptions and
  timed rep events, on phone and watch; a plan switcher on Today; and cardio
  as typed sets (warm-up, work, recovery, cool-down; time ends a set by
  itself, distance ends it with a tap, the other measure may be an aim),
  activity types and manual distance. Cardio-only plans (a "Running" plan
  alternated with a "Strength" plan) are built on those typed sets, not on
  the older cardio suggestion block. (Owner, 2026-10-20; Sol's memo:
  `docs/briefs/plans-separate-cardio-2026-10-20-sol.md`.)
- **After production:** cross-plan "Last" with a 28-day evidence review that
  shows what was done and leaves values editable, with no automatic deload
  and no pre-filled lighter number; Health Connect run linking; and every
  record type Health Connect can hold. Each new Health Connect permission
  ships with its rationale line, privacy policy text, Data safety answer and
  Play declaration. GPS only if manual and linked distance fall short, and
  then on the watch first.
- **Plans stay generic and rotation-based.** No stored plan kind (derive one
  if a screen ever needs it); alternating plans by weekday is the lifter
  switching, not a calendar.
- §11 numbers and the shipped cardio vectors do not move.

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
