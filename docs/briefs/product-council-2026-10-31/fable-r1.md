# Fable, round 1: what strength-log should be

**Thesis.** The app has one idea worth keeping: a rotation that survives missed weeks, with loads the lifter owns. Everything added since (saved plans, authored prescriptions, cardio sets, timed reps) was bolted on beside that idea instead of under it, so the app has two plan kinds, three cardio representations, and a progress view that knows four lifts. The fix is structural: one plan model in which the goal system is one way to write a prescription; progress derived from history for every exercise; Today as the overview with a separate start screen; a watch that is a companion for the set loop. Distance belongs on the watch, later, if at all.

## 1. Product

**Recommendation.** The user is an adult training for maintenance who misses weeks and wants to be told what to load without becoming a hobbyist (spec §1). The job Strong, Hevy, JEFIT and a notes app do not do: generate a program with owned loads and advance it on completion, so an absence shifts the plan instead of breaking a streak. That is a *program-first logger*: one opinion given once, then faithful recording and derived progress; not a coach that talks every session.

August was half right. The unmade decision was real; the cure (a narrator, a chapter, an assessment line) decorates the same split. The split is in the data: the goal system reaches only generated mains (`JournalBuilder.kt:65`), so every accessory, authored plan and cardio block is a notebook by construction. Fix that and most of the "boring" goes without the app saying a word.

**Confidence:** 75%. **Against:** the owner is pushing toward test prep and PT, which a program-first app serves poorly without a progression engine; a plain notebook with the best in-workout screen on Android might be the more honest product.

## 2. The watch

**Recommendation.** A companion for the set loop: the next set, one press logs it, rest, undo, finishing the day. Nothing that needs reading or editing. The wrist wins "log what I just did" because it is attached and the phone is on a bench, often locked; the phone wins plates, the set list, swaps and edits. Both can run a session (PLAN A8). The watch uses no sensors today (`wear/src/main/AndroidManifest.xml:10-20`).

The owner's observation does not argue against the watch: people have the phone out *between* lifts. Last week's redesign showed a watch logger good enough for weighted and reps sets with a known prescription, and conceded everything else to the phone. Both are correct.

**Confidence:** 70%. **Against:** a Pixel Watch ships Fitbit, which records runs better than this app ever will; our tracker's marginal value is attributing the run to the plan, which Health Connect linking (#283) buys for far less.

## 3. Distance and sensors

**Recommendation.** Track distance only on the watch, through Wear Health Services (GPS and heart rate), writing both to Health Connect. Never on the phone. Order: manual distance (shipped) → Health Connect run linking (#283) → Health Services on the watch, only if the owner runs without the phone.

It fits the cardio blocks as built: a `CardioSetSpec` already ends by distance, and `DistanceSource.GPS` is declared with no producer (`Cardio.kt:43`).

Cost: fine and coarse location, `BODY_SENSORS`, a location and health foreground service with its Play declaration and demo video, plus `WRITE_DISTANCE` and `WRITE_HEART_RATE` (today four Health Connect permissions, `HealthConnectPermissions.kt:36`). Battery: about ten to fifteen percent of a Pixel Watch per half-hour run. Local-first survives: all on-device, no INTERNET. The listing does not: Data safety ("Location: never requested", `docs/play-data-safety.md`) and the privacy policy change. Store distance, never routes.

**Confidence:** 60%. **Against:** the owner cannot verify it from CI, GPS is worst where intervals happen (a 400 m track), and the plans review already settled "GPS only if manual and linked fall short."

## 4. Phone structure

**Recommendation.** Keep Today / Log / Plan. The owner's distinction becomes two screens where the redesign drew one.

- **Today**, the overview: plan name; the next day as the hero (block, title, contents) with "Start day A" as a shortcut; the other days as rows the lifter can choose; one fact line ("Last trained Oct 28"); a standalone cardio row when the plan has one (`TodayScreenBuilder.kt:63` hard-codes it today). In progress: "2 of 18 sets" and Continue.
- **Day**, the start screen: the lifts with prescription and last time, "Edit day", a filled "Start day C". Reached from any day row on Today or Plan, where today a row starts the workout with no warning (`PlanScreen.kt:110`).
- **Workout**, the redesign's one-set screen; then **Receipt**, then Today.

Path: Today → Start → Log … → Finish → Receipt → Today: two taps to the first logged set, three on a day other than the suggested one. Off Today: the numbered lift list with set counts (`01-today.png` names the day three times). On Plan: rotation order, goals, plans, training inputs.

**Confidence:** 70%. **Against:** other days on Today reintroduce the reference October removed as "outranking the action". If the owner's sessions are almost never off-rotation, the redesign's Today is right.

## 5. Progress and what we track

**Recommendation.** Progress means per exercise, for every exercise, derived from history. The data is there: `session_set` holds every set (`SessionEntities.kt:41-58`); `LastPerformed` and `PersonalRecord` are already derived from it (`LastPerformed.kt:55`, `PersonalRecord.kt:21-29`). Only the Log restricts itself to generated mains (`JournalBuilder.kt:65`). That is the disconnect the owner feels.

Per exercise: last time, best set, the heaviest set per session as a single-series trend; best hold for timed, best count for reps; time and distance per cardio activity. The goal line overlays the trend where a goal exists; without one the chart is still true. Adherence: "rotation 7 complete" on the receipt, the one August item I would keep. Estimated 1RM: no; it invites chasing numbers the maintenance factor deliberately sits below (spec §4). Volume: cut.

The Log becomes two tabs: **Sessions** (as the redesign) and **Exercises** (everything ever performed, searchable, each opening its history). The redesign's "Lifts" tab is this, limited to four rows.

**Confidence:** 80%. **Against:** per-exercise pages are Hevy's feature; the differentiator is the goal line only mains have.

## 6. Plans, wizard and modularity

**Is the wizard wrong?** Misplaced, not wrong. It is a plan generator presented as onboarding: eight steps (`WizardScreen.kt:118-128`) whose defaults already produce the spec program (`WizardAnswers.kt:32-40`). The wrong part is that it is the only way to make a plan the goal system reaches; any other plan is dead data: no goals, no cascade, no trajectory.

**Comparison.** Generated (today): the actual product. Templates: the generator with defaults *is* the template; skip. Build your own: needed for PT and test prep, exists, second-class. Import: skip. **Mix on one model:** a slot's prescription is *goal-derived* (catalog `GoalSource` plus ramp seeding, spec §4 unchanged) or *literal* (`PrescribedTarget`), in any plan. The wizard becomes "New plan from your goals" (the redesign's three screens) beside "Build your own" and "Duplicate".

**Half-built, with evidence.**

- Two plan origins gate features inconsistently: Setup always shows emphasis and cardio (`SetupScreen.kt:117-118`) while writes are dropped for authored plans (`TrackerRepository.kt:241`, `:254`); minimum exercises (`PlanRules.kt:6-9`), rotation (`Rotation.kt:12-13`) and add-exercise (`TrackerRepository.kt:677-678`) branch per origin; supersets are refused on prescribed slots (`Types.kt:52-53`).
- Cardio exists three times: English prose generated inside `:domain` (`CardioPlanner.kt:27-42`), interval steps (`CardioIntervals.kt`), and typed sets adapted at read time (`Cardio.kt:66-72`, `CardioBlocks.kt:19`). Generated standalone cardio days are a `CardioDay` outside `Program` (`ProgramGenerator.kt:426-445`) though `ProgramDayKind.CARDIO` exists (`Types.kt:56`). The catalog has no cardio entries (`ExerciseLibraryTest.kt:76-78`).
- `LoggedSet` overloads fields: `seconds` is ignored on weighted sets (`Types.kt:32`); `startedAtMillis` is watch-only and unread (`Types.kt:33-38`).
- Timed holds on the phone log the prescription with no clock (`ExercisePage.kt:221-227`); the watch times them.
- Custom exercises: create only (`CustomExerciseViewModel.kt:130-181`); `removeCustomExercise` (`TrackerRepository.kt:368`) has no screen.
- Health Connect writes sessions and calories only (`HealthConnectPermissions.kt:36`); the watch cannot finish a day (watch redesign, question 5).

**Finish:** the one plan model, with cardio as a slot kind and standalone cardio days folded into plan days; typed sets as the only cardio, the pinned vectors kept as fixtures behind `fromLegacy`; a phone clock for timed holds; per-exercise history; session notes into Room (`TrackerRepository.kt:717-718` has the `TODO(schema)`); custom exercise edit and delete. **Cut:** the prose `CardioSuggestion` and the `CardioDay` side channel; the life line; the volume chart; five wizard steps from the first-run path; per-set start stamps once logging is one press.

**Plan model to end on:** Plan (name, ordered days, rotating or repeatable, position) → Day (title, slots) → Slot (exercise, prescription goal-derived or literal, sets, partner, note) or CardioSlot (activity, typed sets). Working values per slot per plan. History per set keyed by exercise; plan and day are labels. `PlanOrigin` goes.

**Confidence:** 70%. **Against:** the riskiest migration in the app (Room past v12, the backup version, the watch wire) for a product with few installs; shipping the bifurcation and calling authored plans a utility costs nothing now.

## 7. Keep and cut from last week

**Phone, keep:** the one-set screen and its selection rule; the rest band with Undo; the Lift and Day sheets; the scoped `primary` and contrast tables; the three-screen wizard; sessions-first Log; the cascade inside the receipt; the KEEP inventory retired.

**Phone, wrong or premature:** Today as the start page (see 4). The Lifts tab limited to mains (see 5). Plan's "Training" sub-screen, emphasis and cardio for generated plans only, is the bifurcation made into UI; hold it until decision 1. Cardio Redo with an in-place Health Connect amend is a week of work for a slip the Cardio sheet can correct; cut it. Fifteen UI PRs over a data model about to move: plan model first.

**Watch, keep:** Wear Material 3, one press per set, the Undo chip, the Rest face naming the next set, the Lift page, the cardio faces, the phone states, ambient as two lines.

**Watch, wrong or premature:** hold-to-log. The phone logs on a tap; the watch should too, with the Undo chip as the correction and holds reserved for stopping a running clock. "No stray tap writes" is a preference, not a law, and it costs a learned gesture on every set. The watch should finish the day (question 5): a companion that sends you to the phone as you leave is not one.

**Confidence:** 75%. **Against:** three council rounds chose the hold on a safety argument; I sat in one.

## 8. The owner's next five decisions

1. **One plan model.** (A) A slot's prescription is goal-derived or literal in any plan, `PlanOrigin` goes, cardio is a slot kind. (B) Keep generated and authored as two plan kinds. *A.*
2. **Progress for every exercise.** (A) An Exercises tab with history for everything performed, goal lines where goals exist. (B) Trajectories stay mains-only. *A.*
3. **Today versus Day.** (A) Today is the overview with the other days choosable; Day is the start screen. (B) The redesign's Today is the start page; Day lives under Plan only. *A, unless you never go off-rotation.*
4. **The watch logs on a tap, with Undo, and can finish the day.** Yes or no. *Yes.*
5. **Do you run without the phone?** Yes: Health Services on the watch is the distance path, after linking. No: never build GPS; close #284. *A fact about you, not a design choice.*

## What would change my mind

- A week logged watch-only, then phone-only, counting looks, taps and corrections. If the watch week is not fewer of all three, the watch is a tile and a rest timer.
- A count of the owner's own off-rotation sessions. Near zero overturns decision 3.
- A throwaway Exercises tab over real history. If non-goal charts read as clutter, decision 2 shrinks to last and best on the card.
- A Health Services spike against Fitbit over a 5 km and a 400 m track. Worse than linking by more than attribution is worth, and decision 5 is no however the owner runs.
- Play install numbers. Under a few dozen, the migration in decision 1 is cheapest now; higher, ship the bifurcation.
