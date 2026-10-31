## 1. Separate plans

**Recommendation:** Yes. I agree with Opus: strength, cardio and PT should be ordinary plans with independent rotations. My change is to keep one preferred plan for the watch and other small displays.

**What the lifter sees:** Today shows separate rows, such as “Strength · B” and “Cardio · Intervals,” each opening its own preview. Resume comes first whenever a workout is open; only one workout can run at a time.

Plans offers “Use alongside,” “Pause” and “Use only this plan.” Choosing “Use only PT” pauses the others explicitly. Returning to strength means turning it back on, with its place preserved. Nothing decides that rehabilitation has ended.

**How it fits the model:**

- Add `plan.onToday`, exposed through `PlanEntity`. Pause changes this flag; archive remains separate.
- Keep DataStore’s `selectedPlanId` as the preferred workout focus. Opus’s proposal to replace selection with membership mixes two different questions: which plans appear, and which workout a small display shows.
- Use this display rule: open run first, preferred included plan second, first included plan otherwise. The existing [Today target already gives an open run priority](data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:1289).
- Reuse `plan.suggestedDayId` and `WorkoutRunEntity.planId`. Finish advances only that run’s plan. Equipment remains in each plan’s recipe.
- Reuse `CardioBlock`. Step 3’s proposed rotation fallback already accommodates an all-cardio plan. Keep C1/C2 outside the rotation in mixed plans; separate cardio plans do not make mixed plans obsolete.

This amends the council’s single suggested workout on Today. It preserves the one-plan-model decision.

**Order and cost:** Beginning of step 4. **Medium**, because Today, glances, selection, backups and migrations must agree. Migrate the current selected plan onto Today; leave other plans paused.

**Leave out and risks:** No plan dependency system, automatic resumption, shared recovery calculation or injury-based adjustments. Store pauses locally and include them in backups. PT uses entered prescriptions and “keep to plan”; its name must not trigger medical decisions. Verified: [Medbridge GO describes programs prescribed by a therapist](https://play.google.com/store/apps/details?id=com.medbridgeed.hep.go), which supports that boundary.

## 2. Gyms and locations

**Recommendation:** A smaller version. I agree with Opus on optional, manually selected profiles, but I would review substitutions before Start and put profiles after the existing build order.

**What the lifter sees:** An optional “Training places” screen creates “Home” or “Main gym” with a short equipment checklist. “What is this?” opens a bundled drawing and description. Skip the room-by-room survey.

Once enabled, the preview shows “At Home.” Selecting another place proposes replacements together: “These two exercises need different equipment.” The lifter accepts or changes them before Start. A missing alternative offers Choose another or Skip.

A machine exercise can be marked “Unavailable here” as the lifter encounters it. This gradually improves the profile without an exhausting first visit.

Verified: [Fitbod distinguishes saved gym profiles from equipment changes for one workout](https://fitbod.me/blog/your-gym-profile/). That is the useful behavior to borrow.

**How it fits the model:**

- Add `gym_profile`, represented by `GymProfile`, containing its name, `Equipment` choices and excluded exercise IDs. Store nullable `currentGymId` in DataStore.
- The recipe retains the equipment used to generate the plan. The profile describes today’s available equipment. Without a profile, reuse the recipe.
- Capture the chosen profile and resolved equipment/exclusions in `workout_run` through `WorkoutRunEntity`. Copy its identifying metadata into `workout_session` through `WorkoutSessionEntity`. Renaming a profile later must not relabel history.
- Phone suggestions, `WatchSnapshotBuilder` alternates and `SetEditApplier` validation use the captured run context. Changing the selected place must not alter an underway workout.
- Add a run-only swap path. Opus correctly identified that [today’s swap changes `program_exercise.exerciseId`](data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:700). The substitute belongs in `run_track`, its results belong to the performed exercise, and its loads must not replace the original slot’s remembered values. Rotation stays unchanged.

I disagree that useful machine exclusions require retagging the whole catalog. Existing exercise IDs distinguish [hack squat from leg press](domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseLibrary.kt:60), despite both using `MACHINE`. Verified: [MacroFactor supports excluding specific exercises within gym profiles](https://help.macrofactorapp.com/en/articles/300-set-up-your-gym-profiles). The inference is that strength.log can take this smaller route too.

This extends step 3’s equipment handling without changing its plan or cardio decisions.

**Order and cost:** Run-only swaps belong in step 4; profiles follow GPS. Fix #339 before claiming reliable equipment tailoring: [the fallback explicitly returns unavailable equipment](domain/src/main/kotlin/cloud/trotter/log/strength/domain/generator/ProgramGenerator.kt:393). **Medium** for the smaller feature because it touches storage, backups, previews and watch validation. The complete illustrated machine survey is **large**.

**Leave out and risks:** No automatic location detection, public gym directory, machine recognition or automatic plan rewrites. Ship illustrations; defer camera photos until their backup and restore behavior is designed.

Do not silently transfer machine loads between gyms. Ask for load confirmation and label previous results with their place. Verified: [Alpha Progression warns that equal machine labels can represent different resistance](https://alphaprogression.com/en/glossary/gym-profile). [Bevel users report distorted previous weights and records](https://feedback.bevel.health/feature-requests/p/gym-profiles-for-equipments); that is anecdotal feedback, not evidence of prevalence. Separate machine histories can follow later.

## 3. What else

Ranked, with additions and corrections to Opus:

1. **Save a completed session as a plan — Medium, after step 5.** Gives Start empty a reusable outcome through the session menu; [Hevy verifies this history-to-routine flow](https://www.hevyapp.com/features/gym-routines/).
2. **Exercise-and-place setup notes — Small, with profiles.** Agree with Opus, but swaps currently preserve the slot note: [the update changes only `exerciseId`](data/src/main/kotlin/cloud/trotter/log/strength/data/db/dao/ProgramDao.kt:109); the missing capability is remembering which machine those instructions describe.
3. **Available weights — Medium, after profiles.** Agree on usefulness, disagree on “small”: [PlateMath hardcodes denominations](domain/src/main/kotlin/cloud/trotter/log/strength/domain/units/PlateMath.kt:14), without quantities, dumbbell limits or irregular increments.
4. **Optional plan reminders — Small, later.** Agree with Opus; add the requirement that pausing a plan also silences its reminders.

I would refuse accounts, social feeds, a networked gym database, automatic rehab prescriptions and recovery clearance. I would also reject a standard “return at 90%” rule; the app cannot derive an appropriate return prescription from a pause.

## Sources

Linked product behaviors were independently verified. Recommendations, priorities and sizes are my judgments. The concurrent-plan recommendation follows strength.log’s model; I did not verify an equivalent competitor pause system. I could not retrieve Opus’s original JEFIT or Medbridge links and used Medbridge’s vendor listing instead.