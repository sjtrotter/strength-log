**Verdict.** Build saved plans before production, including a constrained authored-plan editor and complete phone/watch persistence. Accept cardio as exercises with sets in the interaction model, but give those sets typed targets and execution states. Ship manual distance before GPS. Use cross-plan history to improve factual return reviews; reject muscle-derived clearance or automatic load reductions. Round 1 misread military “PT” as physical therapy, so its clinician-specific restrictions should go. Its historical attribution and seeded-load return offer also need revision. This review was read-only; no builds were run.

**A. One card grammar for lifts and cardio**

The owner’s analogy is useful. “Run → warm-up → five work sets with recovery → cool-down” fits the pager better than a separate interval vocabulary. Round 1’s categorical rejection of cardio as sets was too strong.

However, sharing a card and set abstraction does not mean extending `LoggedSet` with more zero-valued fields. It currently combines remembered working values, completion, duration, and optional timestamps; tracking also derives from the exercise’s GOAL source. Those assumptions do not fit authored prescriptions or measured distance. See [Types.kt:27](/home/betty/Projects/strength.log/domain/src/main/kotlin/cloud/trotter/log/strength/domain/model/Types.kt:27) and [ExerciseEntry.kt:30](/home/betty/Projects/strength.log/domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseEntry.kt:30).

A suitable shared domain vocabulary is:

```kotlin
Target =
    LoadReps(lb, reps) | Reps(count) | Hold(seconds, addedLb?)
    | Travel(end: TimeOrDistance, secondaryTarget: TimeOrDistance?)
    | RepsInTime(seconds, repGoal?)

SetSpec(id, role, target, recoveryAfter?)
SetResult(setId, status, actual: TypedActual?, observedSpans, source)
Role = LiftRole(RAMP | TOP | BACKOFF | WORK | EXTRA)
     | CardioRole(WARMUP | WORK | COOLDOWN)
```

`RepsInTime` matters: a military test may ask for repetitions within two minutes. A model that handles running perfectly but cannot represent that event has missed the clarified use case.

For cardio, distinguish **termination** from **performance target**:

- “400 m, aim for 2:00”: distance determines the endpoint; time evaluates performance.
- “Run for 2:00, aim for 400 m”: time determines the endpoint; distance evaluates performance.

Do not introduce configurable AND/OR expressions. Completing 400 m in 2:07 finishes the set while missing its time target. That is neither an unfinished set nor a fabricated success.

Generated strength retains its calculated GOAL and living working values. Authored work and cardio show **Target**, **Last**, and the current result. A target distance never initializes actual distance. Unknown distance remains null; zero means an observed zero. Canonical weight stays pounds, distance metres, and elapsed time milliseconds.

Keep the existing strength seeder and cascade behind their existing policy. Only generated main-lift TOP weight edits cascade; cardio and fixed authored prescriptions never do. This preserves [SetSeeder.kt:22](/home/betty/Projects/strength.log/domain/src/main/kotlin/cloud/trotter/log/strength/domain/seeding/SetSeeder.kt:22) and [SetEditor.kt:20](/home/betty/Projects/strength.log/domain/src/main/kotlin/cloud/trotter/log/strength/domain/seeding/SetEditor.kt:20), including every §11 result.

Recovery belongs to the preceding work set’s prescription, but execution records it as its own interval. It may mean standing, walking, or easy running. Active recovery contributes duration and possibly distance; ordinary lift rest does not become another performed set. Share timer mechanics, not policy: cardio recovery must not inherit the user’s TOP-rest setting or start a second rest countdown.

“Add set” inserts another work interval before cooldown, copying its target and recovery. It does not duplicate whichever row happens to be last. Removing upcoming work is allowed; removing performed work requires a history correction. Changes during execution affect this run unless the user explicitly saves the prescription.

A running set needs `READY`, `RUNNING`, `PAUSED`, and terminal outcomes such as `FINISHED`, `SHORTENED`, and `SKIPPED`. Timer expiry proves elapsed time, not that somebody ran. Timed intervals may advance automatically, but saving confirms the performed workout. Manual distance completion records the entered result, including a shortened interval. The final timed phase enters overrun until Stop, preserving useful existing behavior.

On the phone, keep one exercise page and one set in focus:

> Work 3 of 5 · Target 400 m  
> **1:37 elapsed** · Distance not entered  
> **Finish set**

With GPS later, the large value can become accrued distance. Pause and Stop remain accessible; “All sets” shows targets and results. Swiping away does not stop execution. Use the existing pager, M3 buttons, progress indicators, and sheets; the chosen interaction already specifies these components ([ui-redesign:326](/home/betty/Projects/strength.log/docs/briefs/ui-redesign-2026-10-12.md:326)).

The watch retains one cardio dial state. Timed work drains its ring; manual distance shows target and elapsed time without invented progress. Tap finishes a manual interval; the existing deliberate hold stops the block. Crown navigation must not silently skip a running interval.

Persist the executable prescription snapshot, stable set IDs, transitions, pauses, and measurement provenance. Manual boundaries cannot be reconstructed from one start timestamp. Same-boot elapsed time remains monotonic; after reboot, uncertain time requires review rather than treating powered-off time as exercise. The current phone explicitly guarantees boundary cues only while foreground; changing the card does not solve that limitation ([CardioExecution.kt:39](/home/betty/Projects/strength.log/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/CardioExecution.kt:39)).

Migration must distinguish definitions from history:

- Convert live generated plans through an exact legacy adapter: `[1500]`, `[1200]`, and `[300] + 5 × [120,120]`. Preserve the recovery after the **last** interval; do not silently replace it with cooldown.
- Keep historical `cardio_session` rows as aggregate legacy results. They lack the original `fiveK` setting and plan snapshot, so individual results cannot reliably be recovered ([CardioSessionEntity.kt:6](/home/betty/Projects/strength.log/data/src/main/kotlin/cloud/trotter/log/strength/data/db/entity/CardioSessionEntity.kt:6)).
- Preserve existing IDs and Health Connect client IDs. New authored sessions explicitly saved under 60 seconds must survive; the legacy accidental-start discard rule must not become a per-set minimum.

History should expand cardio into actual work/recovery intervals. Extend `cardio_session` with child results; a universal SQL set table is unnecessary. Version JSON backup. CSV needs explicit format/version, plan/run/set identity, target, actual, and source columns. Today it stores `hard` in Distance Unit and `stepsCompleted` in RPE; retain a legacy reader, stop emitting that overloading ([HistoryCsvWriter.kt:117](/home/betty/Projects/strength.log/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/csv/HistoryCsvWriter.kt:117)).

For Health Connect, write actual activity segments and, where appropriate, interval laps with observed lengths. These cannot fully preserve authored targets or warm-up/work labels; JSON remains lossless. Do not invent segment timestamps or publish overlapping enclosing strength and cardio sessions. [Exercise segments](https://developer.android.com/reference/androidx/health/connect/client/records/ExerciseSegment) and [laps](https://developer.android.com/reference/android/health/connect/datatypes/ExerciseLap) represent different facts.

The analogy breaks at continuous execution, active recovery, uncertain measurements, and performance versus completion. Typed variants preserve those distinctions.

**B. Cardio types and GPS**

Use specific activities, with location/context choices beneath them. “Bike” and “cycle” are synonyms, not separate types.

| User choice | Sensible targets/results | GPS offer later | HC exercise type |
|---|---|---|---|
| Run: road or trail | Time, distance, both | Yes | `RUNNING` |
| Run: track | Time, known distance/laps | No; prefer measured track | `RUNNING` |
| Run: treadmill | Time, machine distance | No | `RUNNING_TREADMILL` |
| Walk: outdoors | Time, distance | Yes | `WALKING` |
| Walk: treadmill | Time, machine distance | No | `WALKING` |
| Cycle: outdoors | Time, distance | Yes | `BIKING` |
| Cycle: stationary | Time; optional machine distance | No | `BIKING_STATIONARY` |
| Elliptical | Time; optional machine distance | No | `ELLIPTICAL` |
| Rowing machine | Time, machine metres, both | No | `ROWING_MACHINE` |

These mappings use the [HC exercise-type catalogue](https://developer.android.com/reference/androidx/health/connect/client/records/ExerciseSessionRecord). Machine distance needs provenance and should not be compared across machines as equivalent performance.

Defer swimming: pool lengths, strokes, water interaction, and open-water recording deserve an actual swimming design. Preserve legacy `LOW_IMPACT` without pretending to know which machine was used. Its current mapping to outdoor biking follows old prose, not observed equipment ([CardioRecordMapper.kt:21](/home/betty/Projects/strength.log/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/CardioRecordMapper.kt:21)).

**My shipping recommendation is manual distance, then external-run linking.** GPS is worthwhile only if users repeatedly need distance during execution and existing sports apps do not satisfy that need.

If implemented, the smallest useful version is **phone recording, distance only**, for outdoor run/walk/cycle. No maps, retained routes, pace coaching, automatic pause, or phone/watch handoff. Select one recorder for the run; never average independent phone and watch distances. GNSS acquisition needs no app `INTERNET` permission; Android exposes a satellite provider directly. [LocationManager](https://developer.android.com/reference/android/location/LocationManager)

Request fine and coarse location together, in context after “Use GPS.” Approximate-only access falls back to manual distance. Start a `location` foreground service while the activity is visible, declaring `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`, with an ongoing Stop notification. This user-started recording does not require `ACCESS_BACKGROUND_LOCATION`; do not request it for restart convenience. [Runtime permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime), [FGS requirements](https://developer.android.com/develop/background-work/services/fgs/service-types)

Play policy still reviews the use case: a foreground service is not a loophole around background-location rules. Recording must follow an explicit user action and end with that activity. [Play location policy](https://support.google.com/googleplay/android-developer/answer/9799150)

There is also a current timing issue: the app targets API 37 ([app/build.gradle.kts:17](/home/betty/Projects/strength.log/app/build.gradle.kts:17)). Play’s announced policy requires justification for persistent precise access; its declaration opens November 2026, with stated enforcement January 27, 2027. Continuous fitness tracking is an identified use case; the Android 17 location button requirement concerns transactional access. [Minimum-scope policy](https://support.google.com/googleplay/android-developer/answer/17033915)

Do not promise exact interval endpoints. Reject stale fixes and jumps, suppress stationary jitter, and record coverage gaps. Signal loss keeps the timer running but makes total distance incomplete; never bridge the gap as a straight-line observation. Offer manual correction. Battery cost comes from continuous sensing and screen activity; measure it on actual devices before setting sampling defaults.

A 400 m track is not universally disastrous for GPS—validation results vary—but known markings are better evidence than an estimated threshold. Curves, short intervals, canopy, and buildings complicate measurement; one eight-watch study found substantial device/environment variation. [Validation study](https://pubmed.ncbi.nlm.nih.gov/32396865/) Initially, GPS should announce “distance reached,” with a tap to finish. Automatic distance completion belongs behind later validation and explicit opt-in.

If wrist sensing follows, use Health Services `ExerciseClient`, not a homemade sensor stack. Query capabilities, handle unavailable data, maintain the appropriate `health|location` service, and persist execution independently of the screen. Distance requires activity recognition; GPS requires fine location. Heart-rate permissions are unnecessary without HR. Health Services permits only one exercise across apps, so starting ours can terminate another recorder. [ExerciseClient](https://developer.android.com/health-and-fitness/health-services/active-data), [permissions](https://developer.android.com/health-and-fitness/health-services/permissions)

That is precisely when the manual-logger exception should be revisited—not merely because timers now have more phases ([wear-dial-redesign:284](/home/betty/Projects/strength.log/docs/briefs/wear-dial-redesign.md:284)).

HC distance writes require `WRITE_DISTANCE`; routes additionally require `WRITE_EXERCISE_ROUTE` alongside exercise access. Request them separately and only for shipped functionality. Neither permission is needed for local manual distance. [Record permissions](https://developer.android.com/health-and-fitness/health-connect/data-types), [routes](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes)

Local GPS processing does not automatically mean “location collected” in Data safety: collection concerns off-device transmission. Reassess actual SDK, backup, Wear, export, and HC flows; explicit user-directed sharing can qualify for exemptions. Update the privacy explanation regardless. Round 1 overstated the automatic Data safety consequence of adding a permission. [Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469)

The cheaper alternative is “Link recorded workout”: extend the existing strength-only HC reader to list runs, let the user select one, and retain provider identity. Distance needs `READ_DISTANCE` and careful source/time matching; do not sum every overlapping provider. Never re-export an imported run or invent its interval splits ([HealthConnectReader.kt:77](/home/betty/Projects/strength.log/transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectReader.kt:77)).

**C. Plans, generalised**

A plan is an ordered collection of runnable days with remembered state. Maintenance, test prep, rehabilitation, and cardio are user names and contents—not new subclasses.

```kotlin
Plan(id, name, origin, navigation, nextDayId, archived)
Day(id, planId, label, items, participatesInRotation)
Slot(id, prescription: MaintenanceGenerated | Fixed)
Run(id, planId, dayId, prescriptionRevision, snapshot, status)
```

Keep personal attributes global; equipment, emphasis, generator recipe, cardio preferences, and cursor belong to the plan. `origin` records provenance. Prescription policy controls behavior: duplicating or editing a plan should not accidentally grant every slot maintenance math.

Generated rotations remain 2–6 days. Authored plans allow a single repeatable day or a short ordered rotation, one exercise minimum. Cardio-only days can finish normally. An authored cardio day may advance **its own** rotation; it must never advance another plan’s strength rotation. Round 1’s “cardio never advances rotation” needs that qualification.

Plan shows name, next day, and last session, with Create, Duplicate, Switch, and Archive. Today and the watch show one selected context. Switching preserves each plan’s values and position. An active run retains its originating prescription; changing plans does not relabel or discard it.

Authored prescriptions support repetitions, holds, optional load, sides, resistance descriptions, and notes. Each run starts from its prescription; actual deviations remain history unless explicitly saved as future targets. Generated slots retain their existing remembered-working-value behavior. This resolves the two raw memos’ disagreement about authored seeding.

“Ramp up” initially means the user edits upcoming prescriptions or duplicates a plan. No progression engine, target-date calculations, or phases are necessary. A date can live in the plan note. If later demand supports automation, progress by completed exposures rather than silently compressing missed sessions before a deadline.

Ship the maintenance generator, duplication, and an empty editor. Generic cardio starters can follow. Reject official military-test templates and scoring for now: standards maintenance and test-specific coaching are separate commitments.

The editor does cross into workout-building, appropriately. The boundary is a small set of supported measures and ordinary ordered days. Reject arbitrary metrics, nested circuits, scheduling rules, and clinical workflows. Generated plans keep lift-first defaults; authored test rehearsal may intentionally order events differently.

I also reject round 1’s attribution of all native history to the migrated plan. `replaceProgram` preserves history while deleting old program definitions, so native history can span multiple vanished programs ([TrackerRepository.kt:246](/home/betty/Projects/strength.log/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:246)). Wrap current live state; leave unprovable historical plan membership unknown. Cross-plan exercise history remains usable.

The principal risk is identity leakage. Phone/watch mutations need plan, day, run, slot, set, and prescription identity. Late messages must retain their original context; incompatible watch versions must not interpret unfamiliar targets as weighted sets.

**D. Training exposure across plans**

Build an **exposure ledger**, not a muscle-based load recommender.

The owner’s directional example is unreliable. Bodyweight work can be demanding; high-volume push-ups do not establish tolerance for heavy benching. Heavy squats do not establish running-impact tolerance. Maintenance training also does not establish test-specific endurance. “Same muscles” is useful context, insufficient clearance.

The existing single `MovementPattern` serves substitution, not anatomy. Pec deck and bench share `H_PUSH`; their triceps involvement differs. Nordic curls are bodyweight yet emphatically not “low stress.” See [ExerciseLibrary.kt:113](/home/betty/Projects/strength.log/domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseLibrary.kt:113) and [ExerciseLibrary.kt:128](/home/betty/Projects/strength.log/domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseLibrary.kt:128).

Use one catalog resolver:

```kotlin
ExposureTags(
    primaryRegions, secondaryRegions,
    contraction: Dynamic | Isometric,
    eccentricEmphasis: Boolean,
    impact: None | Running | Jumping
)
```

Derive conservative defaults from movement pattern, with explicit exercise overrides and unknowns for unclassified customs. Keep primary and secondary participation categorical; do not invent activation percentages. Resolve through `ExerciseCatalog`, the existing lookup boundary. Historical results retain the definition/version needed to interpret them.

“Heavy” is not a permanent exercise tag. Record load, repetitions, sets, hold duration, running duration/distance, and aerobic modality separately. Zero external load does not imply zero stimulus. Without effort or capacity measurements, the app cannot calculate physiological intensity.

I would use an intentionally simple computation:

\[
w(d)=\begin{cases}1&0\le d<28\text{ days}\\0&\text{otherwise}\end{cases}
\]

For exercise/modality \(e\) and dimension \(k\):

\[
Q_{e,k}=\sum_s w(d_s)\,q_{s,e,k},
\qquad
B_{e,k}=\max_{s:w(d_s)=1}q_{s,e,k}
\]

Here \(q\) contains performed work-set count, repetitions, hold seconds, or modality-specific minutes/metres. Exclude unchecked seeds, skipped work, duplicates, and unrelated external summaries. Count known activity in unfinished durable runs once. Keep recovery distinguishable.

This is a reporting window, **not biological decay**. I reject exponential decay here because no defensible common half-life translates these records into retained capacity. Regions group the evidence; they do not sum it into a scalar score.

Compare each incoming day separately, because rotation length does not establish weekly frequency. For comparable quantities:

\[
R_{e,k}=P_{e,k}/B_{e,k}
\]

An absent or zero baseline means unknown. \(R>1\) means “above the recent recorded maximum,” not “unsafe.” Display concrete values, not the ratio: “Five work sets planned; recent sessions had three.”

Load comparison needs a stronger witness: a prior set of the same exercise and load convention with at least the proposed load **and** repetitions. A heavy single plus separate light repetitions cannot jointly establish that witness. Related exercises offer context but no numeric load conversion.

Recommendations are per exercise: **Review starting loads**, **Review added volume**, or **No comparable history**. There is no computed starting-load factor and no “no deload needed” verdict. Example:

> “Bench last logged six weeks ago; push-ups logged this week.”

Retain 28 days as the return-review trigger, but calculate relevant exercise exposure across all plans. A dormant plan containing lifts performed elsewhere should not manufacture a layoff. Recent running should not erase a lifting gap. Acknowledgements prevent repeated prompts for unchanged prescriptions.

Reject round 1’s `min(saved, seededGOAL)` return offer. It may provide no reduction, and reducing TOP can still raise a manually lowered ramp through the existing cascade. Offer editable values and preview every affected row; keep §11 unchanged. Call this a return review, not a deload prescription.

Evidence supports that restraint. [Bosquet’s meta-analysis](https://onlinelibrary.wiley.com/doi/10.1111/sms.12047) associates cessation with performance losses but supplies no individual restart formula. [Halonen’s trial](https://pubmed.ncbi.nlm.nih.gov/39364857/) found losses during ten weeks off and rapid regain in healthy, previously untrained adults. [Load-specific training research](https://pubmed.ncbi.nlm.nih.gov/25853914/) shows strength and endurance adaptations differ. The [repeated-bout effect](https://pubmed.ncbi.nlm.nih.gov/21720885/) supports retained protection and some transfer, not interchangeable loading. [Return-to-running literature](https://pmc.ncbi.nlm.nih.gov/articles/PMC11393297/) emphasizes individual progression and weak evidence for universal percentage rules; its injury population limits generalisation.

Pin exact fixtures: push-ups do not validate bench load; cycling does not supply running exposure; the same lift in another plan counts; missing distance remains unknown; corrected/deleted history recomputes results. Never produce readiness scores, muscle recovery percentages, injury probabilities, or permission to train.

**E. Product fit and production boundary**

Saved plans fit the reference user: they prevent destructive setup and preserve context when life changes. Shared execution and manual distance also fit. Automated periodisation, native sports recording, and physiological inference collectively pull toward a different product.

Before production, ship plans with fixed authored prescriptions, phone/watch parity, honest history/export, and repeatable or cardio-only completion using existing timed cardio recipes. General interval authoring, manual distance, external-run linking, and exposure summaries can follow. GPS should require demonstrated demand.

Keep [§11:542](/home/betty/Projects/strength.log/STRENGTH_TRACKER_SPEC.md:542) and [cardio vectors:46](/home/betty/Projects/strength.log/docs/briefs/cardio-promotion.md:46) untouched. Newly pin endpoint semantics, pause accounting, 28-day boundaries, explicit sub-minute saves, unit conversion, and migration outcomes. Do not invent GPS accuracy constants before field testing.

**Decisions for the owner**

1. **Shared sets:** universal nullable row, or typed targets/results behind one interaction. **Pick typed variants.**
2. **Progression:** automatic ramp-up, or explicit prescription edits. **Pick edits initially.**
3. **GPS:** launch prerequisite, later recorder, or external recording. **Pick external linking first; GPS later if needed.**
4. **Return assistance:** seeded reset, percentage reduction, or evidence-backed review. **Pick review without a default multiplier.**
5. **Legacy attribution:** assign all native sessions, or preserve uncertainty. **Pick unknown membership where unprovable.**
6. **Templates:** official test programs, or generator/duplicate/empty editor. **Pick the latter.**
7. **Launch scope:** all five proposals, or complete plans first. **Pick complete plans first.**

**Build order**

Each slice should land behind existing behavior until its dependencies are ready.

1. **Before production:** plan/day identities, per-plan cursor and settings; migration preserving live values and uncertain history.
2. **Before production:** multi-plan JSON backup/restore and export attribution, with legacy fixtures.
3. **Before production:** durable run identity, transactional completion, same-day repeats, delayed watch-message routing.
4. **Before production:** Plan list; create, duplicate, switch, archive; wizard always creates.
5. **Before production:** fixed prescription persistence and editor, one-exercise/repeatable days, explicit prescription-versus-result behavior.
6. **Before production:** authored phone/watch execution and capability gating; existing timed cardio selectable; cardio-only receipts.
7. **Before production:** integrated migration, process-death, switch/replay, and export verification. **Production cut here.**
8. Typed cardio definitions and exact legacy adapter; new result persistence and CSV format.
9. Phone interval execution, recovery, pause/skip/overrun, and explicit short-session saving.
10. Watch interval parity and durable completion protocol.
11. Manual distance, dual-target semantics, timed-repetition events, HC distance writes.
12. User-selected external-run linking with provenance and deduplication.
13. Cross-plan exercise return review; subsequently add audited region tags and exposure comparisons.
14. GPS field prototype; production implementation only after accuracy, battery, lifecycle, and policy review.

**Confidence**

| Section | Confidence | What would change my mind |
|---|---:|---|
| A | 90% | Common workouts requiring nested intervals or different recovery semantics. |
| B | 86% | Repeated evidence that users need live distance and reject external recording. |
| C | 93% | Sustained demand for deadlines and automatic phases over manual prescription edits. |
| D | 88% | Prospective validation linking this app’s actual inputs to useful restart recommendations. |
| E | 87% | Usability evidence that the smaller production cut cannot support ordinary plan switching. |