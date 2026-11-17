# Sprint week redesign: high/low layouts for Sprint and Both + sprint

Date 2026-11-16; revisions 2-4 on 2026-11-17 (final after council round 3). Status: design for a spec, before PR 7 exposes sprint setup. Base: origin/main 47ba54e4 (#601).

## Revision 4 (council round 3, final: Opus 8.5/10, Sol 9/10; lead adjudication of 2026-11-17)

| Finding | Change |
|---|---|
| Opus S1 | **The replay runs on the real Finish path** (§4.2 "Where the replay runs"). <ul><li>Site: `CardioJudge.judge`, called by `TrackerRepository.cardioJudgment` after the session and its set results are inserted, and again when effort or distance is amended.</li><li>Anchor: the local date of `session.startedAt` (`clock.zone`), not `floorDiv(completedAt, 86_400_000)`, with local-date ledger keys at both ends.</li><li>Ledger: judge's existing `prior` order (completedAt, id).</li><li>Rules: the stored rules at judgment time; on a re-judgment, the day's pre-write rule from the session's `progressionUndoJson`.</li><li>On sprint days the `opening != cardioToRun` guard and the `held` override give way to the decision, so trial, restart and shortened-restart writes are kept.</li><li>Storage: no new Room column. `CardioUndoDto` gains an optional `rule` before/after pair, a JSON field. `CardioRun` (Parcelable) is unchanged.</li></ul> |
| Sol S1 | **G-b is stated with its conditions and two named exceptions**, and every claim that weekly exposure is always capped is qualified (§1, §4.2, §4.3). <ul><li>Exception 1: a restart lowers the planned rotation while post-break work is in the window. Fixture: 130 m against 110 m at B's day-24 decision.</li><li>Exception 2: a manual override, a block the user edits above its decision. Fixture: 410 m against 380 m.</li><li>In both, the next decisions shorten or substitute until the window clears, and G-a still holds. Edits are recorded at what was run.</li><li>Both are simulator fixtures.</li></ul> |
| Sol S2 | `sim_guard.py`: <ul><li>B's trial-tempo decision computes its cap and is checked.</li><li>G-b violations are labeled (restart or override in the window). An unlabeled one fails the run.</li><li>Result: 76,750 grid decisions, 0 unlabeled and 0 labeled. The two fixtures produce exactly one labeled exception each.</li></ul> |
| Opus S2 | `trialEpochDays` is derived from history by shape: a Sprint A session with a WORK rep longer than A's range allows is a trial. Rotation indexes are not stored per session. A test is added. |
| Opus S3 (nits) | <ul><li>§2.4 gains a ONE_DAY row; its RDL is skipped when the day's main is a hinge.</li><li>The ledger counts the prescribed distance of each finished WORK set, not GPS meters.</li><li>The structural rule compares the WORK run against the decision's own block, so an edited-down restart is not intro session one.</li><li>The cap reads every maximal day's stored rule.</li><li>`replay_mismatch` is labeled a determinism self-check.</li></ul> |

## Revision 3 (council round 2: Opus 8/10, Sol 8/10; lead adjudication of 2026-11-17)

| Finding | Change |
|---|---|
| Opus S1 = Sol S2 | **Finish replay anchor.** Finish rebuilds the allowance decision from three fixed inputs: the session's Start date as `todayEpochDay`; a ledger that excludes this session's own rows (an exclusive cutoff by session ID); and the day's rule snapshot captured at Start. Normal credit is also denied structurally: a non-trial, non-restart session earns credit only when the WORK it ran equals `sprintBlock(session, rule.steps)`'s WORK. Shortened and substitute sessions never earn credit. Midnight-rollover and edited-block tests are specified (§4.2, §8.3). Simulated: a naive anchor (the Finish date, own rows included) would have credited 1,205 no-credit decisions. |
| Sol S1 | **Rolling-cap guarantee.** The trial allowance stays in the cap while the trial's work is inside the 7-day window. The guarantee is stated two ways and both are asserted on every decision, substitutes included: each admitted dose fits its own decision's cap; and the window before every decision is within that decision's cap. 76,750 decisions, 0 violations (§4.2, §4.3). *Qualified in revision 4: G-b has two named exceptions.* |
| Sol S3 | **Declined trials.** A trial is offered only at A's first maximal session of the rotation. A declined trial that runs a normal or shortened block ends the trial for that rotation. A decline that runs the tempo substitute leaves the trial available at A's next session in the same rotation, still subject to the cap. A decline never sets `sprintTrialCycle`, so it never triggers B's tempo substitute. The simulator matches and asserts this (0 cases of tempo without a completed trial). |
| Opus S2 | **Fallback.** For the Sprint branch and the careful group, the squat day never takes the hinge main; it keeps its single-leg slot. The careful group on Both + sprint needs a resolved squat main. Non-careful Both + sprint users keep the squat-to-hinge fallback, and the "one heavy hinge per rotation" claim is qualified for them (§2.3, §2.4, §5). |
| Opus S3 | Spacing tier (c) applies only while the plan holds a Nordic slot. The seated-curl fallback does not count [P] (§3.2). |
| Opus S4-S6, Sol S4 (nits) | Several smaller fixes: <ul><li>One-day Mon/Thu doubles the step rate. This is now listed under "What it does not do".</li><li>The shortened copy names the cause.</li><li>The trip wording here now matches §4.3.</li><li>The drift tests are scoped to two days and up; one-day plans get a Mon/Thu scenario.</li><li>A shortened layoff restart has Finish semantics.</li><li>Allowance copy takes precedence over the Start note.</li><li>The Both gate reads resolved mains.</li><li>`fullDoseMinutes` gets a grid property test, since no current fixture trims at 90.</li><li>`research.md`'s tempo-at-3-4-days lines are marked not adopted.</li><li>One-day reference assertions at 90 minutes are added.</li></ul> |

## Revision 2 (council round 1: Opus 7.5/10, Sol 7/10; lead adjudication and owner answers of 2026-11-17)

| Finding | Change |
|---|---|
| Sol S1 (blocker), Opus S2 | The rolling 7-day **weekly sprint allowance** is now part of this design (§4.2) and a hard prerequisite of PR 7. It counts completed maximal WORK at Finish and decides the dose at Start: normal, trial, shortened, or a tempo substitute. Its cap is the planned rotation plus the largest single dose. In round 2 it tripped twice on 480 ordinary schedules, both one-day plans around a trial week; with revision 3's trial retention it trips 0 times on 400 two-to-six-day ordinary runs and 0 times on the one-day merged scenarios. The per-rotation bound reused as a weekly cap trips 3,400 times on the same 400 runs. Under dense calendars it caps weekly exposure; it does not only freeze steps. Simulated in `sim_guard.py`. "Closes" is withdrawn; see §4.3 for what it does and does not do. |
| Sol S2 (blocker) | Both + sprint needs a lower-body main. If neither the squat nor the hinge row resolves, the planner rejects the answers and setup says why (§2.4). An Upper entry with no upper mains becomes a Rest entry. Floor tests are added (§8). |
| Opus S1 | The one-day rotation gains a trailing rest. G1 and G2 now apply to every day count; the "days ≥ 2" exemption is gone. |
| Opus S3 | The 72 h tier also covers a user's first 3 Nordic exposures and a New lifter's first 3 exposures of each high-day lower main [P] (§3.2). |
| Opus S4, Sol S3 | The Start note is **required** in PR 7, on every maximal day including one-day plans. Its copy comes from the actual gap and the current tier. All spacing advice is calendar-day advice; the app cannot measure elapsed hours (§3.2). |
| Sol S4 | `budget.py`: the 65+ Farmer's Carry is a 20 s hold (65+ two-day short-event intro: 2559 / 2958 s). Ladder step numbers are fixed: step 7 always drops plyometrics, step 8 always drops the Nordic, and a step whose flag is off does nothing. |
| Sol S5 | "Longer sessions" is offered whenever a plan was shortened, not only when it is over budget, with the minutes the full dose needs (`Planner.fullDoseMinutes`). Over-budget reporting is unchanged (§3.4). |
| Sol S6 | The dense-calendar table lists every day count. The false "4-6 days match 3 days" line is gone (§4.1). |
| Sol S7 | Tests are split into full-prescription assertions (reference plans) and fitted-plan invariants (grids). A ownership list for implementation and tests is added (§7.3). |
| Sol S8 | Francis's same-session practice, Johnston's same-day 2 h pairing and Haugen's separate-day example are now kept apart. "One heavy hinge a week" now reads "one per rotation" (§2.3; `research.md`). |
| Opus S5, S6, S8; S7 | Nits for PR 7 copy are recorded in §3.4 and §7.3. `sprintIntro`, careful-group and spacing tests are added. G2 now defines lower-body slots by pattern and role, so the 65+ balance block cannot trip it (§3.1, §8). |
| Owner Q1 | **Tempo stays at revision 31's policy.** `research.md`'s support for tempo or aerobic work on low days is practice only (Haugen Table 2/3, Francis). There is no graded trial in sprinters, and Ferraro-Farro is sprint-interval conditioning, not tempo. That does not outweigh revision 31's choice, so low days at three and four days are Upper, and one tempo day appears at five and six days, at F as today (§2.2). |
| Owner Q2 | A sprint-only "40 or older" checkbox. Together with 65+ and a hamstring strain it forms the **careful group** [P; the 40 cutoff is practice]. The careful group gets a longer intro (six counted sessions per sprint day), 90-95% effort copy during the intro, and the 72 h spacing advice for the whole plan (§5). |
| Lead calls | Q3 yes (hamstring question in PR 7; intro for 65+). Q4 yes, required. Q6 accepted, with the Longer-sessions fix. Q7: 50 contacts on B. Q9 accepted. Q10 kept, plus the trailing R. Q8 (volume balancing for Muscle users) is deferred to a follow-up by owner decision. |

Inputs:
- `research.md` in this folder (revised for Sol S8).
- The merged planner: `SprintLayout.kt`, `DoseLadder.kt`, `SessionBudget.kt`, `SprintRules.kt` (with `SprintContext` and `CardioRule`).
- Design doc §2.1, §3.1.3, §3.1.4, revisions 30-32 and 43.
- Audit decisions rows 5-9 and 14.

Proof files in this folder, all re-run for revision 4:
- All three simulators were re-run for revision 4. `budget_results.txt` and `sim_week_results.txt` are unchanged since revision 2, because the layouts did not change.
- `budget.py` / `budget_results.txt`: a SessionBudget + fitDay port. It reproduces every current PlannerSprintTest seconds and reason pin before it evaluates the proposal.
- `sim_week.py` / `sim_week_results.txt`: the merged simulator (`sim_s10_lib.py`, byte-identical to `fix/runaudit/sim_s10_lib.py`) on the proposed rotations, plus the calendar tables.
- `sim_guard.py` / `sim_guard_results.txt`: the weekly allowance.

**Labels.** [P] = practice. [weak] and [moderate] carry `research.md`'s grades. No rule here is claimed to prevent injury.

---

## 1. Summary

From one day up, every sprint plan has high days and low days. A trailing rest marks the wrap back to A.
- **A high day** is: the sprint session, then plyometrics, then one lower-body main, with the Nordic last on one of them.
- **The max-velocity day is the "hamstring day"**: it carries the hinge main and the Nordic. Short events (40 yd, 60 m, 100 m): A = acceleration, B = max velocity. 200/400 m: A = max velocity, B = sprint endurance. The event mapping is unchanged.
- **Low days** hold upper-body mains, and tempo at five and six days. They never hold leg lifts or the Nordic.
- **Both + sprint** uses the same rotations with the lift sub-goal's rules. It needs a lower main, and gets two or more lifting days from two days up.
- **Fitting** keeps plyometrics and the Nordic on sprint days until last.
- **The progression rules and the per-rotation bound are unchanged.** The new **weekly sprint allowance** admits each sprint session only if the 7-day total, with that session included, stays within the planned rotation plus its largest single dose. Two named exceptions are in §4.2: a restart lowering the cap while recent work is in the window, and a user's manual edit above the prescription.

---

## 2. Layouts (question 1)

### 2.1 Day types

The cardio block comes first. The lifts then follow in this order:
1. The 65+ balance block (warm-up), if any.
2. Plyometrics.
3. The lower main.
4. The lower accessory.
5. Upper mains.
6. The Nordic, last.

| Type | Cardio block | Lifts, in order | Kind / title |
|---|---|---|---|
| **HA** | Sprint A | 65-contact plyos; lower main; single-leg if it is the squat day; row 2 at two days; Nordic if it is the hinge day | CARDIO / session title |
| **HB** | Sprint B | 50-contact plyos; lower main; single-leg if it is the squat day; rows 3-4 at two days; Nordic if it is the hinge day | CARDIO / session title |
| **U** | none | upper mains, rows 2-4 | STRENGTH / "Upper" |
| **T** | Sprint tempo | none | CARDIO / "Tempo" |
| **A1** (one day, contents unchanged) | Sprint A | 65-contact plyos; squat main; RDL; single-leg | CARDIO / session title |
| **R** | none | none | REST |

- **Hinge day:** the high day whose session is max velocity. It hosts the hinge main (main row 1, fallback row 0) and the Nordic (or `nordicPick`'s knee-flexion fallback, 2 sets).
- **Squat day:** the other high day. It hosts the squat main (row 0, fallback row 1) and one single-leg accessory.
- **Why [P]:** hamstring-heavy eccentric work sits on one day, after its sprints. The next max-velocity session is then a rotation away.

### 2.2 Rotations

The same shape serves both event groups; only the sessions and the hinge-day side differ. The maximal days stay at A and C (two days) and at A and D (three to six days). Tempo stays at F at five and six days, and the six-day Upper at G, as in the merged layout.

| Days | Rotation | 40 yd–100 m contents | 200/400 m contents |
|---|---|---|---|
| 1 | A1, R | acc + plyos, squat, RDL, BSS | MV + plyos, squat, RDL, BSS |
| 2 | HA, R, HB, R | A: acc, plyos 65, squat, BSS, bench. C: MV, plyos 50, trap-bar DL, OHP, row, Nordic | A: MV, plyos 65, trap-bar DL, bench, Nordic. C: SE, plyos 50, squat, BSS, OHP, row |
| 3 | HA, U, R, HB, R | A: acc, plyos, squat, BSS. B: bench, OHP, row. D: MV, plyos, trap DL, Nordic | A: MV, plyos, trap DL, Nordic. B: Upper. D: SE, plyos, squat, BSS |
| 4 | HA, U, R, HB, U, R | as 3, E Upper | as 3, E Upper |
| 5 | HA, U, R, HB, U, T | as 4, F Tempo in place of the trailing rest | same |
| 6 | HA, U, R, HB, U, T, U | as 5, G Upper | same |

**Two days.** With no low day, the upper mains ride on the high days after the lower lifts and before the Nordic: row 2 on A, rows 3-4 on C. That is the split that fits a default 100 m intro plan in 60 minutes without trimming (`budget_results.txt`).

**Tempo (owner Q1).** Revision 31's policy stands: one tempo day at five and six days, for every event, at F. `research.md` grades tempo on low days as practice only:
- Haugen 2019 Table 2/3: tempo days with a 24 h recovery.
- Francis: low days.
- No graded trial shows that low-day tempo or aerobic work improves 200/400 m or short-sprint results.

By the owner's rule ("science for the event must outweigh the original choice"), the earlier tempo proposal is withdrawn.

**Both + sprint.**
- Same rotations; the lift sub-goal's rules and mains (revision 31); the lift-primary fitting variant (§3.4).
- Lifting days, counted as days with a MAIN slot, with both lower rows and the upper mains present:

| Days | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| Lifting days | 1 | 2 | 3 | 4 | 4 | 5 |

- Row 14's floor of two holds from two days. One day cannot hold two (Q9, accepted).
- "Lift first" means lift-primary, not lift first within a visit: sprints always lead the day. PR 7 copy must not promise lift priority within a visit (Opus S8).

### 2.3 Prescriptions (question 4)

All from `liftSlot`, so there are no new numbers.

| Slot | Sprint (Strength rules) | Both: Muscle / General | 65+ |
|---|---|---|---|
| Lower main | 3 × 5 at 2 RIR, 180 s. New: 3 RIR. A year or more: 60% × 5, 80% × 3, TOP × 5, 2 back-offs at 90% × 5 | Muscle 3 × 8-12 (experienced 6-10), 120 s. General 2 × 8-15 at 3 RIR, 90 s | 2 × 6-10 at 3 RIR, 120 s |
| Single-leg (squat day) | 3 × 8-12, 90 s | Muscle 3 × 10-15. General 2 × 10-15 | 2 × 8-12 |
| Nordic (hinge day, last) | 2 × 4-6, 60 s (fallback knee flexion, 2 sets, its own target) | same | same, at 3 RIR or more |
| Upper mains | as the lower main | lift sub-goal | 2 × 6-10 |
| Plyos | A: pogo 3 × 15 + A-skip 1 × 20. B: pogo 2 × 15 + A-skip 1 × 20. Easy, 60 s | same | same |

- **Heavy vs light.** Both high days use main-lift loading, because the second exposure's value is the strength stimulus [weak + P]. Each high day's lower dose stays low: 3 main sets plus 3 single-leg or 2 Nordic sets. That is a fraction of the 50 heavy reps behind Thomas 2018's 72 h. Light is carried by structure: low days carry no lower-body loading.
- **Hinge vs squat.** One squat-pattern day and one hinge day per rotation. The RDL leaves the two-day-plus layouts, so a rotation has one heavy hinge.
  - It is one a week only when the user runs one rotation a week. The allowance in §4.2 limits sprinting in 7 days, not lifting [P].
  - The Sprint branch and the careful group always keep it to one, because their squat day never falls back to the hinge row (§2.4).
  - A non-careful Both + sprint user who has no squat main runs the hinge main on both high days: two heavy hinges per rotation, the only exception.
- **Precedents (Sol S8).** A one-visit high day follows Francis's same-session practice [P]. The closest tested pairing is Johnston 2016/2017: the same day, 2 h apart [weak]. Haugen's example weeks separate sprint and strength days [P]. Copy offers a split day with 6 h or more between sessions as the preferred variant (Robineau 2016, weak-moderate).

### 2.4 Missing mains and the Both floor (Sol S2)

- **Row fallback, by plan:**

| Plan | Squat day, squat row empty | Hinge day, hinge row empty |
|---|---|---|
| Sprint branch (any user) | no fallback: keeps its single-leg slot, no lower MAIN | takes the squat main |
| Careful group, Sprint or Both | no fallback: keeps its single-leg slot | takes the squat main |
| Both + sprint, not careful | takes the hinge main (two heavy hinges that rotation; claim qualified in §2.3) | takes the squat main |
| ONE_DAY entry (one day), squat row empty | Sprint branch and careful group: no lower MAIN, keeps RDL + single-leg. Non-careful Both: takes the hinge main and **skips the RDL accessory**, so the day holds one hinge, not today's hinge main + RDL | — |

- **The Both gate reads resolved mains** (`resolveMains`' output after equipment, exclusions and the TIMED swap), not the user's picks.
  - Non-careful: the gate needs a squat or a hinge main.
  - Careful: the gate needs a squat main, so the squat day always holds a MAIN and the floor holds without the single-leg slot.
- **Both + sprint failing its gate.** `resolveMains` allows this when the user omits rows or excludes every candidate. The planner throws `IllegalArgumentException`:
  - non-careful: "Both with a sprint goal needs a squat or hinge main";
  - careful: "... needs a squat main".
- **PR 7's Your lifts screen** for Both + sprint keeps Start disabled until the gate passes. Its copy:
  - non-careful: "A sprint plan needs one leg lift. Choose a squat or a hinge."
  - careful: "Choose a squat lift for your sprint days."
- Every equipment preset resolves both lower rows (bodyweight: `sit_to_stand`, `back_ext`), so only explicit omission or exclusion reaches this.
- **Sprint branch:** no floor (the plan is sprint-primary). A high day without a lower main keeps its plyos, single-leg and Nordic slots.
- **An Upper or upper-carrying low day with no upper mains** becomes a Rest entry and keeps its ID. The lifting-day floor then rests on the two high days, which always hold a lower MAIN for Both.

---

## 3. Calendar semantics, spacing and trimming (questions 2 and 3)

### 3.1 Entries are not days

`Program.dayToShow` resolves a Rest suggestion to the next training day. `Rotation.upNextAfter` advances only when a day is finished. A rest entry never consumes time.

**The layout guarantees order. Invariants, read cyclically, for every day count:**
- **G1.** No maximal entry is adjacent to another maximal entry, or to itself. The one-day plan's trailing R makes A1's predecessor a rest.
- **G2.** The entry before each maximal entry holds no lower-body lift and no maximal block.
  - A lower-body lift is a slot whose pattern is SQUAT_BILATERAL, HINGE, SINGLE_LEG, KNEE_FLEXION, KNEE_EXTENSION or a calf pattern, and whose role is MAIN, SECONDARY, ACCESSORY or ISOLATION.
  - BALANCE and CORE roles never count, so the 65+ balance block on U is allowed (Opus S7).
- **G3.** Within a day: sprint block, then plyos, then lower, then upper, then Nordic last.
- **G4.** The hinge main and the Nordic sit on the max-velocity day.

**Time is advised (§3.2) and capped for sprinting (§4.2), never guaranteed.** The plan cannot guarantee hours between high days, that sessions run in order, or a correctly split day. It does not see matches outside the app; copy asks the user to count one as a sprint day.

Rotation spacing, one entry per calendar day:

| Days | A → B | B → A |
|---|---|---|
| 1 | — | 48 h |
| 2 | 48 h | 48 h |
| 3 | 72 h | 48 h |
| 4 | 72 h | 72 h |
| 5 | 72 h | 72 h |
| 6 | 72 h | 96 h |

The suggested weeks are three days apart both ways:
- 2 days: Mon and Thu.
- 3 days: Mon A, Tue Upper, Thu B.
- 4 days: plus Fri Upper.
- 5 days: plus Sat Tempo.
- 6 days: plus Sun Upper (Wed off).

### 3.2 Spacing tier and the required Start note

**Tier [P; `research.md` Q2].** The plan advises **three days apart** while any of these holds, and **two days apart** otherwise:
- (a) any maximal day's `intro` is above 0;
- (b) the user is in the careful group (§5);
- (c) the plan holds a Nordic slot (`nordic`, not the seated-curl or other knee-flexion fallback) and it has fewer than 3 exposures (`SlotState.exposures`). This covers everyone's first Nordic weeks. With no Nordic slot (excluded, or trimmed by step 8), (c) does not apply. The machine fallback is excluded because the recovery studies behind the 72 h advice tested the Nordic's eccentric overload [P];
- (d) the lifter is New and a high-day lower MAIN has fewer than 3 exposures (Opus S3).

The tier is a calendar-date rule. "Three days apart" is Mon → Thu; "two days apart" is Mon → Wed. Copy never says "72 hours", because epoch days cannot tell 48 h from 60 h.

**Functions** (`progression/SprintRules.kt`):

```kotlin
/** Practice: calendar days wanted between maximal sessions (three in the first weeks or the careful group, else two). */
fun spacingDays(firstWeeks: Boolean, careful: Boolean): Int = if (firstWeeks || careful) 3 else 2
/** Calendar days since the last maximal date, or null with no earlier maximal date. */
fun daysSince(lastMaximalEpochDay: Long?, todayEpochDay: Long): Long? = lastMaximalEpochDay?.let { todayEpochDay - it }
```

The caller derives `firstWeeks` as (a), (c) or (d) from the program, and `careful` from the recipe answers. The last maximal date is the latest maximal date the caller already gathers for `SprintContext.of`.

**Start note, required in PR 7.**
- Shown on every maximal day, including one-day plans, when `daysSince < spacingDays`. It is advisory and blocks nothing.
- Built from the gap and the tier. The `{state}` text is chosen from the state; when several apply, the careful reason wins.

| Gap | First line |
|---|---|
| 0 | "You already sprinted today." |
| 1 | "Your last sprint session was yesterday." |
| n | "Your last sprint session was n days ago." |

Then: "{state}, sprint days work best {three / two} days apart. You can train a different day from your plan instead."

| `{state}` | When |
|---|---|
| "While you're starting out" | (a), (c) or (d) |
| "Since you're 40 or older" | the 40+ or 65+ answer |
| "After a hamstring strain" | the strain answer |
| "For now" | none of the above (two-day tier) |

**Your plan copy, derived from the same tier.**
- Three-day tier: "Keep sprint days three days apart, like Monday and Thursday."
- Two-day tier: "Keep at least one rest day between sprint days."
- The careful group always sees the first line.
- This replaces round 1's "after your first three weeks", which contradicted the per-day intro count (Sol S3).

**Other copy for PR 7** (run through prose-whip):

| Where | Copy |
|---|---|
| Suggested week, derived from the rotation | "Suggested week: Mon sprint, Tue upper, Thu sprint." |
| Rest entry before a sprint day, and the trailing rest | "Rest before your next sprint day." |
| High-day header | "Sprints first, then jumps, then lifts. Nordic curls last." |
| High day, shown once | "Splitting the day? Sprint first and lift at least six hours later. If you must lift first, leave two hours before sprinting." |
| Your event, team sports | "A match or hard practice counts as a sprint day." |

### 3.3 Volume

- **Maximal days** stay at one (one day) or two (two to six days). The planned maximal meters and the per-rotation bound are unchanged.
- **Plyometric contacts per week:** 65 at one day (80 at the pogo cap); 115 opening at two to six days (65 + 50), and 140 at the cap. Per session they stay within 40-80 [P, S14]. No weekly cap has evidence behind it [P].
- **Tempo:** one tempo day at five and six days (6 × 100 m to 6 × 300 m), not maximal. It is outside the bound and outside the allowance.
- **Lower-body hard sets per rotation** (Strength, New): 9 at one day; 11 at two to six days.

### 3.4 Trimming high days

**Ladder** (`DoseLadder.kt`): `fitDay(day, answers, budgetSeconds, keepPlyos = false, keepNordic = false)`.
- Step 1 also keeps PLYO slots under `keepPlyos`, and knee-flexion slots under `keepNordic`.
- Steps 2-6 are unchanged.
- **Step 7 drops plyos, step 8 drops the knee-flexion slot.** Both numbers are fixed: when its flag is off, a step makes no change but keeps its number. With both flags off, the ladder still ends at step 6, so lift and run plans are untouched (Sol S4).

| Plan | Flags | Effective order after steps 2-6 |
|---|---|---|
| Sprint | keepPlyos, keepNordic | 7 plyos, 8 Nordic. Sprint is #1 (Francis) [P]; plyos have weak-moderate sprint evidence; the Nordic costs 174 s and goes last (safety first) |
| Both + sprint | keepNordic | plyos leave at step 1 with the accessories; 7 does nothing; 8 Nordic |

**Grid** (96 plans a cell: 2 event groups × 6 days × 2 experience × 2 age × 2 intro; `budget_results.txt`):

| Minutes | Sprint: trimmed / over / plyos lost / Nordic lost | Sprint with today's ladder | Both-Strength |
|---|---|---|---|
| 30 | 96 / 50 / 94 / 44 | 96 / 50 / 96 / 80 | 96 / 50 / 96 / 44 |
| 45 | 65 / 2 / 18 / 0 | 65 / 2 / 65 / 16 | 65 / 2 / 65 / 0 |
| 60 | 9 / 0 / 0 / 0 | 9 / 0 / 9 / 2 | 9 / 0 / 9 / 0 |
| 75 | 2 / 0 / 0 / 0 | 2 / 0 / 2 / 0 | 2 / 0 / 2 / 0 |
| 90 | 0 | 0 | 0 |

**At 60 minutes** (Strength, New):
- Most plans fit. 3-6-day plans fit for every event.
- 100 m intro, two days: fits (A 2936, C 3530).
- Two-day plans that trim:
  - 100 m non-intro: A drops BSS (ShortSession(A,1), 3253 s).
  - 200/400 m intro: C drops BSS (ShortSession(C,1), 3537 s).
  - 200/400 m non-intro: C drops BSS and its mains go to 2 sets (ShortSession(C,3), 3543 s).
- 65+ two-day short-event intro: 2559 / 2958 s.

**Longer sessions (Sol S5).**
- `Planner.fullDoseMinutes(answers, known, today): Int?` returns the smallest minutes option at or above the answer whose plan has no ShortSession (step ≥ 1) and no OverBudget, or null if even 90 trims.
- PR 7 shows "Longer sessions" whenever the plan has a ShortSession or an OverBudget, with "The full plan needs {n} minutes." Two-day short events need 75; two-day 200/400 m non-intro needs 90 (4560 s). OverBudget keeps its "Over by N min" row and its two choices.
- **Copy nits for PR 7:**
  - (Opus S6) A ShortSession reason names the cumulative cuts, for example "Single-leg work, extra sets and jumps left out to fit 30 minutes", not only the last step.
  - (Opus S5) At 30 minutes a two-day sprint plan can lose the Nordic while OHP and row stay. Your plan says "30 minutes is short for a two-day sprint plan." The ladder keeps chosen mains, as the design's "nothing is dropped until the user chooses" requires.

---

## 4. Progression, the weekly allowance and the simulations (question 3)

### 4.1 The per-rotation rules on the new layouts

`SprintRules` and `SprintContext` read the first and second maximal days in program order (A, then C or D). The proposal keeps them.

**The bound holds by construction:**

| Plan | Worst rise |
|---|---|
| 1-day short | +10 m at 40 m (25.0%) |
| 1-day 200/400 m | +10 m at 30 m (33.3%) |
| 2-6-day short | +20 m at 70 m (28.6%) |
| 2-6-day 200/400 m | +30 m at 230 m (13.0%) |

**The merged grid** (`sim_week_results.txt`): 52 rotations, 5 events × 1-6 days × intro × 9 scenarios.
- Every A and B cell equals `fix/runaudit/results_s10.txt`.
- The tempo day reaches 1,800 m at r27 (intro) and r24 (non-intro).
- The strict check "actual maximal meters in a trial rotation ≤ planned + max(15%, 20 m)" still fails in 82 of 540 rows: repeat 41, order 41. Worst cases: +123% (1-day 40 yd intro, rotation 5, 60 m planned, 134 m run) and +109%.

**The calendar the rotation bound cannot see** (one rotation a week vs dense, rest entries ignored). The cell is the largest week ÷ the mean of the 4 weeks before it; peak = the most maximal meters in any week:

| 100 m non-intro | One rotation a week | Dense: rotations/week, ratio, peak | 400 m non-intro dense |
|---|---|---|---|
| 1 day | ×1.23, peak 240 m | 7.00, ×1.10, peak 1,540 m | 7.00, ×1.25, 700 m |
| 2 days | ×1.22, 340 m | 3.50, ×1.31, 1,260 m | 3.50, ×1.29, 2,700 m |
| 3 days | ×1.22, 340 m | 2.33, ×1.35, 920 m | 2.33, ×1.48, 2,000 m |
| 4 days | ×1.22, 340 m | 1.75, ×1.48, 680 m | 1.75, ×1.67, 1,400 m |
| 5 days | ×1.22, 340 m | 1.40, ×1.64, 580 m | 1.40, ×1.63, 1,300 m |
| 6 days | ×1.22, 340 m | 1.17, ×2.02, 580 m | 1.17, ×2.06, 1,300 m |

Weekly-spacing rows repeat across day counts because the sprint days keep the same offsets. Dense rows differ: a longer rotation spreads the two sprint days over more calendar days. Intro rows are in the results file.

### 4.2 The weekly sprint allowance (PR 7 prerequisite) [P]

**What it counts.** At Finish, the plan records the **completed maximal WORK meters** of the session under its epoch day.
- Counted: the **prescribed distance** (`spec.end` as a `Measure.Distance`) of every FINISHED WORK set of an acceleration, max-velocity or sprint-endurance block, whether normal, shortened, restarted, a trial, or a block the user edited. GPS `metres` / `metresAtEnd` are not used. Pain sessions count, because the reps were run.
- Not counted: tempo, and B's tempo substitute.
- The ledger is derived from history (`cardio_set_result` rows on maximal days), not stored, so the meters live in one place. Each session is keyed by the **local date of its `startedAt`** (`Instant.ofEpochMilli(startedAt).atZone(clock.zone).toLocalDate().toEpochDay()`, the repository's existing conversion).
- **Trial dates** (`trialEpochDays`) are derived the same way:
  - A session on Sprint A is a trial when any of its WORK sets has a distance end longer than the longest rep A's range allows (acceleration 30 m, max velocity 20 m).
  - Trials are 37, 60, 100, 200 or 400 m, so the shape test is exact.
  - Rotation indexes are not stored per session, so "A's maximal date in rotation `sprintTrialCycle`" cannot be computed from history alone.

**What it decides at Start**, for a maximal day. The decision is a pure function of three inputs, which together form the **replay anchor**:
1. `todayEpochDay`: the local date of the session's start (`startedAt`; at Start, the moment the run opens).
2. The ledger of sessions before this one in judge's existing `prior` order (completedAt, then id). At Start that is every finished maximal session; at Finish it is every session ordered before this one, so this session's own rows are never in it.
3. The rules: every maximal day's stored `CardioRule` (the cap reads all of them: the planned rotation and the largest dose). On a re-judgment, the judged day's own rule is its pre-write value from the session's undo record (below).

From those inputs:
- `recent` = ledger meters on epoch days *today − 6 … today* (seven calendar dates, including any earlier finished session today).
- `cap` = **the planned rotation** (the sum of every maximal day's stored block) **plus the largest single dose**. The largest dose is the largest stored maximal block, or the trial's meters when this decision admits a trial **or a completed trial lies in the window**. The trial allowance stays while the trial's work counts against the window (Sol S1).
- `left` = `cap − recent`.

| Order | Decision | Finish (replayed from the same anchor) |
|---|---|---|
| 1 | Layoff restart, as today. Its step-0 block is then checked like a normal block (rows 4-6) | Restart state written (steps 0, credits 0, full intro: 3, or 6 for the careful group). A full restart block counts as intro session one; a **shortened restart does not** (Opus S6) |
| 2 | B's tempo substitute, only when A's `sprintTrialCycle` equals this rotation (a *completed* trial) | unchanged; not a maximal date |
| 3 | Trial: offered only at **A's first maximal session of the rotation** (A not yet in `maximalDaysThisCycle`), when today's rules admit it and the trial ≤ `left` | `sprintTrialCycle` written; no credit |
| 3a | Trial declined by the cap: rows 4-6 decide A's session | A decline writes nothing. If A then ran a maximal block, the trial is over for the rotation (A is now in `maximalDaysThisCycle`). If A ran the tempo substitute, the trial stays available at A's next session in the same rotation, under the cap. A decline never triggers row 2 |
| 4 | The normal block, if its WORK ≤ `left` | credit and steps as today, **only if the WORK run equals the decision's own block's WORK** (structural rule below) |
| 5 | Otherwise the normal block shortened by dropping reps from the end, never below the range's minimum rep count | no credit, no intro countdown, no step; a maximal date and in the ledger |
| 6 | Otherwise the step-0 tempo block in place of sprinting | no credit; not a maximal date; not in the ledger |

**Structural credit rule** (Opus S1, independent of the replay).
- A normal or restart decision earns credit or an intro countdown only when the WORK meters it ran equal the WORK of **the decision's own block**:
  - normal: `sprintBlock(session, rule.steps)`;
  - restart: `sprintBlock(session, 0)`.
  - An edited-down restart therefore writes the restart state but is not intro session one (Opus S3).
- Trials write `sprintTrialCycle` whatever the edits; they never earn credit.
- A block the user edited down in the rounds editor (#536) therefore earns nothing. So does a shortened block, and so does a substitute block, even if every rep finished.
- A block edited back up to the full prescription after the guard shortened it also earns nothing, because the replayed decision is row 5.
- This also closes a gap that exists today: `fullSuccess` judges success against the supplied (editable) block.

**Why the anchor matters** (Sol S2, Opus S1).
- `judge` dates its context `floorDiv(completedAt, 86_400_000)`, which is the UTC day of Finish. For a US evening run that is already the next date.
- Replaying with that date, or with a ledger that includes the session's own rows, can drop day today − 6 from the window. `left` then grows, row 4 wins, and a shortened block passes `fullSuccess`.
- In the simulation, a naive anchor (the next date, own rows included) would have credited **1,205** decisions that the specified anchor leaves uncredited.
- The anchor check in `sim_guard.py` is a determinism self-check: `decide()` called twice on the same inputs. Its 0 mismatches show the decision is a pure function of the anchor, not that the app wires it.

**Where the replay runs** (Opus S1).
- **Site.** `CardioJudge.judge`, via `TrackerRepository.cardioJudgment`.
  - It runs at Finish, inside the transaction, after the cardio session and its set results are inserted. Until then the results live in the `CardioRun` Parcelable.
  - It runs again on `setCardioEffort` and on distance amendments, possibly days later.
- **Date.** For sprint days `judge` builds the context with `todayEpochDay` = the local date of `session.startedAt`, and the ledger keys use the same local dates. Start uses the local date of the run's start, so both ends agree across midnight and time zones.
- **Order.** The ledger comes from `judge`'s existing `prior` list: the family's sessions ordered (completedAt, id) before this one.
- **Rules.** At the first judgment, the stored rules are the Start rules: nothing writes a sprint rule between Start and this Finish, and `CardioRun` cannot carry a snapshot because a Parcelable must not gain a field. That first judgment writes the day's new rule and records the pre-write rule in `progressionUndoJson`. A re-judgment (effort or distance amended) replays against that recorded pre-write rule, not the already-updated one, then rewrites. This is what Undo already does for blocks.
- **Guard relaxed for sprint days only.** `cardioJudgment` returns null when the run block differs from `day.cardioToRun`, and `judge` forces Repeat when set roles differ. For a sprint day both give way to the replayed decision: trial, restart, shortened, substitute and B's tempo substitute all differ from the stored block by design. The outcome, not the guard, denies credit. Run and lift days keep both checks.
- **Rule writes.** `applyCardio` writes only `CardioChange.Increase`. For sprint days it also writes Repeat and Restart outcomes whose rule differs (credits, intro countdown, restart state, `sprintTrialCycle`), once per session, guarded by `increaseApplied` and the undo record.
- **Storage.** No new Room column and no migration.
  - `CardioUndoDto` gains an optional `rule: CardioRuleUndoDto(before, after)` with CardioRule JSON strings. This is a new nullable field inside the existing `progressionUndoJson` text, read with defaults, so old rows decode unchanged. Backup carries it as text.
  - The ledger, trial dates and anchor are all derived from existing columns: `startedAt`, `completedAt`, `id`, set results.

**Why this cap.**
- Under the suggested week, a rolling 7 days holds one rotation (A + B). Under ordinary drift it can hold three sessions. Opus's case is Mon A, Sat B, Mon A, Thu B: the window at Thursday holds B + A + B = P + B. Adding the largest single dose to P admits every such three-session window, whichever of A or B is larger.
- The per-rotation bound reused as a weekly cap (P + max(15%, 20 m)) trips in exactly these cases.
- **The cap is a product limit on exposure relative to the plan.** It is practice, informed only by weak team-sport associations between high-speed-running spikes and injury (Duhig 2016, Malone 2017). It is not a validated threshold.

**The guarantee, stated precisely** (Sol S1). Both parts are asserted in `sim_guard.py` on every decision, substitutes included:
- **(G-a) Admission.** Every admitted dose keeps the 7-day window at or below the cap of the decision that admitted it. A shortened block is used only when the full block does not fit; a substitute only when even the minimum rep count does not fit.
- **(G-b) Rolling.** At every decision, the window *before* it is within that decision's cap, given the trial allowance retention, **except in two named cases**:
  1. **Restart in the window.** A layoff restart lowers the planned rotation, and so the cap, while work run after the break is still in the window. The per-day layoff predicate lets a restart come after other post-break sessions. Fixture (Sol): 100 m, two days, non-intro; A on day 0; B on days 18, 20 and 22; A restarts on day 23. At B's day-24 decision the window holds 130 m against a 110 m cap.
  2. **Manual override.** The user edits a block above its decision's dose (nothing is forbidden). Fixture: 100 m, two days, non-intro, daily, every shortened block run in full. At B's day-11 decision the window holds 410 m against 380 m.

  In both cases G-a still held for every session as admitted. The excess comes from the cap falling after admission, or from the user's edit. The next decisions see a negative `left` and shorten or substitute until the window clears. Edits are counted at what was run and never earn credit.
- **What is claimed.** The plan never *prescribes* a session that takes the 7-day window above the cap in force when it is prescribed. It does **not** claim that actual weekly exposure is always below the current cap: after a restart, or after a user's edit, it can sit above it for up to 7 days.
- **Not adopted.** Retaining the higher historical cap across restarts (Sol's alternative). It would make G-b unconditional only by letting a post-layoff user keep a pre-layoff allowance, which runs against the restart's safety purpose.

**Copy (PR 7), with precedence.**
- Shortened: "You've done more sprint sessions in the last 7 days than this plan has, so today is shortened to {n} reps." The copy names the cause (Opus S5).
- Substitute: "You've done more sprint sessions in the last 7 days than this plan has. Today is an easy tempo session instead."
- Precedence (Opus S6): the allowance message comes first. The spacing Start note (§3.2) follows it on a shortened day and is not shown on a substitute day, because that day holds no sprinting.
- The user can still change the block in the rounds editor. Nothing is forbidden; the default is the capped dose. Edits never earn credit (structural rule) and count in the ledger at what was run, so the next decisions absorb them.

**Domain shape.**
- `SprintContext.of(..., maximalMetresByEpochDay: Map<Long, Int>, trialEpochDays: Set<Long>)`.
  - The caller passes the ledger that excludes the current session, and the Start date as `todayEpochDay`.
  - It adds `weekLeft: Int?` (meters left; null without a ledger) and `careful`.
  - It folds `left` and the first-session-of-rotation rule into trial admission.
- `SprintRules.opening` picks rows 3-6.
- `afterSession` takes the same `SprintContext`, built from the same anchor, and applies the structural credit rule. Rows 5-6 return `CardioChange.Repeat` with the rule unchanged; a shortened restart writes the restart state without the intro countdown.
- No new Room column. The rule pre-write value lives in `progressionUndoJson` (above). PR 7 wires the ledger query, the anchor and the relaxed sprint-day guard. All of it lands before any sprint plan reaches a user.

### 4.3 Guard simulation (`sim_guard_results.txt`)

**Ordinary schedules, two to six days.** 400 runs: 5 events × 5 day counts × intro × {all, skip4, trial5, both, break, pending, drift (Mon A, Sat B, Mon A, Thu B), slip (A Mon/Wed/Tue, B Thu/Sun/Sat)}.
- **The chosen cap trips 0 times.** Every run's tops, finals and trial counts equal the unguarded merged rules.
- One-day plans on the six merged ordinary scenarios: 0 trips. (Round 2's two one-day trips around a trial week are gone with the trial retention.)
- **The tight cap (P + max(15%, 20 m)) trips 3,400 times**, for example 22 trips in a two-day 40 yd intro plan under Opus's drift. That is why it is rejected.

**Irregular schedules, unguarded → guarded.** peak7 = the largest rolling 7-day actual maximal meters ÷ the planned rotation.

| Scenario | Events | Runs tripped | peak7 | Worst delay to range top | Trials |
|---|---|---|---|---|---|
| repeat (A twice a rotation) | short / long | 0 / 0 of 36 / 24 | ×2.23; ×2.06 (unchanged) | 0 | unchanged (348; 195) |
| nine (A nine days running) | short / long | 36 / 24 | ×7.00 → ×2.00 | 4 / 3 rotations | 342 → 336; 190 → 190 |
| order (B first, extra A) | short / long | 30 / 0 | ×2.41 → ×2.25; ×2.00 | 5 / 0 | 336 → 331; 95 |
| every 48 h | short / long | 12 / 8 | ×4.00 → ×2.25 / ×2.11 | 27 / 16, with 9 / 3 days not at top by r52 | 324 → 305; 190 → 160 |
| dense (daily) | short / long | 24 / 16 | ×6.50 / ×7.00 → ×2.11 | 17 / 30, with 26 / 11 days not at top by r52 | 324 → 224; 190 → 135 |
| one-day Mon/Thu | short / long | 0 / 0 of 6 / 4 | ×2.11 / ×2.00 | 0 | 54; 0 |

**Invariants over all 76,750 guarded grid decisions.** B's trial-tempo decisions are included and checked against their own cap. The run fails on any non-zero count except the labeled exceptions and the naive-anchor count.

| Check | Count |
|---|---|
| admitted dose over its own cap (G-a) | 0 |
| needless shortening | 0 |
| needless substitute | 0 |
| window over a decision's cap, unlabeled (G-b) | 0 |
| G-b exception: restart in the window | 0 on the grid; 1 in its fixture (130 m vs 110 m, B day 24) |
| G-b exception: manual override in the window | 0 on the grid; 1 in its fixture (410 m vs 380 m, B day 11) |
| B tempo without a completed trial | 0 |
| replay mismatches (determinism self-check) | 0 |
| no-credit decisions a naive anchor would credit | 1,205 (the hazard the anchor removes) |

**What it does:**
- Under dense or every-48 h calendars, it holds the prescribed weekly maximal exposure to about twice the planned rotation (2.0-2.25× peak on the grid), down from 4-7×. The two §4.2 exceptions can exceed the current cap for up to 7 days.
- It stops progression there, because capped sessions earn no credit.

**What it does not do:**
- It does not make the strict per-rotation trial check pass in the repeat/order rows. A repeated A after a trial fits the weekly cap by design: tightening the cap to the per-rotation bound is exactly what false-trips under drift.
- **It does not slow a one-day plan run twice a week** (Opus S4). On a one-day plan the cap is 2A, so a Mon/Thu habit runs the full dose twice a week with credit. That is two rotations, and up to two steps, per week. With trials on, 100 m non-intro reaches its top at rotation 19 either way (trial5 vs Mon/Thu, checked in `sim_guard.py`). That is about 10 weeks on Mon/Thu against 19 once a week. The Start note still advises the spacing.
- It does not enforce rest days, and it does not see lifting or plyometric load.

**Careful group's six-session intro** (trial5, guarded):
- Range tops arrive 1-4 rotations later (for example 200 m A: r17 → r20).
- A few trials are lost (37 m two days: 9 → 8).
- Zero guard trips.

---

## 5. Over-40, 65+ and recent-strain users (question 5; owner Q2, lead Q3)

**Inputs.**
- The app asks experience and 65+.
- The domain has `sprintedRecently` and `hamstringStrain`.
- PR 7 adds to sprint setup:
  - "Sprinted all-out in the past month" (exists);
  - "Hamstring strain in the past 12 months" (exists in the domain, now asked);
  - **"40 or older"** (new: `SprintAnswers.fortyOrOlder: Boolean = false`, a field at the end).
- These persist in the PR 7 recipe JSON.

**The careful group** = `fortyOrOlder || olderAdult || hamstringStrain`.
- Grounding: Green 2020, the risk factors, strong-moderate. The 40 cutoff, the 12-month window and every dose below are practice.

| Effect | Who | Mechanism |
|---|---|---|
| Intro on | careful, and no all-out sprint in the past month | `SetupAnswers.sprintIntro = sprint.intro || careful` |
| **Longer intro: 6 counted sessions per sprint day** | careful | `SprintRules.CAREFUL_INTRO_SESSIONS = 6`, used at creation and on layoff restart (via `SprintContext.careful`). `CardioRule` accepts `intro in 0..6` |
| 90-95% effort copy during the intro | careful | "Run these reps at about nine-tenths effort." (copy; there is no velocity or effort cap on sprint reps) |
| Three-days-apart advice for the whole plan | careful | §3.2 tier (b) |
| One heavy hinge per rotation (the squat day never falls back to the hinge main), Nordic last, sprints before lifts | careful group and the Sprint branch; non-careful Both + sprint without a squat main gets two hinges (§2.4) | layout |
| Both + sprint needs a resolved squat main | careful group | gate (§2.4) |
| 65+ lift modifiers and balance block | 65+ | existing |

No general injury question is added: "Pain now" and "Injury in the past year" belong to §2.1's unbuilt Work-around list, for every branch.

---

## 6. Migration (question 6)

- **No production plan, recipe or rule uses these layouts.**
  - No production path builds Sprint or Both + sprint.
  - `SetupRecipeJson` encode and decode both require LIFT.
  - No caller writes a sprint `CardioRule` before PR 7 (revision 43).
  - So there are no rotation identities, histories, rule state, trial markers or layoff state to convert. The new layouts and the allowance replace the old layouts before PR 7.
- **If a plan existed:**
  - The maximal days keep IDs A and C/D, and five- and six-day tempo stays F, so rule state, cycle markers and date history by day ID would carry over.
  - B, and four-day E, change from Lower to Upper.
  - Changing `intro`'s upper bound from 3 to 6 widens what `CardioRule` accepts, so older values stay valid.
- **After PR 7:**
  - New layouts apply to new plans only. Stored plans keep their days, rules and history.
  - Reset day follows the current planner, as lift plans do. PR 7 hides it when the regenerated day's cardio session differs from the stored day's.
- **What existing users see:** nothing, until PR 7.

---

## 7. Build shape

### 7.1 Domain PR (this redesign; before PR 7)

**`planner/SprintLayout.kt`**
- `LowerDay { ONE_DAY, SQUAT, HINGE }`.
- `SprintEntry(session, plyos, lower, upperRows, nordic)`.
- `FIRST_PLYOS` (pogo 3, A-skip 1) and `SECOND_PLYOS` (pogo 2, A-skip 1).
- `sprintEntries` returns §2.2's table:
  - `high(session, plyos, upperRows)` sets HINGE + Nordic when `session == MAX_VELOCITY`, else SQUAT;
  - the one-day plan is `[A1, R]`.
- `lowerLifts(day, ..., squatFallsBackToHinge: Boolean)`: the row with its fallback.
  - The hinge day always falls back to the squat row.
  - The squat day falls back to the hinge row only for non-careful Both + sprint (§2.4).
  - Accessories: [HINGE, SINGLE_LEG] for ONE_DAY (the HINGE accessory is skipped when the day's main is a hinge), [SINGLE_LEG] for SQUAT, none for HINGE.
- `upperLifts(rows, ...)`. An Upper entry with no upper mains becomes Rest.
- `sprintDraft`:
  - applies the Both gate to the resolved mains: a squat or hinge main for non-careful users, a squat main for the careful group (§2.4);
  - orders slots as §2.1;
  - writes `intro = if (careful) 6 else 3` when `sprintIntro`;
  - calls `fitDay(day, liftAnswers, keepPlyos = branch == SPRINT, keepNordic = true)`.

**`planner/DoseLadder.kt`**
- The two flags; fixed steps 7 and 8 (§3.4).

**`planner/Planner.kt`**
- `ShortSession` steps 1-8.
- `fullDoseMinutes`.

**`planner/SetupAnswers.kt`**
- `careful` and `sprintIntro`.

**`planner/SprintAnswers.kt`**
- `fortyOrOlder`.

**`progression/SprintRules.kt`**
- `CAREFUL_INTRO_SESSIONS = 6` and the restart rule.
- `spacingDays`, `daysSince`.
- The allowance decision in `opening` and `afterSession`.

**`progression/SprintRules.kt` (`SprintContext`)**
- `careful`, `maximalMetresByEpochDay` and `trialEpochDays` (the ledger excluding this session, anchored at the Start date) → `weekLeft`; trial admission against `left`, only at A's first maximal session of the rotation.
- Structural credit rule in `afterSession`: the WORK run must equal the decision's own block, which is `sprintBlock(session, rule.steps)` for a normal decision and `sprintBlock(session, 0)` for a restart.
- `trialEpochDays`, derived by shape from Sprint A's WORK sets (§4.2).

**`progression/CardioRule.kt`**
- `intro in 0..CAREFUL_INTRO_SESSIONS`.

**Docs**
- A new revision of `wizard-design-2026-11-08.md` replaces:
  - §2.1's Sprint column;
  - §3.1.3's Sessions, Intro and Progression rows (two high days, plyos on both, hinge + Nordic on the max-velocity day, careful intro, weekly allowance);
  - §3.1.4's sprint row;
  - revision 31's upper-placement sentence;
  - revision 43's out-of-scope items 1-2.

### 7.2 Spec notes

- Domain stays pure Kotlin.
- No Room or backup change.
- The CardioRule JSON codec already omits `intro` at 0; its decode validation follows the new bound.

### 7.3 Ownership for implementation and tests (Sol S7)

| Piece | PR | Tests |
|---|---|---|
| Layouts, fallback/floor, ladder flags, `fullDoseMinutes`, careful intro, allowance and spacing functions | domain PR | §8 domain tests |
| Sprint setup screens: event, all-out in the past month, hamstring strain, 40 or older; Both + sprint lower-main gate and copy | PR 7 | wizard ViewModel and state-builder tests |
| Recipe JSON: branch, sprint answers including `fortyOrOlder` | PR 7 | `SetupRecipeJson` round-trip and default tests |
| Ledger query (prescribed WORK distance of finished sets and trial dates, keyed by the local date of `startedAt`, in `judge`'s `prior` order). Start and `CardioJudge.judge` wiring of `SprintContext`. The relaxed sprint-day guard in `cardioJudgment`. Sprint rule writes in `applyCardio`. The `CardioUndoDto.rule` JSON field (no Room column) | PR 7 (data + app) | repository tests with seeded `cardio_set_result` rows; Start/Finish integration tests |
| Start spacing note; allowance copy (shortened, substitute) | PR 7 | Today/Start UI-state tests per gap and tier |
| `PlanWhy` for steps 7-8; Longer-sessions offer when shortened; cumulative-cut copy; 30-minute two-day note; no lift-priority promise for Both | PR 7 | `SetupFlowStateBuilderTest` |
| Strings (all copy in §3.2, §3.4, §4.2, §5) | PR 7 | prose-whip pass; string-resource lint |
| Watch | none (parity does not block phone features) | none |

---

## 8. Tests that change (question 7)

All the arithmetic is from `budget.py` (main set 210 s, accessory 129 s, Nordic set 87 s, pogo set 120 s, A-skip set 135 s).

### 8.1 PlannerSprintTest: full-prescription pins (reference plans)

| Test | Old → new |
|---|---|
| `a 100 m plan at two days matches the table` | IDs A,B,C → A,B,C,D. A [pogo, a_skip, bb_back_squat, rdl, bss] → [pogo, a_skip, bb_back_squat, bss, bb_bench]. C [bb_bench, ohp, bb_row, nordic] → [pogo, a_skip, trap_dl, ohp, bb_row, nordic], plyo sets [2,1]. Seconds [2693,0,3155] → [2936,0,3530,0]. No reasons |
| `... at three days` | Kinds unchanged. B "Lower" [bb_back_squat, rdl, bss] → "Upper" [bb_bench, ohp, bb_row]. A [pogo, a_skip] → [pogo, a_skip, bb_back_squat, bss]. D [nordic] → [pogo, a_skip, trap_dl, nordic]. Seconds [1289,1704,0,1265,0] → [2306,2190,0,2270,0] |
| `... at four days` | IDs A-E → A-F. Kinds → [CARDIO, STRENGTH, REST, CARDIO, STRENGTH, REST]. E Lower + Nordic → Upper. Seconds → [2306,2190,0,2270,2190,0] |
| `the other events keep the table` | Kinds and sessions as today. Lifts compared within each event group (37/60 = 100; 200 = 400), because the hinge day swaps sides. At 75 min, every 2-4-day intro plan has no reasons (two-day long [3020,0,3924,0]) |
| `200 and 400 m non-intro at two days fit sixty minutes at ladder step three` | Reference 75 → 90 min: [3020,0,4560,0], no reasons. 60 min: ShortSession(C,3), [3020,0,3543,0]. C [pogo, a_skip, bb_back_squat, ohp, bb_row], sets [2,1,2,2,2] |
| `200 and 400 m intro at two days fit sixty minutes without shortening` | Rename to "...drop only the single-leg accessory". ShortSession(C,1), [3020,0,3537,0]; C sets [2,1,3,3,3] |
| `five and six days append tempo then upper lifts` | Assertions unchanged (`four.take(5) == five.take(5)`, F tempo 816, G Upper 2190). Five-day E is now Upper instead of Lower + Nordic, through `four` |
| `the intro opens every range at its low end` | A seconds 1993 → 3010. Add: 65+ turns on the intro (`sprintIntro`); careful writes `intro = 6` on every sprint day |
| `plyometrics and Nordic open at the owner doses` | Read A's plyos by role. Add D's plyos [2,1] = 50 contacts. The Nordic and the seated-curl fallback stay last on D |
| `thirty minutes fits ...` | One day: ShortSession(A,1), [bb_back_squat], 1424 → ShortSession(A,3), [pogo, a_skip, bb_back_squat], 1709 s, IDs [A,B]. Recent 3-day: [ShortSession(A,6), OverBudget(A,193)] → [ShortSession(A,7), ShortSession(B,3), ShortSession(D,7)], with A 1762 [bb_back_squat] and D 1529 [trap_dl, nordic] |
| `Both with a sprint sub-goal builds the sprint layout with the lift rules` | The squat is A's first MAIN. conv_dl is D's MAIN. bench on B and E (four days); six days B, E, G |
| `Reset day reproduces each sprint day` | Unchanged logic; it covers the new IDs |

### 8.2 PlannerSprintInvariantsTest: fitted-plan invariants (1800 + 360 + 648 grids)

- Sizes [1,3,5,5,6,7] → [2,4,5,6,6,7]. Kinds and sessions per §2.2: tempo at F at 5-6.
- G1 and G2 read cyclically for every day count, with the pattern-and-role definition of a lower-body lift. They replace `previous.title != "Lower"`. G3 order.
- Plyos only on maximal days and ≤ 80 per day, whatever trimming removed. The 65/50 opening is asserted in §8.1, not here.
- At most one day with knee flexion. If present, it is on the max-velocity day and is the day's last slot. No unconditional presence: exclusions and step 8 can remove it.
- Each high day hosts at most one lower MAIN, from its row or the fallback.
- Upper mains appear only on U days, or at two days on A (row 2) and C (rows 3-4). This replaces `(days == 2 && C) || (days == 6 && G)`.
- **Both floor:** for days ≥ 2, the number of days with a MAIN is ≥ 2 on the fitted plan, because fitDay never removes a MAIN.
- Budget and OverBudget consistency; working-row keys; weekly cap 20 (unchanged).

### 8.3 New tests

- **PlannerSprintTest `Both with a sprint goal needs a lower main`.** The gate reads resolved mains.
  - mains [bb_bench, ohp, bb_row] throws.
  - A squat main removed by `excluded = {bb_back_squat, ...}` throws for the careful group, even though the user picked it. The gate sees the resolved list.
  - mains [bb_back_squat] alone: the squat is the MAIN on A and on D (the hinge day's fallback); the 3-day B becomes Rest; lifting days = 2.
  - mains [trap_dl, bb_bench]:
    - non-careful Both: trap_dl on both high days, bench on B;
    - careful Both: throws (no squat main);
    - Sprint branch: A keeps [pogo, a_skip, bss] with no lower MAIN, D has trap_dl + Nordic.
- **PlannerSprintTest `full prescription at ninety minutes`.** Every event, Full gym, no exclusions, no reasons.
  - One day: A = [pogo 3 × 15, a_skip 1 × 20, squat main, rdl, bss], 65 contacts, no Nordic; B Rest (Sol S4).
  - Two to six days: A 65 and B 50 contacts; the hinge day ends with the Nordic; the squat day has single-leg.
- **DoseLadderTest.** The flag grid (off/off, plyos + Nordic, Nordic only). Steps 7 and 8 keep their numbers; a step whose flag is off changes nothing; lift and run days are unchanged.
- **PlannerTest `fullDoseMinutes`.**
  - 2-day 100 m non-intro at 60 → 75.
  - 2-day 400 m non-intro at 60 → 90.
  - 3-day at 60 → 60.
  - Grid property: over the Sprint and Both + sprint grids, `fullDoseMinutes` is never null. No current sprint layout trims at 90 (0 of 96 grid plans; Opus S6), so no null fixture exists. The `Int?` return stays for future layouts, and the property test fails if one appears.
- **SprintRulesTest:**
  - `spacingDays` (3 in the first weeks or the careful group, else 2);
  - `daysSince` (null without a date, 0 the same day);
  - tier (c) with a Nordic slot under 3 exposures (applies); with the seated-curl fallback, and with no knee-flexion slot (neither applies);
  - the careful restart writes intro 6;
  - `CardioRule` accepts 0..6 and rejects 7.
- **ProgressionSprintTest / PlannerSprintProgressTest (allowance).** Each row is a named fixture.

| Test | Fixture and expectation |
|---|---|
| drift never trips | Two to six days (one-day plans have no drift pattern): Mon A, Sat B, Mon A, Thu B for 52 rotations; every decision is normal or trial |
| one-day Mon/Thu | No trips; two rotations a week; credit on every session (the documented limit) |
| dense substitute | 100 m non-intro, two days, daily: A 160, B 30, A 160 fills cap 350; the fourth session (B) runs the tempo substitute, with no credit and no maximal date |
| shortening | Keeps the range's minimum rep count; no credit, no intro countdown |
| shortened layoff restart | Restart state written (steps 0, credits 0, intro 3 or 6); intro not counted down |
| declined trial, maximal block run | No `sprintTrialCycle`; A runs shortened; A's next session in the same rotation gets its normal or shortened block, not the trial; B is not tempo |
| declined trial, substitute run | A's next session in the same rotation is offered the trial again if it fits; B stays maximal unless that trial completes |
| trial retention | One-day 40 yd intro run daily: a 60 m normal session, then the 74 m trial the next day (window 134 m against trial cap 134 m). The following day's decision still includes the trial allowance (cap 134 m, not 120 m), so G-b holds |
| midnight rollover | Start at 23:50 on day d, Finish at 00:10 on d + 1, with ledger rows on d − 6 that would expire at d + 1. Finish with the specified anchor (Start date d, own rows excluded) reproduces Start's shortened decision and grants no credit. The naive anchor is asserted to differ in the fixture, documenting the hazard |
| edited block, down | A normal decision whose block the user cut by one rep in the rounds editor gets no credit (structural rule) |
| edited block, back up | A shortened decision whose block the user edited back to the full prescription gets no credit (replayed decision is shortened) |
| G-a and G-b | Over the simulator's scenario set, every admitted dose, and every pre-decision window except the labeled exceptions, is within its decision's cap |
| G-b exception, restart | Sol's fixture (100 m, 2 d, non-intro; A 0; B 18, 20, 22; A 23): B on day 24 sees 130 m against 110 m and runs the substitute; no credit |
| G-b exception, override | 100 m, 2 d, non-intro, daily, shortened blocks run in full: the edited sessions earn no credit, enter the ledger at what was run, and the next decision is shortened or substituted |
| Finish anchor (data) | `CardioJudge.judge` for a sprint day: evening run at 21:30 local (UTC next day). The context's `todayEpochDay` is the local start date. The ledger excludes the session and orders by (completedAt, id). Trial, restart and shortened-restart outcomes are written, not dropped by the `opening != cardioToRun` guard |
| re-judgment | An effort amendment two days later replays against the undo record's pre-write rule, so the credit is neither duplicated nor lost |
| trialEpochDays | A Sprint A session with one 100 m WORK set is a trial date; a 30 m acceleration session is not; a B session never is |
| edited-down restart | Restart state written; intro not counted down |
| ONE_DAY fallback | Non-careful Both, mains [trap_dl, bb_bench], one day: trap_dl main, no RDL, BSS kept |

### 8.4 Unchanged

- PlannerSprintProgressTest's A/C and A/D pins (`the planner writes the intro on every sprint day` stays A, D, F).
- ProgressionSprintTest's existing cases.
- PlannerBothTest and PlannerRunInvariantsTest.
- Lift-plan fitDay tests.

**STRENGTH_TRACKER_SPEC.md §11.2:** no case covers sprint layouts, fitting, contacts or the allowance; none changes.

---

## 9. Owner questions

Answered on 2026-11-17 (see the revision table): Q1 keeps revision 31's tempo policy; Q2 adds the 40-or-older checkbox; Q8 is deferred. The lead settled Q3, Q4, Q6, Q7, Q9 and Q10. No owner question is open.

**Open for the lead:**
1. **The allowance cap level** (planned rotation + largest single dose, about 2× the rotation at worst). It is a product choice [P]. The simulation tested only this cap and the tight bound. A cap between the two (for example 1.5 × the rotation) is untested, and would need its own drift run before it replaces this one.
2. **Six counted intro sessions for the careful group.** The number is practice; the simulation shows its cost (1-4 rotations to range tops).
