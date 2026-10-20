# Design memo

## 1. Verdict

Build several named plans, including both generated strength plans and narrowly constrained hand-authored routines. Plans may coexist, but the app should show one selected plan at a time; do not merge PT and strength into a calendar or coaching schedule.

Make cardio a typed workout block with warm-up, main work, and cool-down, while keeping GPS, sensors, and sports analytics out. Returning after an absence should produce a plan-specific, user-confirmed easing option—never an automatic reset or medical recommendation.

## 2. Q1 — Saved plans

A plan should mean “a named collection of executable workout days plus its own progress.” Generated and authored plans should use the same execution and history machinery; `origin = GENERATED | AUTHORED` controls how they are edited, not how they are logged.

Allow any number of saved plans and let several remain runnable, but keep exactly one selected context on Today and the watch. Switching from “Strength” to “PT” must preserve each plan’s next day, live ACTUALs, partial checks, and last-session date. This supports plans “alongside” each other without inventing a merged schedule. The watch continues to mirror only the selected plan, consistent with its current suggested-day-only contract (`domain/.../sync/WatchSnapshot.kt:6-10`).

A suitable shape is:

```kotlin
Plan(id, name, origin, navigation, generatorSnapshot?, nextDayId)
WorkoutDay(id, planId, key, position, title, inRotation, timesPerDay?)
StrengthBlock(id, dayId, position, exerciseId, prescription, progression, note)
LiveLog(blockId, slot, setsJson, checkDate, updatedAt)
```

`navigation` is `ROTATION` or `REPEATABLE`. Generated plans use rotation. A PT routine can remain on the same day and show “1 of 2 today”; frequency is a completion hint, not a weekday schedule or reminder system.

An authored prescription supports weighted sets, reps, timed holds, `EACH_SIDE`, and notes. Bands use reps or time plus a plain instruction such as “red band”; do not fabricate a weight. Add `RESISTANCE_BAND` to equipment. Do not collect clinician identity, diagnosis, pain scores, or treatment outcomes.

Generated slots retain current GOAL math and TOP cascade unchanged. Authored slots show an editable `TARGET`, have no calculated GOAL, and never cascade. Their target seeds ACTUAL once; later target edits do not overwrite the living log unless the user explicitly chooses “Use target next time.” That preserves the rule that seeding occurs only when no log exists (`data/.../TrackerRepository.kt:429-454`) and keeps every pinned §11 number intact.

DONE archives the selected plan and day. Rotation plans advance only themselves; repeatable routines stay put and increment today’s completion count. Session history needs nullable `planId` plus denormalized `planName`; exercise names already use that survival strategy (`data/.../db/entity/SessionEntities.kt:27-47`). Cardio sessions need the same attribution. CSV gains optional Plan Name; full backup carries all plans and the selected ID; Health Connect titles can include plan and day; watch snapshots and deltas must carry `planId` so queued changes cannot land in another plan’s “Day A.”

Migration should create one “Strength plan,” move the global wizard answers and suggested day into it, rebuild days around surrogate IDs, and copy existing exercises/logs transactionally. This is necessary because `dayId` is currently the global primary key (`data/.../db/entity/ProgramEntities.kt:29-40`) and re-running the wizard deletes every live log (`data/.../TrackerRepository.kt:246-261`). Existing native A–G sessions can be attributed to the migrated plan; `csv:` imports should remain unattributed.

On screen, Plan becomes a list of named plans, then the selected plan’s days. “New plan” offers “Generate a strength plan” or “Create a routine.” Use M3 list items, dialogs, and sheets; the redesign explicitly forbids bespoke components (`docs/briefs/ui-redesign-2026-10-12.md:258-269`).

Risk: authorship can turn into a generic workout builder. Reject arbitrary metrics, calendars, nested routines, clinician workflows, and plan-to-plan automation. Also reject one destructive active plan: that is the current failure mode, not a solution.

## 3. Q2 — Cardio as a first-class workout

Make cardio a sibling block inside `WorkoutDay`, not a fake `ProgramExercise`:

```kotlin
CardioBlock(id, dayId, position, mode, completion, warmUp?, main, coolDown?)
Main = Duration(seconds) | Distance(meters) |
       Intervals(repeats, workTarget, recoveryTarget)
```

`completion` is optional for a strength finisher and required for a cardio workout. Standalone cardio therefore becomes a real executable day, while an optional finisher still does not gate strength DONE.

New defaults should be 5-minute warm-up, a 20-minute easy/tempo main piece or the existing 5 × 2/2 intervals, then a 5-minute cool-down. Preserve the shipped step vectors (`docs/briefs/cardio-promotion.md:35-49`) as `legacy-v1`; migrate existing cards unchanged and offer “Add warm-up and cool-down.” New templates receive new pinned vectors rather than silently changing old ones.

Make cardio user-editable, but only through this three-part editor. Warm-up and cool-down may be omitted; the main piece is time, distance, or simple intervals. No arbitrary nested interval language.

Distance is manual. The phone displays target plus elapsed time and asks for the treadmill/readout distance on completion. On the watch, a distance stage says “Tap when the display reaches 1.00 mi”; an early stop records duration with unknown distance. Store meters and derive pace when useful—never store duplicate pace truth. Write distance to Health Connect only when known. Do not read Health Connect distance in v1: matching another device’s records to a local active block is ambiguous.

The phone already places cardio as a final pager page (`app/.../ui/day/DayScreenModels.kt:58-63`; `DayScreen.kt:380-410`). Keep that grammar, but show one cardio stage in focus, phase indicators, and an “All stages” M3 sheet. Timed stages advance automatically; distance stages require confirmation. The watch stays one dial state: ring segments show stages, the center shows countdown or distance target, tap completes a manual stage, hold stops. No third watch face.

Keep out GPS, routes, live pace, HR zones, calories, VO₂ estimates, sensor permissions, automatic treadmill integration, and background workout services. Those would turn a local strength tracker into a sports tracker. Reject representing cardio as lifting sets: its targets, timing, partial completion, and Health Connect records have different semantics.

## 4. Q3 — Returning after time away

Never reset automatically. Calculate absence per plan from its most recent attributed strength session; the current Today line uses the latest session globally (`app/.../ui/today/TodayViewModel.kt:105-115`), which becomes wrong as soon as plans coexist.

Use this ladder for generated strength plans:

- 14–27 days: factual return line only.
- 28–55 days: offer 90% of saved ACTUAL.
- 56–111 days: offer 80%.
- 112+ days: offer 70%, with “Review this plan” equally prominent.

The 14-day acknowledgement already exists (`app/.../ui/today/TodayScreenBuilder.kt:65-80`). Evidence supports the thresholds more strongly than the fractions: a meta-analysis found measurable strength losses emerging around weeks three to four and worsening with cessation duration ([Bosquet et al.](https://pubmed.ncbi.nlm.nih.gov/23347054/)); one older-adult study found roughly 16–17% strength loss after 24 weeks, with recovery after retraining ([Henwood and Taaffe](https://pubmed.ncbi.nlm.nih.gov/18693231/)). The 90/80/70 values are deliberately conservative product heuristics, not clinically validated prescriptions.

Apply the factor to last ACTUAL, never to GOAL. GOAL is a population-derived maintenance target and changes through setup (`STRENGTH_TRACKER_SPEC.md:30-40`); it is not a measurement of current readiness. For a main lift, reduce TOP and let the existing ramp/back-off cascade run unchanged (`STRENGTH_TRACKER_SPEC.md:226-231`). Scale other weighted rows directly; do not alter reps-only, timed, bodyweight, or authored/PT prescriptions. Show a preview and require confirmation.

This is an explicit mutation of the living ACTUAL, not reseeding. Re-running the wizard remains an optional long-gap alternative, not the default: it answers programming questions, not readiness after injury. If the absence involved injury or clinician instructions, the screen should say to follow those instructions instead.

Reject resetting to original seeds, because they may exceed the lifter’s last chosen load. Reject a silent percentage reduction, because cause, activity during the gap, and recovery vary too much.

## 5. Decisions for the owner

1. **Plan coexistence:** one destructive active plan, merged concurrent schedules, or multiple runnable plans with one selected context. Pick: multiple runnable, one selected.

2. **Authored-plan boundary:** generic builder, medical/PT product, or constrained routines using the app’s existing reps/time/weight vocabulary. Pick: constrained routines.

3. **Cardio distance:** GPS/sensor tracking, Health Connect reconciliation, or manual distance with optional Health Connect write. Pick: manual-only.

4. **Return behavior:** fact only, user-confirmed reduction ladder, or automatic reset. Pick: offered ladder, never automatic.

5. **Launch cut:** ship now and migrate public users later, or finish the plan schema plus authored phone routine during internal testing. Pick: finish those two before production; ship cardio phases, distance, and return assistance incrementally afterward.

## 6. Build order

1. Add plan/day identities, per-plan run state, history attribution, next-version Room/backup migrations, and migration tests. No visible change.

2. Add Plan list, generated-plan creation, switching, deletion safeguards, and plan-aware watch snapshots.

3. Add the minimal authored routine flow: exercises, fixed sets/reps/holds, each-side, notes, bands, rotation/repeatable cadence.

4. Make Today/history/CSV/Health Connect fully plan-aware.

5. Add per-plan absence detection and the confirmation/preview flow for return loads.

6. Replace cardio suggestions with typed cardio blocks; migrate legacy cards; implement the three-phase phone pager.

7. Add manual distance, export/backup/Health Connect writes, then update the watch dial and delta protocol.

## 7. Confidence

- **Q1: 91%.** I would change course only if real users need automatic interleaving rather than quick plan switching.
- **Q2: 86%.** Lower if GPS, HR, or live pace proves central in testing.
- **Q3: 67%.** Thresholds are evidence-aligned; exact percentages are cautious UX policy. Clinician review or observed return-session data should supersede them.