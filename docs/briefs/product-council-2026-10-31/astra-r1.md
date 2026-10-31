strength-log should be a **private training log built around repeatable workouts**, with optional help creating them. Its core promise should be: choose today’s workout, record what actually happens with little interruption, and return later knowing where you left off. Strength remains the center; conditioning belongs alongside it. The central repair is separating the plan, the workout underway, and the historical record. A simpler screen cannot compensate when changing a plan erases performed work or a delayed watch action lands in the wrong workout.

## 1. Product

**Recommendation:** Serve adults managing their own strength training, including people following someone else’s routine, with irregular schedules and some conditioning. Do not make complete beginners needing instruction, competitive endurance athletes, or clinical rehabilitation the primary audience.

The one job to win is **carrying a repeatable routine through interruptions without losing either its intent or the record of deviations**. Against notes, offer remembered values, timers, structured history, and wrist logging. Against Strong, Hevy, and JEFIT, compete on that complete experience plus local ownership—not feature count. [Strong](https://www.strong.app/) already positions itself as a notebook; [Hevy](https://www.hevyapp.com/) and [JEFIT](https://www.jefit.com/watch) already offer watch logging. Superiority remains something to demonstrate.

The August diagnosis is still right about the promise mismatch. Its proposed cure—becoming a quiet coach—is optional. A logger can offer templates without assuming responsibility for training decisions. The current goal calculator derives loads from bodyweight, standards, age, and emphasis; those are not observations of this person’s performance ([GoalCalculator.kt:30](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/standards/GoalCalculator.kt:30)).

**Confidence: 90%. Strongest objection:** Removing coaching from the central promise may remove the owner’s most distinctive idea.

## 2. The watch

**Recommendation:** Make the watch a complete workout execution surface with companion simplicity.

- Default: next set, load/reps, one-tap confirmation, rest countdown, haptic.
- Essential exceptions: change actual reps/load, skip, undo, choose another exercise, finish.
- Phone: authoring, substantial rearrangement, history.
- Outdoor cardio: interval guidance, distance, pace, optional heart rate.

A timer-only companion leaves too many ordinary exceptions requiring the phone. A full wrist plan editor spends effort on the wrong moments. A cardio-only watch abandons the core strength job.

People having phones out proves availability, not preference. A watch can win if confirming a set is faster than unlocking the phone and correcting four reps instead of five remains easy. Heart rate should be optional context, never permission to increase a load.

First fix session identity: the sync implementation explicitly acknowledges that a late watch tick can mark the next run of a one-day plan ([SetEditApplier.kt:43](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/sync/SetEditApplier.kt:43)). Offline logging and Finish need durable run IDs and replay-safe operations.

**Confidence: 85%. Strongest objection:** Frequent load changes may make the phone faster despite excellent wrist design.

## 3. Distance and sensors

**Recommendation:** Yes, on the watch first; defer native phone GPS. Prototype outdoor running and walking before expanding activities. This deliberately advances GPS beyond October’s “only if linking falls short” sequence.

Health Connect linking supplies past results; it cannot supply live interval guidance. Native recording therefore solves a different problem.

Existing cardio already distinguishes time/distance endpoints, aims, outcomes, and measurement sources ([Cardio.kt:12](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/model/Cardio.kt:12)). Attach measurements to those sets. Retain manual machine distance, unknown values, and source labels. Initially, distance alerts should prompt completion rather than automatically ending intervals. Store no route.

Use Wear Health Services `ExerciseClient`, checking each device’s capabilities. It provides distance, pace, and heart rate with power-optimized sensor configuration ([Health Services](https://developer.android.com/health-and-fitness/health-services)). Required scope:

- `ACTIVITY_RECOGNITION` for distance/pace; precise location for GPS, requesting fine and coarse together.
- `READ_HEART_RATE` on API 36+; legacy `BODY_SENSORS` on older watches.
- A workout foreground service with applicable health/location types and permissions, started while visible; an ongoing notification. No always-on background tracking. ([Sensor permissions](https://developer.android.com/health-and-fitness/health-services/permissions), [foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types))

Persist locally, sync to the phone, then write Health Connect exercise, distance, heart-rate, and speed records with their respective grants ([data types](https://developer.android.com/health-and-fitness/health-connect/data-types)). Health Services permits only one active exercise across apps: never silently displace another recorder ([ExerciseClient](https://developer.android.com/health-and-fitness/health-services/active-data)).

GPS and continuous sensing add battery cost; measure drain and accuracy on real watches with screens off. Keep manual logging usable after permission denial.

Retain no INTERNET permission. Update privacy text, health/foreground-service/location declarations, and Data safety answers. Local sensing alone does not imply off-device collection, but actual transfer flows determine declarations ([Play guidance](https://support.google.com/googleplay/android-developer/answer/10787469)).

**Confidence: 75%. Strongest objection:** Sensor reliability and platform maintenance could consume the effort needed to finish strength logging.

## 4. Phone structure

**Recommendation:** Three tabs: **Today, Plans, Log**. Settings stays in the app bar.

Today should contain:

- An unfinished workout’s Resume card.
- The recommended next workout, with plan name and brief summary.
- Other runnable routines, including pinned conditioning.
- Recent completion and “Start empty workout.”

It should help choose, not display every exercise in the selected day. Current Today resolves the selected plan’s suggested day and observes that day’s logs ([TodayViewModel.kt:55](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayViewModel.kt:55)); that is narrower than a genuine overview.

**Path:** Today → workout preview → Start → simple set screen → Log → rest/next set → app-bar Finish → receipt → Today.

Preview owns exercise order, prescriptions, last results, and session adjustments. Opening preview must not start the clock. Start creates the durable run. Returning to Today preserves it. Finish saves partial work honestly and advances rotation exactly once.

**Confidence: 90%. Strongest objection:** Preview adds a tap for habitual users; Resume and a direct Start action can shorten that path.

## 5. Progress and what we track

**Recommendation:** Progress should answer “What have I done, and how does comparable work differ?” Maintenance counts.

The current trajectory filter explicitly excludes accessories and prescribed slots ([JournalBuilder.kt:58](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/JournalBuilder.kt:58)). Replace that eligibility rule with an exercise history available from every planned exercise and every historical exercise.

Log opens on sessions; an **Exercises** view provides:

- Every performed set, date, plan, and relevant notes.
- Weighted work: best sets by explicit criterion, reps at a given load, working-set volume, optional estimated 1RM.
- Reps-only work: counts and comparable resistance conditions.
- Holds and timed repetitions: actual duration, count, and resistance.
- Cardio: duration, distance, pace, and interval outcomes by activity.

Estimated 1RM is a labeled estimate for suitable weighted sets, not a universal ranking. Volume describes workload; it does not make unrelated machines comparable. Preserve per-hand and assistance conventions.

Plan adherence means workouts and prescribed work completed, shortened, skipped, or substituted—not punishment for missing calendar days. Reliable adherence requires the original prescription snapshot.

**Confidence: 90%. Strongest objection:** This could become another statistics dashboard. Default to history and one useful comparison; put other measures behind selection.

## 6. Plans, wizard, and modularity

**Recommendation:** The wizard is wrong as the mandatory entrance, not as an optional tool.

Offer **templates, build your own, import, and start empty**. Bundle a small template collection; include the existing maintenance generator as one option. Ask equipment and practical constraints before producing a routine. Request bodyweight/age only for the formula option. Let users enter known working loads.

Import should mean reviewed structured plan files; historical CSV import must not pretend to recover prescriptions.

The unfinished parts are behavioral:

- **Plan edits erase live evidence.** Swapping deletes logs; changing authored prescriptions does too ([TrackerRepository.kt:539](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:539), [:662](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:662)).
- **Cascade mixes future targets with performed values.** TOP edits rebuild ramp rows without checking completion ([SetEditor.kt:25](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/seeding/SetEditor.kt:25)). Preserve the numerical function; separate its planning output from observed results.
- **Origin controls capabilities.** Authored prescriptions cannot have supersets ([TrackerRepository.kt:738](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:738)).
- **Timed execution is inconsistent.** The phone’s ordinary timed-set action confirms the displayed duration; the watch’s timed-hold face ticks through a delta that supplies no measured seconds ([ExercisePage.kt:327](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/ExercisePage.kt:327), [TickDelta.kt:26](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/TickDelta.kt:26)).
- **Equipment filtering can surrender.** Generator fallback selects an unavailable exercise rather than report an unresolved slot ([ProgramGenerator.kt:394](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/generator/ProgramGenerator.kt:394)).
- **Cardio export is incomplete.** Its Health Connect mapper writes a session summary; permissions omit distance ([CardioRecordMapper.kt:42](/home/betty/Projects/strength.log-wt/design-md/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/CardioRecordMapper.kt:42), [HealthConnectPermissions.kt:36](/home/betty/Projects/strength.log-wt/design-md/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectPermissions.kt:36)).
- **Run identity and universal progress remain unfinished**, as above.

End on one model: **Plan → ordered workouts → typed exercise/cardio blocks → set prescriptions**. A workout run freezes those prescriptions and records actual results separately. Supersets group independently recorded exercises. Origin is provenance, not an editing restriction. Each exercise explicitly chooses fixed targets or remembered working values. History crosses plans without silently rewriting them.

Finish session integrity, safe substitutions, authoring parity, timed results, and complete exports first. Then watch execution and sensors. Retire parallel legacy cardio execution after lossless migration. Cut automatic deloads, inferred readiness, and an open-ended coaching engine. Keep §11 numbers unchanged.

**Confidence: 90%. Strongest objection:** This requires data-model work before delivering much visible improvement.

## 7. Keep and cut from last week

**Recommendation:** Keep the simple set screen, app-bar Finish, app-only Settings, consistent icon, visible Undo, clearer rest state, session-first Log, and consolidated receipt. Keep the watch’s large readable values and simpler navigation.

Reject three product assumptions:

- The shortened wizard still forces everyone through generated goals ([phone brief:887](/home/betty/Projects/strength.log-wt/design-md/docs/briefs/phone-redesign-2026-10-30.md:887)).
- “Lifts” still organizes progress around the privileged mains.
- A watch declaring the day done while requiring phone Finish is an incomplete execution experience ([watch brief:449](/home/betty/Projects/strength.log-wt/design-md/docs/briefs/watch-redesign-2026-10-30.md:449)).

Replace the mysterious exercise-name chevron with a clearly labeled “Sets and options” affordance. Test tap-to-log against hold-to-log; council agreement is not evidence of wrist usability.

Preserve Material 3 and enforce contrast, font scaling, TalkBack, and touch targets. “Never scroll” cannot override accessibility.

**Confidence: 85%. Strongest objection:** Reopening settled interaction choices delays useful improvements already designed.

## 8. The owner’s next five decisions

1. **A: logger with optional planning help, or B: adaptive coach?** Choose A.
2. **Separate prescriptions, active runs, and results before further expansion—yes/no?** Yes.
3. **A: Today chooses among workouts, or B: Today presents one selected workout?** A.
4. **A: watch completes workouts including corrections and Finish, or B: timer and confirmation companion only?** A.
5. **Fund a bounded watch GPS prototype after session integrity, while deferring phone GPS—yes/no?** Yes.

**Confidence: 90% in this order. Strongest objection:** A quick watch prototype could settle demand before the larger data-model investment.

## What would change my mind

Observe six target users across three real workouts each. Compare phone, companion watch, and complete watch execution: corrections, accidental logs, phone retrievals, and voluntary device choice. Include a missed week, shortened hold, equipment substitution, disconnected Finish, and delayed replay. Test GPS against measured routes and record battery drain. If users choose the phone consistently or already prefer another cardio recorder, narrow the watch scope.