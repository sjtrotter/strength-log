# Performance profile (user-approved, no GitHub issue)

session_set/workout_session (PLAN.md A1) already holds every completed set a
lifter has ever logged. The day screen's "last time" chip (#14) proved that
history is cheap to query and welcome on the card — this brief extends the
same idea one step further: an all-time-best "profile" per exercise, derived
the same way, shown the same way.

## Derive, never store (SSOT)

There is no `personal_record` table and there will not be one. A record is a
`MAX`-shaped read over `session_set`, recomputed on demand — the same
philosophy as `lastPerformed` (#14) and for the same reasons:

- **One fact, one place.** The set history already IS the record; a stored
  copy is a second place the same number can drift from the first (a deleted
  or edited session would silently orphan a cached PR).
- **Free durability.** Because it's just a query, the profile survives a JSON
  full backup/restore and gets auto-populated by CSV history import (A2) with
  zero additional plumbing — session rows ride along, the profile falls out.
- **Free correctness.** No migration, no invalidation logic, no "recompute on
  write" step to forget.

The cost is a query over the whole session_set table per exercise id instead
of an indexed point lookup. At the data volumes this app deals with (one
lifter, a few thousand sets a year) that trade is free; it stops being free
long before v1's data model would need to change for other reasons.

## Phase 1 — this PR: baseline "Best" display

- `SessionDao.personalRecordRows` / `TrackerRepository.personalRecords`: the
  same batched-per-day query shape as `lastPerformedRows`/`lastPerformed`,
  ordered heaviest weight → more reps → earliest achievement, reduced to one
  `PersonalRecord` per exercise id (first row wins, same pattern as
  `toLastPerformedByExercise`).
- Day card: a "Best: 245×5" line under "Last time: 225×5", same TextFaint/
  bodySmall register — a calm reference, not a celebration. Hidden when there
  is no record, and hidden when it would just repeat the last-time number
  (redundant noise, not signal — the two lines sit right next to each other).
- Nothing here reads or writes GOAL. `GoalCalculator`/`SetSeeder` are
  untouched; the profile is purely an additional read-only history view.

## Phase 2 — PENDING USER DECISION: history-aware re-seeding

Today, swapping an exercise into a slot (or re-adding one that was removed)
always seeds its ACTUAL log from GOAL (D4), even if the lifter has performed
that exact exercise before and GOAL has since drifted from what they can
actually do. Phase 2 would let a **re-encountered** exercise seed from its
own performance history instead of GOAL when one exists.

**Recommendation:** seed from the most recent working weight (the same value
the "Last time" chip already shows) with a GOAL fallback when there's no
history — i.e. reuse `lastPerformed`, not a new profile-driven max/PR seed. A
fresh program's first-ever seeding stays GOAL-based exactly as it is now;
this only changes the swap-in/re-add path. Pinned seeding numbers (spec §11:
squat GOAL 235, ramp 130/165/190/210, etc.) are for a *fresh* program and are
untouched either way.

This needs an explicit user decision before implementation — it changes when
history overrides GOAL in a lifter-facing way, which is a product call, not
an engineering one.

## Phase 3 — PARKED for v2

Progression nudges, estimated 1RM, and a dedicated profile screen/export are
parked, not planned. The philosophy tension: this app's stance is "own your
training, don't grind it" (spec's whole framing rejects gamification and
progress-chasing UI) — a nudge or an e1RM number is one step from turning a
maintenance tool into a numbers-go-up app. If any of this ships, it should be
inert-by-default and hard to weaponize against yourself; that design work
hasn't happened yet.

**Hard guardrail, all phases:** GOAL is calculated from `LifterConfig`
(spec's standards tables) and never from performance data, full stop.
Performance history may only ever *inform* an explicit, never-silent prompt —
the same pattern Health Connect bodyweight sync already uses (a dialog the
lifter accepts or dismisses, never a value that changes itself). A diff that
makes GOAL move because of a logged set is a review-blocking finding at any
phase.
