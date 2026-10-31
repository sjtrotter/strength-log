# Plans side by side, training places, and extras: council, 2026-10-31

The owner asked three questions after step 3 of milestone M9:

1. Should a cardio plan, or a PT plan that pauses the others, be separate from the strength
   plan?
2. Should the app have gyms, with a first-visit equipment survey, so workouts fit the place?
3. What else is missing?

**Seats.** Claude (Opus) went first with web research. Sol, then Astra, read the earlier
answers and searched the web too (`codex --search`). Fable sat out because it was over its
usage limit. The brief and the three answers are in `plans-and-places-2026-10-31/`.

## The owner's decisions

| Question | Decision |
| --- | --- |
| Plans side by side | Yes, at the start of step 4 |
| Training places | After GPS (#284) |
| Extras | File all four |

## 1. Plans side by side

All three seats agreed. Strength, cardio and PT plans are ordinary plans, with no plan type.

- **Plans.** A plan is either on Today or paused, and archived stays as it is. The Plans
  screen offers:
  - "Use alongside";
  - "Pause";
  - "Use only this plan", which pauses the others and keeps their place. This is the PT case.
- **Pausing and resuming.** The lifter can pause every plan; Today then offers Start empty.
  Nothing resumes on its own, and the app makes no medical decisions.
- **Today** shows one "Up next" row per plan on Today, each with the plan's name, for example
  "Strength · B" and "Cardio · Intervals". Only one workout runs at a time, and Resume comes
  first.
- **Small displays** (the watch, widget, tile and launcher letter) show the open workout first,
  then the preferred plan, then the first plan on Today.
- **The model.** Add `plan.onToday`, and keep `selectedPlanId` as the preferred plan; two seats
  out of three chose this. Rotation, runs and equipment stay per plan. Finish advances only the
  run's own plan.
- **This amends** product council decision 4, which gave Today one suggested workout. It keeps
  the one-plan model.
- **Left out:**
  - plan types;
  - pauses that end on a date;
  - dependencies between plans;
  - weekday scheduling.

## 2. Training places

A smaller, optional version of the owner's idea.

- **Setting up a place.** Settings has "Training places". A place is a name plus the equipment
  checklist, and each item can show a built-in drawing ("What is this?"). A lifter who skips
  setup sees no change.
- **Choosing a place.** The preview shows "At Home". Switching places lists the needed swaps
  **before Start**; the lifter accepts, changes or skips each one. The swaps apply to that
  workout only, and the plan is not rewritten.
- **Learning over time.** "Unavailable here" saves an exclusion to the place, and "Station
  taken today" affects only the workout. This replaces the full first-visit machine survey.
  Exercise ids already separate machines such as leg press and hack squat, so no catalog retag
  is needed.
- **History.** A run records its place and the equipment it used. Sessions show where they
  happened. Switching places on a machine lift asks the lifter to confirm the load, because
  stacks differ between gyms.
- **Prerequisites:**
  - workout-only swaps, built in step 4 because they help anyway when a station is taken;
  - #339, extended: the generator ignores equipment for main lifts as well as accessories.
- **Left out:**
  - GPS or Wi-Fi detection, which needs a location permission;
  - a shared gym database, which needs the network;
  - machine recognition;
  - camera photos, until backups can carry them.

## 3. Extras, ranked

1. **Save a finished session as a plan.** Start empty gets a reusable result. Medium, after
   step 5.
2. **Setup notes per exercise and place,** such as "Seat 4, pin 3". Small, with places.
3. **Weights a place actually has:** its plates, bar weights and dumbbell range, used for load
   rounding. Medium, after places. Plate math assumes fixed denominations and unlimited plates
   today.
4. **Optional reminders for a plan,** mainly PT. Local and on chosen weekdays. Pausing the plan
   silences them. Small.

**Rejected:** "ease back in at 90%". Two seats out of three rejected it, and `docs/PLAN.md`
rules out prefilling a lighter number.

**Refused by every seat:**
- accounts or sync;
- social feeds;
- streaks and badges;
- an AI coach;
- video libraries;
- a networked gym database;
- automatic rehab prescriptions or clearance.

## Build order after this council

| Step | Work |
| --- | --- |
| 4 | Phone flow (#344), starting with plans side by side and workout-only swaps |
| 5 | Log (#345) |
| 6 | Watch (#346) |
| 7 | GPS (#284) |
| 8 | Training places, after #339 |
