## Where the others are wrong

1. Claude’s claim that a deadlift working value is a person-level fact derivable from latest history is wrong. Performance is personal, but the next prescribed load is plan-specific. The same person may program deadlifts as a heavy TOP set in maintenance and as 3×10 in test preparation; the latest completed set cannot determine both prescriptions. History also records actual rows, including deviations, rather than future intent. The repository deliberately stores the complete living set track by plan slot (`data/src/main/kotlin/cloud/trotter/log/strength/data/db/entity/ProgramEntities.kt:60-81`) and archives that track separately (`data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:686-706`). Keep targets per plan; use all-plan history for “last performed” context and optional initialization.

2. Claude’s proposal to make cardio types ordinary library exercises does not fit the library contract. Every `ExerciseEntry` requires one strength-oriented `GoalSource`, and tracking can only derive to WEIGHTED, REPS, or TIMED (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseEntry.kt:7-40`). There are currently no CARDIO entries; the catalog test explicitly exempts CARDIO from its population requirement (`domain/src/test/kotlin/cloud/trotter/log/strength/domain/ExerciseLibraryTest.kt:76-82`). A road run requiring distance and live measurement would therefore need a fake goal and tracking type. Cardio should remain a typed workout item beside library lifts.

3. Claude’s “reuse `session_set` by adding nullable metres” is insufficient. That row still requires lift-specific `slot`, `weightLb`, `reps`, and `done` fields (`data/src/main/kotlin/cloud/trotter/log/strength/data/db/entity/SessionEntities.kt:37-58`), while the parent has no activity type (`SessionEntities.kt:11-25`). It cannot represent a cardio target versus actual, shortened versus skipped outcome, measurement source, or recovery result merely by adding metres. A new typed cardio-result child is cleaner than turning the existing strength table into a discriminator-plus-null matrix.

4. Both memos mishandle active recovery. Astra says recovery executes as its own interval, but its `Role` has no RECOVERY and its `SetResult` can reference only a `SetSpec`; Claude attaches recovery to `restAfterSeconds` while claiming its time and distance count. The shipped interval vector contains five distinct EASY 120-second steps, including the final one (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/generator/CardioIntervals.kt:29-36`). A shortened, skipped, walked, or measured recovery needs its own prescription identity and result. It is not lift rest.

5. Claude’s statement that movement patterns already encode regions is false. `MovementPattern` is a one-value substitution taxonomy (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/model/Types.kt:11-18`). H_PUSH groups bench press, pec deck, flies, dips, and push-ups despite materially different triceps and loading exposure (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseLibrary.kt:123-141`). Consequently, “another H_PUSH exercise exceeded 0.6×its GOAL” cannot clear bench loading. GOALs also mix standards, fractions, flat machine numbers, reps, and time, so 0.6 is not a comparable load class.

6. Calling external-run linking “cheap” is overstated. The current reader requests only exercise and weight permissions, filters exclusively for strength sessions, and discards record identity and distance (`transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectReader.kt:41-42,77-96`; `HealthConnectPermissions.kt:29-36`). Linking requires new permissions, durable provider/record identity, distance association, overlap handling, and no-republish rules. It is still cheaper than native GPS, but not a trivial reader extension.

7. Claude’s phone-first GPS assessment omits the imminent policy cost. The app already targets API 37 (`app/build.gradle.kts:12-17`), and Google’s January 27, 2027 policy requires a precise-location declaration and justification when coarse location or the Android 17 location button is insufficient. Continuous fitness recording is eligible, but still reviewed ([Play minimum-scope policy](https://support.google.com/googleplay/android-developer/answer/17033915)). This strengthens the case for manual distance and external linking before native recording.

## Where the others are better than me

Astra correctly adds timed repetitions. My target algebra could represent “400 m under 1:45” but not “maximum push-ups in two minutes,” a direct military-test case. I now add a time-terminated `RepsInTime` target and support either time or distance as the primary endpoint.

Astra is also right that Add Interval should copy a WORK interval immediately before cooldown, not duplicate whichever set is selected. My rule could create a second warm-up or recovery.

Astra’s stable set IDs, pause transitions, measurement provenance, and preservation of the final legacy EASY interval are more complete than my execution sketch.

I withdraw my exponential region/channel formula. Its half-lives, coefficients, and normalization against nominal weekly frequency imply precision the app cannot validate and conflict with rotation-not-calendar. I now prefer Astra’s factual, exact-exercise review, without its ratio: show prior comparable sets and let the user edit.

Astra is also right about legacy plan attribution. `replaceProgram` deletes definitions and live logs while retaining history (`data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:246-260`), so old Day A sessions may belong to vanished programs. I now leave historical plan identity unknown rather than assigning all native history to the migrated plan.

Claude’s useful correction is narrower than his conclusion: latest performance should be shared across plans as evidence, while prescriptions remain plan-owned.

## Three disagreements that matter most

1. Shared history-derived working values versus plan-owned prescriptions with shared performance evidence. Final pick: plan-owned prescriptions; cross-plan history supplies Last and review context.

2. Cardio as catalog exercises using `session_set` and rest versus typed cardio items with explicit recovery results. Final pick: typed cardio items and explicit recovery sets.

3. Pattern/muscle-based automatic deload clearance versus exact-exercise factual review. Final pick: exact-exercise review across all plans; related patterns appear only as explanatory context and never suppress review.

## Revised confidence

A: 95%. Timed-repetition targets and explicit recovery close gaps in my original model.

B: 91%. Manual distance and external linking clearly precede GPS; demand testing still determines whether native recording exists.

C: 95%. Prescriptions must remain plan-owned; no phases or dates before observed demand.

D: 86%. Confidence rose after removing invented decay math; confidence remains low for any region-derived recommendation.

E: 94%. Plans and authored prescriptions remain the production cut; cardio restructuring, exposure, and GPS follow.