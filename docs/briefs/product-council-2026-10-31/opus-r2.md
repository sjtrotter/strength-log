# Opus, round 2

## Where we split: my final positions

1. **Watch scope.** I moved toward Astra and Sol. The watch runs a prepared workout from start to Finish. On the wrist: log, undo, record fewer reps or a different load, skip a set, pick another lift, finish the day. Finish is a watch action that the phone applies, which is what Fable and I both meant. The line is plan editing: swap, add, remove, reorder and history stay on the phone. Sol's sentence moved me: "a beautiful 'log five' button is inadequate when four reps happened."

2. **Tap or hold.** I hold my position: tap to log, with the Undo chip, and holds only for starting and stopping clocks. The test is cheap: a week with tap; more than one stray log a session sends it back to hold.

3. **When GPS gets built.** Mostly held, with one concession. Astra is right that Health Connect linking cannot give live guidance during an interval. Live guidance by time needs no GPS; only distance-ended intervals do. So GPS is a bounded spike on the owner's watch after the strength watch work, not a product commitment and not next.

4. **Templates.** I hold: no bundled catalog. Curated templates are content a one-person project must maintain; the owner accepted "no templates" on 10-19. The doors are **Make me one** (the short wizard), **Build your own**, **Duplicate** and **Start empty**. Import waits.

5. **The run as a first-class record.** These are not the same change, and Astra and Sol have the deeper one. Today the live log is both the next-time values and the record of the workout in progress. That is why:
   - swaps and prescription edits delete performed sets (`TrackerRepository.kt:545`, `:668`);
   - checks reset by calendar date (`CheckmarkReset.kt:33`);
   - a late watch tick can re-tick the next run of a one-day plan (`SetEditApplier.kt:46-47`).

   Carry-forward is a policy that sits on top of that split: at Finish, results go to history, and each slot's working values update from them. The data comes first and the UI second; I moved here.

6. **Carry-forward policy.** I moved partway. Every slot remembers its working values by default. One per-slot switch, "Keep to plan", holds a fixed prescription for physical therapy (PT) and test-prep work.

7. **Metrics.**
   - **Estimated 1RM:** dropped. Fable moved me: the maintenance math deliberately sits below max effort, and e1RM invites chasing it.
   - **Best set:** the heaviest set, ties broken by reps, as `PersonalRecord` already derives it.
   - **Volume:** cut.
   - **Adherence:** completed versus prescribed sets per session, which the run record gives for free, plus Fable's "rotation complete" on the receipt. I withdraw my 28-day count.

8. **Tabs.** **Today, History, Plans.** "History" says what the tab holds (Sessions and Exercises). "Progress" promises a climb that a maintenance lifter is not on. I moved from Progress.

9. **Start empty.** In, after the run record exists. It is the owner's original use. It saves as a session and offers "Save as plan".

## Fact check

**Fable**
- **Watch has no sensors** (`wear/src/main/AndroidManifest.xml:10-20`): true. Its only permissions are wake lock, notifications and vibrate.
- **`DistanceSource.GPS` has no producer** (`Cardio.kt:43`): true. My round 1 cited `Cardio.kt:166`, which is wrong. The correct citations are:
  - `Cardio.kt:43` (`DistanceSource`);
  - `Cardio.kt:66-72` (`effectiveCardio`);
  - `Types.kt:91-104` (`CardioSuggestion`).
- **Four Health Connect permissions** (`HealthConnectPermissions.kt:36`): true.
- **`removeCustomExercise` has no screen:** true; nothing in `app/` calls it.
- **"Ten to fifteen percent per half-hour run":** no source, and nothing in the repo supports it. Measure it.

**Astra**
- **Late tick** (`SetEditApplier.kt:43`): true. The class comment states it at lines 46-47.
- **Generator fallback** (`ProgramGenerator.kt:394`): true. `bestAccessory` ends `?: ranked.first() // never leave a hole` and ignores equipment.
- **TOP cascade** (`SetEditor.kt:25`): true. `editWeight` rewrites every RAMP and BACKOFF row, done or not.
- **Authored supersets refused:** true. `setSupersetPartner` returns early on a prescribed slot.

**Sol**
- **No run ID on deltas** (`SetEditDelta.kt:20`): true. The delta carries `planId` and nothing that identifies a run. That contradicts PLAN A13, which says snapshots and deltas carry "the run they belong to". Run identity was specified and never built.
- **Swaps and prescription edits delete logs** (`TrackerRepository.kt:545`, `:668`): true.
- **Superset round ticks mark both tracks together** (`DayScreenBuilder.kt:199`): true.

## The strongest point I missed

Astra and Sol saw that the plan and the performance share one table. I framed the problem as carry-forward, which is a symptom. The cause is that the live log is at once tomorrow's prescription and today's result. Until those are separate, every feature (watch Finish, Start empty, adherence, safe swaps) inherits the same data-loss edges.

## The owner's five decisions, final

1. **Separate the plan, the active run and the results**, with a run ID on every phone write and watch delta, before more UI work? **Yes.**
2. **One plan model:** origin is provenance only, and each slot remembers its working values, with a per-slot "Keep to plan"? **Yes.**
3. **(A)** Today as the overview with a preview screen, a direct Start on the suggested day, and tabs Today, History, Plans; or **(B)** the redesign's Today, Log, Plan? **A.**
4. **(A)** New plan from Make me one, Build your own, Duplicate or Start empty; or **(B)** add a bundled template catalog? **A.**
5. **Does the watch run a prepared workout end to end** (tap and Undo, a different number of reps or load, skip, pick a lift, Finish), with GPS a bounded spike afterward rather than a commitment? **Yes.**

## Build order

1. **The run record.** Start creates a run; results live apart from the plan's working values; every phone write and watch delta carries the run ID; a tick for a finished run is rejected. *The owner sees:* a mid-workout swap keeps the sets already logged, and a workout open past midnight survives.
2. **One plan model.** Retire `PlanOrigin` gating; carry forward with "Keep to plan"; fold the legacy `CardioSuggestion` into typed blocks; seed authored standard lifts from the goal math rather than 0 lb (`TrackerRepository.kt:686`); move plan settings into the plan. *The owner sees:* authored plans remember last session's weights, and Setup loses the controls that did nothing.
3. **Phone structure.** Today as the overview, the preview, the one-set screen, Finish in the app bar, one receipt. *The owner sees:* the new Today and start flow, and a labeled "All sets".
4. **History.** Sessions and Exercises; an exercise page for every exercise, also opened from the workout's Lift sheet; completed versus prescribed on each session; "rotation complete" on the receipt. *The owner sees:* history for every accessory, authored lift and cardio activity.
5. **Plan creation.** The New plan sheet with four doors, the three-screen wizard, and Start empty. *The owner sees:* first run no longer forces the wizard.
6. **The watch.** Wear M3 set loop, tap to log with Undo, reps and load correction, skip, lift choice, Finish. *The owner sees:* a watch that can run a whole session with the phone in the locker.
7. **Distance.** Health Connect distance writes and run linking, then a one-week Health Services spike on the owner's watch measuring accuracy and battery. *The owner sees:* distance from Fitbit runs attached to cardio sets, and a go or no-go report on native GPS.
