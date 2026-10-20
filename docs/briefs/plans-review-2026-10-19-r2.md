# Plans, cardio as sets, GPS, training exposure: round 2 (2026-10-19)

Round 2 of `plans-review-2026-10-19.md`, after the owner's follow-up. It
replaces round 1's open decisions; round 1's settled list still stands except
where this report says otherwise.

Method: Sol (gpt-5.6-sol) and Astra (gpt-6-astra) at xhigh each wrote a memo
from the same brief (`-r2-brief.md`); Claude Opus 5.5 sealed its own memo
before reading theirs (`-r2-claude-sealed.md`). Each Codex model then
critiqued the other two (`-r2-sol-critique.md`, `-r2-astra-critique.md`).
Every repo claim used below was checked against the code. Fable was not
available.

## Verdict

Build saved plans before production, as decided, with authored prescriptions,
watch support and plan identity everywhere. That is the whole production cut.

Cardio intervals become sets on the card, the way the owner described,
backed by cardio-specific set types underneath. Distance arrives by hand
first. GPS is worth offering for outdoor types, and comes after manual
distance.

The muscle-aware deload idea survives in a narrower form all three readers
now agree on: when you switch plans or come back, the app shows what you
actually did on each exercise recently, in any plan, and asks you to review
loads for the ones you haven't done. It does not decide from muscle overlap
that you are ready for a weight.

## The owner's four ideas

### Cardio intervals as sets: yes on the card, typed underneath

The card reads as the owner wants: a warm-up set, one set per work interval,
a recovery set between them, a cool-down set. The pager shows one set in
focus, and the focused numeral is the live countdown or the distance so far.
The watch keeps its one cardio dial and draws each set as a ring segment.

Underneath, cardio sets do not reuse the lift's set row. `LoggedSet`
already overloads its fields (seconds means "ignored" on weighted sets,
`Types.kt:32`), the history row needs weight, reps and a lift slot
(`SessionEntities.kt:37-58`), and there are no cardio exercises in the
library to hang them on (`ExerciseLibraryTest.kt:76-82` exempts the
pattern). So the target is typed:

```kotlin
sealed interface Target {
    data class LoadReps(val lb: Double, val reps: Int) : Target
    data class Reps(val count: Int) : Target
    data class Hold(val seconds: Int, val addedLb: Double?) : Target
    data class RepsInTime(val seconds: Int, val repAim: Int?) : Target   // push-ups in 2:00
    data class Travel(val end: Measure, val aim: Measure?) : Target      // 400 m, aim 1:45
}                                                                        // or 2:00, aim 400 m
sealed interface Measure { data class Time(val s: Int) : Measure; data class Distance(val m: Int) : Measure }

enum class CardioRole { WARMUP, WORK, RECOVERY, COOLDOWN }

data class CardioSetResult(
    val status: FINISHED | SHORTENED | SKIPPED,
    val elapsedMs: Long, val metres: Int?,          // unknown distance is null, never 0
    val distanceSource: MANUAL | GPS | HEALTH_CONNECT?,
)
```

"A combination" means one measure ends the set and the other is an aim.
Finishing 400 m in 2:07 completes the set and misses the aim; the app says
both. Rules all three readers now share:

- Time ends a set on its own. Distance ends it with a tap; GPS buzzes at the
  estimated mark and waits. A 400 m track is a known GPS weak spot.
- Recovery is its own visible set with its own result, because it is
  walked, jogged, shortened or skipped. It never uses the lift rest timer or
  `RestPolicy`.
- "Add set" adds a work interval (with its recovery) before the cool-down.
- A run starts from a frozen copy of the plan; changes mid-run are recorded
  as deviations, and saving them to the plan is a separate act.
- The shipped vectors stay pinned and map exactly: easy 1500, tempo 1200,
  warm-up 300 then five 120/120 pairs including the last easy 120. Old
  `cardio_session` rows stay as single summaries; nothing invents per-set
  detail for them.
- The CSV stops packing `hard` and `stepsCompleted` into columns meant for
  other things (`HistoryCsvWriter.kt:131-135`); the reader keeps the legacy
  form.

`RepsInTime` belongs to lifts as much as cardio: it is how a military test's
two-minute push-up event is prescribed.

### Cardio types and GPS: yes, after manual distance

| Type | Targets | GPS | Health Connect |
|---|---|---|---|
| Road run, trail run | time, distance, both | offer | RUNNING |
| Track run | time, laps of known length | no (track markings beat GPS) | RUNNING |
| Treadmill run | time, machine distance | no | RUNNING_TREADMILL |
| Outdoor walk / ruck | time, distance | offer | WALKING |
| Treadmill walk | time, machine distance | no | WALKING |
| Outdoor ride | time, distance | offer | BIKING |
| Stationary bike | time, optional machine distance | no | BIKING_STATIONARY |
| Elliptical | time, optional machine distance | no | ELLIPTICAL |
| Rowing machine | time, metres, both | no | ROWING_MACHINE |

Swimming waits for its own design (pool lengths, strokes, wet screens). The
legacy `LOW_IMPACT` mode keeps its current mapping only for old rows; today
it writes outdoor biking whatever the machine was (`CardioRecordMapper.kt:24`).

What GPS costs, so the owner can weigh it:

- Fine and coarse location requested together, after the user picks GPS.
  Denied or approximate-only falls back to manual distance.
- A `location` foreground service started from the visible screen, with an
  ongoing notification. No background location permission.
- A Play foreground-service declaration with a demo video, and Play's new
  precise-location rules. Google's own pages give two effective dates
  (28 Oct 2026 and 27 Jan 2027); either way a fitness recorder needs a
  declaration and is an accepted use.
- `docs/play-data-safety.md:30` ("Location: never requested") and the
  privacy policy change. Location processed only on the device is still not
  "collected" under Play's definition.
- Field testing for accuracy and battery before any number is pinned.
- No INTERNET permission. GNSS, Health Services and Health Connect are all
  on-device.

Store distance only, never the route. Writing distance to Health Connect is a
separate permission (`WRITE_DISTANCE`); routes need another
(`WRITE_EXERCISE_ROUTE`).

A cheaper route for people who already record runs elsewhere: pick a run
another app wrote to Health Connect and attach its distance. The current
reader only reads strength sessions and drops record identity
(`HealthConnectReader.kt:77-96`), so this is real work, but far less than GPS.

### Plans for any purpose: yes, without a progression engine

Maintenance, test prep, physical therapy and cardio blocks are names and
contents, not plan types. A plan is ordered days with remembered state,
generated or authored, rotating or repeatable. Generated rotations stay 2 to
6 days; authored days can hold a single exercise. Duplicate is the verb that
serves test prep: copy the maintenance plan and change what needs work.

All three readers push back on "ramp up" as an app feature. A ramp engine
needs missed-session rules, phases, deadlines and tapering, which is coaching
software and contradicts rotation-not-calendar. For now a ramp is the user
editing next session's targets, with Last shown beside each. No military-test
templates: the tests differ by service and change.

Plans own their prescriptions. A deadlift at 225 × 5 in one plan and
135 × 15 in another are both intentional, and neither goes stale when the
other is performed. History across plans informs a prescription; it does not
replace it. (Claude proposed the opposite and withdrew it.)

### Muscle-aware deloads: replaced by an evidence review

Both counterexamples to muscle/pattern matching hold. Push-ups and bench share
a pattern and muscles but not loading. A 35 lb kettlebell swing is "heavy" by
a pattern-and-load rule (its GOAL is 53, `ExerciseLibrary.kt:103`) and would
wave through an old deadlift prescription untouched. Sol withdrew its decay
formula for the same reason: its constants implied precision nothing in the
app can check.

What all three now recommend:

- On switching plans, each exercise shows its Last from any plan, with the
  date. If a recent result is comparable, the app offers to adopt it.
  Comparable means the same exercise at no less than the prescribed load
  **and** reps. Nothing is overwritten without a tap.
- An exercise not performed in any plan for 28 days gets a return review on
  its card: last performance, current values, editable next values, a
  preview of every row the change touches. Running and cycling are tracked
  per type the same way; cycling never counts as running.
- The owner's two examples come out right. Physical therapy with bands, then
  back to maintenance: squat and bench have not been loaded in 8 weeks, so
  they get the review; band work does not count. Maintenance into test prep:
  shared lifts were done last week, so no review; a new two-minute push-up
  event says "no comparable history".
- Muscle tags wait until the review's explanations prove they need them.
- Never: readiness scores, recovery percentages, injury risk, clearance.

Round 1's "back to starting loads, `min(saved, GOAL)`" is withdrawn. It can
lower nothing (saved 185, GOAL 235), and applying it through the cascade can
raise a ramp the lifter had lowered by hand (`SetEditor.kt:27` rebuilds every
ramp from TOP).

Old sessions keep an unknown plan, since `replaceProgram` has deleted earlier
programs whose days were also called A, B, C (`TrackerRepository.kt:246-260`).
With the review based on exercises, not plans, that costs nothing.

## Owner's decisions (2026-10-19, evening)

1. Return review shows the evidence and editable values. No pre-filled
   number, no automatic deload.
2. GPS only if it proves needed. Prefer distance from another app through
   Health Connect.
3. If GPS is built, the watch first (Health Services).
4. Timed rep events are in, before production. The lifter records the count.
   Presentation needs design: the screen must not dim or lock while the clock
   runs, and the count entry (a stepper, raised automatically when time ends)
   follows the clock.
5. Everything Health Connect can hold, the app writes. Play Console, the Data
   safety form and the privacy policy change with it.

The consensus list below is accepted as written. Work is tracked in GitHub
issues under the saved-plans epic.

## Decisions for the owner (as put, before the answers above)

1. **Should the return review pre-fill a lighter number?** (a) No; show the
   evidence and let the lifter edit (Sol and Astra). (b) Pre-fill 90% of
   each weighted row, row by row so nothing rises, editable. *Claude's pick:
   (b).* You asked for the app to recommend, and a wrong default that is
   visibly editable is better than a blank on a return day. 90% is a product
   choice, not a research number.
2. **GPS timing.** (a) Manual distance, then Health Connect run linking, then
   GPS only if needed (Sol and Astra). (b) Manual distance, then GPS for
   outdoor types; linking whenever. *Claude's pick: (b).* You are the demand
   they are waiting for.
3. **GPS on which device first?** Phone (Astra, Claude) or watch through
   Health Services (Sol). This depends on how you run: phone in a pocket or
   watch only?
4. **Two-minute rep events before production?** `RepsInTime` is small and is
   what test prep needs. *Claude's pick: yes, in the authored-prescription
   slice.*
5. **Health Connect distance writes** when distance lands: request
   `WRITE_DISTANCE`, or keep the current permission set. *Pick: request it;
   it is opt-in and the data is real.*

Confirm or overrule (all three agree): no progression engine, no templates,
no swimming yet, no routes stored, muscle tags later, legacy history plan
unknown, cardio sets after production.

## Build order

Before production, each one PR:

1. Plan, day and run identity; migration (today's program becomes one plan;
   old history plan unknown); backup version with legacy fixtures.
2. Plan list: create (the wizard always creates), duplicate, rename, switch,
   archive; per-plan position and settings.
3. Today, Log, CSV, Health Connect titles and widget made plan-aware;
   existing cardio usable in any plan; a cardio-only day finishes normally.
4. Watch snapshot and deltas carry plan and run identity; tests for a late
   watch tick after a switch and for a same-day repeat.
5. Authored prescriptions (load × reps, reps, hold, reps-in-time if decided,
   each side, band in text, notes), one-exercise and repeatable days, phone
   execution.
6. Watch rendering for authored prescriptions; migration, restore and
   process-death adversarial tests. **Production cut.**

After:

7. Cross-plan Last on every card; switch and return review.
8. Typed cardio sets, legacy adapter, durable phone execution with recovery
   sets.
9. Cardio history, CSV, backup, Health Connect segments; watch phases.
10. Activity types, manual distance, end-and-aim targets.
11. Health Connect run linking.
12. GPS, only if 10 and 11 fall short: field spike, then the watch first.

New pins: authored days minimum one exercise; time ends a set, distance needs
a tap; unknown distance is null; legacy cardio is never expanded into sets;
recovery never uses `RestPolicy`; the 28-day review boundary; the
comparable-result rule. §11 and the shipped cardio vectors do not move.

## Corrections made along the way

- Astra's claim that round 1 misread "PT" as physical therapy is wrong: the
  owner's first message was about physical therapy, and the military test
  came later. Both cases are real and both are covered.
- Sol said Health Connect laps need a known distance; `ExerciseLap` length
  is optional (Astra).
- Both first GPS recipes forgot coarse location alongside fine (Astra).

## Confidence after cross-critique

| | Claude | Sol | Astra |
|---|---|---|---|
| Cardio as typed sets | 85% | 95% | 88% |
| Types and GPS later | 70% | 91% | 84% |
| Plans own prescriptions | 90% | 95% | 95% |
| Evidence review, no clearance | 80% | 86% | 79% |
| Plans-only production cut | 90% | 94% | 92% |

Sources: [Play minimum-scope location policy](https://support.google.com/googleplay/android-developer/answer/17033915),
[Help Net Security on the 2026 Play location changes](https://www.helpnetsecurity.com/2026/04/16/google-play-store-policy-updates/),
[Android FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[Runtime location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime),
[Health Services ExerciseClient](https://developer.android.com/health-and-fitness/health-services/active-data),
[Health Connect data types](https://developer.android.com/health-and-fitness/health-connect/data-types),
[ExerciseLap](https://developer.android.com/reference/androidx/health/connect/client/records/ExerciseLap),
[GPS watch accuracy study](https://pmc.ncbi.nlm.nih.gov/articles/PMC7381051/),
[Bosquet et al. 2013](https://pubmed.ncbi.nlm.nih.gov/23347054/),
[Halonen et al. 2024](https://pubmed.ncbi.nlm.nih.gov/39364857/).
