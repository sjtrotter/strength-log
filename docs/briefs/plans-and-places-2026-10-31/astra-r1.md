## 1. Separate plans

**Recommendation:** Yes. Strength, cardio, and PT should be ordinary plans with independent rotations. I agree with both seats on that separation and on optional, manually selected training places.

**What the lifter sees:** Today shows “Strength · B” and “Cardio · Intervals.” Each opens a preview. Plans offers “Use alongside,” “Pause,” and “Use only this plan.” Choosing only PT pauses the others while preserving their positions. Resuming them is explicit. An open workout always gets Resume first; only one workout runs at a time.

**Model:** Choose Sol’s `plan.onToday` alongside DataStore’s `selectedPlanId`, kept as the preferred plan. Membership and preference answer different questions. Small displays show the open run, otherwise the preferred included plan, otherwise the first included plan. This extends the existing [open-run priority in TrackerRepository.kt:1289](data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:1289).

Add `onToday` to `PlanEntity`; retain `plan.suggestedDayId`, `workout_run.planId` in `WorkoutRunEntity`, and per-plan equipment recipes. Finish already [advances the run’s own plan](data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:1567). PT uses entered `Prescription` values and `program_exercise.keepToPlan`, without a PT type.

Reuse `CardioBlock` and step 3’s [specified rotation fallback](docs/briefs/one-plan-model-2026-10-31.md:130): all-cardio plans rotate through their days; mixed plans retain C1/C2 outside their strength rotation. Opus’s proposed eventual deletion of that distinction is unnecessary.

**Both missed:** Allow every plan to be paused. Today then offers Start empty and Plans. The [current selection fallback](data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:137) would otherwise select another unarchived plan. Pausing must also leave an open run intact.

**Order and cost:** Beginning of step 4, **medium**: Today, glances, migration, and backups must agree. Initially include only the previously selected plan. This amends the council’s single suggestion on Today; it preserves the one-plan design.

**Return after a gap:** Reject the suggested “90%.” [PLAN.md:240](docs/PLAN.md:240) explicitly prohibits a prefilled lighter number. [Runna documents adjustments to running plans](https://support.runna.com/en/articles/8895444-need-to-pause-your-plan), but supplies no basis for that lifting percentage. Show last performance and editable targets; keep the existing decision.

**Leave out and risks:** No automatic resumption, dependencies between plans, or recovery judgments. Persist pause and preference changes locally and include them in backups.

## 2. Gyms and locations

**Recommendation:** Build the smaller version: named equipment profiles, chosen manually, with gradually learned exclusions. Defer the comprehensive illustrated machine survey.

**What the lifter sees:** Settings → Training places → Add place. Enter “Home,” check equipment, and optionally open bundled drawings explaining unfamiliar items. Skip setup entirely if unwanted.

The preview shows “At Home.” Changing it proposes substitutions together, **before Start**, as Sol recommends. Accept, choose another, or skip an unresolved exercise. Unchanged workouts retain direct Start. “Unavailable here” saves an exclusion; “Station occupied today” affects only this workout.

**Model:** Add `gym_profile` / `GymProfile` with name, `Equipment` choices, and excluded exercise IDs, plus nullable `currentGymId`. The recipe remains the generator’s equipment setting; the selected profile supplies today’s equipment, falling back to the recipe.

Capture the profile ID, name, and equipment context in `workout_run` / `WorkoutRunEntity`; preserve the context in `workout_session` / `WorkoutSessionEntity`. Phone suggestions, `WatchSnapshotBuilder`, and `SetEditApplier` must use that captured context. A profile edit must not change an underway workout.

Add temporary substitutions through `run_track` / `RunTrackEntity`. Today’s [swap rewrites `program_exercise.exerciseId`](data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:694). The new path preserves the plan and original remembered loads; results belong to the performed exercise. Rotation stays unchanged. This adds a capability without reversing step 3.

**Catalog dispute:** Sol is right for exercise exclusions. [Hack squat and leg press already have separate IDs](domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseLibrary.kt:60), and [MacroFactor documents per-profile exercise exclusions](https://help.macrofactorapp.com/en/articles/300-set-up-your-gym-profiles). No catalog retag is required. However, identifying one physical machine and excluding every exercise using it would require additional mappings.

**Both missed:** Equipment failure extends beyond accessories. The [generator’s fallback for main lifts also ignores availability](domain/src/main/kotlin/cloud/trotter/log/strength/domain/generator/ProgramGenerator.kt:372). Resolve both paths under #339 before promising reliable tailoring.

**Order and cost:** Temporary swaps in step 4; Locations **after GPS**, agreeing with Sol and preserving the [accepted sequence](docs/briefs/product-council-2026-10-31.md:176). The watch builder [already accepts equipment as an input](app/src/main/kotlin/cloud/trotter/log/strength/sync/WatchSnapshotBuilder.kt:72); profiles need not delay its rebuild. **Medium** for storage, review, backups, and watch consistency. The complete machine survey is **large**.

**Risks and exclusions:** Require load confirmation when changing machine environments. [Alpha Progression warns that equal stack labels can represent different resistance](https://alphaprogression.com/en/glossary/gym-profile). Equipment matching also cannot establish that a substitution suits a rehabilitation prescription.

Ship illustrations inside the app. Defer camera photos until backup and restore include them. Leave out GPS/Wi-Fi detection, machine recognition, and a public gym directory.

## 3. What else

Ranked:

1. **Save a session as a plan — Medium, after step 5.** Give Start empty a reusable outcome through the session menu.
2. **Exercise-and-place setup notes — Small, with Locations.** Show “Seat 4” beside the exercise; existing swaps [preserve the slot note](data/src/main/kotlin/cloud/trotter/log/strength/data/db/dao/ProgramDao.kt:109), contrary to Opus, but cannot attach it to the right machine.
3. **Available weights — Medium, after Locations.** Sol’s size is right: [PlateMath.kt:14](domain/src/main/kotlin/cloud/trotter/log/strength/domain/units/PlateMath.kt:14) assumes fixed denominations and unlimited quantities; actual plate counts, bar weights, and irregular dumbbell racks need new calculation and editing behavior while preserving pinned goal math.
4. **Optional local plan reminders — Small, later.** Chosen weekdays can support PT repetition; pausing silences them without changing rotation.

I would refuse automatic rehabilitation prescriptions or clearance, accounts, cloud sync, social feeds, and a networked gym database.

## Sources

Code links above were checked. Additional verified vendor evidence:

- [Hevy](https://www.hevyapp.com/features/gym-routines/): routine folders, session-to-routine saving, and optional routine updates.
- [Fitbod](https://fitbod.me/blog/your-gym-profile/): saved equipment profiles and temporary workout changes.
- [Medbridge GO](https://www.medbridge.com/care/medbridge-go): patient-set exercise reminders.

[Bevel users report machine-history problems across gyms](https://feedback.bevel.health/feature-requests/p/gym-profiles-for-equipments); this is anecdotal evidence. Recommendations and sizes are my judgments. The proposed concurrent-plan pause model is an inference from strength.log’s needs, not a verified competitor feature.