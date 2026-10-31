# Claude (Opus) seat, round 1

## 1. Separate plans

**Recommendation:** Yes, but as "more than one plan on Today," not as kinds of plan. A cardio
plan or a PT plan is an ordinary plan. What changes is that several plans can be active at once,
and the others are paused with their place kept.

**What the lifter sees:**
- On the Plans tab, each plan is either On Today or Paused. Archived stays as it is now.
- A plan's menu has two actions:
  - "Use alongside" adds it to Today.
  - "Use only this plan" pauses the others. This is the PT case, one tap.
- Today shows one "Up next" row per active plan, labeled with the plan's name, for example
  "Strength · B  Start" and "Cardio · C1  Start." Resume still comes first when a workout is open.
- Resuming a paused plan picks up at the day it stopped on.

**How it fits the model (code checked):**
- Rotation is already per plan (`plan.suggestedDayId`), and runs already carry
  `workout_run.planId`. Pausing needs no rotation change.
- The one DataStore `selectedPlanId` becomes a column, `plan.active`:
  - a Room bump, plus a one-shot fixup that marks the old selected plan active;
  - a backup format bump, where older files derive the column from `settings.selectedPlanId`.
- About 52 references in 7 files move from "the selected plan" to "active plans."
- "At most one open run" stays.
- **No conflict with the one-plan model; it reinforces it.** Don't add a strength/cardio/PT type
  column. That would undo decision 3.
- **It does amend product council decision 4.** Today would show one suggested workout per
  active plan instead of one. Change that now, before step 4 designs Today.
- **Generator:** leave C1/C2 inside generated plans for now. If the owner ends up keeping cardio
  as its own plan, the wizard could later write cardio days into a separate plan. Then the
  "cardio days stay out of the rotation" rule (`Rotation.kt`) could be deleted.

**Build order and cost:** The data change and the Today list go in step 4's first PR. The watch
takes a list of next days in step 6; until then it shows the first active plan. **Medium:** the
logic is easy, but many places read the selected plan: Today, the widget, the launcher letter,
the watch snapshot, and the swap default (`TrackerRepository.kt:664`).

**Leave out:**
- plan types;
- date-based pauses with auto-resume (calendar thinking in a rotation app);
- one plan changing another plan's loads;
- assigning plans to weekdays.

**Risks:**
- The widget, launcher letter, tile and complication each show only one thing. Rule: they show
  the first active plan by `plan.position`.
- Two plans can both have a day "A." Always show the plan name with the letter. Sessions already
  store `planName`.

## 2. Gyms and locations

**Recommendation:** A smaller version. A location is a name plus an equipment checklist, picked
by hand, and the workout adapts for that run only. No guided machine survey and no automatic
detection.

**What the lifter sees:**
- Settings has a Locations screen, empty by default.
- Adding a location asks for a name ("Home," "Hotel") and uses the wizard's equipment checklist.
  Each item has a small line drawing and one line, for example "Cable station: a tower with an
  adjustable pulley."
- Once a location exists, Today and the preview show a chip, "At Home." Tapping it switches.
  The choice holds until changed.
- At Start, any lift that needs missing equipment is swapped **for this workout only** to the
  best available alternate. It is marked "Swapped for Home," and the substitution list is one
  tap away. The plan is unchanged.
- The Log shows where each session happened.

**How it fits the model:**
- **New storage:**
  - a table, `location(id, name, equipmentJson, position)`;
  - a DataStore `currentLocationId`, where null means the feature is off and the app behaves as
    today;
  - `workout_run` and `workout_session` gain the location name.
- **Equipment stays one fact in each place.** The recipe's `equipment` is what the generator
  built for. A location is what is here today.
  - Every runtime reader goes through one function, `availableEquipment()`: the location if one
    is set, else the recipe.
  - Those readers are the picker's filter seeds (`DayEditModels.kt`), the watch alternates
    (`WatchSnapshotBuilder.alternatesFor`) and the watch swap check (`SetEditApplier.kt:173`).
  - The snapshot and the swap check must use the same source, or watch swaps get rejected.
- **It needs a run-only swap, which doesn't exist.** `swapInPlan` (`TrackerRepository.kt:694-703`)
  rewrites `program_exercise.exerciseId`, so today a hotel-gym swap would rewrite the home plan.
  - A run-only swap adds a substitute track to the run.
  - Its sets count toward the substitute's own history and don't carry forward into the slot.
    Dumbbell press numbers aren't bench numbers.
  - Hevy draws the same line: at Finish it asks whether to update the routine.
- #339's "unresolved slot" marker is the same UI. Fix #339 alongside.

**Build order and cost:**
- Build the run-only swap in step 4. It's worth having anyway, for when a station is taken.
- Put Locations between step 5 and step 6, so the watch rebuild reads `availableEquipment()`
  from the start.
- **Medium:** a table, a backup bump, a settings screen, the Today chip, and swapping at Start.
  The line drawings are about 11 vector images, **small**.

**Leave out:**
- **A machine-by-machine survey.** The equipment list has a single MACHINE value. Naming leg
  press, pulldown and so on means retagging 184 catalog entries. Do that later, only if MACHINE
  proves too coarse.
- **GPS or Wi-Fi detection.** Reading the Wi-Fi network name needs a location permission on
  Android 10+ (inferred from secondary sources), which costs trust and adds a Data safety
  declaration.
- **Camera photos.** They bloat backups.
- **Separate progress per gym.** Gravl tracks machine records per gym, and Bevel users ask for
  it. Later, and only if the owner feels the need.

**Risks:**
- Machine weights differ between gyms; Alpha Progression says so. A remembered machine slot used
  at two gyms will drift. Version 1 accepts that and shows the location in history.
- Drawings must ship inside the app, since nothing can be downloaded.

## 3. What else

1. **Ease back in. Small to medium.** After a paused plan or a gap of a few weeks, the preview
   offers "Ease back in," for example the first run at 90%, on remembered slots. It changes that
   run only, never the goal math or the §11 numbers. It is offered, not automatic, which respects
   PLAN's "no automatic deload." This serves both the PT pause and DESIGN.md's "come back after a
   gap." Runna has comparable tools.
2. **Setup notes per exercise and location, such as "Seat 4, pin 3." Small.** They show on the
   set screen. Slot notes exist (`program_exercise.note`), but they belong to the slot, so a swap
   loses them and they can't differ by gym.
3. **Dumbbell and plate inventory per location. Small, after Locations.** It feeds the existing
   `PlateMath` and load rounding, so a hotel's dumbbells that stop at 50 are respected. Alpha
   Progression's gym profiles carry increments.
4. **Optional reminders per plan. Small.** A local notification on chosen weekdays, mainly for
   PT plans. It nudges and never schedules. Medbridge GO has patient-set reminders.

**I'd refuse even if asked:**
- automatic location detection;
- a shared or "verified" gym database (it needs the network);
- an exercise video library;
- streaks and badges;
- an AI coach chat;
- accounts or cloud sync;
- calendar scheduling.

## Sources

Marked V where the seat read the page itself, and S where only search snippets support it.

- Fitbod gym profiles (home, gym and travel profiles; one-off workout changes not saved to the
  profile), V: https://fitbod.me/blog/your-gym-profile/
- Alpha Progression gym profile (the active gym limits exercises and the generator, carries
  increments, and warns that machines differ), V:
  https://alphaprogression.com/en/glossary/gym-profile
- Gravl gym profiles (machine records per gym, no switching gyms mid-workout), V:
  https://gravl.ai/help/en/articles/10283745-gravl-gym-set-up-profiles-and-available-equipment
- MacroFactor gym profiles (individual equipment items, a default profile), V:
  https://help.macrofactorapp.com/en/articles/300-set-up-your-gym-profiles
- Bevel users on progress skewed when switching gyms, V:
  https://feedback.bevel.health/feature-requests/p/gym-profiles-for-equipments
- Hevy asks at Finish whether to update the routine, V:
  https://www.hevyapp.com/features/gym-routines/
- Runna Holiday Mode and "Not Feeling 100%," V:
  https://support.runna.com/en/articles/8895444-need-to-pause-your-plan
- Runna strength "integrates directly with your running plan," V:
  https://runna.com/training/strength-training
- Medbridge GO reminders and videos, S (vendor pages):
  https://www.medbridge.com/enterprise/medbridge_go
- JEFIT "set plan" makes one routine the default, S:
  https://www.jefit.com/wp/product-tips-faq/how-to-reorder-exercises-android-and-ios
- Boostcamp running several programs at once: unverified, weak sources only. Not relied on.
- Wi-Fi network name needs a location permission, S (secondary):
  https://github.com/flutter/plugins/pull/3143

**Inferred, not verified:** whether Strong, Hevy or Boostcamp have any concept of an "active" or
paused program. The seat found no source for it, so it makes no claim.
