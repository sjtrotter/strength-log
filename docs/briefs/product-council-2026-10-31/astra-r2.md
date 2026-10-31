1. **Separate prescriptions, active runs, and results before broad UI work—yes/no? Yes.** Reliable recording comes first.
2. **Carry-forward: A, explicit Fixed/Remembered policy per plan slot, or B, remember everything automatically? A.** Keep the prescription intact and provide Reset to plan.
3. **Watch: A, complete execution of a prepared workout, or B, confirmation companion? A.** Include corrections and offline Finish; default to tap plus Undo.
4. **Creation: A, focused creation doors plus ad hoc workouts, or B, build a bundled template catalog now? A.** Keep structured import; defer the catalog.
5. **GPS: A, prototype after durable runs, or B, wait until Health Connect linking proves insufficient? A.** Prototype early; make production investment depend on demonstrated value.

The build order:

1. **Introduce durable runs and captured prescriptions.** The owner sees Resume survive midnight and process death, and edits stop erasing completed work. Verify delayed replay, duplicate Finish, migration, and backup restoration.
2. **Prototype outdoor watch distance.** The owner can try live interval distance and alerts on their watch. Measure accuracy, battery use, and interruption recovery before committing to production.
3. **Unify plans, typed cardio, and working-value policies.** The owner gets consistent editing capabilities, Fixed/Remembered controls, and Make me one, Blank, Duplicate/repeat, and reviewed structured Import.
4. **Build the phone navigation around those records.** The owner gets Today’s choices, preview, direct Start, Resume, and Start empty, using the agreed set screen and receipt.
5. **Complete prepared-workout execution on the watch.** The owner can correct actuals, select exercises, skip, undo, and Finish while disconnected. Validate tap logging during real workouts and with accessibility settings.
6. **Deliver universal progress and cardio interchange.** The owner gets Sessions and Exercises, captured-intent comparisons, appropriate optional metrics, and Health Connect linking and supported exports.
7. **Productionize watch recording if the prototype earns it.** The owner gets dependable outdoor distance and interval guidance, with permission-denial fallbacks and measured limitations; routes remain unstored.

My final positions on the nine splits:

1. **Watch scope—hold with Astra/Sol.** The line includes actual reps/load/duration corrections, exercise selection, skipping, rest, Undo, and offline Finish for a cached workout; plan authoring and substantial rearrangement stay on the phone.

2. **Tap or hold—move to Opus/Fable’s tap plus Undo.** Their repeated-friction argument persuades me: choose tap now, retain correction through the set list after Undo disappears, and use wrist testing to validate that choice.

3. **GPS timing—hold with Astra/Sol.** Prototype immediately after durable runs: linking historical results cannot settle the value of live interval guidance, and carrying a phone does not settle whether wrist guidance is useful.

4. **Templates—move toward Opus/Fable.** Generator defaults already provide a starting routine, so defer a bundled catalog while retaining Blank, Duplicate/repeat, and reviewed structured Import; their scope argument outweighs my earlier enthusiasm for more packaged choices.

5. **First-class runs—hold: these are complementary changes, not equivalent ones.** Run identity protects today’s facts; working memory supplies next time’s starting values, so establish the data contracts and a complete Resume/Finish path before migrating the broader UI.

6. **Carry-forward—hold on explicit Fixed/Remembered, adopting Fable’s scope per slot per plan.** Identical exercises can serve different roles; preserve existing behavior during migration, make both policies available everywhere, and let Reset to plan restore the unchanged prescription.

7. **Metrics—hold with Astra/Sol:** optional, labeled estimated 1RM and volume for the same exercise belong inside exercise detail; adherence compares outcomes with captured intent, explicitly showing skipped, shortened, and substituted work. Session frequency and rotation completion remain useful facts, but neither establishes adherence by itself.

8. **Tabs—move to Opus’s Today, Progress, Plans.** Progress covers both reviewing sessions and comparing exercises; plural Plans accurately names the library.

9. **Start empty—hold: in, without requiring a plan.** Start it on the phone, use the same run/result model, optionally save it as a routine afterward, and never advance a plan’s rotation.

I checked at least two claims from every paper against `44a680f9`:

- **Opus:** Confirmed that adding an authored standard lift seeds **0 lb × 10** ([TrackerRepository.kt:686](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:686)), and prescribed working rows are deleted **after archiving** ([TrackerRepository.kt:1310](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:1310)). The blanket “cannot log different reps” claim is too broad: timed-rep execution sends the entered count ([TimedRepState.kt:100](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/TimedRepState.kt:100)); ordinary strength corrections remain missing.

- **Fable:** Confirmed the current watch has no workout-sensor integration; its manifest permissions cover wake, notifications, and vibration ([AndroidManifest.xml:10](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/AndroidManifest.xml:10)). Health Connect requests no distance write permission ([HealthConnectPermissions.kt:36](/home/betty/Projects/strength.log-wt/design-md/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectPermissions.kt:36)). **Fable and Opus overstate the Finish gap:** standalone cardio sends `finishesDay` ([WearApp.kt:180](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/WearApp.kt:180)), which the phone applies to rotation ([TrackerRepository.kt:1086](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:1086)); strength still lacks equivalent Finish.

- **Astra:** Confirmed the late-tick vulnerability: the applier explicitly acknowledges a tick reaching the next run of a one-day plan ([SetEditApplier.kt:45](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/sync/SetEditApplier.kt:45)). Equipment fallback also genuinely bypasses availability when no eligible accessory exists ([ProgramGenerator.kt:396](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/generator/ProgramGenerator.kt:396)).

- **Sol:** Confirmed strength deltas lack run identity ([SetEditDelta.kt:20](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/sync/SetEditDelta.kt:20)), and calendar rollover clears completion flags and timestamps ([CheckmarkReset.kt:33](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/checkmark/CheckmarkReset.kt:33)). Both support prioritizing durable runs.

The strongest point I missed was **Fable’s observation that generator defaults already constitute a starter template**. Beginner help does not require maintaining a catalog; showing a concrete, editable routine meets that need and changes my creation recommendation.