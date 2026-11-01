# Step 4: plans side by side, workout-only swaps, and the phone flow — design, 2026-10-31

Step 4 of milestone M9. Issues: #361 (plans side by side), #362 (swap a lift for this
workout only) and #344 (phone flow).

- **Designer:** Claude Opus, standing in for Fable, which is over its usage limit.
- **Status:** revision 4, approved after three review rounds and a plain verdict (Astra 9/10, Claude 9/10). The ledgers are at the end.
- **Line numbers:** main at `452e6922`. Room is v15 and the backup format is 14.

## 1. Sources and scope

- **`docs/briefs/phone-redesign-2026-10-30.md`** (the redesign) is the base for every screen.
  Phases 2, 3, 4, 5, 7 (in part) and 10 fall in step 4.
  - Phase 6 (Log) is step 5.
  - Phases 8 (backup and custom exercise), 9 (Edit day and the picker) and 11 (navigation
    suite and cleanup) are not in step 4.
- **`docs/briefs/product-council-2026-10-31.md`** changed the redesign:
  - Today is an overview;
  - a preview sits one level down;
  - New plan has four doors;
  - the tabs are Today, Log and Plans;
  - Settings holds app preferences only.
- **`docs/briefs/plans-and-places-2026-10-31.md`** added plans side by side (#361) and
  workout-only swaps (#362).
- **This doc designs** the data rules for #361, #362 and explicit Start in full. For the UI
  PRs it gives the deltas from the redesign and the order. Start empty's data model gets its
  own design inside PR 4.8.

**Out of step 4:**
- cardio Redo and the in-place Health Connect amend;
- capturing equipment on the run, which comes with training places (#363), so #362's issue
  text is amended to say so;
- `NavigationSuiteScaffold` and the KEEP cleanup;
- the Log rebuild (#345).

## 2. Explicit Start (new; it serves #362 and #344)

- **Today.** A run opens only on the first done tick (`applyToRun`, `TrackerRepository.kt:1312-1329`).
  So nothing can be swapped "for this workout" before the first set, which is exactly the
  station-taken case. The preview's Start can't begin a run either.
- **`startRun(planId, dayId, seeds: List<RunSeed>): RunStartResult`** opens a run for that day
  with no done sets.
  - It creates the run and its tracks with the same code the first tick uses
    (`startRun(day)` and `initialRunTracks`).
  - The caller passes complete seeds from `DayScreenBuilder.seedPlan`, as `ensureSeeded` and
    `SetEditApplier` build them today, so every slot gets its track even when it has no stored
    working values.
  - It refuses a day with no strength slots. A cardio-only day keeps the cardio flow
    (`CardioRun`, then `logCardioSession`), which doesn't use a run, so Start there opens the
    cardio screen.
  - It's idempotent: on that day's own open run it returns that run.
  - It refuses when another run is open, and returns that run so the caller can offer Resume
    or Discard.
- **A run with no done rows:**
  - Finish stays disabled. Finish needs a done row, per step 3's PR 4.
  - Discard works, as now. Today shows it as Resume.
  - The first-tick path keeps working unchanged. A tick on a day whose run is already open is
    an ordinary run write.
- **The wire.** Before Start, the watch's snapshot already carries the day's `nextRunId`. So
  only a run-identified **swap** with `runId == day.nextRunId` and no open run opens the run,
  then applies. Value edits before the first set keep step 2's rule: they write working values
  and open nothing.

## 3. Plans side by side (#361)

### The current model

- One DataStore `selectedPlanId` is the only "which plan" fact.
- Its fallback is copied nine times: `TrackerRepository.kt:139-143`, `:151-152`, `:398-401`,
  `:619`, `:898`, `:1670`, `:1796`, `:1885`, and `RestoreJournal.kt:111`. The rule: the stored
  id when that plan is unarchived, else the first unarchived plan by position.
- `todayTargetFlow` (`:1273-1276`) gives the open run's plan and day, else the selected plan's
  suggested day. Today, the day screen's default, the widget and the Wear snapshot read it;
  the tile and complication read the snapshot.
- `WearSyncPublisher` filters out null snapshots (`WearSyncPublisher.kt:85`), so the watch
  keeps the last workout it was sent.

### The target model

**Storage.**
- `plan.onToday` (Boolean, default false), in Room v16. A plan is on Today, paused, or
  archived.
- `selectedPlanId` stays, as the *preferred* plan.
- One function, `preferredPlan(plans, storedId)`, owns the fallback, and all nine copies call
  it.

**`ensureSelectedPlan`** inserts its plan with `onToday = true`.

**The display rule** (`displayTarget`), used by the watch snapshot, tile, complication, widget
and the day screen's default:
1. the open run's plan and day;
2. else the preferred plan, when it is on Today;
3. else the first plan on Today by `position`;
4. else **nothing on Today**.

**"Nothing on Today" is an explicit state,** not a null:
- the snapshot source publishes an empty snapshot with a blank `dayId` and no exercises. It
  still carries the acknowledgment logs (`cardioAckStamp`, `cardioAckedStamps`,
  `processedEditStamps`), so the watch's queued work is acknowledged. A restore transient
  stays null and filtered, as now. A v0.5/0.6 watch shows its "no program" state for this
  snapshot;
- the watch, tile and complication show "Nothing on Today";
- the widget shows "Nothing on Today", not "Set up your program";
- Today shows "Nothing on Today", with a Plans button.

The no-program state stays for "no plans at all". The watch change is small: it renders the
empty snapshot as its own state.

**`todayPlans`** is the plans on Today, preferred first, then by `position`. #344's overview
lists them.

**Actions, under `planSelectionLock`.** Each writes Room first, then DataStore.
- **Use alongside:** `onToday = true`.
- **Pause:** `onToday = false`, on any plan, including the preferred one and the last one.
  - An open run on that plan stays open and shows as Resume.
  - Pausing the preferred plan leaves `selectedPlanId` alone; the display rule skips it.
- **Use only this plan:** in one Room transaction, this plan on Today and every other plan off.
  Then the DataStore write makes it preferred. A crash between the two leaves this plan the
  only one on Today, so the display rule shows it anyway.
- **Make preferred** (`selectPlan`) also sets `onToday = true`, so a chosen plan is never
  invisible. The Plans screen's select uses it.
- **Today's plan picker** (until 4.3 replaces it) uses Use only this plan, which keeps its
  current meaning: switching plans.
- **Archive** clears `onToday`. The refusal to archive the preferred plan stays.

**New plans.**
- A plan from the wizard or Build your own: on Today and preferred; the others don't change.
- A duplicate: paused.
- A plan restored from the archive: paused.

**Who owns each selection-scoped reader and write.** "Viewed" means the plan the screen shows.

| Reader or write | Owner after this change |
| --- | --- |
| Today, widget, Wear snapshot, tile, complication | `displayTarget` or `todayPlans` |
| Day screen's default day | `displayTarget` |
| `completeDay`'s next-day fallback and `advancedTo` (`DayViewModel.kt:1189`, `:1194`) | the run's plan |
| The deferred cardio receipt's `advancedTo` (`DayViewModel.kt:763`) | the run's plan |
| `logCardioSession` with no `planId` (very old watches) | log the session without a plan and never advance a rotation: what that watch showed can't be known |
| Plan tab / `PlanViewModel` | the plan being viewed, from the Plans list |
| Setup's training writes | the preferred plan until PR 4.7, then the plan being viewed |
| Log (`programFlow` for main lifts, `hasProgram`) | the union of plans on Today, else the preferred plan |
| Wizard prefill | the preferred plan |
| Backup export | `onToday` per plan, plus `selectedPlanId` |

### Migration and restore

- **Room v16:** `ALTER TABLE plan ADD COLUMN onToday INTEGER NOT NULL DEFAULT 0`.
- **A one-shot fixup** in the plan-settings gate sets `onToday = 1` on `preferredPlan(plans,
  storedId)`, so the first launch looks as it does now. A DataStore flag records that it ran.
  It is skipped under the restore marker. `todayPlans` and `displayTarget` wait for the gate.
- **Backup format 15** carries `onToday` on each plan.
  - Restoring an older file sets it on `preferredPlan(restored plans, settings.selectedPlanId)`.
    That falls back when the id is null, stale or archived.
  - The legacy plan inserts on the restore paths (`:1773-1782`, `:1869-1880`) set it too.
- **Marking the fixup done.** `importSnapshot` marks the fixup done, so a v15 file with every
  plan paused stays that way.
  - A `reconcilePendingRestore` replay marks it done only when its journal was staged by v16+
    code. The journal gains a membership marker for this. Such a journal's Room write already
    carried `onToday`.
  - A journal staged before v16 replays onto rows that the migration defaulted to paused, so it
    leaves the flag alone, and the fixup puts the preferred plan on Today.
  - A startup reconcile that replays nothing leaves the flag alone.
- **Telling the formats apart.** The decoder records the file's format, so the restore can tell
  a v15 file (keep `onToday` as written) from an older one (derive it).

### Shippable in 4.1

4.1 ships the "Nothing on Today" states (Today, widget, watch) and the Plans screen actions.
Today keeps its current single-target layout until 4.3, showing `displayTarget`.

## 4. Workout-only swaps (#362)

The run already supports it: `run_track` carries `exerciseId` per track, and its unique key
includes it (`RunEntities.kt:21`). There is no schema change. The rules:

**`swapInRun(runId, programExerciseId, slot, newExerciseId, seed: RunSeed)`,** in one
transaction:

1. **The active track is retired,** whether or not it has done rows. Today's plan swap deletes
   an undone track; this swap never does, so swapping back always finds it. At Finish,
   `archiveRun` already drops the undone rows of retired tracks.
2. **A retired track for `(programExerciseId, slot, newExerciseId)`** is reopened, if it exists.
   This covers swapping back to the plan's exercise, which keeps its prescription and
   remembered loads.
3. **Otherwise a new active track** is inserted from `seed`:
   - The caller builds `seed` with `DayScreenBuilder.seedPlan`, leaving only the swapped track
     out of `existing`, and keeps the swapped track's seed:
     - **A MAIN swap** seeds from `pe.copy(exerciseId = new, prescription = null)`, so a
       substitute seeds from its own goal math, never from the plan exercise's prescription.
     - **An SS swap** seeds from `pe.copy(superset = pe.superset!!.copy(exerciseId = new))`. MAIN
       stays in `existing`, so `seedPartner` sizes the new partner to MAIN's live row count.
   - `swapInRun` doesn't seed by itself.
4. **Superset alignment.** When the slot has a partner, the two active tracks (the one just
   activated and the other side) are reconciled together, in the same transaction, with PR 3's
   paired rule (`RunReplan`'s paired variant):
   - the target length is the longer of the two;
   - neither side loses a done row or an existing row's values;
   - the shorter side is padded: the swapped side from its seed or its planned rows, and the
     other side with `seedPartner` rows for SS or prescription/goal rows for MAIN.

   A plain slot uses its seed as built. Apart from this padding, a MAIN swap never changes the
   SS track, and an SS swap never changes MAIN.
5. **Adding and removing sets** apply to the active tracks only. Retired tracks keep their
   rows until step 4 aligns them on reopen.

**Unchanged.**
- The plan: `program_exercise`, working values and rotation.
- At Finish, `archiveRun` writes the performed `exerciseId`, and `carryForward` skips a track
  whose `exerciseId` differs from the slot's (`TrackerRepository.kt:1607`). So the substitute's
  results go to its own history, and the slot's remembered values stay as they were.

**The performed exercise.**
- **One resolver,** `performedExercise(slot, track)`, returns the active track's `exerciseId`
  and that exercise's tracking, goal, catalog entry and **effective prescription**. The
  effective prescription is the slot's when the plan exercise is performed, and none for a
  substitute. Seeding, headers, timed-rep controls and the applier's validation all read the
  effective prescription, never `pe.prescription` directly.
- **Its readers:**
  - `DayViewModel`'s card builder;
  - `fetchDayHistory`; its history keys also observe the run's tracks, so a swap refreshes
    "last time";
  - `TodayViewModel`;
  - `WatchSnapshotBuilder`, for names, tracking, goal, alternates and `WatchExercise.exerciseId`;
  - `SetEditApplier`, for tracking, the "already there" check and alternates validation.
- **`dayTracksFlow`'s `LoggedSlot`** gains `exerciseId`, so the readers can see the performed
  exercise.
- **The watch's swap check.** The allowed targets are the plan exercise, plus the performed
  exercise's alternates. "Already there" compares the performed exercise. This fixes a watch
  swap back to the plan exercise, which would otherwise report APPLIED without writing.

**Plan edits on a substituted slot.**
- `setPrescription` changes only tracks whose `exerciseId` equals the slot's. The substitute
  keeps its own values. The working values are still cleared, so the next run seeds from the
  new prescription.
- A plan swap (`swapInPlan`) on a substituted slot retires the active track, as now, and the
  new plan exercise seeds fresh. The substitute's done rows stay as a retired track.
- **`carryForward`'s shape check** looks at the slot's own MAIN track, the one whose
  `exerciseId` equals the slot's, whether it is active or retired. When that track no longer
  matches the prescription's shape, it clears both sides' working values, as PR 1's rule does.
  A substitute's track never carries forward, because its `exerciseId` doesn't match.

**Where the swap applies, on the phone.**
- The swap sheet picks the exercise, then "This workout" or "The plan". It defaults to "This
  workout" when that day's run is open.
- With no run open, "This workout" first calls `startRun` (§2). The preview's Edit uses the plan
  swap.
- The redesign's phase-2 swap Undo reuses `swapInRun`: undoing a workout-only swap reopens the
  retired track.

**The watch.** A swap from the watch with a `runId` is a workout-only swap; per §2, it opens
the run when needed. Without a `runId`, the old-watch path keeps the plan swap. The wire
doesn't change.

## 5. The phone flow (#344)

### Today, the overview (the redesign's "Today", changed)

- **Resume** `<plan> · Day <X>` when a run is open, first and filled.
- **Up next.** One row per plan in `todayPlans`, labeled `<plan> · Day <X> <title>`. A row
  opens its preview. The preferred plan's row has a direct Start.
- **While a run is open,** a Start that would conflict shows the open run: Resume, or Discard
  and start this one, with a confirm.
- **Other days** expands to the other days of the plans on Today.
- **One fact line,** for example "Last trained Oct 28".
- **States:**
  - "Nothing on Today", with a Plans button;
  - no plans at all: the redesign's first-run state.
- **Start empty** appears in 4.8, not before.

### Preview (the redesign's "Today", moved down)

The route is `preview/{planId}/{dayId}`. It shows:

- the day block and title;
- the lifts with today's loads and last time;
- Edit;
- Start, which calls `startRun` and opens the workout.

### The workout (redesign phases 2 and 3)

This is the redesign's set screen, selection rule, workout record, rest band with Undo and
action slot. On top of that:

- **Finish** is in the app bar.
- **"All sets"** is a labeled button that opens the Lift sheet.
- **A superset** is two consecutive sets in the selection order, MAIN then SS, keeping step 3's
  per-exercise rule.
- **The pager goes** once timed reps, holds and cardio use the same layout. Redo and the
  amend are out.

#### Supersets on the set screen (owner, 2026-11-01; v2 the same day)

**Skipping a lift or a set (4.4c3, #390).** With the set screen enabled, the Lift sheet offers “Skip for this workout” with Snackbar Undo; skipping either half of a superset leaves a plain lift with rest after every set, and skipping a plain lift moves the workout on. The owner chose the same action for regular lifts and superset halves, with plan changes staying in Edit day and no skip rows in the swap list. A run track's `dropped` flag means the lifter skipped that track for this workout: it is always retired and kept even without done work; skipping MAIN of a pair promotes the partner through the existing workout swap and preserves the displaced MAIN as dropped. The workout resolver clears dropped partners and removes slots with a dropped MAIN and no active MAIN, preserving the remaining positions while plan editors retain the real plan. “Bring back” in the Lift sheet and Snackbar Undo of a half skip restore the captured half and align the pair, including workout-swapped partners. “Skip this set” (or “Skip this round” for a superset) uses the existing remove-set action and Undo, with no new per-set state. “Skip for this workout” is hidden and rejected for the last active strength lift, while either half of an active superset may still be skipped because the other half remains. Bring back on a skipped lift returns it as planned. It clears the slot’s skip state with the plan-edit helper, then explicitly reseeds the slot from the plan, reopening matching retired tracks to preserve done work and aligning the pair. Today’s workout swaps on that slot are not restored. Snackbar Undo of a whole-lift skip uses this same rule; bringing back a single skipped half keeps its precise restore behavior. A plan MAIN swap, partner change, partner removal, or exercise removal during an open run clears that slot’s skip state and retires or deletes its promoted MAIN before applying the plan edit and reseeding, invalidating any saved skip Undo.

**Timed and cardio on the set screen (4.5a).** Behind `WORKOUT_SET_SCREEN`, timed reps use a
clock hero with “Aim 20” when prescribed, then Start, tonal Cancel while running, and Enter reps
when the count is pending. Cancel discards the event without logging. The running clock drains a
bar; only the ready hero opens the set editor. Superset MAIN uses the same clock with its partner
card below. The event keeps its own set index even if an earlier set is unchecked.

Cardio keeps the activity title and All sets button. The position reads “Work, set 2 of 6” (or
Recovery or Cool-down). Ready shows the interval’s time or distance target and Aim when supplied;
time-ended work counts down with a draining bar and tonal Stop. Distance-ended work shows elapsed
time, “to 400 m”, filled Reached 400 m and outlined Stop beside it. Recovery and cool-down start
automatically. Done keeps the activity and shows a check, Done and the saved summary (or the frozen
results while saving), then Next or Finish day A when everything is logged. A cardio-only day keeps
that done view without an action or “All 0 sets logged”.

All sets opens the Cardio sheet: read-only prescribed sets with Finished, Shortened (including known
distance), Skipped or Now, unsaved Enter distance rows, and applicable Skip set, Add set and End cardio
actions. End cardio retains “End block?” and “End and log”. Finishing after all strength rows are
complete while cardio remains unlogged asks “Finish without Road run?” on the set screen only.

An AssistChip above the sliding content returns to a clock on another group: “Push-Up 0:27”,
“Push-Up, enter reps” for a pending count, or the cardio activity and its clock. The screen stays on
while an execution clock runs; clock digits stay outside live regions. Both action buttons share the
600 ms double-tap guard, also armed after a clock or interval slot changes, including automatic
recovery, but never on first composition. Every group keeps an error band when a cardio save fails:
“Couldn’t save the cardio. The sets are still here.” with Retry. Empty days show “No lifts in day A
yet.” and Edit day. With the flag on, the set screen now renders every workout state without falling
back to the pager; loading and no-program states keep their shared handling. Flag off keeps the
pager’s behavior. Redo and the cardio amend remain out of scope.

The owner, looking at the pager's superset page: supersets "almost fade into the same UI as a
normal exercise". Today the pair shows as one line, "then Seated Cable Row 120 × 10", under
the first lift's numerals (`ExercisePage.kt:240-252`). Logging per exercise makes this worse:
the bench set and the row set are two consecutive screens, and nothing but the name changes
between them, with no rest in between (`DayViewModel.kt:975-977` starts a rest only when the
round is complete).

v1 (shipped behind the flag in #388) put both names in one `OutlinedCard` above the hero. On
the renders the owner saw the next problem: "the eye has to look up to see what exercise to
do, and then down to see the rep/set and weight". The card's edge separated the current lift
from its numbers. v2 keeps the lift that is now exactly where a single lift's name goes,
directly above its numerals, and the card holds only the other half. Sol reviewed v1 (7/10)
and v2 (8/10, sound with changes); both reviews are folded in.

```
 [←]  Day A                          [≡]  Finish

 Superset, round 2 of 3                                  position line (titleMedium, onSurfaceVariant)
 DB Bench Press                          All sets        the lift now: as a single lift
 80 × 10                                                 the hero (SetNumerals), as any set
 [plates or authored detail for the lift now]
 ┌─────────────────────────────────────────┐
 │ Next   Seated Cable Row   120 × 10    › │            OutlinedCard, one ListItem row ≥ 48 dp
 └─────────────────────────────────────────┘

 [            Log 80 × 10             ]
```

After "Log 80 × 10" (no rest between halves; name, hero and detail crossfade in place):

```
 Superset, round 2 of 3
 Seated Cable Row                        All sets
 120 × 10
 ┌─────────────────────────────────────────┐
 │ ✓ Done  DB Bench Press    80 × 10     › │
 └─────────────────────────────────────────┘
 Then rest                                               bodyMedium, onSurfaceVariant, outside the card

 [Undo DB Bench Press 80 × 10]                           the band: Undo only, 10 s
 [           Log 120 × 10             ]
```

During the rest after the round (round 3 next):

```
 Superset, round 3 of 3
 DB Bench Press                          All sets
 80 × 10
 ┌─────────────────────────────────────────┐
 │ Next   Seated Cable Row   120 × 10    › │
 └─────────────────────────────────────────┘
 ┌ Rest  1:04 ━━━━━━━━━──────  [Undo Row 120 × 10] [+15 s] [Skip rest] ┐
 [            Log 80 × 10             ]                  tonal while the rest runs
```

- **One glance reads the job.** Position, name, numerals: the same order and places as a
  single lift. "Superset, round 2 of 3" right above the name and the card (only supersets have
  one) keep the pair visible. The card stays: a plain row would let the partner read as an
  afterthought. Gym notation (A1, A2) is out: the letters already mean days.
- **The card holds the other half**, one `ListItem` row (≥ 48 dp, a chevron, its own TalkBack
  node) that opens that lift's sets. "All sets" opens the sets of the lift that is now. Opening
  either keeps the workout's focus.
- **State in words.** The row starts with "Next" or "Done" (with a check icon); Done shows the
  recorded values. Derive both from the recorded rows, so a partner ticked first in its sheet
  reads Done.
- **"Then rest"** sits under the card, and only when logging the half that is now completes the
  round and a rest will start (rest timer on, not the day's last set).
- **Each half uses its own tracking**: "120 × 10", "12 reps", "45 s", "45 s at 25 lb". The
  plates or authored detail belong to the lift that is now and sit directly under its numerals,
  above the card.
- **Motion says "same pair".** Between halves and rounds of the same pair nothing slides; the
  name, hero, detail and the card's row crossfade (M3 fast effects, none under reduced motion).
  The 300 ms slide is kept for leaving the pair for another lift. A switch is announced once,
  politely ("Seated Cable Row, round 2 of 3"); outgoing crossfade content is hidden from
  TalkBack.
- **Rest and Undo.** No rest between halves: the band holds only Undo for 10 s. The rest starts
  when the round completes and its band carries Undo for the second half; Undoing that cancels
  the rest and leaves the first half done. The rest notification's "next" reads "Round 3,
  DB Bench Press 80 × 10". Undo touches one half only (this overrides the redesign's "both
  tracks of a superset round", `phone-redesign-2026-10-30.md:483`).
- **Completed pair** (finished from the sheets or the watch): the position line reads "Done, 3 of
  3 rounds", both names with their summary lines, no hero, and "Next: …" as the action.
  **All logged** drops the card, keeps a valid Undo, starts no rest, and keeps Finish's 3 s
  arming.
- **Largest font.** Names and the position line wrap; the partner's value drops below its name;
  the band and the action stay reachable under the redesign's large-font rule. Both themes.
- **TalkBack order:** position, the lift now and All sets, the numerals (edit), the detail, the
  partner row (state, name, value, open sets), "Then rest", the band, Log.
- **Day sheet and preview** show the pair as one `ListItem` with `overlineContent` "Superset"
  (sentence case, no tracking) and the headline "DB Bench Press + Seated Cable Row". The Day
  sheet's trailing text is "Round 2 of 3, now" on the selected pair.
- **States.** The focus is the earliest round not complete on both halves, MAIN before SS. A
  partner whose track is missing (unknown catalog entry) shows its row as "Not available". A
  partner swapped mid-workout shows its new name at once.
- **Out of scope:** the watch (step 6), and round counting, which stays MAIN-based. The model
  allows exactly one partner (`domain/.../model/Types.kt:45`), so giant sets need nothing.

### Receipt (redesign phase 4)

One receipt route, with the new-top-set rows inside it.

### Glances (redesign phase 5)

- The rest notification's copy, actions and channel.
- The widget's light and dark versions, in sentence case.
- `GlanceLines` in sentence case.
- The "Nothing on Today" copy from §3.

### Tabs and Settings (redesign phase 7, in part)

- **Tabs:** Today, Log, Plans. The Plans tab lists plans; a plan opens its days; a day opens
  its preview.
- **The gear** opens Settings: units, theme, rest timer, keep screen on, Health Connect,
  backup, and About.
- **Plan settings** (emphasis and equipment) live on the plan.
- **Bodyweight, age and level** go to a "You" screen reached from Plans.

### New plan, four doors (the redesign's "Wizard", changed)

- **Make me one:** the three-screen wizard.
- **Build your own.**
- **Duplicate.**
- **Start empty:** a workout with no plan. Its data design is part of this PR, and it has to
  answer three things:
  - how tracks are addressed without a slot (SQLite's unique index doesn't dedupe a NULL
    `programExerciseId`);
  - the projections and the lifecycle;
  - backup validation of dayless runs.
- **First run** opens the four doors and Restore.

### The launcher icon stops changing letter

- **Today.** `MainActivity` isn't exported, and the seven `Launcher_Day*` aliases carry the
  launcher filter. `DayIconManager` enables one and disables the rest, and the user's
  home-screen icon points at whichever alias was enabled.
- **The change.** Every alias keeps its name and enabled state, so no home-screen icon breaks
  on upgrade. One new adaptive icon, `ic_launcher`, is built from the existing
  `ic_barbell_foreground` and `ic_barbell_monochrome`. These references move to it:
  - every alias's icon;
  - the `<application>` `icon` and `roundIcon` (`AndroidManifest.xml:45-46`);
  - the splash icon (`themes.xml:23`).
- **Then delete:**
  - `DayIconManager`'s switching and its `MainActivity` hook;
  - the per-letter foregrounds and `ic_launcher_day_*`.
- **A one-shot startup pass** keeps exactly one alias enabled, the first enabled one, or DayA
  when none is. "Enabled" means the effective state: `ENABLED`, or `DEFAULT` combined with the
  manifest's `android:enabled` (DayA ships enabled), as `DayIconManager.shouldReapplyIcon` reads
  it today. That covers the two-enabled window an interrupted switch could leave, which
  `DayIconManager` used to tolerate.
- **Test:** an upgrade with day C's alias enabled keeps one launcher icon, now the barbell.

## 6. PR order

| PR | Content | Size |
| --- | --- | --- |
| 4.1 | #361: `onToday`, `preferredPlan`, `displayTarget`, the Plans actions, "Nothing on Today" on Today, widget and watch, the readers table, restore rules (Room v16, backup 15) | M |
| 4.2 | Explicit `startRun` and #362 workout-only swaps: `swapInRun`, the resolver and its readers, the watch's swap check, the swap sheet's choice | L |
| 4.3 | Today overview, the preview route, tabs Today/Log/Plans, glances, the launcher icon | M |
| 4.4 | The set screen behind a flag (phase 2) | L |
| 4.5a | Timed reps, cardio, clock chip, Cardio sheet and empty day on the set screen, behind the flag; without Redo | M |
| 4.5b | Turn the set-screen flag on and delete the pager | M |
| 4.6 | The receipt route with the cascade inside (phase 4) | M |
| 4.7 | Settings split: app preferences, plan settings on the plan, "You" (phase 7, in part) | M |
| 4.8 | New plan doors, the three-screen wizard, Start empty with its data design | L |

Each PR leaves the app shippable. 4.4 and 4.5a–4.5b reach users together.

## 7. Must not change

- **Pinned tests.** The §11 tests and their numbers.
- **Step 2 and step 3.** Step 2's run rules, except the explicit Start that §2 adds. Step 3's
  next-time, cardio, one-plan and per-exercise rules.
- **The first launch after the upgrade** shows the same plan and day as before. Rotation
  advances only the run's plan.
- **Backups.** Old backups restore with a plan on Today.
- **The wire** stays as it is. The old-watch paths keep working.

## 8. Risks and owner questions

- **A new plan doesn't pause the others.** "Use only this plan" does that.
- **A watch swap with a `runId` becomes workout-only.** The station-taken case is the reason.
- **The launcher icon becomes the barbell for everyone.** This follows the owner's comment;
  nobody's home-screen icon is removed.
- **Bodyweight, age and level move to "You".**
- **Explicit Start opens a run before any set.** An abandoned Start shows as Resume until it's
  discarded.

## Review round 1 ledger

Seats: Astra 5/10, CHANGES; Claude 6/10, CHANGES.

| ID | Finding | Outcome |
| --- | --- | --- |
| A1 | Pausing everything leaves the watch on a stale workout | Fixed: an explicit empty snapshot and "Nothing on Today" on every surface |
| A2, C8 | Restore and the fixup could re-enable a paused plan; old files may lack the id | Fixed: `preferredPlan` in restore and legacy inserts; every restore marks the fixup done |
| A3 | Old-watch cardio could advance a paused preferred plan | Fixed: `displayTarget`'s plan, else log without advancing |
| A4 | Projections lose the performed identity | Fixed: `LoggedSlot.exerciseId`, the resolver and its readers, history keys observe tracks |
| A5, C4 | Plan edits on a substituted slot | Fixed: `setPrescription` only touches the slot's own exercise; plan swap and shape-check rules stated |
| A6 | Superset alignment on swap | Fixed: equal row counts; each side's swap leaves the other alone |
| A7, C3 | The watch applier checks the plan exercise | Fixed: performed identity, allowed targets, tracking |
| A8, C11 | Start empty's data | Deferred to its own design inside 4.8; open questions listed |
| A9, C1 | No default launcher activity | Fixed: keep the aliases, give them all one barbell icon |
| A10, C7 | 4.1 not shippable; Start empty advertised early | Fixed: "Nothing on Today" in 4.1; `selectPlan` sets `onToday`; Start empty only in 4.8 |
| A11, C6 | Start can't begin a run; no swap before the first tick | Fixed: explicit `startRun` (§2) |
| A12, C9 | Readers and copies missed; cross-store transaction | Fixed: nine copies, the owner table, Room-then-DataStore with crash outcome |
| C2 | Swapping back loses the prescription | Fixed: always retire, then reopen |
| C5 | `swapInRun` duplicates seeding | Fixed: callers pass a `RunSeed` from `seedPlan` |
| C10 | Use only across two stores | Fixed (see A12) |
| C12 | #362 text, the "four readers" count, swap Undo | Fixed: issue amended, readers listed, Undo reuses `swapInRun` |

## Review round 2 ledger

Seats: Astra 7/10, CHANGES; Claude 7/10, CHANGES.

| ID | Finding | Outcome |
| --- | --- | --- |
| CN1 | A pre-Start watch edit would open a run | Fixed: only a run-identified swap opens the run |
| CN2, N1 | Start on a cardio-only day leaves an empty run open | Fixed: `startRun` refuses days with no strength slots; they keep the cardio flow |
| N2 | Start gives no seeds | Fixed: `startRun` takes complete seeds from `seedPlan` |
| N3 (A3) | Old-watch cardio attribution | Fixed: no plan, no advance |
| N4 | A no-op reconcile would mark the fixup done | Fixed: only an import or a real replay marks it |
| CN3, N5 (A4) | A substitute seeds from the plan exercise's prescription | Fixed: an effective prescription in the resolver; the seed drops the prescription |
| CN4 | The empty snapshot drops acknowledgments | Fixed: the ack logs stay; restore transients stay null |
| N6 (A6), CN5 | A reopened track misaligns; padding cut TOP rows | Fixed: PR 3's paired alignment on any activation, only for paired slots; set add and remove touch active tracks |
| N7 (A5, C4) | The shape check skipped a retired original | Fixed: it checks the slot's own MAIN track, active or retired |
| CN6, N8 | Icon references and a two-enabled window | Fixed: application, round and splash icons move; one-shot single-alias pass |
| CN7 | Today's picker would add, not switch | Fixed: the picker uses Use only this plan |
| CN8 | v15 versus older files; old watches' empty state | Fixed: the decoder records the format; old watches show "no program" |
| C12 | #362's issue text | Fixed: the issue body is edited |

## Review round 3 ledger

Seats: Claude 8/10, APPROVE (one nit: effective alias state, fixed). Astra 8/10, CHANGES, with
three precise gaps, fixed in revision 4:

| ID | Finding | Outcome |
| --- | --- | --- |
| R1 (N6) | Reopening a longer track misaligns when the other side can't change | Fixed: both active tracks reconciled together; the shorter side padded; no done row or value lost |
| R2 | SS-swap seed recipe used the old partner | Fixed: separate MAIN and SS seed construction |
| R3 | Replaying a pre-v16 journal could leave every plan paused | Fixed: only v16+ journals mark the fixup done |
