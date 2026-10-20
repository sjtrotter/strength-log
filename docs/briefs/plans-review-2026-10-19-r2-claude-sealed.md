# Claude (Opus 5.5) round 2 verdict, sealed before reading Sol/Astra r2

## Core insight
What a person can lift on deadlift is a fact about the person, not about a
plan. Today the "living ACTUAL" lives per plan slot (exercise_log). Once plans
multiply, that duplicates a fact: maintenance and test-prep both have deadlift
and each would remember its own stale number. So: plans hold PRESCRIPTIONS;
the person's current working values come from HISTORY (latest performance of
that exercise, any plan). Most of the owner's "smart deload" falls out of that
plus movement patterns, with no muscle database.

## A. Cardio as sets: yes, with one honest break
- A cardio type is a library exercise (pattern CARDIO): Road run, Trail run,
  Track run, Treadmill, Walk/ruck, Outdoor ride, Stationary bike, Elliptical,
  Rower. CardioMode becomes the wizard's default pick. No swim (pool distance
  needs laps detection).
- Sets: WARM_UP, WORK xN, COOL_DOWN. Target per set:
  CardioTarget(metres: Int?, seconds: Int?) at least one; when metres is
  present it ends the set and seconds is a goal time ("2 mi under 16:00",
  "400 m in 1:45"); otherwise seconds ends it. ACTUAL always records elapsed,
  and metres when known.
- Recovery between work sets reuses the rest grammar (restAfterSeconds, the
  rest pill), but it is active and its time/distance count toward the session.
  This is where the analogy breaks: rest is not a set for lifts, and recovery
  is not idle for cardio. Accept the break, say it in KDoc.
- Done: a cardio set completes itself (time elapsed, GPS distance reached) or
  by a confirming tap ("Reached 400 m") for manual distance. Never claims a
  target the user didn't confirm.
- History: cardio sets go into session_set (new nullable metres column); the
  separate cardio_session table migrates in and is retired. One history.
- Cascade stays lifts-only. Generated cardio seeds from CardioPlanner; the
  shipped vectors map to legacy sets unchanged.
- Pager fits: the focused set's numeral IS the live countdown/distance left.

## B. GPS: offer it, smallest version, phone first
- Type decides: road/trail run, walk/ruck, outdoor ride offer GPS; track uses a
  lap tap (400 m per tap; GPS on a track is known-bad); indoor types manual.
- Phone: fine location while-in-use, foreground service type location started
  from the foreground; no background location. Store distance only, never
  coordinates, and no HC route. Signal loss: time keeps running, set falls
  back to a confirming tap.
- Watch GPS (Health Services) later, and only if the watch becomes where runs
  start. Reading a finished run from HC is the alternative for people who
  already track runs elsewhere; cheap, worth doing regardless.

## C. Plans generalised
- Plan = name + days + prescriptions; origin generated/authored; rotation or
  repeatable. Duplicate is the key verb (start test-prep from maintenance).
- No shipped military-test templates (tests vary by branch and change).
- Progression: none before production; afterwards one opt-in per-exercise rule
  ("add X when every work set is done"). No target dates or phases: rotation,
  not calendar.

## D. Exposure: build it on patterns, not muscles
- For each exercise in the plan you switch TO, in order:
  1. Same exercise performed in last 28 days (any plan): start from that.
  2. Same MovementPattern performed at a comparable load class in last 28 days:
     start from the plan's saved value, no deload.
  3. Otherwise: offer the deload (owner picks the rule: min(saved, GOAL) or a
     ladder by weeks since the pattern was last loaded).
- Load class: HEAVY = weighted set >= 0.6 x that exercise's GOAL; LIGHT =
  weighted below, bodyweight, band, timed holds. Cardio: by modality, running
  vs non-impact.
- Replaces the 28-day return offer; absence is the empty-window case.
- One line per affected exercise: "Hinge: only light work since 2 Sep.
  Suggest 185." No scores, no readiness, no muscle heat maps.
- Muscle tagging: reject for now. Patterns already encode region and the
  library would need 184 debatable entries. Display regions derived from
  patterns if wanted.

## E. Scope
This broadens the reference user from "40, maintenance" to "anyone running
more than one program". Owner should accept that out loud. Before production:
plan identity + migration, plan list/switch/duplicate, authored prescriptions,
working values from history across plans. After: exposure offer, cardio sets
+ history unification, GPS phone, HC run read, progression rule, watch.
New pins: 28-day window, 0.6 load class, deload rule, cardio template vectors.

Confidence: A 75, B 60, C 80, D 65, E 70.
