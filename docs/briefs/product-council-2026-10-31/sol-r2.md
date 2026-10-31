1. **A/B: Establish durable workout runs first, or redesign screens first? A.** Separate captured prescriptions, active execution, and results before expanding either device.
2. **A/B: Fixed/Remembered policies per plan slot, or universal carry-forward with Reset? A.** Preserve both intentional prescriptions and remembered working values.
3. **Yes/no: Must a prepared workout be correctable and finishable entirely on the watch? Yes**, including disconnected execution.
4. **A/B: Finish Make me one/Blank/Duplicate, or add a template catalog and plan import now? A.**
5. **A/B: Prototype watch GPS after durable runs, or wait until Health Connect linking proves insufficient? A.** Prototype early; make release depend on measured usefulness and reliability.

Relative to Sol’s round 1 paper, these are my final positions on the nine splits:

- **Watch scope — held.** A cached, prepared workout supports rep/load/duration corrections, exercise selection, skipping, logging, rest, Undo, and Finish because these are ordinary execution; the phone owns authoring and substantial restructuring.
- **Tap or hold — moved to tap plus visible Undo.** Opus and Fable persuaded me that mandatory holds impose recurring friction; test accidental logs and correction effort in real workouts before release.
- **GPS timing — held.** Prototype immediately after durable runs: live interval guidance merits testing even when the owner carries a phone, while Opus/Fable’s maintenance concerns justify a demanding release gate.
- **Templates — moved toward Opus/Fable.** Make me one, Blank, and Duplicate provide enough creation choices; defer a catalog and new plan-import format while retaining backup restore.
- **Workout runs — held; these are different changes.** Carry-forward chooses next time’s starting values, whereas a durable run preserves identity, captured intent, unfinished work, and replay-safe completion, so implement its lifecycle before the navigation redesign.
- **Carry-forward — held, with Fable’s slot-per-plan scope.** Offer Fixed or Remembered with sensible creation defaults and editable overrides; remembered values come from completed rows, while fixed targets survive a poor session.
- **Metrics — held, with Fable’s restraint governing presentation.** Keep labeled, optional estimated 1RM and volume inside suitable exercise details; adherence compares actual work against captured intent, while session frequency and rotation completion remain separately named facts.
- **Tabs — held: Today, History, Plans.** “History” describes both Sessions and Exercises without suggesting that improvement is required.
- **Start empty — held: in.** Start a run without a plan, add exercises on the phone, and optionally save it as a reusable workout afterward.

I checked these claims against `44a680f9`, with at least two checks from each other seat:

| Seat | Code check |
|---|---|
| **Opus** | **Confirmed:** adding an authored standard-derived lift seeds **0 lb × 10** ([TrackerRepository.kt:686](/home/betty/Projects/strength.log-wt/design-md/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:686)). **Correction:** “the watch cannot log different reps” is too broad: timed-rep execution already sends the entered count ([TimedRepState.kt:100](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/TimedRepState.kt:100)); ordinary weighted-set corrections remain unfinished. |
| **Fable** | **Confirmed:** the watch manifest declares wake-lock, notification, and vibration permissions, with no location or health-sensor permissions; source inspection found no sensor recorder ([AndroidManifest.xml:10](/home/betty/Projects/strength.log-wt/design-md/wear/src/main/AndroidManifest.xml:10)). **Confirmed:** Health Connect’s requested writes cover exercise sessions and calories, with no distance write ([HealthConnectPermissions.kt:36](/home/betty/Projects/strength.log-wt/design-md/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectPermissions.kt:36)). |
| **Astra** | **Confirmed:** a late watch tick can mark the next execution of a one-day plan; the implementation explicitly documents this limitation ([SetEditApplier.kt:45](/home/betty/Projects/strength.log-wt/design-md/app/src/main/kotlin/cloud/trotter/log/strength/sync/SetEditApplier.kt:45)). **Confirmed:** accessory selection falls back without respecting available equipment ([ProgramGenerator.kt:396](/home/betty/Projects/strength.log-wt/design-md/domain/src/main/kotlin/cloud/trotter/log/strength/domain/generator/ProgramGenerator.kt:396)). |

The strongest point I missed was **Astra’s equipment fallback**. Even optional planning must honor the equipment the lifter selected; an unresolved slot should expose a choice instead of presenting an unusable prescription as ready.

Build in this order:

1. **Introduce the durable run lifecycle and sync identity.** Capture intended work, store actuals independently, scope watch operations to a run, and make Finish idempotent; migrate backup and wire formats alongside it. The owner sees unfinished work survive midnight, process death, and delayed sync.

2. **Build a bounded watch GPS prototype against that lifecycle.** The owner tests live outdoor distance and interval alerts on their watch, with measured battery drain, accuracy, interruption recovery, and permission-denial behavior. This settles feasibility before production polish.

3. **Unify plans, typed cardio, and working-value policies.** Preserve pinned goal math, provide authoring parity, and replace unavailable-equipment fallbacks with explicit resolution. The owner can retain a prescription or remember completed values regardless of how the plan originated.

4. **Deliver creation choices and the phone workflow together.** Add Make me one, Blank, Duplicate, and Start empty; implement Today → preview → workout, with direct Start on the suggestion. The owner gets the simple set screen, labeled exercise options, accurate hold timing, rest/Undo, app-bar Finish, and one receipt.

5. **Complete watch execution.** Add quick corrections, exercise selection, skipping, and durable offline Finish using Wear M3. The owner can complete a prepared workout without retrieving the phone, and later sync produces one result.

6. **Deliver History and complete interchange.** Add Sessions and all-exercise history, restrained detail metrics, and prescription-based completion reporting; older intent stays unknown where uncaptured. The owner can link external cardio and export supported results without invented interval splits.

7. **Productize GPS if the prototype passes.** Integrate watch-only outdoor recording, clear measurement sources, manual fallback, and duplicate-free Health Connect writes. The owner receives live distance guidance and saved interval results, with no stored routes.