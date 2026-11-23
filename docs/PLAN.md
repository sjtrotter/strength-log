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

## Amendments (A1–A20)

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
- Generated plans use only ticked equipment: an unavailable anchor becomes its
  pattern's best available weighted lift, a slot nothing available can fill is
  left out, and a day left empty is dropped (#499).

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

- **Keep screen on**: Off, While a timer runs (default) or During workouts; set-end and rest buzzes work with the screen off.
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
  values, rotation position, equipment and typed lift goals.
  Bodyweight, age and units stay global. The wizard always creates a
  new plan; nothing deletes another plan's working values. Sessions record
  the plan they ran under; history from before plans has an unknown plan.
- **Identity on the wire.** Snapshots carry the run: the open run, or the day's next run ID. Deltas carry the run and the exercise. A delta for a finished or unknown run is rejected. A delta with no run (an old watch) only edits a run the phone opened after that day's last close. `schemaVersion` stays 1; a field's presence is the capability.
- **Exercise floor (§8.3 amendment).** Generated days lose the floor of three; every training day keeps at least one exercise unless it has a cardio block. Rest entries have no exercises or cardio.
- **Authored prescriptions.** A plan's exercises are generated (spec §4 math,
  seeding and cascade, unchanged) or authored: load × reps, reps, hold,
  reps-in-time, each side, a band described in text, a note. Authored slots
  have no GOAL and never cascade. Every training day keeps at least one exercise
  unless it has a cardio block. Rotation visits every training day in plan
  order, lift and cardio alike (#493). Generated Cardio + Core extras
  (C1/C2 marked `ProgramDayKind.CARDIO`) stay outside the rotation; legacy
  plans containing only those extras retain their all-days fallback. Initial
  and fallback selection use the same order. Finishing a day out of order
  leaves the suggestion unchanged.
- **Rest entries (#495).** A plan may hold lettered Rest entries (no exercises,
  no cardio). The rotation visits them in order. Today shows “C — Rest” with
  “Rest taken”, which advances once, and “Skip rest and train”, which opens the
  next training day; finishing the training day after a rest moves past it, and
  finishing any other day leaves the rest up next. Nothing is recorded: calendar
  gaps consume nothing, and rest never counts as a workout, volume or progression.
  Other surfaces show the training day after a rest. Rest entries are added, moved
  and removed on the Plan screen; the cardio-first wizard places 0 to 2 (after the
  long run, then after intervals, or the first Easy run without intervals).
  Backup v24 carries them; Room remains v22.
- **Set structures amendment (owner decision 2026-11-02, #415):** a ramped structure on an authored load × reps uses that load as the top set and cascades; straight-set prescriptions never cascade.
- **Cardio and Finish (owner decision 2026-11-05, #469):** logged cardio counts toward Finish and appears on the day’s receipt; Finish ends an open cardio run and logs the rounds done; Discard keeps saved cardio; a block of one timed set counts up and ends when the lifter stops.
  A finished cardio session can be logged after the fact from the day's cardio page (activity, date and start time, duration, optional distance and note); it is stored as one finished timed work set and counts toward its day like a live one (#498). Room v22 adds `cardio_session.note` through migration 21→22; backup v22 carries it. History edits a cardio session's activity and note, and the duration and distance of a one-set session; date and start time are read-only, with no delete action.
- **A cardio run survives leaving the day (#627):** a cardio run in progress, including edits made before Start, is
  kept on the device per plan and day until it is logged, ended or invalidated, and comes back when that day's
  workout opens again; a copy last changed on an earlier local day is dropped. The JSON backup does not carry it,
  and a restore clears it.
- **Timed rep events.** The clock runs with the screen kept on (no dimming,
  no lock), and when time ends the count entry (a stepper) comes up by
  itself. The lifter records the count.
- **Timed holds (#628).** Start on a TIMED set runs a hold with the screen kept on: it counts up toward the target,
  buzzes once and logs the target when it gets there. Stop logs the whole seconds held; a Stop under one second
  logs nothing. A set done each side runs one hold per side, left first, each started with its own tap; it keeps both
  sides and its value is the shorter side (#644). Each side comes from the authored prescription, else the catalog entry. A hold in a superset still logs
  with its round, and leaving the day drops a hold in progress.
- **Reps in time (#642).** The window stays fixed. With a rep aim, the rows open at the aim; when every window reaches
  it for the full duration, including at Max, the aim rises one rep at the lift's cadence, and any miss repeats it.
  Counts beyond the aim never become the aim (council 2026-11-23 Q2). Without an aim the set records the count and
  nothing progresses. No rep ceiling, no weight offer,
  no estimate and no automatic extra set, and a window neither gives nor takes another slot's shared credit.
  Identical windows on two days still share progression. Pinned as §11.2 case 39.
- **Hold progression and Past target (#643).** A bodyweight hold whose catalog entry has a ceiling gains 5 s when a
  session meets every planned hold at the slot's cadence, up to the ceiling; misses keep the target. When every
  required hold was rated Easy, the step is 10 s within the same ceiling. A logged hold takes optional Easy / Good /
  Hard / Max perceived effort, stored as 4 / 2 / 1 / 0 in rir; no rating earns no bonus and never blocks Next or Finish.
  Holds show words even when lifts use Reps left. Loaded holds and holds without a ceiling keep their seconds.
  The watch logs elapsed whole seconds on an early tap and earns the unrated 5 s step only when every hold was met.
  A target above the ceiling keeps neutral TIMED status; only a target at the ceiling reports HOLD_CEILING.
  "Past target" beside Start runs a hold past its target: one buzz at the target, Stop logs the seconds held, and
  the clock ends itself at 10:00. The log keeps the held seconds beside the planned target, and an overshoot counts
  as one success. The wall sit never runs past its target and shows "Keep
  breathing" while it runs. Pinned as §11.2 cases 40 and 41.
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
  Play declaration. **Phone GPS distance (owner, 2026-11-11, superseding "GPS only if manual and linked distance fall short, and
  then on the watch first").** Outdoor runs, walks and rides measure distance with the phone's GPS through a
  foreground service of type location started while the app is visible. Precise location is asked in context
  (the first outdoor Start or Settings › GPS distance), never at install and never in the background. Declining,
  approximate location, location off or no fix falls back to typed distance. A set GPS covers from start to end is
  measured; one it doesn't is offered as a typed starting point. Measured readings (GPS, the watch's exact end)
  win over derived ones, interpolation between two real fixes counts as measured, and a typed value always
  sticks. Coordinates are stored only when the user turns on Save route (default off). The watch follows later
  (Health Services) and never blocks a phone feature.
- **Plans stay generic and rotation-based.** No stored plan kind (derive one
  if a screen ever needs it); alternating plans by weekday is the lifter
  switching, not a calendar.
- **Cardio-first setup (#494).** Setup asks “What comes first?”. Cardio first asks
  the activity and where, a comfortable time or distance, sessions per rotation
  and “Also lift?” (0, 2 or 3 days); lifting questions follow only with lifting
  days, including recent lifts. It generates one rotation: Easy, Intervals and
  Long days (1 to 6 sessions), lift days interleaved after the run days, lettered
  in order. A time answer gives open-ended timed sets; a distance answer gives
  distance sets. Cardio-first plans start with cardio increase offers on. The
  answers are stored in the plan’s recipe as generator inputs; there is still
  no stored plan kind. Backup v23 recipes may carry them.
- The shipped cardio vectors do not move (A19 retired the §11 formula numbers).

### A14. Next time carries the plan, not the result (owner council, 2026-11-05, #467)

Superseded by A19.

Next time keeps the prescription's shape and holds the opening working weight.
A miss never lowers it and never pre-fills a lighter number.
Ramp and back-off rows carry as logged when the top held.
An increase is offered (opening plus one stepper increment on full success, or a
heavier set that made its reps; the larger, never stacked) and applied only when
the lifter accepts; §11 numbers do not move.
The offer reads the lift's latest qualifying evidence from any day in the same
plan (#502): a heavier full-rep working set anywhere, or full success at the opening
weight. A later miss or skip does not erase qualifying evidence. The current slot's
opening prescription is the baseline when its local archive is absent or retired.
Archived tracks keep their own rep targets and row identities.
On screen the heading is built from the working rows, 'Repeat' or a '+5 lb' chip
sits beside the weight, the Today preview's Last, like the Lift sheet's, lists every
working set of the exercise's newest session from any day or plan, naming the day,
the plan when it is another, and the date when that is not the day shown (#501, #645),
and the Next time control reads Progress / Plan with the disclosure 'Planned reps;
increases need your approval.'
Choosing Plan parks the slot's Progress values on the slot and choosing Progress
again restores them.
The receipt shows each lift's Next time (+5 lb or Repeat), read the same way as the preview; a ramped lift's receipt row lists its top set and then its other working sets (#501).

Room v20 adds nullable `program_exercise.parkedLogsJson` through migration 19→20.
Backup v18 carries the optional field; older backups restore it as null.

### A15. Cardio distance unit (owner, #494)

Distance shows in mi or km from a global setting, suggested from the weight unit until the lifter chooses; track and rower segments show meters. Storage, CSV, Health Connect and the watch stay in meters. Backup v19 carries the setting when chosen.

History shows this ISO week's recorded distance per activity group (running, cycling, rowing, walking, elliptical) and counts sessions without distance; it never estimates a missing distance.

### A16. Cardio Next time (owner, #496)

Each cardio block progresses by Distance, Time or Pace (pace opt-in) with an editable Increase by: distance +5%, time +5% of work duration, pace 1% faster, rounded to a meter or second, at least one. Only work sets change; rounds never grow on their own. Full success: every work set met its opening target and any aim; a lone open-ended run reaches its planned duration or distance. Misses repeat. The offer is the strength chip, applied only on tap, before Start and on the Today preview. A cardio block has Next time Progress / Plan; Plan runs the plan's block and parks the working block. Plan settings offer strength increases (default on) and cardio increases (default off; on for cardio-first plans); an offer appears when the switch is on and the slot is on Progress. A distance or time increase keeps an existing pace aim by scaling its counterpart. Room v21 adds the day's cardio Next time columns and the plan's two switches; backup v20 carries them.

### A17. Plans edit as plans (#500, owner-approved 2026-11-06)

Edit day from a day preview opens the day editor as its own screen with no workout
behind it; Back at its root returns to the preview. During a workout it stays a sheet.
Choosing Straight sets on a ramped lift opens one sheet with Sets, Reps and Weight,
prefilled with three sets at the lift's working weight and reps; Save writes them together.
An added lift, and a lift swapped in for the plan or the workout, arrives as straight sets
(a ramp's six rows become three) at the nearby weight #492 chooses; a main-capable lift does
its top-set reps. A plan swap drops the old exercise's prescription.
### A15. Plans from the lifter's numbers (#499, owner-approved 2026-11-06)

Superseded by A19.

The wizard's optional recent-lifts step takes a recent working weight and reps
per main lift. Each entered lift seeds its main as a load × reps prescription.
Skipped, the plan is generated as before and §11 does not move. Novices start
every main as three straight sets, barbell mains at the empty bar.

Entries are not part of the plan's recipe, so Reset day to template regenerates
from the formula. Goals stay calculated (#503). Part C appends goal editing to
this amendment.

Goals are editable from the plan: an edited goal is stored in the plan's recipe,
replaces only that lift's goal everywhere the goal is read, and never rewrites
stored working values. Backup v21 carries it inside the recipe.

### A18. Cardio goals and test attempts (owner, #497)

A plan may carry one goal: an activity, distance and target time, strictly under
("2 mi under 16:00"), or a shuttle test (protocol and target shuttles, entered by
hand), each with an optional date from 2000-01-01 through 2100-12-31. A timed goal's
best is the fastest finished single work set of that activity covering at least
the goal distance; intervals never count. Shuttle counts are logged against the
goal. Official level tables, beep audio and sprint goals (times are whole seconds)
are out of scope. Room v23 adds `plan.goalJson`; backup v25 carries it.

### A19. Setup by goal, generator from the log (owner, 2026-11-08; wizard rebuild PRs 1 to 6)

Normative design: `docs/briefs/wizard-design-2026-11-08.md`, with its revisions. Setup asks the goal, the week,
the equipment, training experience, the lifts and recent numbers; the planner (`domain/planner`) builds the plan
and progression (`domain/progression`) moves every target from the log. No load comes from a bodyweight formula:
a lift opens from the lifter's numbers, its history, or the equipment floor with calibration.

- Supersedes spec §1 (load and goal principles), §2 (onboarding and setup), §4 (goal math and seeding),
  §6 (setup wizard and generator), §8.3's Reset day to template
  (setup plans have Reset day to plan; other plans have no reset), §8.4 (Setup screen) and the formula types in
  §3. Spec §5's Tracking column records Weighted, Reps or Time; starting loads come from the planner and
  the log (design §3).
- Replaces A14 and the recent-lifts A15 (#499); absorbs A16 and A13's cardio-first paragraph; extends A18 (a
  timed goal may have no time, sprint goals, the goal date drives the taper).
- Retires A13's "§11 numbers do not move". Spec §11.1's formula numbers went with the formula; spec §11.2 is the
  contract, and a diff that changes one of its numbers must change that case on purpose.
- Existing plans convert once (design §4.4): every slot gets a rule; equipment, typed goals and the old wizard's
  mains move into plan settings; the v1 recipe is cleared. Workout history is never rewritten. A restored old
  backup converts the same way on every later build.
- The profile is two optional numbers, bodyweight and age (#527). Units stay global.

### A20. Matching slots share progression (owner, 2026-11-20, #629)

Within one plan, slots on different days with the same exercise, sets, reps and effort progress as one: Finish
copies the performed slot's next-time rows and its progression (rep target, cadence, streaks, calibration, stall
dates, exposures) to the others, in the same undo record, and Undo and Rough day restore every slot it changed.
A match also needs the same set structure, role, scheme and Next time mode; load is left out, since it is what they
share. An authored target matches by type, reps or seconds, and side, without its load. A generated plan's
SECONDARY exposure differs in role, sets or effort, so it keeps its own numbers and still takes the shared streak
credit of design §19. Superset partners, Plan slots and slots without stored rows keep their own progression.
Accepting or reverting an offer reaches the slots still holding what Finish copied. Plans never share with each
other; there the log is evidence only, as decided 2026-10-19. The Lift sheet's and the Today preview's Last (#645)
name the day, and the plan when it is another, that the newest session came from when that is not the day shown.
For matching slots this supersedes design §19's "It never writes another slot's load."

### A21. Each side keeps left and right (council 2026-11-21, #644)

An each-side set stores left and right (load, reps, seconds) when they were measured or entered; its single value is
the weaker side, field by field, so progression needs both sides to meet the target and never reads the better side
or an average. A side at zero was not done. Sets logged as one value, and all history from before v33, are side
unknown; nothing invents two results from one. An edit to a value both sides share moves both; an edit to a value
they differ on drops the sides. Room v33, backup v33 and the CSV's six side columns carry them; the watch shows and
sends the single value. A comparison against a published norm follows the protocol behind the norm: Springer 2007
measured a leg the subject chose, so a unipedal stance comparison uses the leg the lifter names, not the weaker or
the better side by default.

## Architecture

```
:domain     pure Kotlin — types, catalog, planner, progression, set shapes and cascade, rotation. Zero Android deps. (spec §3–§6)
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

- **Catalog quality at 200 entries** — each entry's pattern, equipment,
  muscles and tracking type need review; Fable reviews the full table before data
  entry.
- **Health Connect availability** varies by device/Android version — feature
  must degrade invisibly.
- **Wear Data Layer** is the fiddliest platform surface in the plan; it's last
  deliberately, and `:domain` purity keeps it thin.
- **CSV import name-matching** is inherently fuzzy — best-effort with a
  preview/confirm screen, never silent guessing.
