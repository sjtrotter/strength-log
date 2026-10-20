# Saved plans, cardio phases, returning after time away (2026-10-19)

Round 2 (`plans-review-2026-10-19-r2.md`) replaces this report's decisions
and its return offer.

Owner's three asks: store and switch between workout plans (the PT case),
make cardio a full workout with warm-up, main piece and cool-down, and keep
a lifter from resuming old loads after a long absence.

Three independent readers, same brief (`plans-review-2026-10-19-brief.md`):
Codex gpt-5.6-sol and gpt-6-astra at xhigh, read-only
(`plans-review-2026-10-19-sol-raw.md`, `-astra-raw.md`), and Claude
Opus 5.5, whose verdict was written before reading the other two. Fable was
not available this session.

## Verdict

Build saved plans first, and before production if launch can wait for one
schema change. Re-running the wizard today deletes every live working weight
(`data/.../TrackerRepository.kt:249-253`), so the owner cannot set up a PT
routine without wiping the strength plan. Saved plans fix that trap and make
the PT case possible with a small, constrained routine editor. The return
offer is a small layer on top and is never automatic. Cardio phases are
worth doing and are the largest design job; they go last.

## Settled: all three agree

Nothing here needs a decision unless the owner disagrees.

- A plan is a named container: its days, exercises, live working weights,
  rotation position, cardio prefs and goal emphasis. Bodyweight, age, level
  and units stay global.
- Two origins share one execution and history path. Generated plans keep
  today's GOAL math, seeding and cascade unchanged; §11 does not move.
  Authored plans have a prescription per exercise (sets × reps, hold
  seconds, optional load, each-side, a band described in text, a note), no
  GOAL, no cascade, no automatic progression.
- The wizard creates a new plan. It never replaces one.
- Switching keeps every plan's position, working weights and last-session
  date intact.
- "Daily" and "twice a day" are a repeatable day that DONE leaves in place,
  not a calendar. No reminders.
- Authored days may hold one exercise; the three-exercise minimum
  (`app/.../ui/day/DayEditLogic.kt:38`) applies to generated plans only.
- Sessions (strength and cardio) gain `planId` plus a copied plan name, the
  same survival rule exercise names already follow. Backup, CSV and Health
  Connect titles carry the plan; the watch only ever sees the selected plan,
  and its deltas carry plan identity so a late tick cannot land in another
  plan's Day A.
- Migration wraps today's program into one generated plan. Day letters
  become display labels on stable ids, because `dayId` is a global primary
  key today (`data/.../db/entity/ProgramEntities.kt:31-32`), and the
  suggested day moves from DataStore onto the plan row.
- Cardio becomes a typed block beside lifts, never a pretend exercise:
  optional warm-up, a main piece (time, distance, or one work/recover pair ×
  rounds), optional cool-down. One phase in focus on the pager page, phases as
  segments, an "All steps" sheet. The watch keeps its one cardio dial state;
  ring segments become phases.
- Distance needs no GPS: an optional target, and the observed distance typed
  in at stop. Unknown stays unknown, never zero.
- The shipped cardio vectors (`cardio-promotion.md` §2) stay pinned for
  existing cards. Warm-up and cool-down arrive through new templates with
  their own pinned vectors, never by silently rewriting old ones.
- Out of scope: GPS, routes, live pace, HR zones, calories, sensors,
  background workout services, clinician workflows, diagnosis or pain
  tracking, nested interval languages, any medical claim in copy.
- Returning loads are offered, never reset automatically, computed per plan
  from that plan's own sessions, and confirmed with a before/after preview.
  Authored (PT) plans get no load offer: the clinician owns the dose.

## Where they split, and my call

### Return after absence (the biggest split)

| | Trigger | Offer |
|---|---|---|
| Sol | 28 days | 90% of last working weight, 80% at 56 days, 70% at 112 |
| Astra | 28 days | Review screen only: last, current, editable next; no multiplier |
| Claude (before) | 21 days | Reseed from GOAL ("original lift levels") |

Sol and Astra both rejected a reset to GOAL, and they are right: GOAL is a
formula, and a lifter who had settled below it would come back to heavier
loads after a layoff. I withdraw that version.

My call now: one threshold at 28 days, and one offer, **back to starting
loads, never above what you last lifted**. For each row the new value is the
lower of the saved working weight and the seeded GOAL; on a main lift that
is applied to the TOP row and the existing cascade derives the ramp and
back-off. Preview, confirm, undo. This is what the owner asked for in words
the owner would recognise, it uses no invented percentage, and it can only
lower a load. Sol's ladder is defensible but its fractions are product
guesses, as Sol says itself. Astra's review-only screen is safe and leaves
the arithmetic to the person least placed to do it on a return day.

Evidence, verified: Bosquet et al. 2013 (103 studies) finds strength loss
grows with the length of the break and is larger past 65, but reports no
week-by-week course, so no paper gives 28 days. Halonen et al. 2024 found a
10-week break in healthy 32-year-olds cost strength that came back within
about five weeks of retraining. Neither says anything about returning after
injury, which is the owner's case; that is a reason to keep copy factual
and say nothing about safety.

Today already says "Last trained 4 Aug, 10 weeks ago" once a gap passes 14
days (`app/.../ui/today/TodayScreenBuilder.kt:65-80`). That line becomes
per plan; the offer appears beside it at 28.

### Running PT alongside strength

Claude: one selected plan, switch in Plan. Sol: any plan stays runnable,
one is selected on Today and the watch. Astra: one default plan on Today plus
one pinned routine reachable from it.

Claude's and Sol's are the same data model; Sol just names it better.
Astra's adds a second entry point on Today. The question is whether the owner
lifts during PT. If PT replaces lifting for now, switching is enough. If both
run in the same week, Astra's pinned routine saves a trip through Plan every
day.

### History from before plans

Astra leaves old sessions unassigned, because day letters cannot prove which
program produced them. Sol attributes native sessions to the migrated plan.
I side with Sol: unassigned history means the return offer cannot fire for
the owner's own strength plan on the day they switch back from PT. That is
exactly the case this work is for. CSV imports stay unassigned.

### Twice-daily runs

Astra says a second same-day PT session needs a new run identity because
check dates are per day. Partly rejected: DONE already clears the completed
day's checks (`data/.../TrackerRepository.kt:711`), so a repeat works on the
phone. The real gap is a late watch tick arriving after the first run was
archived; plan and run identity on the delta covers it.

### Cardio-only days finishing

Astra: a cardio-only workout finishes and gets a receipt without dummy core
work; this amends the shipped lifts-only completion rule. Sol is close. I
agree; it is most of what "first-class" means. It still never advances the
strength rotation.

## Decisions for the owner

1. **Launch timing.** (a) Ship as is, add plans after launch (Astra).
   (b) Land the plan schema and authored routine during internal testing,
   cardio and return after (Sol). *My pick: (b).* A schema and backup
   format migrated with no public users is the cheapest it will ever be,
   and the owner's PT need is now.
2. **PT beside strength.** (a) Switch plans in Plan. (b) A pinned routine
   reachable from Today. *My pick: (a), unless you lift during PT weeks.*
3. **Return offer.** (a) Back to starting loads, never above last lifted
   (Claude). (b) 90/80/70% ladder on last working weight (Sol). (c) Review
   and edit yourself (Astra). *My pick: (a), threshold 28 days.*
4. **Default warm-up and cool-down for new generated cardio.** Proposed
   5:00 each around the existing main pieces. These become new pinned
   numbers, so they need your sign-off.
5. **Distance.** (a) Manual distance in the first cardio slice. (b) Timed
   phases first, distance after (Astra). *My pick: (b); manual stage
   advance changes persistence and the watch more than it looks.*
6. **Health Connect distance.** Writing a distance record is a new
   permission and a Data safety listing change. (a) Write it when known
   (Sol). (b) Keep the current permission set. *My pick: (b) for now.*

## Build order

Each line is one PR through the usual loop.

1. Plan identity: plan table, stable day ids, per-plan cursor, session
   attribution, Room migration and backup version with round-trip tests. No
   visible change.
2. Plan list in the Plan tab: create from wizard, switch, duplicate,
   archive; plan-aware watch snapshot and deltas.
3. Authored routine: prescription fields, the editor, phone execution,
   one-exercise days, repeatable days.
4. Watch rendering for authored prescriptions.
5. Per-plan last-trained line and the return offer with preview and undo.
6. Cardio blocks: typed definitions, legacy adapter that keeps today's
   vectors, phone phase execution, cardio-only receipt.
7. Watch cardio phases.
8. Manual distance targets and results.

Tests that must exist before the matching slice ships: two plans both
showing "A", migration of a real-shaped single-program database, a late
watch tick after a plan switch, a twice-daily repeat, and every §11 and
cardio vector unchanged.

## Confidence

| | Claude | Sol | Astra |
|---|---|---|---|
| Saved plans | 85% | 91% | 90% |
| Cardio phases | 60% | 86% | 90% |
| Return offer | 75% | 67% | 60% on the 28-day trigger, 90% against auto-reset |

My cardio number is lower than theirs because the pager redesign is half
built (phase 2 still open) and cardio phases will be designed against a
moving screen.

Sources: [Bosquet et al. 2013](https://ekoizpen-zientifikoa.ehu.eus/documentos/5f1cda5129995265e44d37a4?lang=en),
[Halonen et al. 2024](https://jyx.jyu.fi/handle/123456789/97584),
[Stronger by Science on the Bosquet time course](https://www.strongerbyscience.com/detraining/).
