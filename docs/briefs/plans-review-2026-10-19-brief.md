# Design review: saved plans, first-class cardio, and returning after time away

You are one of two independent design reviewers. You are READ-ONLY: do not edit
files, do not run Gradle or any build. Read whatever you need in this repo. The
tree holds no personal data. Your output is a design memo, not code.

## The app

strength-log is a native Android + Wear OS strength tracker: local-first, no
accounts, no network permission, paid one-time ($4.99), currently in Play
internal testing. Read for grounding (skim, don't summarise them back):

- `CLAUDE.md` — design principles (simple, SSOT, MAD, Material 3 as the
  component system, no bespoke UI without a reason). Pinned domain numbers in
  spec §11 must never move.
- `STRENGTH_TRACKER_SPEC.md` §1 (vision, reference user: ~40, maintenance,
  unreliable schedule), §4 (goal math, seeding, cascade), §6 (wizard, generator,
  cardio planner), §7 (data layer).
- `docs/PLAN.md` (amendments; A1 session history; it wins over the spec).
- `docs/briefs/cardio-promotion.md` — the cardio work that SHIPPED (C0–C2b):
  CardioPlanner suggestions become executable step plans, a `cardio_session`
  table, phone + watch execution, Health Connect writes. Note its deliberate
  absences: no distance, no pace, no HR, no user-authored cardio.
- `docs/briefs/direction-review-2026-08-25.md` — "notebook or coach?"; it
  already proposed a "humane return" line after absence.
- `docs/briefs/ui-redesign-2026-10-12.md` — the current redesign: Today / Log /
  Plan bottom bar, the workout as a pager with one set in focus (phase 1 merged),
  NO BESPOKE CODE rule, rotation length 2–6 days.
- Code: `domain/.../model/Types.kt` (Program/ProgramDay/ProgramExercise,
  CardioSuggestion), `domain/.../generator/` (ProgramGenerator, CardioPlanner,
  CardioIntervals, WizardAnswers), `domain/.../library/ExerciseEntry.kt`
  (TrackingType WEIGHTED/REPS/TIMED), `data/.../db/entity/` (ProgramEntities:
  exactly ONE program in Room, days keyed "A".."G", `exercise_log` = the live
  ACTUAL per slot that persists forever once seeded; SessionEntities = immutable
  history; CustomExerciseEntity), `app/.../ui/plan`, `app/.../ui/wizard`,
  `app/.../ui/day`.

## What the owner said (verbatim, lightly trimmed)

> i think the main thing we are missing on this app is the ability to store and
> swap out to different workouts. or, workout plans, if that makes more sense.
> like as an example. i am in the middle of doing some physical therapy and i
> need to follow the docs prescribed exercises. that means i can't track my
> workouts on here anymore because we are making (a lot of) assumptions about
> what the user is going to be doing.
>
> additionally, i feel like cardio is absolutely still a second-class citizen in
> our current iteration. like, a warmup and the exercise itself (whether it be
> distance or intervals or what-have-you) plus a cooldown or whatever it is like
> should be built-in to the cardio card or something? i'm not sure. it should be
> consistent with the rest of the app though i think?
>
> also, such as in the case of me with physical therapy i was thinking that
> after a determined amount of time away from a workout plan, it should probably
> be reset to the original lift levels (or possibly re-wizarded?) so that the
> user doesn't try to pick up where they left off and potentially injure
> themselves.

## The three questions

**Q1 — Saved plans.** Should the app hold several plans and let the lifter
switch the active one? What is a "plan" then: a generated program (today's
wizard output), a hand-authored one (the PT case: prescribed exercises,
sets × reps, holds, per-side work, bands with no weight, frequency like
"daily" or "twice a day", the clinician's notes), or both? One active plan at a
time, or can a PT plan run alongside the strength rotation? What happens to
GOAL math, seeding, the cascade, rotation-not-calendar, and DONE-advances for an
authored plan? How do history, backup/CSV, Health Connect and the watch
snapshot attribute sessions to plans? Data-model shape and migration from the
single-program schema.

**Q2 — Cardio as a first-class workout.** The shipped cardio is a finisher
card or a standalone day running a fixed step plan (easy 25:00; tempo 20:00;
warm-up + 5 × 2/2). The owner wants warm-up → main piece (time, distance, or
intervals) → cool-down built in, consistent with the rest of the app. What is
the right model: cardio blocks as steps inside the same plan/day structure as
lifts? user-editable? distance targets without GPS or network (manual entry,
treadmill readout, Health Connect read)? How does it render under the new
pager grammar (one thing in focus) and on the watch dial? What should stay
out?

**Q3 — Returning after time away.** After a set absence from a plan, should
loads reset (to the original seeded GOAL? to a fraction of the last ACTUAL?),
should the app re-run the wizard, or should it only offer? Threshold(s),
graded by length of absence or not? Per plan (switching back from a PT plan to
the strength plan after 8 weeks is the owner's real case)? Automatic vs
offered vs user-confirmed. How it interacts with `exercise_log`'s
"seeded once, then the lifter's living record" rule and the pinned §11 numbers
(which must not change). Ground thresholds/fractions in what you know of the
detraining literature and say how confident you are.

**Cross-cutting.** Is this the right next big thing for this app and its
reference user, or does any part pull the product somewhere it shouldn't go
(e.g. becoming a generic workout builder, a medical tool, a sports tracker)?
Where would you draw the v1 line? What order would you build it in, given
Play launch is close?

## Deliverable

A memo, 1200–1800 words, in this shape:

1. **Verdict** — two or three sentences: what to build, what not to.
2. **Q1 / Q2 / Q3** — each: recommendation, the model/data shape in a few
   lines (Kotlin-ish sketches welcome), what changes for the user on screen,
   risks, and alternatives you rejected with one-line reasons.
3. **Decisions for the owner** — a numbered list of the genuine choices only
   the owner can make, each with options and your pick.
4. **Build order** — phases, each a PR-sized slice, smallest useful first.
5. **Confidence** — per question, a percentage and what would change your mind.

Cite repo files as `path:line` when you rely on them. Be concrete and
opinionated; do not hedge every line. Plain prose, no marketing tone.
