strength-log should be a reliable workout logger with optional planning: choose what to do, record what actually happened, and preserve a useful starting point for next time. Its center should be the workout, not the goal formula or either device. The phone handles planning and detailed review; the watch can handle an entire ordinary session. Distance tracking belongs where it completes that session. “The best workout app” needs a clear boundary; supporting every training ambition would leave this one permanently unfinished.

## 1. Product

**Recommendation:** Serve recreational lifters who want consistent training despite inconsistent schedules, including people following their own routine or someone else’s prescription.

The job to win against Strong, Hevy, JEFIT, and notes is: **resume a familiar workout, record deviations quickly, and leave the next workout intact.** That is a competitive proposition, not an established superiority claim.

Be a logger with guided execution and optional plan creation. Do not promise adaptive coaching. A generated plan is useful without making its generator the product.

August’s diagnosis remains right about the mismatch, but its proposed “quiet coach” does not resolve it ([direction review:30](/home/betty/Projects/strength.log/docs/briefs/direction-review-2026-08-25.md:30)). More commentary cannot turn a bodyweight-based target calculation into individualized coaching; the calculation uses bodyweight, experience, age, and emphasis ([GoalCalculator.kt:30](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/standards/GoalCalculator.kt:30)).

**Confidence:** 85%. **Strongest objection:** People without an existing routine need more guidance, and a restrained logger may struggle to explain why they should choose it.

## 2. The watch

**Recommendation:** Make it a complete execution device for a prepared workout, with a companion experience when the phone is being used.

The common path needs next set, one-press logging, quick weight/rep corrections, rest, undo, exercise selection, and Finish. Planning and extensive editing belong on the phone. Cached workouts should remain usable disconnected; retain the existing durable edit queue ([PendingEditStore.kt:18](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/kotlin/cloud/trotter/log/strength/wear/data/PendingEditStore.kt:18)).

Last week’s watch proposal stops short: it leaves Finish and different-rep logging as open phone dependencies ([watch redesign:754](/home/betty/Projects/strength.log-wt/design-md/docs/briefs/watch-redesign-2026-10-30.md:754)). A beautiful “log five” button is inadequate when four reps happened.

Add optional heart rate and cardio recording. Keep strength logging usable alongside another recorder: Health Services permits only one active exercise across apps. [ExerciseClient guidance](https://developer.android.com/health-and-fitness/health-services/active-data).

The owner’s phone observation is plausible but inconclusive. Having a phone available does not establish a preference for reaching for it after every set. A watch can win by making those reaches unnecessary.

**Confidence:** 75%. **Strongest objection:** Corrections and navigation may erase the watch’s convenience; some users will consistently prefer the larger screen.

## 3. Distance and sensors

**Recommendation:** Yes to watch-first outdoor distance recording; defer phone GPS. Support manual entry and Health Connect linking on the phone.

The existing cardio model already distinguishes time-ended and distance-ended sets, plus an optional second-measure aim ([Cardio.kt:17](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/model/Cardio.kt:17)). One continuous recording should feed those intervals using distance baselines. Preserve measurement source and missing values. External total distance must not invent interval splits.

Use GPS outdoors; machine distance or known laps indoors/on tracks. Show distance, average pace, interval progress, and optional heart rate. Store no route initially.

The implementation needs:

- `ACTIVITY_RECOGNITION`; fine/coarse location requested together for GPS; heart-rate access through `BODY_SENSORS` on older versions or `health.READ_HEART_RATE` on API 36+. [Permissions](https://developer.android.com/health-and-fitness/health-services/permissions), [location requests](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime).
- A user-started foreground recorder with the applicable health/location types and permissions. Avoid background-location permission. [Service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types).
- Phone-side Health Connect writes for exercise, distance, heart rate, and speed when recorded, with separate grants. [Record permissions](https://developer.android.com/health-and-fitness/health-connect/data-types).

GNSS and continuous sensing add battery cost; measure it on actual watches instead of promising a percentage. Use Health Services batching and an economical ambient display.

No INTERNET permission or account is needed. Update privacy explanations, health/location declarations, Data safety answers, and foreground-service demonstration material before release. Local-only processing is excluded from “collection,” but transfers still require review. [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [FGS declarations](https://support.google.com/googleplay/android-developer/answer/13392821).

**Confidence:** 70%. **Strongest objection:** Existing running apps may already satisfy this need with better accuracy and reliability.

## 4. Phone structure

**Recommendation:** Three tabs: **Today, History, Plans**. Settings remains app preferences and integrations.

Today should show an active-workout resume card, the selected plan’s next workout, other selectable workouts, recent activity, and “Start empty.” It should answer “What could I do next?” without displaying an entire day’s exercise list.

Selecting a workout opens its **preview**: exercises, prescriptions, relevant last results, changes, and Start. Starting creates the active workout and opens the simple set screen. Logging advances within the chosen exercise; exercise selection remains accessible. Finish stays in the app bar through completion, then opens one receipt.

An active workout survives navigation, process death, and midnight. Today currently chooses from the suggested-day pointer ([TodayViewModel.kt:62](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayViewModel.kt:62)); resume should instead follow explicit workout identity.

**Confidence:** 90%. **Strongest objection:** A separate preview adds a step for someone who always performs the suggested workout. Offer a direct Start action on that card.

## 5. Progress and what we track

**Recommendation:** History contains **Sessions** and **Exercises**, with plan filtering. Every exercise gets history, including archived and authored work.

Current trajectories explicitly exclude non-main and prescribed exercises ([JournalBuilder.kt:65](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/JournalBuilder.kt:65)). The owner is right: that makes progress subordinate to the generator.

For each exercise show recent sets, last performance, comparable best sets, and appropriate trends:

- Weighted: load/reps, work-set count, volume, optional estimated 1RM.
- Reps: reps per set and total.
- Holds: actual duration, resistance, and each-side context.
- Timed reps: count within the same window.
- Cardio: duration, distance, pace, and interval completion.

Estimated 1RM should be labeled and limited to suitable weighted performances. “Best” should preserve context rather than always selecting the heaviest load.

Plan adherence means completed versus intended work and rotation coverage, using the prescription captured when that workout began. Stable performance can be success. Keep volume available inside exercise detail; remove its dominance over session history.

**Confidence:** 90%. **Strongest objection:** More metrics invite meaningless comparisons and increase implementation scope.

## 6. Plans, wizard, and modularity

**Recommendation:** The wizard is wrongly positioned, not inherently useless.

First offer **use a template, build your own, repeat a workout, import, or restore**. The maintenance generator becomes one optional template builder. This deliberately reverses October’s no-templates recommendation ([plans review:226](/home/betty/Projects/strength.log-wt/design-md/docs/briefs/plans-review-2026-10-19-r2.md:226)).

Ask equipment and practical preferences before showing a generated workout. Formula goals remain optional references with pinned math unchanged; users confirm starting loads. Shortening the questionnaire while retaining its mandatory premise solves the wrong problem.

The unfinished seams are concrete:

- **Creation:** First launch routes through wizard completion, while empty-plan creation sits inside Plans ([AppNavHost.kt:141](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/AppNavHost.kt:141), [PlansScreen.kt:92](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/plan/PlansScreen.kt:92)). Finish equal creation paths.
- **Future prescription versus actual:** Authored logs are deleted after completion to reseed the next run ([TrackerRepository.kt:1312](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:1312)). Make “repeat prescription” versus “remember actual” explicit behavior.
- **Workout identity:** Strength deltas identify plan and slot but contain no run ID ([SetEditDelta.kt:20](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/sync/SetEditDelta.kt:20)); checks reset by calendar date ([CheckmarkReset.kt:33](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/checkmark/CheckmarkReset.kt:33)). Finish durable run identity before expanding recording.
- **Destructive changes:** Swaps and prescription edits delete live logs ([TrackerRepository.kt:668](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:668), [TrackerRepository.kt:545](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:545)). Preserve completed work; separately offer changes to future workouts.
- **Supersets:** Authored slots reject partners; existing round ticks mark both tracks together ([TrackerRepository.kt:742](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:742), [DayScreenBuilder.kt:199](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreenBuilder.kt:199)). Finish authored pairs with independently correctable results. Defer arbitrary circuits.
- **Timed holds:** The phone offers ordinary logging outside its timed-rep clock branch; the watch schedules hold timing ([ExercisePage.kt:305](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/ExercisePage.kt:305), [WearApp.kt:787](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/WearApp.kt:787)). Finish equivalent timers and actual stopped durations.
- **Cardio interchange:** Health Connect currently requests neither distance nor heart-rate writes, and external reading filters to strength sessions ([HealthConnectPermissions.kt:36](/home/betty/Projects/strength.log-wt/design-md/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectPermissions.kt:36), [HealthConnectReader.kt:87](/home/betty/Projects/strength.log-wt/design-md/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectReader.kt:87)). Finish relevant writes and linking.

End with **Plan → workout templates → ordered strength/cardio blocks**, plus separate **WorkoutRun → captured prescription → actual results**. Generated/authored describes provenance. Optional slot policies provide cascade and remembered values. Extend JSON, CSV, and supported Health Connect exports with each result type.

Cut adaptive progression, readiness scoring, nested programming languages, and compulsory formula onboarding.

**Confidence:** 85%. **Strongest objection:** This model migration is substantial, and a less unified product could deliver useful improvements sooner.

## 7. Keep and cut from last week

**Recommendation:** Keep the simple set screen, rest/undo, readable numerals, exercise details, sessions-first history, one receipt, accessibility corrections, and Wear M3 direction.

Keep the owner’s settled Settings split, app-bar Finish, and consistent icon.

Replace Today with workout selection. Replace main-only “Lifts” with all-exercise history. Replace mandatory shortened wizard with creation choices. Do not move Finish into the main action after the last set, as proposed ([phone redesign:388](/home/betty/Projects/strength.log-wt/design-md/docs/briefs/phone-redesign-2026-10-30.md:388)).

Treat hold-to-log timing and navigation removal as prototype hypotheses. Council approval establishes neither gym usability nor product fit.

**Confidence:** 85%. **Strongest objection:** Some polished interaction decisions may work well; reopening everything risks endless redesign.

## 8. The owner’s next five decisions

**Recommendation, in order:**

1. **A/B:** Logger with optional planning, or adaptive coach? **A.**
2. **Yes/no:** Separate active workouts and actual results from future prescriptions? **Yes.**
3. **A/B:** Equal template/build/import/empty entry paths, or mandatory generation? **A.**
4. **Yes/no:** Must a prepared workout be correctable and finishable entirely on the watch? **Yes.**
5. **A/B:** Prototype integrated watch GPS next, or commit to external recording only? **A**, after durable runs.

**Confidence:** 85%. **Strongest objection:** Model work could postpone visible improvements; deliver accessibility fixes alongside it.

## What would change my mind

Test six recreational lifters across three real sessions: phone, watch, then free choice. Include changed reps, a busy station, partial completion, disconnection, and resuming after midnight. Measure errors, corrections, phone reaches, and voluntary device choice.

Separately test one-hour GPS sessions on two watch families for distance, battery, interruption recovery, and duplicate-free Health Connect writes. If users keep choosing the phone, reduce watch scope. If external recording satisfies cardio without friction, defer GPS.