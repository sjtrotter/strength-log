# Dated goals that start on the run/walk ladder: design

Base: origin/main 0de466da (#622). Read: `RunPhase.kt`, `RunWalkLadder.kt`, `RunLayout.kt`, `BothLayout.kt`,
`RunSessions.kt`, `RunAnswers.kt`, `RunReplan.kt`, `Taper.kt`, `CardioHistory.kt`, `SlotSeeding.kt` (`KnownLoads`),
`Progression.cardioAtStart`, `TrackerRepository.afterDay/nextPhase`, `PlanRepository.planned`,
`SetupFlowStateBuilder` (why rows), `PlanScreenBuilder`/`PlanScreen` (phase row), `strings.xml`; the ladder design and
research (`docs/briefs/run-ladder-2026-11-17/`), wizard design §3.1.2 and revisions 47 to 49; the P8 playtest
recipes (`playtest-runs/r5-p8/*/backup.json`). No code was run; the arithmetic below is by hand from the code.

Revised after the four-seat council check (§6) and the owner's decision (§5). The rule in §4.1 is the owner's, not
the first draft's: Some runners only, and the short gate reads continuous running.

## 0. Summary

- **Today, P8** (Some, typed longest 22:00, 1.5-mile test on Dec 12, set up Sunday Nov 8, 34 days out) starts at
  ladder level 8 (4 × 5 min run, 90 s walks). The ladder needs 10 counted Easy runs, at least 4 weeks, so no
  DateTooClose. Even if every run counts, the ladder ends on about Nov 30. Test work then starts around Dec 7,
  inside the 10-day taper, with one 2-rep interval session at most. Any Good, missed or busy run leaves him on the
  ladder on test day. Ladder days never taper, yet the Plan screen says "Taper" from Dec 2.
- **Root cause.** Two rules from revision 49 interact. Field tests and middle distance got a ladder-only path, and
  its exit gate is the 5K gate (30 min or 5 km). But revision 49 §1.2 itself says that gate "is a 5K-readiness
  gate, not a measure of a middle-distance or test racer". On a ladder-only path, `weeksNeeded` also counts zero
  weeks for the event's own plan, so DateTooClose can never warn that the test work has been squeezed out.
- **The rule (owner decision, §5):**
  1. On Fitness test and Middle distance, a runner who is not New skips the ladder once they have run 20 minutes or
     2 miles (3,219 m) without stopping. The log supplies the longest single WORK set; without a log, the typed
     longest supplies it, and the setup label asks for a run without walk breaks. New runners keep 30 min / 5 km.
     Every other goal keeps 30 min / 5 km. The 5K gate still clears every runner it clears today.
  2. On a ladder-only path, DateTooClose counts the ladder weeks plus a 4-week event block.
  3. Ladder plans stop showing "Taper", and Your plan says how long the ladder takes.
  4. No ease-in: a runner who skips the ladder gets the test or race plan that runners past 30 minutes get today.

  P8 then skips the ladder and gets 5 weeks of test training under the run cap, provided his 22 minutes were
  nonstop. A New tester keeps the ladder and is told plainly when the date is too close.
- **The evidence does not decide between 20 and 30 minutes.** Both are practice (§2). The change's gain is fit to
  the event and the calendar, not a shown gain in test scores or injuries.
- **Pinned §11.2 cases touched: none.** No pinned number moves (§4.4).

## 1. What happens today

### 1.1 The code path

- **Entry.** `RunAnswers.phases` gives every on-foot goal a ladder unless the runner is Racing. Middle distance,
  Fitness test and Just cardio get `[LADDER]` alone.
  - `startPhase` picks the first unmet gate. `ladderMet` asks for running-only seconds ≥ 1,800 or meters ≥ 5,000:
    the logged WORK when the family has a log, otherwise the typed longest.
  - The logged WORK is summed over one session's WORK sets (`CardioHistory.longestRunning`), so a run/walk session
    counts its run bouts together. Ladder level 8's four 300-second bouts read as 1,200 seconds.
  - On a ladder-only path whose gate is met, the phase is null and the goal's own plan runs.
  - With no anchor at all, New starts at the ladder and Some starts past it.
- **When the gate is read.** At setup (`PlanRepository.planned` reads the family's log). A rotation wrap re-plans
  only after `LadderDone` has cleared every ladder rule (`RunReplan.next`), and a ladder-only re-plan then returns
  the goal's own plan without reading the gate (`startPhase` with a BASE floor). So on an existing plan the ladder
  ends by finishing it, or by a new setup.
- **Start level.** `ladderStart` takes the highest level whose WORK seconds the anchor covers (minus one for a
  past injury).
- **Length.** `ladderWeeks(level, n) = ceil(2 × (12 − level + 1) / n)`, where n is the number of LADDER entries
  per rotation, at most 3. It is an all-Easy estimate.
  - `weeksNeeded` returns `ladderWeeks` alone for a ladder-only goal. The event's own plan counts 0 weeks.
  - For a phased goal it adds BASE 6, HALF_BUILD 8 and so on, up to the event phase.
  - `runReasons` adds `DateTooClose` when the date is closer than `7 × weeksNeeded` days. The copy is "The date
    is closer than this build takes."
- **Progress.**
  - The ladder level advances by two counted Easy runs.
  - At most 3 counted runs fall in any 7 local days, and a busy-fortnight guard holds level-ups.
  - Good, unrated or unfinished runs repeat; Hard and Pain drop a level.
  - After level 12's second Easy run, `LadderDone` clears every ladder rule. The next rotation wrap re-plans
    (`TrackerRepository.afterDay` → `RunReplan.next`). That re-plan still has to pass the 20-percent weekly guard,
    or it waits for a later wrap.
- **Taper.**
  - `Progression.cardioAtStart` returns a ladder day unchanged ("the ladder is its own guardrail"). Pinned case 19
    holds this as `TaperTest > case 19 ladder day does not taper`.
  - Non-ladder days scale by `Taper.factor`: ×0.5 in the last 10 days for tests and events up to 10K; ×0.6, then
    ×0.45, in the last 14 days for the half.
  - `PlanScreenBuilder` sets `taper = Taper.factor(goal, today) != null` whatever the phase, so the phase row reads
    "Taper" over ladder days that do not taper.

### 1.2 P8: police officer, 1.5-mile test, 34 days out

P8's stored recipe (r5-p8 backups) has the following answers:

- days 4, minutes 45;
- Fitness test, Some, 1.5 mile, target 779 s (12:59);
- recent 2,414 m in 845 s (14:05);
- typed longest 1,320 s (22:00), under today's label "Longest run in the past 30 days";
- set up Sunday Nov 8 (epoch day 20765), test Saturday Dec 12 (20799), 34 days apart.

- **Gate.** 1,320 < 1,800 and there are no meters, so the ladder applies. The level is the highest whose WORK time
  is at most 1,320: level 8 (1,200 s; level 9 needs 1,350). The plan is A, B, C as LADDER (`minOf(days, 3)`) and
  D Rest, which matches the stored plan.
- **Weeks.** `ladderWeeks(8, 3) = ceil(2 × 5 / 3) = 4`, so `weeksNeeded` is 4 weeks, or 28 days. Since 34 ≥ 28,
  there is **no DateTooClose**.
- **Your plan** shows the ladder row ("Run/walk ladder: two Easy runs finish each level, and up to three a week
  count."). Nothing says the test work may not happen. The Your event step told him "Interval reps aim slightly
  faster than the target pace. A date tapers the last ten days."; neither is true of a ladder plan.
- **Best case (every run rated Easy, Mon/Wed/Fri from Nov 9).**
  - The counted runs fall on Nov 9, 11, 13, 16, 18, 20, 23, 25, 27 and 30. `LadderDone` comes on Mon Nov 30, the
    first day of the 4th rotation.
  - B (Dec 2) and C (Dec 4) then run level 12's 30-minute block with a null rule. They are now inside the taper
    window (≤ 10 days out from Dec 2), so they open at 15 minutes.
  - The wrap after C re-plans to the test plan from about Dec 7, provided the weekly guard passes. The plan is A
    Easy, B Test intervals, C Easy, D Easy, E Rest.
  - The single interval session he can reach before Dec 12 is tapered to ceil(4 × 0.5) = 2 × 400 m at 123 s.
- **Realistic case.** P8's own playtest notes have him missing sessions to overtime. One Good rating, a missed
  run or a fourth run in a week holds the ladder. He reaches the test on run/walk intervals, with no test-pace
  running, no taper (ladder days are exempt) and a Plan screen saying "Taper".
- **What he can already do.** He recently ran the test distance in 14:05, and he reports 22 minutes. The ladder
  breaks that into 5-minute bouts. Revision 49 §1.4 already concedes this trade of continuity for walk breaks has
  "no claimed injury or adherence benefit".

### 1.3 First 5K, 8 weeks out, ladder level 1

- **Setup.** New with no anchor, or Some with under 600 s of running, gives level 1. With 3 days, there are 3
  LADDER entries.
- **Weeks.** `ladderWeeks(1, 3) = ceil(24 / 3) = 8`. First 5K's event phase is LADDER, so `weeksNeeded = 8` = 56
  days. With the date at 56 days out, there is **no DateTooClose**: the margin is zero.
- **Calendar.** The 24 counted runs at Mon/Wed/Fri end on day 53, three days before the race, and only if every
  run is rated Easy and none is missed.
- **Taper.** Ladder days do not taper, while the Plan screen says "Taper" from day 46. For this goal the ladder
  is the event build: Couch-to-5K's last week is three 30-minute runs, then the race, with no taper (practice).
  So the behavior is defensible. The only problems are the label and the silent lack of slack.

### 1.4 Half marathon, 12 weeks out, ladder level 6

- **Setup.** A runner with 1,000 to 1,079 running seconds starts at level 6. With 4 days, there are 3 LADDER
  entries.
- **Weeks.** `ladderWeeks(6, 3) = ceil(14 / 3) = 5`, so `weeksNeeded = 5 + 6 (BASE) + 8 (HALF_BUILD) = 19` weeks,
  more than 12. **DateTooClose fires**, and Your plan shows "The date is closer than this build takes." plus the
  ladder row.
- **Calendar.**
  - The ladder ends at the earliest on day 30 (14 counted runs).
  - BASE then opens with the 30-minute level-12 run as its anchor. The long run opens at the capped 1,980 s and
    grows about 5 percent a cycle, reaching roughly 46 to 50 minutes by race day.
  - The BASE gate (60 min or 10 km) is not met, so HALF_BUILD never opens.
- **Taper.** It **does land**, because these days are BASE days: ×0.6 at 14 to 8 days out and ×0.45 in the last
  week.
- **Verdict.** For a half marathon the ladder and the warning are both honest. Five weeks of bridging is small
  next to the 14 the later phases need.

### 1.5 What is actually wrong

1. **The gate.** On the two short-event ladder-only goals (Fitness test, Middle distance), the entry gate asks for
   30 minutes, longer than the event itself. The goal's own plan opens no session longer than 110 percent of what
   the runner has done (the run cap), and its easy floor is 20 minutes (`EASY_SECONDS`).
2. **The weeks.** `weeksNeeded` gives a ladder-only event zero weeks of its own training. DateTooClose therefore
   stays silent while the ladder fills the whole calendar.
3. **The labels.** The Plan screen labels ladder days "Taper", and Your plan never says how long the ladder takes.

## 2. Evidence

Grades follow the ladder research: strong, moderate, weak, practice. Rows were checked by the council on
2026-11-19 (§6). *Verified* means a seat read the abstract or a primary record; *carried* means taken from
`run-ladder-2026-11-17/research.md` or the 2026-11-08 evidence notes without a fresh read.

| # | Finding | Source | Grade, status |
|---|---|---|---|
| E1 | "Novice" in the trials behind the ladder is defined by history, not capacity: GRONORUN took people who "had not been running on a regular basis in the previous twelve months". Videbæk's meta-analysis says "No exact definition of each category was made"; its included studies varied (Buist 2008 and Nielsen used the 12-month criterion, Bovens "little or no running experience", Buist 2010 mixed in 155 recreational runners). Novice injury rate 17.8 vs 7.7 per 1,000 h for recreational runners | [Buist 2007 protocol, PMC1821023](https://pmc.ncbi.nlm.nih.gov/articles/PMC1821023/); [Videbæk 2015, PMC4473093](https://pmc.ncbi.nlm.nih.gov/articles/PMC4473093/) | Verified. Moderate for the incidence comparison. A person who can run 20 minutes but has not run regularly in a year is still a novice in this sense |
| E2 | Run/walk lowered muscle pain and fatigue with similar finish times in 42 randomized recreational marathoners; injury was not measured. No relevant trial of walk breaks and injury was identified | Hottenrott 2016, JSAMS 19:64 (PMID 25467199) | Verified. Moderate for comfort in marathoners; no evidence either way on injury |
| E3 | Published novice programs graduate at about 20 to 25 min continuous (Start to Run: stated goal "learn to run continuously for at least three kilometers"; the protocol's goal "to be able to run for 20 min without breaks after six weeks"; the Canadian Armed Forces copy goes from 10-minute runs in week 5 to a week-6 test of 3 to 4 km continuous "(20 to 25 minutes)"), 30 min (Couch-to-5K, whose first 20-minute continuous run comes in week 5), and 30 min (GRONORUN's protocol tables end on a 30-minute run, toward the Groningen four-mile event; one seat read 30 to 40 min) | Ooms 2013, PMC3735486; [CAF Start to Run](https://www.canada.ca/en/department-national-defence/services/benefits-military/health-support/staying-healthy-active/injury-prevention/start-to-run-programs.html); Relph 2023 Table 1; Buist 2007 | Verified. Practice. Start to Run's 3 km is shorter than the 2-mile arm |
| E4 | Single-session spikes above the 30-day longest raised injury hazard (small spikes HRR 1.64, 1.31 to 2.05; moderate 1.52; large 2.28); week-to-week ratios showed no relationship. The cohort was experienced (median 9.5 years, IQR 4 to 20; novices not excluded), and the exposure was session distance only; intensity was not analyzed. The authors warn that "progressions up to 10% are not necessarily safe either" | [Frandsen 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC12421110/) | Verified. Moderate for the distance association. This is the run cap. It bounds session length, not interval intensity |
| E5 | A 20 to 60 percent weekly rise went with more injuries at day 21 but not at days 56 or 98 (Damsted 2019); above 30 percent vs under 10 percent gave HR 1.59 (0.96 to 2.66) for distance-related injuries, with no overall difference (Nielsen 2014) | Damsted 2019 (PMID 30526231); Nielsen 2014 (PMID 25155475) | Verified. Weak. This bound is why the ladder has two Easy runs per level |
| E6 | Progressing intensity rather than volume gave a similar injury proportion (RD −14.0 percent, −36.9 to 8.9) in runners who had run 1 to 3 times a week for six months, after 8 weeks of common preconditioning | Ramskov 2018 (Run Clever, PMC5841490) | Verified. Weak. Closer to a P8-type runner than to a novice, but not a test of starting intervals right at 20 minutes |
| E7 | In Army basic training, slower 3.2 km run times and lower peak VO2 were risk factors for time-loss injury. The marker is speed in recruits who all completed a timed 2-mile run, not inability to run continuously | Knapik et al. 2001, MSSE 33(6):946 (PMID 11404660) | Verified abstract. Moderate for the association |
| E8 | Low-fit recruits who preconditioned before basic training had less attrition and "tended to have lower injury risk"; non-randomized (158 vs 105); preconditioned men still had 1.5 (1.0 to 2.2) times the risk of normal-fit men. The preconditioning was general training until an entry test was passed | [Knapik 2006, Mil Med 171:45, PMID 16532873](https://academic.oup.com/milmed/article-abstract/171/1/45/4577767) | Verified abstract. Weak. Supports a graded on-ramp for low-fit people in general, not this ladder |
| E9 | Army PRT gave 1.5 to 1.8 times fewer injuries than traditional training, with similar or higher fitness-test scores. PRT is a package (less running mileage, exercise variety, gradual progression); the effect of intervals as such is not isolated | Knapik et al. 2009, JSCR 23:1353 (PMID 19528858) | Verified abstract. Moderate for the package; indirect for intervals |
| E10 | Navy recruits (n = 1,703, 7 weeks): 1.5-mile time improved 15.6 percent in the Poor to Fair group, 7.3 percent in Good, 2.9 percent in Excellent; the gain was "not related to movement mileage or high-intensity run mileage". One observational cohort, division-level exposure, no control; part of the low-fit gain may be regression to the mean | Trank et al. 2001, MSSE 33(6):1033 (PMID 11404670, abstract reachable; DTIC ADA400158 not) | Verified abstract. Weak for any inference about training mode |
| E11 | Military recruits (36: 28 trained, 8 controls): five sessions in two weeks improved 3,000 m by 4 to 8 percent over controls, whether test races, 4 × 4 min intervals or continuous running; the slowest improved most (ρ = −0.65). The recruits could already run 3,000 m | Riiser, Ripe, Aadland 2015, J Sports Med Phys Fitness 55(12):1452-8 (PMID 25286887) | Verified abstract. Weak (small subgroups, no equivalence test, no injury outcome) |
| E12 | Air Force ROTC cadets (n = 27, randomized): HIIT/SIT 2 or 3 times a week and continuous training all improved 1.5-mile time by 6 weeks, with no further gain by 10 | [Dahle 2016, MS thesis, Utah State](https://digitalcommons.usu.edu/gradreports/806) | Verified record. Weak (thesis, small) |
| E13 | Review for military 1.5- and 2-mile tests: combine strength training, HIIT and distance training | [Gleason et al. 2014, J Trainology 3:11](https://www.jstage.jst.go.jp/article/trainology/3/1/3_11/_article/-char/en) | Verified abstract. Practice/review |
| E14 | Four weeks or less of HIIT improves VO2max against no-training controls. (Wen's advantage over continuous training needs long, high-volume intervals of 15 minutes or more over 4 to 12 weeks; the test plan's 4 × 400 m, about 8 minutes of work, does not meet that, so the row is cited only for the control comparison) | Wen 2019, JSAMS 22:941 (PMID 30733142) | Verified. Moderate |
| E15 | Field-test plans: 1 interval, 1 tempo, 1 to 2 easy; practice test 2 to 3 weeks out; lighter final week. The report's "moderate" leaned on DTIC ADA523652, which the report itself flags as unverified; no outcome study tests this layout or timing | wizard evidence report §Fitness tests | Carried. Practice |
| E16 | Taper: cut volume 41 to 60 percent and keep intensity and frequency, over about 8 to 14 days (other lengths also worked in Wang) | Bosquet 2007 (PMID 17762369); Wang 2023 (PMID 37163550) | Verified. Weak for these users (trained athletes) |
| E17 | Police recruits: lower 20 m shuttle-run (MSFT) and 30-15 IFT scores went with more injuries (rs −0.292 and −0.315; 219 recruits, retrospective; Orr 2020). The 2.4 km run was **not** associated with injury in 243 New Zealand trainees (injured 610.6 vs uninjured 604.5 s, p = 0.373; Tomes 2020). The first draft's second link was a 2023 normative-data abstract (Campbell et al.) with no injury data | [Orr et al. 2020, IJES 13(4):1052](https://research.bond.edu.au/en/publications/the-relationship-between-aerobic-test-performance-and-injuries-in/); Tomes, Schram, Pope, Orr 2020, BMC Sports Sci Med Rehabil 12:39, [DOI 10.1186/s13102-020-00188-7](https://doi.org/10.1186/s13102-020-00188-7) | Verified. Weak, mixed. Not used as support for the gate |
| E18 | In the NLstart2run cohort, "no previous running experience" was an independent injury risk factor alongside age, BMI and prior complaints | Kluitenberg et al. 2015, Scand J Med Sci Sports 25:e515, DOI 10.1111/sms.12346 | Verified abstract. Observational. **Direction: for keeping New runners on the ladder**; against reading capacity as experience |
| E19 | In 1,696 Start to Run novices, higher perceived intensity in the previous week went with more injury (HR 1.28, 1.18 to 1.40, as tabulated by Fredette); lower weekly volume did too. The authors call the pattern "contrary to our expectations" and advise against using it as a guideline | Kluitenberg et al. 2016, JSAMS 19:642, DOI 10.1016/j.jsams.2015.09.006 | Verified abstract. Weak. **Direction: against early hard work for novices**; the only novice intensity evidence found |
| E20 | A 13-week graded program did not reduce injuries against an 8-week one in 532 novices (20.8 vs 20.3 percent, p = .90) | Buist et al. 2008, Am J Sports Med, DOI 10.1177/0363546507307505 | Verified abstract. Moderate (RCT). **Direction: a longer on-ramp is not shown safer** |
| E21 | Four extra weeks of walking and hopping before novice running did not reduce injuries (15.2 vs 16.8 percent, p = .69) | Bredeweg et al. 2012, BJSM, DOI 10.1136/bjsports-2012-091397 | Verified abstract. Moderate (RCT). **Direction: extra preparation time is not shown protective**; not a run/walk vs continuous comparison |
| E22 | Across prospective studies, associations between injury and training duration, distance, frequency, intensity and their changes were conflicting | Fredette et al. 2022, J Athl Train 57:650, DOI 10.4085/1062-6050-0195.21 | Verified abstract. **Direction: mixed**; cautions against precise safety thresholds either way |
| E23 | 200 recreational runners over 12 weeks: interval training went with lower injury odds (OR 0.61, 0.43 to 0.88), speed training with higher (OR 1.46, 1.02 to 2.10), previous injury OR 1.88; "these associations may not be causative" | Hespanhol Junior et al. 2013, J Physiother 59:263 (PMID 24287220) | Verified abstract. Weak, observational. **Direction: mixed**; modest intervals were not harmful in non-novices, sprint-type speed work was |
| E24 | 586 recreational runners: a weekly volume rise above 10 percent combined with a pace increase gave RD +8.1 percent (−9.3 to 25.6), not significant | Ramskov et al. 2022, J Athl Train 57(6):557, DOI 10.4085/1062-6050-0165.21 | Verified abstract. Weak/null. **Direction: against**, faintly. P8's switch does both (+20 percent weekly WORK time and new interval pace) |

Also cited by seats, as context: Buist 2010 (in novices, injury history, BMI and sport without axial load predicted
injury; no measure of continuous-run capacity was tested); Knapik 2001, Mil Med 166:356 (fitness-training-unit men
were slower and more often injured even after remediation: speed, not continuity, marks the risk); Wen, Puffer and
Schmalzried 1997 (interval training associated with shin injuries in marathon trainees; retrospective, against);
Litleskare 2020 (three minor injuries, all in a near-maximal sprint group; against, small); Damsted 2019
ProjectRun21 (faster and more experienced runners trended to fewer injuries, none significant). No seat found a
study that tests a continuous-run threshold (20 vs 30 minutes, or a distance) for starting interval work, uses
continuous-run capacity as an injury predictor in new runners, or assigns 20-minute runners to test work versus
continued run/walk.

**What the evidence says for this decision:**

- **Both thresholds are practice.** 30 minutes or 5 km is Couch-to-5K's endpoint, and 20 minutes is Start to
  Run's endpoint and the test plan's own easy floor (E3). No study validates either as a gate for starting
  interval work or as an injury classifier (all four seats). Keeping 30 minutes for a 14-minute test has no more
  evidence behind it than 20. The low-fitness data in recruits (E7, E8, E17 as corrected) support a graded
  on-ramp for low-fit people in general, but none tested a run/walk ladder, and the risk marker there is speed,
  not the continuity this gate reads. Longer or extra on-ramps were not shown safer in novices (E20, E21).
- **The change's gain is fit to the event and the calendar, not a shown gain in test scores or injuries.** Small
  military studies found short-term gains in short test times from several kinds of training (E11, E12, weak), so
  P8 would likely improve on ladder level 8 too; which mode helps more is uncertain. What the change fixes is a
  product problem: a runner who reports 20+ minutes nonstop is held at 5-minute bouts for an event shorter than
  the gate, and nothing tells him or a novice what the calendar can hold. The run cap bounds session length only
  (E4). The interval dose (one session a week of 4 × 400 m) rests on weak, indirect or conflicting evidence (E6,
  E9, E22, E23), and the one novice signal leans against early hard work (E19). That dose is practice, and it is
  the same dose runners past 30 minutes already get.
- **New runners keep the ladder because history is an independent risk.** Novice is defined by history, not
  capacity (E1). No previous running experience predicts injury independently of other factors (E18), and the only
  novice intensity evidence points away from early hard work (E19). So the short gate applies only to runners who
  are not New. Walk breaks are kept for New runners as a comfort and consistency choice (E2), not as a shown
  injury benefit.

## 3. Options

### A. Keep today's rule; only tell the runner on Your plan

- **Rule.** Unchanged. Add a ladder-length row and a DateTooClose that counts event weeks.
- **For.** It is the smallest change, keeps every owner rule, and is honest.
- **Against.** P8 still spends his five weeks on 5-minute bouts. No evidence in §2 supports holding a runner who
  reports 22 minutes nonstop on walk breaks for a 14-minute test, and none supports releasing him either; it is a
  fit question. Copy cannot fix a rule that does not fit.
- **Verdict.** Necessary but not sufficient. Its copy and weeks parts are kept (owner decision 3).

### B. A short-event gate for Some runners: 20 minutes or 2 miles without stopping

- **Rule.** For FITNESS_TEST and MIDDLE_DISTANCE, a runner who is not New also clears the ladder with one
  continuous WORK bout of ≥ 1,200 s or ≥ 3,219 m: the longest single WORK set in the log, else the typed longest.
  The 5K gate still applies to everyone. Everything else stays: Racing exempt, and the ladder's exit at level 12
  for runners who do enter it.
- **Evidence (practice).**
  - The 30-minute gate is the 5K-readiness endpoint of Couch-to-5K (E3), and revision 49 §1.2 already calls it
    "not a measure of a middle-distance or test racer".
  - 20 minutes continuous is the endpoint of the shortest published novice program, Start to Run (E3). It is also
    the test plan's own easy floor (`EASY_SECONDS = 1,200`). A runner who clears it meets no session in the test
    plan longer than 110 percent of what they have done (E4, which bounds length, not intensity).
  - 2 miles (3,219 m) is the longest field test (the 2-mile), and it is longer than the longest middle-distance
    event (3,000 m). Clearing it means the runner has covered any of these events without stopping.
  - At the ladder's own 0.36 s/m ratio (5,000 m ↔ 1,800 s), 2 miles is 1,159 s, within 4 percent of the time arm.
- **Safety.**
  - New runners and runners below the gate keep the ladder, a graded on-ramp of the kind the recruit data favor
    for low-fit people in general (E7, E8; neither tested a run/walk ladder).
  - Runners at or above it get intervals: 4 × 400 m at 5 percent faster than goal pace, once a week, under the
    run cap. That is the dose runners past 30 minutes already get. E6 (weak), E9 (indirect) and E23 (mixed) do not
    show added injury from intensity at that dose; E19 and E24 lean the other way, weakly. The dose is practice.
- **Against.**
  - It amends an owner/lead rule (revision 49 §1.1, one gate for every goal). The owner decided it (§5).
  - The gate is a cliff. At 1,199 s a runner starts at level 7 and climbs to 30 minutes, about 4 weeks; at 1,200
    s they skip. The 5K gate has the same shape (level 11 at 1,799 s). Moving the exit lower would mean leaving
    the ladder at level 8's 5-minute bouts straight into 20-minute continuous runs. That is a ×4 bout jump, the
    transition the research names as untested and that Relph's drop-outs complained of (research §1, Gaps).
- **Considered and declined.** Two seats (Sol, Astra) proposed releasing capable runners from walk breaks but
  introducing faster work as a separate, gradual step. The owner chose no ease-in (decision 4): a runner past the
  short gate gets the plan runners past 30 minutes get today.
- **Verdict.** Adopted, as amended by the owner.

### C. A dated goal shortens the ladder: one Easy run per level

- **Evidence against.** At three runs a week, one run per level climbs three levels a week. From level 1, weekly
  running time goes 600 + 675 + 735 = 2,010 s → 810 + 900 + 1,000 = 2,710 s, which is +35 percent. That breaks the
  owner's about-20-percent table rule, which rests on E5 (weak). It also reverses the owner's 2026-11-17 decision
  of two Easy runs per level.
- **Other problem.** Safety would then depend on the calendar: the same runner gets a riskier ladder because the
  test is sooner.
- **Verdict.** Rejected.

### D. A dated goal starts higher on the ladder (anchor + 10 percent)

- **Rule.** `ladderStart` covers 110 percent of the anchor instead of 100 percent.
- **Evidence.** Frandsen's band (E4) allows up to +10 percent per session. For P8: 1,452 s gives level 10, 6
  sessions, about 2 weeks.
- **Against.**
  - It still puts a runner who reports 22 minutes nonstop on 12-minute bouts.
  - It changes the start for every goal, or makes the start depend on the date (the same objection as C).
  - Frandsen's cohort was experienced, and the ladder's start is deliberately conservative (revision 49 §1.3).
- **Verdict.** Rejected. It saves two weeks without fixing the fit.

### E. Keep the ladder but add test-specific sessions on a dated test

- **Rule.** Replace one ladder session with a TEST_INTERVALS or practice-test session when the date is close.
- **Evidence against.**
  - For the runners who stay on the ladder (New, or under 20 minutes nonstop), a practice test is a maximal effort
    far above a level-3 to level-7 session. That is the single-session spike E4 flags (>100 percent: HRR 2.28),
    and the novice intensity signal (E19) leans against it.
  - Which training mode improves a short test more in low-fit people is uncertain (E11, E12, weak).
  - It also adds a second progression on the same days, which breaks the ladder's weekly bound (design §3).
- **Verdict.** Rejected.

### F. Taper the ladder in the event's window

- **Evidence.** The taper evidence (E16) comes from trained athletes. Couch-to-5K and GRONORUN end straight into
  the event without a taper (practice). Ladder sessions are already ≤ 30 minutes, three times a week.
- **Against.** It would change pinned case 19's `ladder day does not taper` for no evidenced gain.
- **Verdict.** Rejected. The Plan screen label is fixed instead (§4.1 item 4).

## 4. The rule: B as amended, plus A's honesty

### 4.1 The rule

1. **The short-event ladder gate.**
   - It applies when the goal is `RunGoal.FITNESS_TEST` or `RunGoal.MIDDLE_DISTANCE` and the experience is not
     `RunExperience.NEW` (`RunAnswers.shortLadderGate`). Racing never enters the ladder, so in practice this is
     Some runners.
   - Such a runner clears the ladder when the 5K gate is met (unchanged) **or** when one continuous WORK bout is
     ≥ 1,200 s or ≥ 3,219 m.
   - **What "continuous" reads.** With a finished log in the family's 30-day window: the longest single WORK set,
     seconds and meters each their own maximum (`CardioHistory.longestBout`, a new `KnownLoads.longestBout`).
     - A session with one WORK set (an easy run, a long run, a logged past session, ladder levels 11 and 12) is one
       bout of that set's length.
     - A session with several WORK sets has several bouts; the longest counts. RECOVERY between them is a walk
       break. Two WORK sets back to back are not merged either: the second waits for a Start tap, so the log
       cannot show the runner never stopped.
     - WARMUP and COOLDOWN never count. A set's seconds are `secondsAtEnd ?: elapsedSeconds` and its meters
       `RunCap.metres`, the same readings `longestRunning` and `longestRunningMetres` use. Only finished sessions
       count, as for the other running anchors.
     - It never falls back to the session totals (`longestRecent`), which include walk breaks.
   - Without a log: the typed longest, which the setup label now asks for as a run without walk breaks for these
     runners (§4.2).
   - Every other goal, and every New runner, keeps 1,800 s or 5,000 m read exactly as today.
   - The start level, the table, two Easy runs per level, the cap, the guard and the level-12 exit are unchanged.
     `ladderStart` still reads summed WORK.
   - Practice, labelled as such:
     - 1,200 s is `EASY_SECONDS`, the event plan's easy floor, and Start to Run's 20-minute endpoint.
     - 3,219 m is `FitnessTest.TWO_MILE.metres`, the longest field test and longer than any middle-distance event.
   - Both are derived from their existing constants, not restated.
   - **The 30-minute gate does not change for anyone.** `ladderMet` is untouched, and the short gate is an extra
     way out that only short-gate runners have. Every runner who clears the ladder today still clears it.
   - **Consequence for runners already on the ladder.** The gate is read at setup only (§1.1). A ladder runner who
     sets up again clears the short gate only after logging a continuous 20-minute run. The ladder's first such
     bout is level 11's 27 minutes, so in effect ladder runners finish the ladder. Existing plans do not move.
2. **Event weeks on a ladder-only path.**
   - On a ladder-only goal, `weeksNeeded` = `ladderWeeks + 4` when the plan starts on the ladder. It stays null
     when `ladderWeeks` is null.
   - Phased goals are unchanged, and so is a ladder-only plan that starts past the ladder (no DateTooClose), the
     same as for runners past 30 minutes today (owner decision 4).
   - The 4 weeks are a scheduling allowance, not a proven minimum (practice): roughly two weeks of interval
     sessions before a 10-day taper and room for a practice test 2 to 3 weeks out (E15, E16); four weeks or less of
     HIIT already improves VO2max against no training (E14).
   - Constant: `EVENT_WEEKS_AFTER_LADDER = 4` in `RunPhase.kt`, public (the app's copy reads it), with that KDoc.
3. **Ladder length on Your plan.** `runReasons` adds `PlanReason.LadderWeeks(weeks)` when the plan starts on the
   ladder and `ladderWeeks != null`, dated or not. It is plan-level, like DateTooClose.
4. **The Plan screen.** When the stored phase is LADDER, the phase row shows "Run/walk ladder", not "Taper"
   (`PlanScreenBuilder`: `taper = run != null && runPhase != RunPhase.LADDER && Taper.factor(...) != null`).
   Ladder days stay untapered.

Code sketch:

```kotlin
// RunAnswers.kt
/** Owner, ladder-date design §5 (practice): a runner who is not New, aiming at a field test or a race up to
 *  3000 m, also leaves the ladder after one continuous run of EASY_SECONDS or the 2-mile's meters. New runners keep
 *  the 5K gate: running history is an independent injury risk (Kluitenberg 2015). Racing never enters the ladder. */
val shortLadderGate: Boolean get() = experience != RunExperience.NEW &&
    (goal == RunGoal.FITNESS_TEST || goal == RunGoal.MIDDLE_DISTANCE)

// CardioHistory.kt
/** The longest single finished WORK set within [WINDOW_MILLIS] before [asOfMillis]: seconds (secondsAtEnd, else
 *  elapsedSeconds) and meters ([RunCap.metres]) each their own maximum; null when none finished. A RECOVERY, or the
 *  Start tap before a following WORK set, breaks a bout. The short ladder gate reads it. */
fun longestBout(sessions: List<LoggedCardio>, asOfMillis: Long): RecentMaxima? = finishedWork(sessions, asOfMillis)
    .flatten().map { RecentMaxima(RunCap.metres(it), it.secondsAtEnd ?: it.elapsedSeconds) }
    .reduceOrNull { longest, next -> longest.max(next) }

// RunPhase.kt
/** The longest single WORK set when the family has a finished log, else the typed longest. Never the session
 *  totals, which include walk breaks. */
internal fun runningBout(run: RunAnswers, known: KnownLoads): RecentMaxima? =
    if (hasLog(run, known)) known.longestBout[CardioFamily.of(run.activity)] else run.seed()

internal fun shortLadderMet(run: RunAnswers, bout: RecentMaxima?): Boolean = run.shortLadderGate &&
    ((bout?.seconds ?: 0) >= EASY_SECONDS || (bout?.metres ?: 0) >= requireNotNull(FitnessTest.TWO_MILE.metres))

// startPhase gains `bout: RecentMaxima?` before atLeast; the anchored branch becomes:
path.firstOrNull { phase -> !phase.met(anchor, run.marathonBestSeconds != null, week, running, runningMetres) &&
    !(phase == RunPhase.LADDER && shortLadderMet(run, bout)) } ?: path.last().takeUnless { ladderOnly }

// weeksNeeded, first line:
if (goal.phases.isEmpty()) return if (start == RunPhase.LADDER) ladderWeeks?.plus(EVENT_WEEKS_AFTER_LADDER) else 0
```

`EASY_SECONDS` in `RunSessions.kt` becomes `internal`. `RunLayout.runDraft` and `BothLayout.bothDraft` pass
`runningBout(run, known)`. `CardioHistory.known` fills `longestBout`. `KnownLoads` is built only there and is never
stored, so no schema, backup or recipe changes.

### 4.2 Copy

All copy is American English and follows the prose-whip app-copy rules and DESIGN.md's copy direction (there is no
`docs/design/copy-style.md`).

- **`setup_flow_why_about_run`** (the Why sheet on the running-experience step):
  - Old: "Experience sets where lifting and running start. A runner whose longest recent run is under 30 minutes
    and under 5 km starts on a run/walk ladder, as does a New runner who leaves it blank; Racing runners skip it. A
    past running injury starts it one level lower. Nothing here is stored as a profile."
  - New: "Experience sets where lifting and running start. A runner whose longest recent run is under 30 minutes
    and under 5 km starts on a run/walk ladder, as does a New runner who leaves it blank; Racing runners skip it.
    For a fitness test or a race up to 3000 m, a Some runner skips it after 20 minutes or 2 miles without walk
    breaks; a New runner still needs 30 minutes or 5 km. A past running injury starts it one level lower. Nothing
    here is stored as a profile."
- **Longest-run label on Where you are**, for short-gate runners only (`run.shortLadderGate`): "Longest run without
  walk breaks in the past 30 days". Everyone else keeps "Longest run in the past 30 days", so the typed input to
  the 30-minute gate reads as it does today.
- **Ladder row, replacing `setup_flow_why_ladder`.** A plurals resource, from `PlanReason.LadderWeeks`:
  "Run/walk ladder first: at least %1$d weeks. Two Easy runs finish each level, and up to three a week count."
  ("at least" because the estimate assumes every run counts.)
- **DateTooClose on a ladder-only plan that starts on the ladder.** App-side, a new `PlanWhy` chosen when
  `draft.phase == LADDER` and the goal is Fitness test or Middle distance; the domain reason is unchanged. The week
  count comes from `EVENT_WEEKS_AFTER_LADDER`.
  - Fitness test: "After the run/walk ladder, the date leaves less than 4 weeks of test training."
  - Middle distance: "After the run/walk ladder, the date leaves less than 4 weeks of race training."
- **Other DateTooClose plans** keep "The date is closer than this build takes."

**What the worked cases show on Your plan:**

- **P8 (22 minutes nonstop):** no ladder row and no DateTooClose. RunCapped on D (§4.5 test 1) and the usual rows.
- **First 5K, 8 weeks, level 1:** "Run/walk ladder first: at least 8 weeks. …" and no DateTooClose (the margin is
  still zero, but now stated).
- **Half, 12 weeks, level 6:** "The date is closer than this build takes." plus "Run/walk ladder first: at least 5
  weeks. …"
- **New 2-mile tester, 3 days, 8 weeks out:** "After the run/walk ladder, the date leaves less than 4 weeks of test
  training." plus "Run/walk ladder first: at least 8 weeks. …"

### 4.3 What changes for the cases in §1

| Case | Today | After |
|---|---|---|
| P8 (Some, 1,320 s typed nonstop, 1.5 mi, 34 days, 4 days, 45 min) | LADDER level 8, 4 weeks minimum; test work reaches at most one tapered 2-rep session; Plan says "Taper" over ladder days | Phase null. A Easy 1,200 + strength, B Test intervals 4 × 400 m at 123 s with 3 × 120 s recoveries, C Easy 1,200 + strength, D Easy 2,400 capped to 1,440 (RunCapped D), E Rest. About 3.5 weeks of weekly intervals, then the 10-day taper (×0.5: easy runs halved, intervals ceil(4 × 0.5) = 2 reps). No DateTooClose |
| New 2-mile tester, no anchor, 3 days | LADDER level 1, `weeksNeeded` 8; no DateTooClose from 56 days | Same ladder; `weeksNeeded` 12; DateTooClose under 84 days, with the test copy |
| New 1.5-mile tester, 1,500 s typed (25 min), 3 days | LADDER level 10 | Unchanged level 10 (New keeps the 5K gate); `weeksNeeded = ceil(6/3) + 4 = 6` |
| Some 1.5-mile tester, 1,199 s typed, 3 days | LADDER level 7 (1,080 ≤ 1,199 < 1,200); `weeksNeeded` 4 | Level 7 unchanged (under the short gate); `weeksNeeded = ceil(12/3) + 4 = 8` |
| Some tester whose log is a ladder level-10 session (2 × 720 s WORK, 1,440 s summed) | LADDER level 10 | Unchanged: the longest bout is 720 s. Summed WORK would have cleared 1,200 |
| Some tester whose log holds a 1,620 s continuous run (level 11 or any 27-minute run) | LADDER level 11 | Phase null: the bout clears 1,200 |
| Some 5K-to-10K runner, 1,500 s continuous | LADDER level 10 | Unchanged (not a short-gate goal) |
| First 5K, level 1, 8 weeks | No DateTooClose; untapered ladder; "Taper" label | Same rule; ladder row "at least 8 weeks"; phase row "Run/walk ladder" |
| Half, level 6, 12 weeks | DateTooClose (19 weeks); taper lands | Unchanged; ladder row "at least 5 weeks" |
| Just cardio, 5K to 10K, half, marathon, ultra | 30 min / 5 km gate | Unchanged |

Weekly load for P8 under B: about 1,200 + 492 + 1,200 + 1,440 = 4,332 s of WORK in 4 runs, against the ladder's 3 ×
1,200 = 3,600 s in 3 runs, about +20 percent. The rise comes from his fourth weekly run, which he asked for (days 4);
no session exceeds the cap (E4). E24 found a non-significant signal for a volume rise combined with a pace increase;
this is the same switch every runner past 30 minutes makes today.

### 4.4 Pinned §11.2 cases

**None move.** Each case that touches runs or the ladder was checked:

- **Case 17 (run cap).** Not touched.
- **Case 19 (taper).** The factors, and `TaperTest > case 19 ladder day does not taper`, are unchanged. Only the
  app's phase label changes.
- **Case 21 (test aim).** Its fixture is Some with no anchor, so it already starts past the ladder. The 113 s and
  98 s aims are unchanged.
- **Case 24 (rotation).** Not touched.
- **Case 29 (event identity).** The middle-distance fixtures are Some with no anchor, or Racing, so they are
  unaffected.
- **Case 30 (readiness).** The marathon is a phased goal, so its gate and ladder weeks are unchanged. "A date only
  eight weeks out still generates and reports DateTooClose" still holds (30 > 8).
- **Case 34 (ladder and cap).** The progression rules are unchanged.

**Non-pinned expectations that change**, each with a comment naming this revision:

- `PlannerEntryLadderTest > a dated New field test counts the ladder weeks`: 49 / 56 days → 83 / 84 days.
  `ladderWeeks(1, 3) == 8` stays.
- `SetupFlowScreenTest > running experience Why describes the ladder gate`: the new `setup_flow_why_about_run`.
- `PlanViewModelTest > the taper line follows the day as it turns`: its New 5K-to-10K plan starts on the ladder, so
  `taper` would stay false. The fixture becomes Some (BASE), which keeps the test's intent.
- `PlannerTest` and `PlannerSprintTest`: their exhaustive `when` over `PlanReason` gains `LadderWeeks` (compile).

**Expectations that do not change**, though the first draft predicted they would: `PlannerEntryLadderTest > Some
runners under the gate start on the ladder on every goal`. Its `underGate` fixture sets summed WORK (1,200) but no
bout, so the short goals stay at level 8, which is the continuity rule working. `PlannerRunInvariantsTest`'s grid
(Some, typed 1,200 s) moves the two short goals from LADDER to the goal's plan; plan counts do not change and the
invariants must hold, as they already do for Racing at the same anchor. CI checks them.

### 4.5 Tests (given → expect)

All are domain tests at `TODAY` unless named. The spec lists the full set; the key cases:

1. **P8 end to end.** Fitness test, Some, ONE_AND_HALF_MILE, goalSeconds 779, recent 2,414 m / 845 s, longest
   Time(1,320), days 4, minutes 45, date TODAY + 34 → phase null; A EASY, B TEST_INTERVALS, C EASY, D EASY, E REST;
   B 4 × Distance(400) hard at 123 s with 3 × 120 s recoveries; A and C 1,200 s with strength; D 1,440 s and
   `RunCapped("D")`; no `DateTooClose`, no `LadderWeeks`.
2. **Time arm, typed.** Some tester Time(1,199) → LADDER level 7; Time(1,200) → phase null.
3. **Distance arm, typed.** Some tester Distance(3,218) → LADDER level 3; Distance(3,219) → phase null.
4. **Log arms.** A log bout of 1,199 s → LADDER; 1,200 s → null. Bout meters 3,218 → LADDER; 3,219 → null.
5. **Bouts, not sums.** A Some tester whose only log is a finished ladder level-10 session → LADDER level 10. A
   finished level-11 session → phase null.
6. **New keeps 30.** New tester typed Time(1,500) → LADDER level 10; with a log bout of 1,500 → LADDER level 10.
7. **Other goals.** FIVE_TO_TEN_K and CARDIO_FITNESS, Some, a 1,500 s continuous run → LADDER level 10.
8. **Middle distance shares the gate.** M1500, Some, Time(1,200) → phase null; Time(1,199) → LADDER level 7.
9. **Event weeks.** `weeksNeeded(FITNESS_TEST, LADDER, 8) == 12`; New tester at TODAY + 83 → DateTooClose, + 84 →
   none.
10. **LadderWeeks.** New marathoner, 3 days → `LadderWeeks(8)`; half at level 6, 4 days → `LadderWeeks(5)`; a plan
    past the ladder → none; `Planner.day` strips it.
11. **Plan screen.** A stored LADDER recipe with a 5K goal 5 days out → `taper` false, phase LADDER; BASE → `taper`
    true; phase null → `taper` true.
12. **Your plan copy.** The New tester too close shows the test string; an M1500 New runner too close shows the
    race string; the half shows the generic string; ladder plans show "at least N weeks".

### 4.6 Residuals, stated not fixed

- **The cliff at the gate** (§3 B). It is accepted for the same reason as the 5K gate's cliff. DateTooClose now
  tells the runner just under it.
- **The typed longest is self-report.** The label asks for a run without walk breaks, but nothing checks it. The
  log wins as soon as there is one.
- **A Some runner who leaves "longest" blank, with no log, skips the ladder.** One who types 15 minutes enters it.
  This is existing behavior (revision 49 §1.1). Two seats (Sol F3, Astra F3) asked to treat missing continuity as
  unknown; that is not changed here and would be a separate owner call.
- **No DateTooClose for a runner past the ladder,** even with a test a week away. This is today's behavior for
  runners past 30 minutes, kept by owner decision 4.
- **The recent result is not a gate input.** P8's 2,414 m in 845 s shows he covered the test distance, but a field
  test allows walking, and the result carries no continuity.
- **Just cardio fitness keeps the 30-minute gate,** though its own plan's easy floor is also 20 minutes. It is
  undated, so the calendar problem does not arise. Aligning it is a separate owner call.
- **The Your event step's copy** ("A date tapers the last ten days.") is still shown to testers who will start on
  the ladder; Your plan now states the ladder length and the squeeze, which is where the plan is shown.
- **No evidence in §2 is a trial of this rule.** Every threshold here is practice anchored to published programs
  and to the plan's own floors.

## 5. Owner decision

The owner decided after the council check:

1. **Gate.** For Fitness test and Middle distance, a runner whose experience is not New (Some; Racing is already
   exempt from the ladder) skips the ladder once they have run 20 minutes (`EASY_SECONDS`, 1,200 s) or 2 miles
   (`FitnessTest.TWO_MILE.metres`, 3,219 m) without stopping. New runners keep the 30 min / 5 km gate. Every other
   goal keeps 30 min / 5 km.
2. **Continuity.** The gate reads a continuous run, not WORK summed across walk breaks: the longest single
   unbroken WORK bout from the log (seconds and meters), else the typed longest. The 30-minute gate's behavior does
   not change for anyone.
3. **Honesty for everyone.** Ladder weeks + 4 in DateTooClose on a ladder-only path, "Run/walk ladder" rather than
   "Taper" on the Plan screen, and the ladder's length on Your plan.
4. **Intervals.** No ease-in. A runner who skips the ladder gets the same test or race plan runners above 30 minutes
   get today.
5. **Setup copy.** The Why text says the gate is 20 minutes without walk breaks for fitness tests and races up to
   3,000 m, 30 minutes or 5 km otherwise, and that New runners keep 30.

## 6. Council check (2026-11-19)

Four seats checked §2 and the first draft's recommendation against their sources.

| Seat | Score | Verdict | Decision |
|---|---|---|---|
| Opus | 6/10 | Supported with changes | B, with the evidence corrected and preferably the short gate limited to Some or Racing |
| Fable | 7/10 | Supported with changes | B; fix E17, grade E8 and E15 down, and keep New runners on 30 minutes or justify the cap against E19 |
| Sol | 5/10 | Supported with changes | Modified B: let demonstrated continuous capacity skip fragmentation, and introduce event work by recent training and response |
| Astra | 4/10 | Not supported (as written) | Other: A's honesty, continuous easy running at demonstrated capacity, and a separate decision on intensity |

How each blocker and should was resolved:

| Finding | Seats | Resolution |
|---|---|---|
| E17 misrepresented: Orr used shuttle tests; the "Tomes" link was a 2023 normative abstract; the real Tomes 2020 found the 2.4 km run not associated with injury | Opus F1, Fable F1, Astra F6 (should) | Fixed. E17 rewritten with both papers and dropped as support for the gate |
| 20-minute capacity is not non-novice status; novice is defined by history | Sol F1, Astra F1 (blocker); Opus F2, Fable F2 (should) | Fixed. Owner decision 1 limits the short gate to runners who are not New; conclusion bullet 3 rewritten; E18 and E19 added |
| The run cap was used as protection for added intensity | Sol F2, Astra F2 (blocker); Opus F3 (should) | Fixed in text: the cap bounds session length; the interval dose is practice resting on weak, indirect or conflicting evidence. No ease-in, by owner decision 4 |
| The gate read summed WORK, so 4 × 5 min cleared "20 minutes continuous" | Sol F3, Astra F3 (blocker) | Fixed by owner decision 2: the longest single WORK set, else a typed longest the label asks for without walk breaks. The blank-answer part (a Some runner with no answer skips the ladder) is existing behavior, left unchanged and listed in §4.6 |
| Conclusion bullet 1 overstated the ladder's support ("favor") | Opus F4, Sol F4, Astra F4 (should) | Fixed. Now "a graded on-ramp for low-fit people in general"; speed vs continuity stated; the null novice RCTs (E20, E21) added |
| Conclusion bullet 3 overstated equivalence of training modes and ranked harms | Fable F3, Sol F5, Astra F5 (should) | Fixed. Cites E11 and E12 only, as weak; the gain is stated as fit and calendar, not a shown test or injury gain |
| Both thresholds are practice; neither is validated | Opus F5, Sol F6 (should) | Fixed. Conclusion bullet 1 and §4.1 say so |
| E15 graded moderate on inherited, unverified support | Fable F4 (should) | Fixed. Practice |
| Walk-break evidence needs a bounded statement | Sol F7 (should) | Fixed. E2 and bullet 3: comfort in marathoners, no prevention trial identified |
| The quality dose and the four weeks are practice; preparation time when the ladder is skipped | Sol F8, Astra F7 (should) | Four weeks labelled a scheduling allowance. Progressive introduction of faster work declined by owner decision 4; no DateTooClose past the ladder kept, as for runners past 30 minutes today |
| E8 weak; E4 wording; E10 weak and abstract reachable; E11 citation; E14's high-volume condition; E1 and E3 details; Hespanhol's split | Opus F6 to F9, Fable F5 to F8 (nit) | Applied in §2 |
| Decision: a separate, gradual intensity step | Sol, Astra | Declined by owner decision 4 |
