# Run/walk ladder: two Easy sessions per level, re-stepped table (design, Revision 4)

Base: PR 4b-ii-a (#606) at its final head 984c3235 (5873e30c's Ask gate, `judgment.automatic ||
outcome.change == Repeat`), on top of origin/main f0c53d78. Part B (`fix/spec-pr4bii-b.md`) is amended as section 6
says. Owner decisions (binding):
- two Easy sessions per level;
- a table on which no week's running time rises more than about 20 percent;
- level 1 at 10 minutes of running (amends §11.2 case 30);
- at most 3 Easy sessions per rolling 7 days count toward progress.

Files, all under `fix/ladder/`:
- `research.md`: evidence and grades.
- `bound_equal.py` → `results_bound_equal.txt`: the exact bound over any two adjacent 7-day windows with equal run
  counts, any dates, drops included.
- `sim_ladder.py` → `results_ladder.txt`: session-indexed.
- `sim_calendar.py` → `results_calendar.txt`: dated sessions, the cap, the guard, rating changes, kept days,
  backdating.
- `explore/adv.py`, `explore/advrun.py`, `explore/advrun4.py` → `explore/adv_g7or14.txt`, `adv_more.txt`,
  `adv_r3.txt`, `adv_r3_n4.txt`: adversarial hill-climbs over dates and ratings (run from `explore/`:
  `python3 advrun.py <guard> <max runs a week> <seed>`).
- `explore/guards.py`, `explore/drift.py`: the Revision 2 candidate comparison.
- `replan_newladder.py` → `results_replan_*.txt`: part B with this ladder and with deferral.
- `opt.py` → `alternatives.txt`.
- `design-r2.md`, `design-r3.md`: Revisions 2 and 3, for the record.

## 0. Revision 4 changelog (round 3 step-back: delimit the claims, no new rule)

| Finding | Outcome |
|---|---|
| Sol R3 S1 [blocker], Opus R3 S1 [should]: the proof's scope overreaches. Plans are rotations; kept blocks break the shared-level model | §3.1 now states the theorem exactly. It holds for sessions applied in date order, each running the runner's current level block, while no 7 days hold more than 3 runs. The plan's rotation does not guarantee that: Run's 3-day row E Q L R and Both's A B C, followed daily, put 5 to 7 ladder runs in 7 days, which is the searched region. §1 and §3.3 no longer say "every planned cadence" |
| Opus R3 S2 [should], Sol R3 S1: kept blocks and leaving Keep | §3.4 names them as dose changes the user chose. B kept at level 1 while A and C reach 9 gives 600 → 1,350 s (+125%) across weeks that run only B, then only C. Leaving Keep at level 10 or 12 gives +24.1% or +28.6%. A kept day is never judged (`CardioJudge.kt:72`), so a Hard or Pain on it does not drop the ladder. Flagged for PR 7's copy. No rule change |
| Sol R3 S2 [should]: backdating | §3.4: the ledger correctly repeats a backdated Easy, but the historical totals a backdated block creates (+47.2% in Sol's example) are outside the chronological model |
| Sol R3 S3, Opus R3 S5: §3.3 overclaims necessity | Labeled a heuristic, scoped to level-up rules, and states that no proof for windows of 4 or more runs is known. A Start-time hold (opening a lower block after a busy week) is a causal alternative nobody has analyzed |
| Sol R3 S4 [should]: scorers skip boundary windows | Both scorers (`sim_calendar.py`, `explore/adv.py`, `explore/advrun4.py`) now zero-pad beyond the observed dates. Sol's (0, 600) → (7, 675) pair scores +12.5% (`results_calendar.txt` §9). Everything re-run (calendar sim, 16 all-pair and 11 busy-pair searches): no number changed |
| Opus R3 S3 [nit]: bound docstring | `bound_equal.py` describes its model, says the n = 6 and 7 cap-only values exclude drops, and names what is outside the model |
| Opus R3 S4 [nit]: replay count | §4.2 cites the current run's replay count |
| Opus R3 S6 [nit]: running time | "Planned WORK seconds" throughout §3 |

## 0b. Revision 3 changelog (round 2)

| Finding | Outcome |
|---|---|
| Sol R2 S1 [blocker]: Revision 2's guard breaks the rolling bound at three runs a calendar week (+22.98%) | Pinned (`results_calendar.txt` §1). The guard now looks back 14 days: a level-up waits a week after the last one while any 7 consecutive days of the last 14 held more than 3 runs (§2.3). Sol's case falls to +19.15%. Every adjacent pair of rolling windows with equal counts is now measured. §3 separates what is proven (windows of up to 3 runs: +19.15%, exact, any dates, drops included) from what is only searched (4 to 7 runs: worst found +19.15% with this guard). It also says plainly why no small causal rule can prove the 4+ case, and recommends shipping anyway |
| Sol R2 S2 [blocker]: a kept day keeps a stale ledger and overwrites the runner on its return | The runner's rule is written to every ladder day, kept ones included; kept days keep their block. Leaving Keep writes the level's block. Ladder Undo is all or nothing. A judgment whose ladder days disagree fails closed (§4.1, §4.2). Sol's case: 3 counted finishes, and B resumes at level 2 with A and C (`results_calendar.txt` §6) |
| Opus R2 S1 [should]: the four-day spread picks A, B, C | Second key added: fewest pairs at the smallest gap. Pinned at 4, 5 and 6 days: A, C, E (§8) |
| Opus R2 S2 [should]: backdated Finish | An Easy finish dated before the newest stored ladder date (`ladderCounted` or `ladderRaised`) is a `Repeat`. Hard and Pain still drop and write no date. Test added (§2.2, §10) |
| Opus R2 S3 [should]: coverage claims | Non-finishers are measured. Tables corrected. §3.2 is retitled, and every row says what is enumerated and what is sampled. The unresolved list names the sampled parts (§3, §13) |
| Opus R2 S4 [nit]: base and Ask gate | Base set to 984c3235. The Ask drop write is a named branch against `change == Repeat` (§4.3) |
| Opus R2 S5 [nit]: drifting three-a-week runners meet the guard | Stated, with their finish times (§2.3) |
| Opus R2 S6 [nit]: tests | Added `the guard holds LadderDone` and the backdate test (§10.1) |

## 0a. Revision 2 changelog (round 1)

| Finding | Outcome |
|---|---|
| Opus S1, Sol S1 [blocker]: the cap is missing; the cap alone does not hold the bound (Sol: daily, one Hard at session 11, +22.52%) | Cap specified in full (§2.2). Sol's case reproduced: +22.52% with the cap alone. Added the smallest load safeguard that held in every simulation, a level-up guard (§2.3): with it Sol's case is +12.50%, and the worst over every tested schedule is +19.1% (§3). Where no ladder rule can hold the bound, §3.4 says so |
| Sol S2 [blocker]: rating changes break re-folded admissions | Admissions are no longer re-folded from ratings. They are stored on the rule (`ladderCounted`) and written, undone and replayed atomically with progress through part A's gates (§2.2, §4.2). Sol's case: 3 credits in days 1–7, not 4; 3,000 random re-rating histories never break the cap (`results_calendar.txt` §5) |
| Sol S3 [blocker]: part B's floors can keep a re-plan over the bound | Part B change: a re-plan that stays over its allowance after the floors returns null and waits for a later wrap (§6.2). Sparse runners: +308.8% without deferral, +20.0% with it |
| Opus S2, Sol S4 [should]: Ask mode | On Ask, a Hard or Pain drop writes the credit clear (same level) to every ladder day; LadderDone waits for PR 7's offer; part B re-plans Automatic plans only (§4.3, §6.3) |
| Opus S3, Sol S6 [should]: derived length | Derived from the draft's real LADDER sessions. 0 sessions → unreachable → DateTooClose when dated. Every caller listed; described as an all-Easy estimate (§7) |
| Opus S7, Sol S5 [should, nit]: Both spread | Chosen by rotation gaps including the Rest entry; day ids pinned; the reserve is still deducted, and that is accepted (§8) |
| Sol S7 [should]: start anchor includes recovery | Start level reads a running-only anchor (`KnownLoads.longestRunning`, WORK seconds). Test with recovery-heavy history (§9) |
| Sol S8 [should]: tests | Added: dated cap tests, guard tests, sibling credit, replay and Undo tests, part B's credited level-12 Finish, deferral tests, and a judge test that production always supplies the ladder context; pure tests marked cap-free (§10) |
| Opus S4 [should]: stale recipe claim | Corrected: part A relaxes `SetupRecipeJson.encode`; the guard is `SetupFlowStateBuilder` plus Reset day's Lift-only return (§11) |
| Opus S5 [nit]: research says 2-minute walks | Fixed to 1.5 minutes, the same as Couch-to-5K's walk |
| Opus S6 [nit]: write the level's block to every sibling | Adopted (§4.1) |
| Opus S8 [nit]: an old app rejects a backup with ladder credits | Noted for PR 7's schema bump (§13) |
| Opus S9 [nit]: exposure | Stated: R46 and the Both cap own exposure; neither the cap nor two-per-level limits how often anyone runs (§3.4, §8) |
| Sol S9 [nit]: labels, warm-up, recipe | Practice labels kept, with no injury-prevention claim (research §5, gaps); the warm-up equivalence is removed; the recipe claim is fixed |

## 1. The result in brief

- **Table:** 12 levels, unchanged. Level 1 is 10 × (1 min run, 1.5 min walk); level 12 is 30 minutes continuous.
- **Rule:**
  - Two counted Easy finishes per level.
  - An Easy finish counts only when fewer than 3 counted finishes fall in the 7 local days ending today, and it is
    not dated before the newest stored ladder date.
  - A level-up waits for 7 days after the last one while any 7 consecutive days in the last 14 hold more than 3
    runs.
  - Good, no word (R43) and unfinished sessions repeat and keep the credit.
  - Hard or Pain drops one level (floor 1) and clears the credit.
- **Proven bound (chronological model):** sessions applied in date order, each running the runner's current level
  block, while no 7 consecutive days hold more than 3 runs. Under those conditions, of any two adjacent 7-day windows
  with the same number of runs, the second has at most **+19.15 percent** more planned running time. That holds for
  any dates, ratings and drops (`results_bound_equal.txt`). The plan does not guarantee the condition: following
  the rotation daily puts 5 to 7 ladder runs in 7 days (§3.1).
- **Searched, not proven:** windows holding 4 to 7 runs each, with the guard (numbers in §3.2). The cap alone is
  provably at most +22.98 / +25.49 / +27.19 / +28.42 percent for 4 / 5 / 6 / 7 runs (drops left out at 6 and 7).
- **Outside the model** (§3.4): blocks kept to plan, leaving Keep, and backdated entries. These are dose changes the
  user chose, or history entered out of order.
- **Not bounded:** a week with more runs than the week before. That is frequency, which no progression rule
  controls (§3.4).
- **Weeks to finish, all Easy from level 1:**
  - Mon/Wed/Fri: 7.7
  - 2 a week: about 11.5
  - 1 a week: 23
  - 4 to 7 a week: about 11.3
  - drifting three a week: see §3.2; slower whenever some 7-day span holds 4 runs

## 2. The rule

### 2.1 Table (unchanged)

| Level | Run (s) | Walk (s) | Rounds | Running | On foot |
|---|---|---|---|---|---|
| 1 | 60 | 90 | 10 | 10:00 | 25:00 |
| 2 | 75 | 90 | 9 | 11:15 | 24:45 |
| 3 | 105 | 90 | 7 | 12:15 | 22:45 |
| 4 | 135 | 90 | 6 | 13:30 | 22:30 |
| 5 | 180 | 90 | 5 | 15:00 | 22:30 |
| 6 | 200 | 90 | 5 | 16:40 | 24:10 |
| 7 | 270 | 90 | 4 | 18:00 | 24:00 |
| 8 | 300 | 90 | 4 | 20:00 | 26:00 |
| 9 | 450 | 90 | 3 | 22:30 | 27:00 |
| 10 | 720 | 90 | 2 | 24:00 | 27:00 |
| 11 | 1,620 | 0 | 1 | 27:00 | 27:00 |
| 12 | 1,800 | 0 | 1 | 30:00 | 30:00 |

```kotlin
val LEVELS: List<Level> = listOf(
    Level(60, 90, 10), Level(75, 90, 9), Level(105, 90, 7), Level(135, 90, 6), Level(180, 90, 5), Level(200, 90, 5),
    Level(270, 90, 4), Level(300, 90, 4), Level(450, 90, 3), Level(720, 90, 2), Level(1620, 0, 1), Level(1800, 0, 1),
)
val Level.runningSeconds: Int get() = runSeconds * rounds
```

Revision 1's reasons hold:
- Running time rises 6.7 to 12.5 percent per level and never falls.
- The total time per session stays between 22.5 and 30 minutes.
- Bouts grow at most ×1.6 per level, except level 10 → 11 (×2.25 at an unchanged 27 minutes on foot).
- Level 1 matches GRONORUN's week 1 dose with Couch-to-5K's walk.
- With 12 levels and an 8-minute level 1, no table holds 20 percent (`alternatives.txt`).

All of this is practice; none of it has a shown injury or drop-out benefit (research §5).

### 2.2 The cap (owner rule)

- **State.** `CardioRule` gains two ladder-only fields:
  - `ladderCounted: List<Long>`: the local epoch days of the most recent counted Easy finishes, ascending, at most
    3, duplicates allowed.
  - `ladderRaised: Long?`: the local epoch day of the last level-up.

  `init` requires both to be empty or null unless `ladderLevel != null`, with `ladderCounted.size <= 3` and sorted.
  The JSON keys are `"ladderCounted"` and `"ladderRaised"`, omitted when empty or null, so `{"ladderLevel":3}` keeps
  its bytes. These fields and `credits` are the runner's, copied to every ladder day (§4.1).
- **Window.** `today` is the session's local Start date in the plan zone (`localStart(session, plan.zone)`, as
  sprint uses). The window is local dates `today − 6 .. today`. Replay uses the session's own date.
- **Count source.** `ladderCounted` on the pre-write rule. It is not recomputed from history or ratings, and it does
  not depend on which days carry the ladder now. A credit, its date and the level move together in one rule write.
  Undo and replay therefore restore all three at once (§4.2).
- **Backdated finishes.** `CardioEntrySheet` allows any past Start date. An Easy finish whose `today` is before
  `max(ladderCounted, ladderRaised)` is a `Repeat` that writes nothing, so the dates stay sorted and no earlier
  window gains a fourth count. A backdated Hard or Pain still drops, because a drop writes no date; safety
  outranks the order of entry.
- **Effect.** A finished Easy session counts only when fewer than `COUNTED_PER_WEEK = 3` dates in `ladderCounted`
  lie in the window. Over the cap, it is a `Repeat`: rule unchanged, credit kept, nothing written. Unrated sessions
  never count (R43). Hard and Pain still drop.
- **Constant.** `RunWalkLadder.COUNTED_PER_WEEK = 3`, KDoc "owner 2026-11-17; practice: Couch-to-5K and GRONORUN
  run three sessions a week (R46)".

### 2.3 The level-up guard (safeguard, Revision 3)

- **Rule.** A counted Easy finish that would complete the level (the credit is already 1) waits while
  `ladderRaised != null && today − ladderRaised < 7` and some 7 consecutive local days within `today − 13 .. today`
  hold more than `3` family runs.
  - Runs are counted by local Start date and include this run.
  - They count any rating, finished or not, ladder or not.
  - That is 8 spans, ending on `today − 7 .. today`.
  - A waiting finish is a `Repeat`: it is not counted, it uses no cap slot and the credit stays 1. The next counted
    Easy finish tries again. LadderDone waits the same way.
- **Why look back 14 days.** Take a level-up on day u inside a window W2 = [t − 6, t]. The window before it,
  W1 = [t − 13, t − 7], is entirely in the past: it ends between u − 7 and u − 1, so it is one of the 8 spans.
  - If W1 held 4 or more runs, the guard sees it and forces 7 days since the last level-up.
  - If W1 held 3 or fewer, equal-count pairs (W1, W2) hold 3 or fewer runs each, and the §3.1 proof covers them.

  The weaker variants failed this. Revision 2 counted only the 14 days (Sol's +22.98%). "More than 3 in the last 7
  days or more than 6 in 14" switches off after a gap (searched worst +19.57%). This one's searched worst equals
  the proven 3-run bound, +19.15% (§3).
- **Run count source.** The judge's family history, `prior` plus this session. It needs no ladder membership. A
  later edit to an old session cannot change anything already written: the guard checks, it never accumulates.
- **Who meets it:**
  - Runners on 3 fixed weekdays never do.
  - Drifting three-a-week runners do whenever some 7-day span in the last 14 days held 4 runs (finish times in
    §3.2).
  - Runners above three a week are held to one level a week and finish in about 11.3 weeks.
- **Constants.** `LEVEL_UP_DAYS = 7`, `BUSY_RUNS_IN_7_DAYS = 3`, `LOOK_BACK_DAYS = 14`. Practice, sized by simulation
  (§3); no outcome study tests them.

### 2.4 Outcomes

| Finish | Next block | Next rule | Change |
|---|---|---|---|
| Pain flag, or rated Hard | level − 1 (floor 1) | `copy(ladderLevel = level − 1, credits = 0)`, dates kept | `LevelTo(level − 1)`; at level 1, `Repeat` |
| Not finished, Good, or no word | same | unchanged | `Repeat` |
| Easy, dated before the newest stored ladder date | same | unchanged | `Repeat` |
| Easy, cap full | same | unchanged | `Repeat` |
| Easy, no credit | same | `copy(credits = 1, ladderCounted = (counted + today).takeLast(3))` | `Repeat` |
| Easy, credit 1, guard waits | same | unchanged | `Repeat` |
| Easy, credit 1, level < 12 | `RunWalkLadder.block(level + 1)` | `copy(ladderLevel = level + 1, credits = 0, ladderCounted = (counted + today).takeLast(3), ladderRaised = today)` | `LevelTo(level + 1)` |
| Easy, credit 1, level 12 | same | `CardioRule(null)` | `LadderDone` |

```kotlin
/** [busy]: some 7 consecutive local days within todayEpochDay − 13..todayEpochDay hold more than 3 family runs
 *  (this run included). */
data class LadderContext(val todayEpochDay: Long, val busy: Boolean)
// CardioContext gains: val ladder: LadderContext? = null

val finished = CardioMeasures.fullSuccess(block, results)
val l = ctx.ladder
when {
    pain || effort == CardioEffort.HARD -> {
        val down = maxOf(1, level - 1)
        val next = rule.copy(ladderLevel = down, credits = 0)
        if (down == level) CardioOutcome(block, next, CardioChange.Repeat)
        else CardioOutcome(RunWalkLadder.block(down, block.activity), next, CardioChange.LevelTo(down))
    }
    !finished || effort != CardioEffort.EASY -> repeat()
    l != null && l.todayEpochDay < maxOf(rule.ladderCounted.lastOrNull() ?: Long.MIN_VALUE,
        rule.ladderRaised ?: Long.MIN_VALUE) -> repeat()
    l != null && rule.ladderCounted.count { it in l.todayEpochDay - 6..l.todayEpochDay } >= RunWalkLadder.COUNTED_PER_WEEK -> repeat()
    rule.credits == 0 -> CardioOutcome(block, rule.copy(credits = 1, ladderCounted = counted(rule, l)), CardioChange.Repeat)
    l != null && rule.ladderRaised != null && l.todayEpochDay - rule.ladderRaised < RunWalkLadder.LEVEL_UP_DAYS &&
        l.busy -> repeat()
    level == RunWalkLadder.TOP -> CardioOutcome(block, CardioRule(null), CardioChange.LadderDone)
    else -> CardioOutcome(RunWalkLadder.block(level + 1, block.activity),
        rule.copy(ladderLevel = level + 1, credits = 0, ladderCounted = counted(rule, l), ladderRaised = l?.todayEpochDay),
        CardioChange.LevelTo(level + 1))
}
// counted(rule, l) = l?.let { (rule.ladderCounted + it.todayEpochDay).takeLast(3) } ?: rule.ladderCounted
```

- **Production always supplies the context.** `CardioJudge.judge` builds `LadderContext` on every ladder day (ruled
  days already require `plan`, which carries the zone). `today` comes from `localStart`, the same as sprint, and
  `busy` from `prior` plus the session by local Start date.
- **Pure tests may omit it.** `ladder = null` runs the two-per-level rule alone, with no cap, no guard and no dates.
  Case 34's pure tests are explicitly cap-free.
- **Constants and KDoc.** `CardioChange` gains nothing. `RunWalkLadder.EASY_PER_LEVEL = 2` is kept for §7. The KDoc
  of `cardioAfterSession` and `RunWalkLadder` follows the outcome table above.

## 3. The weekly bound: what is proven, what is searched, what cannot hold

Running time = **planned WORK seconds**: each session is charged its block's WORK. An unfinished session's actual
running is lower, so the measure is conservative for it.

The measure is every pair of adjacent rolling 7-day windows, W1 = days t − 13 .. t − 7 and W2 = t − 6 .. t, that
hold the **same number of runs n**, for every day t. It includes windows that reach past the first or last
recorded date, zero-padded (Revision 4). Runners who never finish are measured. Pairs with different counts are
frequency changes (§3.4).

### 3.1 Windows of up to 3 runs each: proven, +19.15 percent, in the chronological model

**Theorem.** The conditions:
- (a) sessions are applied in date order;
- (b) each session runs the runner's current level block;
- (c) no 7 consecutive days hold more than 3 runs.

Then for any two adjacent 7-day windows with the same number of runs n (so n ≤ 3), the second window's planned
running time is at most +12.50 percent (n = 1, 2) or **+19.15 percent** (n = 3) above the first. This holds for any
dates, any ratings and any Hard or Pain drops.

**Proof sketch.** Under (a) and (b), the runs in W1 and W2 are 2n consecutive sessions, each charged its level's
running. Every rule here acts by session: an Easy finish either counts or does not, and a Hard or Pain drops. That
covers two per level, the cap, the guard, the backdate refusal, Good, no word and unfinished. The worst case is
therefore an exhaustive enumeration over count/repeat/drop patterns.

**What the conditions exclude:**
- **(c) is the runner's calendar, not the plan.** Plans are rotations. Run's 3-day row is E Q L R (`RunLayout.kt`
  rows[2]): three ladder days and a Rest entry. Both at 3 lift days is A B C with no Rest. A runner who follows
  either one daily puts 5 to 7 ladder runs in 7 days, which is the searched region of §3.2. Runners on 3 fixed
  weekdays, or any schedule that never puts 4 runs in 7 days, are inside the theorem.
- **(b) excludes blocks kept to plan and leaving Keep, and (a) excludes backdated entries.** Both are covered in
  §3.4.

`bound_equal.py` enumerates every pattern of 2n sessions:
- each session counts, repeats or drops;
- from every start level with either credit;
- with up to 3 counted in each window;
- with level-12 sessions after LadderDone at 1,800 s.

It takes the worst W2 over W1 (`results_bound_equal.txt`):

| Runs per window (n) | Exact worst, any dates, drops included |
|---|---|
| 1 | +12.50% |
| 2 | +12.50% |
| 3 | **+19.15%** (from level 9 with a credit: 1,350 + 1,440 + 1,440 → 1,620 + 1,620 + 1,800) |

The bound holds whatever the guard does, because the guard only turns counts into repeats. `bound_equal.py`
includes drops for n ≤ 5; its cap-only figures for n = 6 and 7 leave drops out.

### 3.2 Windows of 4 to 7 runs each: searched, not proven

The same enumeration with only the cap (3 counted per window) proves an upper bound there: **+22.98, +25.49,
+27.19 and +28.42 percent** for n = 4, 5, 6 and 7. Adversarial search reaches the cap-only n = 5 value, so the cap
alone is not enough.

The guard has no closed proof. What was searched (`results_calendar.txt`, `explore/adv_r3.txt`,
`explore/adv_r3_n4.txt`; all re-run in Revision 4 with zero-padded boundary windows, and no number changed;
`explore/adv_g7or14.txt` and `adv_more.txt` are the Revision 3 candidate runs with the old scorer):

| Schedule | Enumerated | Sampled | Cap only | Cap + Revision 3 guard |
|---|---|---|---|---|
| Fixed weekdays, 4–7 runs a week | all weekday sets; single rating events at sessions 1–45; Hard pairs in 1–30 | 200 random-rating runners per set | +22.5% | **+12.5%** |
| Drifting weekdays, same count each calendar week, 1–7 runs | — | 4,000 runners per count, 70% with random ratings | +25.0% | **+19.1%** (3 a week; 4–7 a week ≤ +18.8%) |
| Adversarial hill-climbs over dates and ratings, 4–7 runs per calendar week | — | 16 searches, 40 restarts × 8,000 moves each | +25.49% | **+19.15%** over all pairs; **+18.75%** over pairs holding 4 or more runs (9 more searches, `explore/adv_r3_n4.txt`) |
| Sol R1 (daily, Hard at 11) | pinned | — | +22.52% | +12.50% |
| Sol R2 (three a calendar week, drifting) | pinned | — | +19.15% | +19.15% (Revision 2's guard: +22.98%) |

Weeks to finish, all Easy from level 1, with the guard:
- fixed weekdays: 3 a week 7.4–8.0, 4–7 a week 11.3–11.7;
- drifting weekdays: 3 a week 8.9–13.6, 4 a week 11.3–14.0.

### 3.3 Why the 4+ case is unproven (heuristic), and what to do

**Heuristic, not a proof.** This reasoning covers rules that gate level-ups only. Take a pair with n ≥ 4 and a
level-up on a day u inside W1. That level-up is decided before the rest of W1 has happened, so a level-up rule
cannot know W1 will hold 4 runs, and two level-ups 4 or 5 days apart inside W1 stay possible. Runs paired across
W1 and W2 can sit up to 13 days apart. Ruling out a two-level shift for every pair would take about one level-up
per 13 days, roughly a 24-week ladder.

That is stronger than the 20 percent goal needs, so it does not show that every small causal safeguard fails. No
proof for windows of 4 or more runs is known. One causal alternative no one has analyzed is a hold at Start that
opens the lower block after a busy week. It changes the dose rather than the level, and it would need its own
design and evidence.

The Revision 3 guard does close the other half exactly. For a level-up inside W2, W1 lies wholly in the past
(§2.3), so a busy W1 always forces the 7-day wait.

**Candidates tried on top of the cap** (`explore/`):

| Candidate | Worst found | Mon/Wed/Fri cost |
|---|---|---|
| none | +25.49% | none |
| level-ups ≥ 4 days apart | +21.5% (fixed) | up to 11.7 weeks |
| level-ups ≥ 5 days apart | +20.3% (fixed) | 10.7–11.7 weeks |
| level-ups ≥ 7 days apart, always | +18.75% | about 11.5 weeks at every cadence |
| new level ≤ 1.2 × shortest run of last 6 days | +22.9% (drift) | up to 9.6 weeks |
| Revision 2: > 6 runs in 14 days | +22.98% | none |
| > 3 in 7 days, or > 6 in 14 | +19.57% | none |
| **Revision 3: any 7 days of the last 14 with > 3 runs** | **+19.15%** | **none** |

**Recommendation.** Ship the Revision 3 guard.
- It costs the runner on 3 fixed weekdays nothing.
- It cannot weaken the proven bound, which holds whatever it does.
- Across every search above 3 runs in 7 days, it stayed within the values in §3.2.

Put this sentence in the revision entry: "proven, in the chronological model, while no 7 days hold more than 3
runs; searched, not proven, above that; kept blocks and backdated entries are outside the model."

### 3.4 Where no ladder rule can hold the bound

- **More runs than the week before.** At one level, 2 → 3 runs is +50 percent, 3 → 4 is +33, and 3 → 6 is +100.
  The ladder controls the dose per session, not how often someone runs. R46, the 3-day ladder cap in Run and Both
  plans (§8), and R52's novice two-week rule (4b-iii) own exposure. PR 7's copy can say so. The cap limits progress,
  not running.
- **The week after a gap.** The week before was light, so any session is a large rise against it. The ladder never
  advances during a gap, so the runner returns at the same level, below their last full week.
- **Blocks kept to plan: a dose change the user chose.** Keep to plan freezes a day's block; the day is never
  judged (`CardioJudge.kt:72`), so a Hard or Pain on it does not drop the ladder. The other days keep climbing.
  Example: B is kept at level 1 while A and C reach level 9. A week that runs only B and a week that runs only C
  then go 600 → 1,350 s (+125%) with no level-up between them.
- **Leaving Keep: also a user-chosen dose change.** The returning day jumps to the runner's level (§4.1), the block
  the runner already runs on the other days. Mon/Wed/Fri with B kept at level 1:
  - A and C at level 10: 1,440 + 600 + 1,440 → 3 × 1,440 is +24.1 percent;
  - at level 12: 1,800 + 600 + 1,800 → 3 × 1,800 is +28.6 percent.

  **For PR 7's copy:** Keep says it holds this day's dose while the plan moves on, that a hard session on it does
  not change the plan, and that leaving Keep moves the day to the current level. No rule change.
- **Backdated entries.** The ledger repeats a backdated Easy (§2.2), so admissions stay right. The historical
  totals a backdated block creates are outside the chronological model. Sol's example: Mon/Wed/Fri from day 0,
  day 9 left out, level 9 reached on day 37, then its 1,350 s block entered for day 9. Days 0–6 total 1,875 s and
  days 7–13 total 2,760 s, +47.2 percent, three runs each. It is history entered after the fact, not a dose anyone
  ran in that order.
- **After LadderDone.** The remaining days of that rotation run the level-12 block under A16. Part B's guard and its
  deferral (§6.2) own the weeks after the wrap.

## 4. Composition with part A (#606)

### 4.1 One runner, every ladder day, kept days included (Revision 3)

The ladder rule (level, credit, `ladderCounted`, `ladderRaised`) is the runner's. Every day that carries a ladder
level holds the same rule, including days kept to plan. Replace part A's `LevelTo`/`LadderDone` sibling loop in
`TrackerRepository.applyRuledRun` (984c3235, lines 1057–1066):

- **On every applied change.** When the judged day had a ladder level and the applied rule differs from its stored
  rule, every other day of the plan with a ladder level takes the applied rule (`CardioRule(null)` on LadderDone),
  **kept days included**:
  - A day not kept to plan also takes `RunWalkLadder.block(theRule.ladderLevel ?: TOP, itsActivity)`.
  - A day kept to plan keeps its block. Keep to plan freezes the day's block; it does not fork the runner's ledger.

  `write()` skips equal JSON, so a credit-only change rewrites no block and a sibling that has drifted heals. Every
  write lands in the session's `CardioDayUndoDto` list. This also lets LadderDone clear a kept day's level, so part
  B's gate opens with a kept day in the plan; part B leaves the kept day's block as stored (its 1.3.1).
- **Leaving Keep** (`setCardioKeepToPlan(planId, dayId, keep = false)` on a day with a ladder level): in the same
  transaction, write `RunWalkLadder.block(rule.ladderLevel, itsActivity)` when the stored block differs. The rule is
  already current, so the day resumes where the runner is. Sol's case (`results_calendar.txt` §6): B resumes at
  level 2 with credit 1 and dates [0, 1, 2]; its Easy on day 3 is refused by the cap, so 3 finishes count, not 4.
- **Fail closed on disagreement.** `cardioJudgment` returns null for a ladder day when any other ladder day of the
  pre-write program (`preWriteDays`) carries a different ladder rule. With the writes above and the all-or-nothing
  Undo below, disagreement can only come from outside the app's own paths (a hand-edited backup). A stalled ladder
  is safe; a forked one is not. A test pins that the invariant holds across Keep, Undo, the day editor and replay.

### 4.2 Ratings, replay and Undo

- **Ratings.** A rating changed later goes through part A's gates. The session must be the newest of its day, and
  every recorded day must still equal its afters. Any later ladder Finish that wrote the shared rule (a credit, a
  level change, a drop) breaks the second condition, so a late re-rating changes nothing, as for every ruled run.
  When the gate passes, the replay restores the recorded befores (including `ladderCounted`) and judges again with
  the session's own date. A later session that wrote nothing cannot have been counted, so no fourth credit can
  appear (`results_calendar.txt` §5: 0 of 3,000 random histories with 3,282 replays; Sol's case gives 3 credits in
  days 1–7).
- **Undo, all or nothing on the ladder (Revision 3).** Part A restores each `CardioDayUndoDto` whose day still
  matches its afters, one by one (984c3235 `undoFinish`, lines 2311–2320). For a session whose records carry a
  ladder rule (before or after), Undo restores the ladder records and the judged day's `rule` record only when
  every one of them still matches; otherwise it restores none of them, and the rest of the Finish's Undo proceeds.
  Partial restoration is what forks a runner (Sol's "divergent Undo restoration").
  - When everything matches, the credit, its date and the level go back together on every ladder day, kept ones
    included, and the next cap check sees the restored dates.
  - A later session the cap refused stays refused. That is conservative: it can lose an admission, never add one.
- **Edits.** The day editor clears a run rule (part A §3.6). That day leaves the ladder, but the dates live on the
  other ladder days' rules. Nothing scans "ladder days" for history.
- **Undo records cleared** (`clearFinishUndoForDay`, Undo itself). Admissions do not depend on them.

### 4.3 Ask mode

Part A's gate at 984c3235 is `judgment.automatic || judgment.outcome.change == CardioChange.Repeat` (line 1042). On
Ask that writes Repeat outcomes, including credit-only changes, and holds everything else for PR 7's offer. For the
ladder:

| Outcome on Ask | What is written |
|---|---|
| Credit earned (`Repeat`, rule changed) | The rule, to every ladder day (§4.1) |
| Cap, guard, backdate, Good, no word, unfinished (`Repeat`, rule unchanged) | Nothing |
| Level-up (`LevelTo` up) | Nothing. It is not counted and uses no cap slot; the credit stays 1 and the next Easy retries |
| Hard or Pain at level 2 or above (`LevelTo` down) | **Named branch:** `else if (!judgment.automatic && judgment.ladderDrop)`, writing `stored.copy(credits = 0)` to every ladder day with blocks unchanged. The level drop waits for PR 7. `ladderDrop` is true when the pre-write rule had a ladder level and the outcome is `LevelTo(n)` with n below it |
| Hard or Pain at level 1 (`Repeat`, credit cleared) | The rule, to every ladder day |
| LadderDone | Nothing (part A round 1, "Ask holds the ladder exit"). The runner stays at level 12 with a credit, so part B's gate stays closed |

PR 7's offer, once accepted, writes the held outcome. Part B re-plans only Automatic plans (§6.3).

## 5. Ladder exit and level 12

Level 12 is still 1,800 s continuous. LadderDone still clears every ladder level at once, and it needs a credit at
level 12 plus a counted Easy that passes the cap and the guard. After it, the rotation's remaining ladder days run
the level-12 block under A16 until the wrap (part A). The calendar simulation includes up to two such sessions.

## 6. What changes in part B (`fix/spec-pr4bii-b.md`)

### 6.1 Fixtures and port

- **Fixture.** "Set A, B, C to `CardioRule(12)`… Finish A → every ladder day `CardioRule(null)`" becomes
  `CardioRule(12, credits = 1, ladderCounted = listOf(d − 2), ladderRaised = d − 9)`, a Finish on local day `d`
  where no 7 consecutive days of the last 14 hold more than 3 runs.
- **Unchanged.** `the ladder exit waits for every ladder day` and `the ladder exit plans base within a fifth over
  the week run` use stored level-12 blocks.
- **Simulator port.** `sim_replan.py`'s ladder port becomes `replan_newladder.py`'s (new table, two per level,
  running-time start, deferral).
- **§6 numbers.** New marathoner at three days: base at week 8 (was 4), half build at 22 (was 17), marathon build at
  35 (was 30). Ladder → base still +20.0 percent. In the guard variant the ladder is no longer the worst rotation
  rise (was +50.0 percent); the worst is +30.7 percent in the case part B already reports.

### 6.2 An infeasible re-plan waits (Sol S3)

Step 5's last sentence "Whatever remains is kept." becomes:

> If `planned(merged)` still exceeds `allowed`, return null: the re-plan waits for a later wrap. The stored program
> keeps running, and the phase and recipe are unchanged.

With deferral, the next wrap's basis is a fuller week. A runner who keeps a sparse week stays on the stored program:
after the ladder, that is the level-12 days on A16 under the run cap. Part B's 1.5 "A week to measure" stays.

Tests to add to part B's 5.1:
- `a sparse ladder exit waits`:
  - the six-day ladder exit fixture, with the week holding one 1,800 s run;
  - basis = min(5,400, 1,800) = 1,800, allowed 2,160;
  - the floors leave 4 × 600 + 1,200 + 1,800 = 5,400 > 2,160, so the result is null.
- `a kept day can make the re-plan wait`:
  - the case 30 base → half re-plan with D kept to plan at a block whose seconds exceed the allowance alone;
  - the result is null.
- `the re-plan runs at the first wrap whose week fits`:
  - the sparse fixture with a week of three 1,800 s runs;
  - basis 5,400, allowed 6,480;
  - re-planned as `the ladder exit plans base within a fifth…`, planned ≤ 6,480.

Simulation (`replan_newladder.py sparse`, 35 percent of sessions skipped at random, 2,880 plans):

| | Re-plans | Deferred wraps | Worst re-planned week vs the week run |
|---|---|---|---|
| With deferral | 3,470 | 1,249 | +20.0% |
| Without deferral | 3,486 | — | +308.8% (marathon, New, 4 days, 60 min) |

No New runner is left in the ladder phase after 104 rotations.

### 6.3 Ask plans

`replan` returns unless `plan.autoApply`. Ask plans keep their phase until PR 7's offer, which owns the re-plan
copy and the accept action. With §4.3 a ladder on Ask never reaches LadderDone without that offer anyway.

## 7. Derived ladder length (DateTooClose)

- **Signature.** `weeksNeeded(goal, start, ladderWeeks: Int?)`. LADDER contributes `ladderWeeks`; the other phases
  keep `minWeeks` (6, 8, 8, 8). `RunPhase.LADDER`'s `minWeeks` goes.
- **Formula.** `ladderWeeks(level, ladderSessions)` = `ceil(EASY_PER_LEVEL × (TOP − level + 1) / ladderSessions)`.
  `ladderSessions` is the number of draft days whose session is LADDER (≤ 3 in Run and Both plans). The KDoc calls
  it "an all-Easy estimate at one rotation a week; ratings, the cap, the guard and drops only lengthen it".
- **Zero ladder sessions** → `null`: the plan cannot climb a ladder it does not run. With a date, that reports
  DateTooClose. It happens in Both on 1 separate day with lifting first, and in Both together at 30 minutes when
  `select` drops the 25-minute ladder. PR 7's setup should offer longer sessions or separate days there.
- **Callers:**
  - `RunLayout.runReasons` (`RunLayout.kt:163`) gets the count from the draft's `sessions` and the start level.
  - `BothLayout.kt:19` (1 day, separate, lifting first) passes 0.
  - `BothLayout.kt:103` counts LADDER in `sessions`.
- **Numbers:**
  - At 3 ladder sessions from level 1: 8, so `weeksNeeded(MARATHON, LADDER, 8) = 30`, and case 30's `TODAY + 56`
    still reports DateTooClose.
  - FIRST_5K: 2 sessions → 12; 1 → 24; from level 8 at 3 → ceil(10 / 3) = 4; 0 → DateTooClose for any date.

Where "8 weeks" lives today:
- `RunPhase.LADDER(8)`;
- RunPhaseTest's `weeksNeeded` assertions;
- §11.2 case 30's "eight weeks out";
- `wizard-design-2026-11-08.md` lines 327, 580 and 1010;
- audit R40;
- `fix/spec-pr4bii.md` Q1.

## 8. Both plans: at most 3 ladder days, spread by rotation gaps

- **Rule.** In the LADDER phase of a together plan, `k = min(trainingLiftDays, 3)` lift days carry the ladder. The
  chosen set is ranked by three keys, in order:
  1. the largest smallest cyclic gap between chosen days, in rotation positions, with the Rest entry counted as a
     position;
  2. then the fewest pairs at that smallest gap;
  3. then the earliest set in day order.

  The others carry no cardio.
- **Lift rotations** (`DayLayout.kt:97–104`: a Rest entry after the third training day at 4 or more days). Every
  case is pinned in `PlannerBothTest`:

  | Lift days | Rotation | Ladder days | Gaps | Why |
  |---|---|---|---|---|
  | 3 | A B C | **A, B, C** | 1, 1, 1 | Only choice |
  | 4 | A B C D(Rest) E | **A, C, E** | 2, 2, 1 | Every set's smallest gap is 1. A, C, E and B, C, E have one gap-1 pair, against two for the others; earliest wins |
  | 5 | A B C D(Rest) E F | **A, C, E** | 2, 2, 2 | The only set with smallest gap 2 and no pair below it |
  | 6 | A B C D(Rest) E F G | **A, C, E** | 2, 2, 3 | Smallest gap 2; ties broken by earliest |
- **Reserve.** `bothDraft` still deducts level 1's 1,500 s from the single lift budget on every lift day. That
  leaves 25 minutes spare on the ladder-free days. Accepted: one lift draft serves every day, and part B's base
  re-plan gives every lift day a run (part B 1.3.1), which uses it.
- **Exposure.** With A, C, E, Both runners run every other rotation entry; Run plans at 3 days put A, B, C together
  before the Rest. Calendar spacing is the runner's.

## 9. Start level from running time only

- **Anchor.** `ladderStart(runningSeconds: Int?, injury)` picks the highest level whose `runningSeconds` fits.
  `runningSeconds` comes from a new `KnownLoads.longestRunning: Map<CardioFamily, Int>`: the most WORK seconds in one
  family session in the 30 days before, RECOVERY excluded. It is filled wherever `longestRecent` is (part B's re-plan
  `known`, PR 7's creation). The fallback is `run.seed()`'s seconds, since the typed longest run is running.
- **Run cap anchor unchanged.** It keeps WORK + RECOVERY.
- **Starts:** 59 or 60 s → 1, 900 → 5, 1,200 → 8, 1,799 → 11; a past injury subtracts one.
- **Recovery-heavy history.** A logged level-1 session (600 s WORK, 900 s RECOVERY) has `longestRecent` 1,500 s but
  `longestRunning` 600, so it starts at level 1. Reading the cap anchor would have started level 10.
- **Ladder gate at creation.** No ladder interval session reaches 1,800 s on foot (the most is 27:00), so the
  table's own sessions cannot pass the 30-minute gate. A runner's own long run/walk outside the ladder could. PR 7
  should read `longestRunning` for the LADDER gate at creation; part B already keeps the log out of the ladder exit.

## 10. Test expectations

Every changed line is commented `// ladder 2026-11-17: <reason>`.

### 10.1 Pure progression, `ProgressionLadderTest` (case 34, cap-free unless named)

The `finish` helper gains `credits` and `ladder: LadderContext? = null`.

Revision 1's re-pins stand:
- **Table and bounds:** `ladder(1)` 10 × (60, 90); `ladder(7)` 4 × (270, 90); `ladder(10)` 2 × (720, 90);
  `ladder(12)` 1,800 s.
- **Climb:** twenty-four Easy finishes climb, one `Repeat` with a credit then `LevelTo` per level, then
  `LadderDone`.
- **Credits:** Good, no word and unfinished keep a credit.
- **Drops:** Hard and Pain clear it, at level 1 as a `Repeat`.
- **Trail run:** the level change needs `credits = 1`.

New, dated (`LadderContext(d, busy)`):
- `the cap counts three Easy finishes in seven days`: Easy on days d, d+1, d+2 → counted [d, d+1, d+2]; the fourth
  on d+3 → `Repeat`, rule unchanged; the fifth on d+7 counts (window d+1..d+7 holds 2).
- `a full cap keeps the credit`: credit 1, cap full → `Repeat` with credit 1 and no level change.
- `the cap window is local days`: dates d−6..d count, d−7 does not; the window is computed from local dates, not
  hours (the judge test below covers the zone).
- `the guard holds a second level-up within a week for a busy runner`: credit 1, `ladderRaised = d − 3`,
  `busy = true` → `Repeat`; `busy = false` → `LevelTo`; `ladderRaised = d − 7` with `busy = true` → `LevelTo`.
- `the guard holds LadderDone`: level 12, credit 1, `ladderRaised = d − 3`, `busy = true` → `Repeat`, still level 12.
  On d + 4 → `LadderDone`.
- `a backdated Easy is a Repeat`: `CardioRule(2, credits = 1, ladderCounted = [10, 11, 12], ladderRaised = 11)`, an
  Easy dated 9 → `Repeat`, rule unchanged and dates still sorted. A Hard dated 9 → `CardioRule(1)`, dates kept.
- `busy counts any seven days of the last fourteen` (in `CardioJudgeTest`, which computes it): runs on d − 13,
  d − 12, d − 11, d − 10 and d → busy on d; runs on d − 11, d − 9, d − 7, d − 4, d − 2, d (Mon/Wed/Fri) → not busy; every other day from d − 13 is busy (d − 13..d − 7 holds four; corrected in the
  build spec).
- `a held level-up does not use a cap slot`: after a guard hold `ladderCounted` is unchanged.
- `a level-up records its date`: `LevelTo` writes `ladderRaised = d` and appends d.
- `a drop keeps the dates`: Hard at level 5 with dates → `CardioRule(4)` with the same `ladderCounted` and
  `ladderRaised`, credit 0.
- `LadderDone clears the ladder state`: `CardioRule(null)` with no dates.
- `rule bounds`: `CardioRule(3, ladderCounted = listOf(1, 2, 3, 4))` and unsorted lists reject; dates without a
  level reject.

`ProgressionLadderCalendarTest` pins the counterexamples (computing `busy` with the same helper the judge uses).
Each runs through `cardioAfterSession` with dated contexts and asserts that every pair of adjacent 7-day WORK sums
with equal run counts rises at most 20 percent:
- `daily running with one Hard rises at most a fifth` (Sol R1): daily from level 1, Sunday start, session 11 Hard
  (worst +12.50%).
- `three runs a calendar week on drifting days rise at most a fifth` (Sol R2): days 4, 5, 6, 9, 10, 12, 14, 18, 20,
  22, 24, 27, 29, 30, 31, then Mon/Wed/Fri, all Easy (worst +19.15%; +22.98% under Revision 2's guard).
- `four runs a week on fixed weekdays`: Mon–Thu, all Easy (worst +12.5%).
- `a composition table` asserting `bound_equal.py`'s worst patterns: the 3-run pattern 111110 from level 9 with a
  credit gives +19.15%, the largest a 3-run pair can reach. This is a pure arithmetic test of the table and rule.

### 10.2 Judge and repository (data)

- `CardioJudgeTest > a ladder day always gets a ladder context`: a ladder day judged with `plan` passes
  `LadderContext(localStart, busy)`, with `busy` from the 8 spans of §2.3. A Start at 23:30 local time on day d, completed after midnight, counts on d.
- `RunFinishTest` (part A's), amended:
  - `two ladder Easy finishes move every ladder day up one level`: the first writes `CardioRule(1, credits = 1,
    ladderCounted = [d])` to A, B and C with blocks unchanged; the second, on d + 2, `CardioRule(2, ladderCounted =
    [d, d+2], ladderRaised = d+2)` and level-2 blocks.
  - `a fourth Easy in a week repeats`: four ladder Finishes on four consecutive days; the fourth writes nothing.
  - `an unrated ladder finish repeats until a rating replays it`: the replay to Easy writes the credit and date on
    every day.
  - `a late rating cannot add a credit`: Finish A (no word, day 0), then B, C, A Easy (days 1–3), then re-rate the
    first A session Easy; nothing changes, since A is not its day's newest and B's write broke the records.
  - `Undo restores credit and date on every ladder day`: Undo the second Finish; all three days return to the first
    Finish's rule; the next Easy on the same day counts again.
  - `Hard drops every ladder day one level`: unchanged expectation plus the dates kept.
  - `the last ladder level leaves the ladder on every day`: the fixture starts at `CardioRule(12, credits = 1, …)`.
  - `on Ask a drop clears the credit and keeps the level`: Ask, `CardioRule(5, credits = 1)` on A, B, C, Hard; all
    three become `CardioRule(5, credits = 0)` and keep level-5 blocks.
  - `on Ask LadderDone waits`: Ask, level 12 with a credit, Easy; nothing written; the stored phase stays LADDER at
    the wrap.
  - `a kept ladder day follows the runner`: A, B, C at level 1, B kept; Easy on A day 0, C day 1, A day 2 → B's
    rule equals A's (level 2, credit 1, dates [0, 1, 2]) and B's block is still level 1. Un-keep B → B's block is
    level 2. Easy on B day 3 → `Repeat` (cap), nothing written; A and C unchanged.
  - `LadderDone clears a kept day's level`: A, B, C at level 12 with a credit, B kept, Easy on A → B is
    `CardioRule(null)` with its kept block; part B's gate opens.
  - `a partial ladder Undo restores nothing on the ladder`: two Finishes; B kept and re-blocked by hand between them
    so its record no longer matches; Undo the second → no ladder day restored; the rest of that Undo proceeds.
  - `diverged ladder days are not judged`: B's rule hand-set to level 3 while A and C hold level 2 → judging A
    returns null, nothing written.
- `CardioRuleJsonTest`:
  - `{"ladderLevel":3,"credits":1,"ladderCounted":[20000,20001],"ladderRaised":20001}` round-trips;
  - `{"ladderLevel":3}` keeps its bytes;
  - more than three dates, unsorted dates, dates without a level, and `credits: 2` reject.

### 10.3 Planner

- `PlannerRunTest > case 30 marathon from no longest starts on the untrimmed ladder`: code unchanged, block meaning
  changes.
- `PlannerRunTest > readiness selects the first unmet single run gate`: a 1,200 s longest → `CardioRule(8)`, block
  4 × (300, 90); with an injury, `CardioRule(7)`.
- `PlannerRunProgressTest > case 30 …`: `repeat(24)`, `index % 2 == 0` → `Repeat`, 23 → `LadderDone`, else
  `LevelTo(index / 2 + 2)`; cap-free (no `LadderContext`), so the case's numbers stay pure.
- `RunPhaseTest`:
  - ladder start by running seconds: 59 → 1, 60 → 1, 900 → 5, 1,200 → 8, 1,799 → 11; injury 60 → 1, 1,200 → 7;
  - `ladderStart reads running seconds, not recovery`, with the 600/900 history;
  - `weeksNeeded` with `ladderWeeks` 8 → 30, 14, 0, 8, 0; `ladderWeeks(1, 2) = 12`, `(1, 1) = 24`, `(8, 3) = 4`,
    `(1, 0) = null`.
- `PlannerRunTest > a plan with no ladder sessions reports DateTooClose`: Both, 1 separate day, lifting first,
  dated.
- `PlannerBothTest > D15 …` → `D15 ladder reserves its own duration on at most three spread lift days`: reserve
  1,500 (`liftDraft(…, 2100)`); `sessions` are exactly A, C, E at six days; B, D(Rest), F and G have no cardio.
- `PlannerBothTest > the ladder spreads by rotation gaps`: three lift days → A, B, C; four → A, C, E (not A, B,
  C, which the first key alone would pick); five → A, C, E; six → A, C, E.

### 10.4 Part B

As §6.1 and §6.2: the credited level-12 fixture; `a sparse ladder exit waits`; `a kept day can make the re-plan
wait`; `the re-plan runs at the first wrap whose week fits`; `an Ask plan does not re-plan`.

### 10.5 Unchanged on purpose

These read the table through `RunWalkLadder.block`:
- `PlannerRunInvariantsTest`
- `TaperTest > case 19 ladder day does not taper`
- `CardioOpeningTest > case 19 …`
- `CardioFinishTest` (`CardioRule(3)`)
- `BackupV27Test` (level 13 rejects)

### 10.6 §11.2 rows

**Case 34** adds to Revision 1's text: "With dates, an Easy finish counts only while fewer than three counted Easy
finishes fall in the seven local days ending today and it is not dated before the newest counted one; a level-up
waits a week after the last one while any seven consecutive days of the last fourteen hold more than three runs; a
waiting finish repeats and keeps its credit." Tests: the case 34 list plus the dated tests and
`ProgressionLadderCalendarTest`.

**Case 30:** "60 s run/90 s walk ×10" and "Twenty-four Easy finishes, two per level". Its loop stays cap-free.

## 11. Someone mid-ladder when this ships (corrected)

No production path creates ladder days before PR 7:
- **Setup.** `SetupFlowStateBuilder.answers` (`app/.../ui/wizard/SetupFlowStateBuilder.kt:46`) never sets `branch`,
  so setup produces Lift answers only.
- **Recipe.** Part A relaxes `SetupRecipeJson.encode` to accept Run and Both, but nothing builds such answers.
- **Reset day.** `resetDayToTemplate` returns unless the recipe's branch is LIFT (part A `TrackerRepository.kt:658`).
- **Backup.** A backup can carry `{"ladderLevel":n}` (validated, `BackupCodec.kt:599`), but only a hand-edited file
  or a fixture has one. It decodes with no credit and no dates and keeps its stored block until its next level
  change.

No migration; no Room, backup, DataStore, SavedStateHandle or watch change.

## 12. Docs

`STRENGTH_TRACKER_SPEC.md` §11.2 rows 30 and 34 (§10.6).

`wizard-design-2026-11-08.md`:
- the Run/walk ladder row (line 450), with the §2.4 table;
- the Foundation 1 row (line 315);
- line 1010: start by running time, a 20-minute runner at level eight, derived ladder weeks;
- the case 30 and 34 rows;
- a revision entry (48, after part B's 47). It covers:
  - owner 2026-11-17: two per level, level 1 at 10 minutes, the cap;
  - the level-up guard;
  - the bound: proven +12.5 / +12.5 / +19.15 percent for windows of 1 / 2 / 3 runs; searched, not proven, above
    that (worst found +18.75 percent); frequency changes unbounded;
  - Ask behavior;
  - running-time start;
  - Both at three spread days;
  - derived weeks;
  - part B's deferral and Ask rule.

Part B's spec takes §6. The audit's R41, R42 and R46 rows are superseded in the revision, not edited.

## 13. Build order, size, open items

- **Order.** One PR after #606 (head 984c3235) is on main, with part B's §6 changes folded into 4b-ii-b (or this PR first, then part B).
- **Size.** About 9 production files:
  - `RunWalkLadder.kt`, `CardioRule.kt`, `Progression.kt`, `CardioContext`'s file;
  - `RunPhase.kt`, `RunLayout.kt`, `BothLayout.kt`;
  - `CardioJudge.kt`, `TrackerRepository.kt` (sibling writes, Leave Keep, ladder Undo, fail-closed check);
  - `KnownLoads` for `longestRunning`.

  Plus about 10 test files. `:domain` stays pure Kotlin.
- **For PR 7's schema bump.** An older app imports backups strictly: `CardioRule.init` rejects `credits` on a ladder
  rule, so a backup carrying ladder credits fails the whole import there. This is moot until PR 7 creates ladder
  plans. The new keys are ignored (`ignoreUnknownKeys`).
- **Unresolved** (the design's last round; none blocks a build spec):
  - **Windows of 4 to 7 runs.** Pairs of adjacent windows holding 4 to 7 runs each have no proof. With the guard,
    adversarial search and sampling found no more than the proven 3-run bound, +19.15 percent. The cap alone is
    proven to reach +22.98 to +28.42 percent there. No proof is known. A heuristic suggests level-up rules alone would need about one level-up per 13 days
    (§3.3).
  - **Sampled parts.** Fixed-weekday ratings beyond single events and Hard pairs; triple and later drops; drifting
    weekdays; and the adversarial search. All of these are sampled, not enumerated. The exact proof (§3.1) has no
    such gap for windows of up to 3 runs.
  - **Frequency.** Weeks with more runs than the week before are outside any ladder rule (§3.4).
  - **Outside the model.** Blocks kept to plan, leaving Keep and backdated entries (§3.4) are user-chosen dose
    changes or out-of-order history. PR 7's copy should say what Keep and leaving Keep do to the dose.
  - **The plan does not hold the theorem's condition.** Following a rotation daily puts 5 to 7 ladder runs in 7
    days (§3.1).
  - **Constants.** The guard's 7 days, 3 runs and 14 days are practice, sized by simulation. No outcome study backs
    the cap or the guard.
  - **Fail closed.** A ladder whose days were forked outside the app (a hand-edited backup) stalls rather than
    progresses. PR 7 may want a repair action.
