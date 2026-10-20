# Design review, round 2: plans, cardio as sets, GPS, and training exposure

You are one of two independent design reviewers (the other is a different
model; you will see its memo later). You are READ-ONLY: do not edit files and
do not run Gradle or any build. Read whatever you need in this repo; the tree
holds no personal data. If you have web access, use it for platform facts
(Android location/foreground-service rules, Google Play location policy, Wear
OS Health Services, Health Connect record types) and cite what you use. Your
output is a design memo, not code.

This round is meant to think harder than round 1. Take your time, read the
code that matters, and reason from first principles about this app and its
user. Push back on the owner where an idea is wrong for the product; agreeing
is not the job.

## Read first

- `docs/briefs/plans-review-2026-10-19.md` — round 1's merged report, plus
  `plans-review-2026-10-19-sol-raw.md` and `-astra-raw.md` (the two round-1
  memos). Round 1's "settled" list stands unless you argue it should not.
- `CLAUDE.md` — design principles (simple, SSOT, MAD, Material 3 as the
  component system, no bespoke UI without a stated reason). §11 pinned numbers
  in `STRENGTH_TRACKER_SPEC.md` must not move.
- `STRENGTH_TRACKER_SPEC.md` §1, §4, §5, §6, §7; `docs/PLAN.md`.
- `docs/briefs/cardio-promotion.md` (shipped cardio: step plans, cardio_session,
  phone + watch execution, HC writes; its deliberate absences).
- `docs/briefs/ui-redesign-2026-10-12.md` (the workout is now a pager, one set
  in focus; NO BESPOKE CODE; rotation 2–6 days).
- `docs/briefs/wear-dial-redesign.md:284` — "No Health Services while the watch
  is a manual logger … revisit with #154's cardio build, where passive exercise
  and sensor data become part of the product."
- Code: `domain/.../model/Types.kt`, `domain/.../library/ExerciseEntry.kt` and
  `ExerciseLibrary.kt` (184 entries, each with ONE `MovementPattern`, no muscle
  data), `domain/.../seeding/`, `domain/.../generator/` (CardioPlanner,
  CardioIntervals), `data/.../db/entity/`, `data/.../TrackerRepository.kt`,
  `app/src/main/AndroidManifest.xml` (current permissions: HC write exercise,
  write active calories, read exercise, read weight; vibrate; notifications;
  no INTERNET, no location), `app/.../ui/day/`, `app/.../health/`.

## What the owner decided and said after round 1

Decided: **saved plans are built before production.**

Owner, verbatim (lightly trimmed):

> cardio i was thinking like intervals are *sets* if that makes sense? … maybe
> perhaps like a cardio 'card' in a workout could have a *set* for warmup,
> individual *sets* for the work section, then a *set* for cooldown — and
> depending on the workout it could be based on distance or time, or a
> combination or something. this should be explored further.
>
> we could potentially *offer* gps tracking for distance, with fallback to
> manual entry if denied; also, if the user could choose the type (i.e.
> treadmill, trail, track, elliptical, cycle, bike... idk) then that could
> also drive the GPS question itself because we can't track GPS for say
> treadmill, stationary bike, or elliptical.
>
> the user may want to build (and swap between) different workouts, not
> necessarily just a PT routine; that was just an example. maybe they have a
> 'maintenance' workout they do, but they are getting ready for say a PT test
> or something for the military, and they need to ramp up work in specific
> areas. they could swap out the workout for that.
>
> this makes me think about like tracking the exercises inside the workout and
> what muscle groups they work, and in what way; then we can make smarter
> recommendations on deloading or what-have-you when swapping between
> workouts too. i.e. for a PT plan, there's no (or low) weight usually so
> swapping to a maintenance workout would recommend deload; but swapping from
> a maintenance workout to a pre-test or show plan wouldn't recommend a deload
> because the app knows that you've been working those muscles in the other
> workout. (and the same goes for cardio type stuff too...)

(Here "PT test" means a military physical fitness test, e.g. a timed run plus
push-ups/sit-ups/deadlift-style events, not physical therapy.)

## Questions

**A. One card grammar for lifts and cardio.** Take the owner's idea seriously:
a cardio card is an exercise with sets — a warm-up set, one set per work
interval (with recovery between, the way lifts have rest), a cool-down set.
Each set's target is time, distance, or both. Work out whether one set model
can carry both without lying about either: the set's measure (load × reps,
reps, seconds, metres, metres + seconds), GOAL vs ACTUAL for cardio, what
"done" means for a running set, set kinds (today RAMP/TOP/BACKOFF/WORK/EXTRA),
seeding and the cascade (lifts only?), add/remove set, the recovery between
work sets vs the rest timer. How does the pager's one-set-in-focus show a set
that is live (timer counting, distance accruing)? The watch dial? History,
CSV, backup, Health Connect (ExerciseSessionRecord segments/laps?). How the
shipped cardio (step plans, `cardio_session`, pinned vectors) migrates into
it. Say plainly where the analogy breaks.

**B. Cardio types and GPS.** Propose the type list (treadmill, track, road,
trail, elliptical, stationary bike, outdoor bike, rower, walk, swim? — decide)
and what each type implies: GPS offered or not, sensible measures, HC exercise
type. Then the GPS design: phone, watch, or both; Android permissions
(fine location, foreground service type, background location or not), Play
location-permission policy and Data safety consequences, battery, accuracy
(a 400 m track is a known GPS failure case; indoor), behaviour on denial or
signal loss, whether a distance target can auto-complete a work set, HC
distance/route writes and their permissions, Wear Health Services. Keep "no
INTERNET permission" intact. Weigh it honestly: is GPS worth its cost here, and
what is the smallest version worth shipping? Alternatives (reading a run from
another app via Health Connect after the fact) count.

**C. Plans, generalised.** Plans are not just generated vs PT. Maintenance,
test prep with ramp-up in specific areas, rehab, cardio-focused, whatever the
user builds. What is the smallest plan model that covers these? Does a plan
need progression rules of its own (the owner says "ramp up work"), a target
date, phases? Does the app ship templates (e.g. for a fitness test) or only an
editor? Where is the line between this and a generic workout builder, and is
crossing it right for this app's reference user?

**D. Training exposure across plans.** The owner's new idea: tag exercises by
the muscles/regions they work and the way they load them, keep a record of
recent exposure from session history across ALL plans (and cardio), and use it
when switching plans or returning after time away to recommend a deload or
not. Design it or argue against it. If you design it: the tagging model and
where it lives (derive from `MovementPattern` with per-exercise overrides? a
new field? keep SSOT), the dimensions of "way" (heavy load, volume, isometric
/ low load, impact / running, aerobic), the exposure computation (window,
decay, normalisation — give actual formulas), how it compares the plan you are
switching TO against what you have been doing, what the recommendation is
(per exercise? per region? a factor on starting loads?), how it explains itself
in one plain line, how it is tested and pinned, and what it must never become
(readiness scores, fatigue gimmicks, false precision). Say whether it replaces
round 1's 28-day return offer. Ground it in the training literature you know
(detraining, specificity, repeated-bout effect, return to running) and state
confidence.

**E. Cross-cutting.** Is the whole set right for this app and its reference
user (~40, maintenance, unreliable schedule, wants the app to get out of the
way), or does it pull toward a different product? What exactly is in the
before-production cut now that plans are committed, and what follows? Build
order as PR-sized slices. What new numbers get pinned like §11.

## Deliverable

A memo of 2500–4000 words:

1. **Verdict** — a short paragraph.
2. **A, B, C, D, E** — each: recommendation; the model in Kotlin-ish sketches
   where it helps; what the user sees; risks; rejected alternatives with
   one-line reasons; where you disagree with the owner or with round 1, and why.
3. **Decisions for the owner** — numbered, each with options and your pick.
4. **Build order** — PR-sized slices, before-production cut marked.
5. **Confidence** — per section, a percentage and what would change your mind.

Cite repo files as `path:line`. Concrete and opinionated, plain prose, no
marketing tone.
