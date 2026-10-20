**1. Verdict**

Build saved generated plans, a deliberately small authored-routine editor, and editable cardio blocks using the existing workout interaction. Offer a review after absence; never automatically reset loads. This fits the maintenance lifter, but the full expansion should follow Play launch rather than become another launch prerequisite.

**2. Q1 — Saved plans**

Support both generated and authored plans. Have **one default plan driving Today**, with an optional pinned routine alongside it. PT can replace strength temporarily or remain available alongside its rotation; only one workout executes at a time. Every plan retains its own position and working values.

Plan gains a standard list: name, next workout, last recorded session, Make default, Duplicate, Archive. “Create plan” offers the wizard or an empty routine. Re-running the wizard creates a new saved plan by default; today it deletes all program rows and live logs ([TrackerRepository.kt:249](/home/betty/Projects/strength.log/data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:249)).

The essential shape is:

```text
Plan(id, name, origin, progressionPolicy, recipe?, nextDayId, archived)
Day(id, planId, displayLetter, position, items)
Prescription = GeneratedMaintenance | ExplicitTargets
WorkoutRun(id, planId, dayId, prescriptionSnapshot, status)
```

Use stable day identities; “A” becomes a display label. Keep bodyweight, age, and experience global; move emphasis, equipment, cardio preferences, and generator settings into the plan. Explicit profile changes may update generated GOALs, but never existing ACTUALs.

Generated slots retain current math, seeding, and cascade unchanged. Authored slots have explicit sets, reps, duration, optional load, side, resistance description, and notes. Distinguish a continuous hold from “ten repetitions, holding five seconds each.” “Blue band” must not become fictional pounds; left/right must not reuse `perHand`, which describes load convention.

The current tracking modes already cover weighted, repetitions, and timed work, but derive tracking from the exercise’s GOAL source. Authored prescriptions must supply their own targets independently ([ExerciseEntry.kt:21](/home/betty/Projects/strength.log/domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseEntry.kt:21)). Their screen says “Prescribed,” with previous performance under “Last.” Start each authored run from its prescription without overwriting prior ACTUALs; actual deviations never silently rewrite the prescription. No cascade or automatic progression.

Support an ordered rotation or a repeatable routine. Generated rotations remain 2–6 days. Authored routines may contain one exercise, removing the current three-exercise minimum for that mode ([DayEditLogic.kt:38](/home/betty/Projects/strength.log/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditLogic.kt:38)). “Daily” and “twice daily” are authored frequency labels with completion counts, not calendar scheduling. Finish archives performed work; it advances only that plan’s rotation. Repeatable routines remain available.

Twice-daily PT requires run identity: today’s `checkDate` cannot distinguish morning from evening ([ProgramEntities.kt:61](/home/betty/Projects/strength.log/data/src/main/kotlin/cloud/trotter/log/strength/data/db/entity/ProgramEntities.kt:61)). Put run state and rotation advancement in Room transactions; retain `exercise_log` as remembered working values, not session identity.

Migration wraps current live data in one generated plan, preserves exercise-slot IDs and values, remaps live day references, and migrates the cursor and recipe idempotently. **Old sessions remain “Legacy / unassigned.”** Reused day letters cannot prove which historical program produced them. Preserve session IDs and denormalized labels; plan edits and archival must not rewrite history, consistent with [docs/PLAN.md:34](/home/betty/Projects/strength.log/docs/PLAN.md:34).

New strength and cardio sessions capture plan ID and name. Versioned JSON includes every plan, cursor, prescription, and live record; old backups receive the same migration. CSV gains optional plan/run columns while preserving compatible workout names; imports lacking attribution stay unassigned. Health Connect titles include plan/workout names, retaining existing client IDs and truthful activity types.

Watch snapshots and deltas need plan/day/run identity and prescription revision. Late offline work belongs to its original run, never whichever plan is now active. Unsupported prescriptions require a compatibility gate.

Rejected: generated-only saves leave PT unsupported; several competing active rotations make Today ambiguous; a calendar scheduler creates unnecessary product scope. The principal risk is identity leakage across plans, runs, and delayed watch messages.

**Q2 — Cardio as a first-class workout**

Make cardio a typed item inside a workout, alongside lift items—not a pretend exercise set. The phone already has a cardio pager page, so extend it rather than introduce another navigation system ([DayScreen.kt:381](/home/betty/Projects/strength.log/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:381)).

```text
WorkoutItem = Lift | CardioBlock
CardioBlock(mode, warmup?, main, cooldown?)
Main = Timed | Distance | RepeatedWorkRecovery
StepTarget = Seconds | Metres
StepResult(elapsed, distance?, completedOrSkipped)
```

Offer editable warm-up, main work, and cool-down fields, with optional phases. Limit intervals to one repeated work/recovery pair; no arbitrary nesting. Persist the executable definition as truth and derive summary text from it. Existing generated cardio vectors remain unchanged: adding a cooldown is an explicit edit or new template version, not a silent migration ([docs/briefs/cardio-promotion.md:46](/home/betty/Projects/strength.log/docs/briefs/cardio-promotion.md:46)).

One cardio block occupies one pager page, with the current phase in focus: “Warm-up,” “Work 3 of 5,” or “Cool-down.” Show one large countdown, overall elapsed, and the relevant action. “All steps” opens an M3 sheet. The existing watch countdown ring and stop-and-log hold remain; distance phases show target plus elapsed, with manual advancement and no invented progress ring.

Distance needs neither GPS nor networking: enter the target beforehand and the observed distance from a treadmill or known route afterward. Store metres, display preferred units, and distinguish unknown from zero. Advancing a phase must not automatically claim its target was achieved. Defer Health Connect distance reads: they require separate permission and source/time reconciliation ([Android data types](https://developer.android.com/health-and-fitness/health-connect/data-types)).

Snapshot the block when execution starts. Persist phase transitions and results, because manual distance boundaries cannot be reconstructed from elapsed time alone. Extend `cardio_session`; do not squeeze these facts into its current completed-prefix count ([CardioSessionEntity.kt:6](/home/betty/Projects/strength.log/data/src/main/kotlin/cloud/trotter/log/strength/data/db/entity/CardioSessionEntity.kt:6)).

Finishers remain optional and never gate strength completion. A cardio-only workout can finish and receive a receipt without dummy core work; it does not advance the strength rotation. This explicitly amends the shipped lifts-only completion rule ([docs/briefs/cardio-promotion.md:200](/home/betty/Projects/strength.log/docs/briefs/cardio-promotion.md:200)).

Keep GPS, pace coaching, HR zones, sensors, and automatic sports-session matching out. The risk is execution complexity, particularly recovery after interruption; cosmetic card changes alone cannot solve it.

**Q3 — Returning after time away**

Offer, never reset. After **28 days without recorded strength work in that plan**, show: “Last strength session recorded in this plan: eight weeks ago,” with Review loads and Keep saved values. Offer once per absence episode; leave review available afterward. Use one threshold in v1, without graded percentage reductions.

Twenty-eight days is a product trigger for attention, not a physiological boundary. Bosquet’s 103-study meta-analysis found greater losses with longer cessation, moderated by age and training status ([Bosquet et al., 2013](https://pubmed.ncbi.nlm.nih.gov/23347054/)). A newer randomized study of healthy, previously untrained adults averaging 32 years found roughly 3–6% strength loss after ten weeks, followed by rapid regain during retraining ([Halonen et al., 2024](https://onlinelibrary.wiley.com/doi/pdfdirect/10.1111/sms.14739)). These findings do not establish safe restart loads, especially after injury.

Therefore, ship **no default 80% or 90% multiplier**. Review shows last performed loads, current working values, and editable upcoming values. The owner’s eight-week PT return warrants this review; it does not justify a computed clearance to resume. Prescribed rehabilitation targets remain under the user’s and clinician’s control.

Derive absence from performed sets in saved sessions or durable unfinished runs—not opening a plan, editing weights, or `exercise_log.updatedAt`. PT or cardio activity must not erase a strength-plan gap. Store only review acknowledgement keyed to the last relevant activity. Where legacy attribution is unknown, do not invent a plan-specific interval.

Confirmed edits update working values atomically, with a before/after preview and undo. Generated TOP edits use the existing cascade; explicitly show affected ramps and back-off. Preserve history, GOALs, prescription targets, and §11 constants ([STRENGTH_TRACKER_SPEC.md:542](/home/betty/Projects/strength.log/STRENGTH_TRACKER_SPEC.md:542)). This respects “seed once” because the user edits existing values; the app does not delete logs to force reseeding.

Reject original-seed reset: a formula-derived target may exceed the lifter’s last actual load. Reject automatic re-wizarding: it changes program structure without assessing present capacity. Offer a new wizard only when goals, equipment, or routine have changed.

This implements the earlier humane-return direction while respecting the newer requirement that Today state facts rather than issue instructions ([direction-review:85](/home/betty/Projects/strength.log/docs/briefs/direction-review-2026-08-25.md:85), [ui-redesign:261](/home/betty/Projects/strength.log/docs/briefs/ui-redesign-2026-10-12.md:261)).

**3. Decisions for the owner**

1. **Launch timing:** delay for the whole expansion, or release the current coherent product first. **Pick:** launch first; include the small factual return treatment if ready.
2. **Concurrent plans:** strict switching only, or one default plus a pinned routine. **Pick:** default plus routine, covering PT alongside strength.
3. **Authoring scope:** saved generated plans only, or explicit prescriptions too. **Pick:** both, with simple routines and no medical recommendations.
4. **Distance timing:** include immediately, or follow editable timed cardio. **Pick:** follow timed cardio; manual completion changes persistence and watch behavior.

**4. Build order**

Each numbered slice is a separate PR; expose features only after their dependencies land.

1. **Before launch:** factual absence line and a route to existing load editing.
2. **After launch:** plan/day identities and non-destructive Room migration behind the current single-plan interface.
3. Versioned multi-plan backup and legacy restore migration.
4. Plan attribution in history, CSV, and Health Connect titles.
5. Durable run identity and atomic completion, including same-day repeats.
6. Watch identity/capability changes; verify delayed messages cannot affect another run.
7. Plan list, create/save, switch, duplicate, archive, and independent cursors.
8. Explicit prescription persistence and seeding policy.
9. Authored editor and phone execution for reps, holds, sides, bands, and notes.
10. Watch rendering for authored prescriptions and the pinned-routine entry.
11. Plan-specific return review with preview, confirmation, and undo.
12. Editable timed cardio definitions and exact legacy adapters.
13. Phone/watch phase execution, results, exports, and standalone receipts.
14. Manual distance targets and results.

Require migration/backup round trips, two plans both displaying “A,” twice-daily sessions, interrupted execution, late watch replay, and unchanged pinned vectors before releasing the relevant slices.

**5. Confidence**

- **Q1: 90%.** Real prescriptions requiring complex circuits or scheduling would change the authoring boundary.
- **Q2: 90%.** Evidence that users need live pace or sensor feedback would require reconsidering the product boundary.
- **Q3: 90% against automatic resets; 60% on 28 days.** Relevant return-to-training trials or usability evidence would change the trigger and assistance offered.