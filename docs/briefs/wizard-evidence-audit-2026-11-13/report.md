# Audit keeps most rules, changes twenty-nine

> **Status: research input, not a spec.** `docs/briefs/wizard-design-2026-11-08.md` and its revisions stay the source of truth until a revision adopts or rejects these verdicts. No verdict here touches the spec §11 pinned numbers. Date: 2026-11-13; code base `origin/main` at `0c4e774b` (#585), plus open PR #586.

Most of the 193 rules in the planner and progression inventory hold up against the primary literature. Nearly all of them hold up as coaching practice, though, not as tested evidence. Twenty-nine need a new value or a new behavior. The safety risks sit in three places. The three-day sprint row puts max-velocity sprinting the day after Nordic curls and heavy legs, inside the 48 to 72 hours in which hamstring force stays measurably down. The lift calibration jump (×1.25, at least two steps) can hand a brand-new lifter a failed barbell set. And the run/walk ladder treats an unrated session as Easy, so a beginner who never rates can go from one-minute runs to 30 minutes of continuous running in 12 sessions, against Couch-to-5K's 27. The biggest training cost is different: easy runs never grow, so the default "Just cardio fitness" plan, and most of every runner's volume, stays at its opening length for good. The best-supported numbers are now confirmed against primary text: the 110% single-run cap, Epley up to 10 reps, 180 s of rest on strength mains, ACSM 2009's two-session cadence and 10% ceiling, the weekly set floors, Borde's dose for adults 65 and older, and the taper lengths. The audit also corrects the first review in four places. Its hand-timing bias came from Mann 2015, not Hetzler. The "plateau at 18 to 20 sets" is ACSM's paraphrase; Pelland himself reports no plateau. The "40% to 2%" gains do not appear in ACSM 2009. And older adults do not need a full recalibration after two weeks off. Almost every injury finding comes from people unlike this app's users (experienced male distance runners, elite team-sport athletes, soccer players), so the app must not claim that any of its limits prevent injury.

**How to read the verdicts.** KEEP means the number holds against the named source. CHANGE gives the new value and its source. LABEL AS PRACTICE means no study tests the rule: the design should call it a coaching or product choice, and copy should make no evidence claim for it. Grades follow the first review. **Strong** means consistent meta-analyses or a high-certainty guideline. **Moderate** means one or two good meta-analyses, several consistent trials or a very large cohort. **Weak** means small trials, observational data or exploratory meta-regression. **Practice** means coaching convention or a position stand's consensus. **None** means nothing was found. The population column is there so no finding is stretched beyond the people studied. Numbers marked "arithmetic" were computed by the researchers from a published model (Epley, Riegel or Haugen's per-second rest rule). They are not data. Sources were read in full text where the domain notes say so, otherwise at abstract level through Europe PMC. Items still unverified are listed under open gaps.

## Eight rules carry most of the harm

The ranking weighs how many users a rule reaches against how badly it can hurt or stall them. Injury exposure ranks first, then failed or grinding sets for novices, then stalled training for many users, then claims that mislead. "Lands" names where the fix goes; the section after the domain tables groups the fixes by place.

| Rank | Row(s) | What goes wrong for a real user | Change | Lands |
|---|---|---|---|---|
| 1 | S02 | A 3-day sprinter does Lower + Nordic and then max-velocity Sprint B in the next slot. Hamstring force is down for up to 48 h after Nordics ([Alonso-Fernandez 2024](https://pubmed.ncbi.nlm.nih.gov/34748450/)), and eccentric strength is down through day 3 after a first bout ([Coratella 2025](https://pubmed.ncbi.nlm.nih.gov/40280182/)). Max velocity is where strains happen. | Row becomes "A + plyos, Lower, R, B + Nordic, R" | merged `planner/SprintLayout.kt` |
| 2 | L28 | A New lifter rates a calibration set "Easy" at a true 4 to 6 reps left. ×1.25 then leaves them 2 to 5 reps short of target (Epley arithmetic), often under a barbell, which breaks L18's "never below 2 RIR". The two-step floor doubles a 10 lb dumbbell. | Easy (4–6 left) → ×1.10; "very easy" → ×1.25; one-step minimum | merged `progression/Progression.kt` |
| 3 | R11 (with R10, R05) | Easy runs repeat forever. The default sub-goal never progresses, and weekly volume grows only through the long run. If the cap clips the opening easy run to a short recent longest, that clip is permanent (the clip at creation is inferred, not traced end to end). | Grow easy runs by time on full success (5%) up to the session minutes, under the run cap | merged `progression/Progression.kt`, `planner/RunSessions.kt`; minutes check with 4b-ii |
| 4 | R43 | An unrated ladder session advances. Twelve sessions take a non-rater from 1-minute runs to 30 minutes continuous. Novices are injured at 17.8 vs 7.7 per 1,000 h ([Videbæk 2015](https://pmc.ncbi.nlm.nih.gov/articles/PMC4473093/)), and C25K drop-outs blamed fast progression ([Relph 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10487403/)). | Unrated repeats; consider at least 2 sessions per level | merged `progression/Progression.kt` |
| 5 | S29 | The compounding 15% allowance saves up and spends it in one week: acceleration 80 → 160 m (+100%) at four holds, or a weekly 130 → 210 m (+62%) at three (arithmetic). That is the transient spike [Duhig 2016](https://eprints.qut.edu.au/221358/) linked to hamstring strain (OR 6.44 the following week). | No single week above about 30%; fill in finer units | PR #586 |
| 6 | O09 | Older beginners open with a farmer's carry at the catalog's 50 lb. If that is per hand, it is about 32% of body mass for a 70 kg person, over three times the 10% ceiling of the only older-adult loaded-walking data ([PMID 42413170](https://europepmc.org/article/MED/42413170)). | About 10% of body mass per hand, or the lightest pair; calibrate by effort | merged `planner/DayLayout.kt`, `library/ExerciseLibrary.kt` |
| 7 | L30 | No effort word means 3 reps left, yet 3 RIR is the target for every New, Under-a-year and General main. An honest "Hard" (stored 1) on a 3-RIR slot is an effort miss (1 < 3 − 1); two in a row hold the load and slow the cadence. | Show the rep count with each word ("Hard: 1 left"); add a 3-RIR word or split Easy | merged `progression/Effort.kt`, `ui/day/WorkoutSetScreen.kt`, `strings.xml` |
| 8 | S30 | A sprinter who stops for 15 to 20 days comes back at full volume, after Nordic-built fascicle length has reverted (within 2 weeks; [Presland 2018](https://pubmed.ncbi.nlm.nih.gov/29572976/)). Counting per sprint day also resets a user who only skipped A days. | Reset at 14 days since the last maximal sprint of any kind | PR #586 |
| 9 | L42 | An all-Easy session on a 3-RIR target doubles a novice's 5% lower-body step to 10%. That takes a true 4-RIR 5-rep set to about 0.5 RIR (arithmetic). | No doubling on 3-RIR targets; keep it on 2-RIR targets | merged `progression/Progression.kt` |
| 10 | O07 | On a 2-day plan an older adult gets balance work on 2 days, under WHO's 3 or more (strong). | Balance on 3 or more days a week, whatever the lifting days | needs design; PR 6 |
| 11 | S10 | Sprint-endurance reps of 100 to 150 m rest 480 s, Haugen's elite floor. A recreational 100 m of about 13 to 16 s calls for 13 to 32 min under his 1 to 2 min per second rule (arithmetic). | At least 600 s, scaling with rep time; the intro opens at 2 × 100 m | merged `progression/RunRange.kt` |
| 12 | G02 + W05 | A flat 45 s a set misjudges heavy, high-rep, per-side and timed sets, and the suggested cool-down is not budgeted. High-rep and per-side days overrun the chosen minutes. | Set time = setup + reps × 3 s (× sides; timed sets at their duration); budget the cool-down when on | merged `planner/SessionBudget.kt` |
| 13 | C04 | A lift-primary Both user on 2 separate days gets one strength day a week, under WHO's 2 or more (strong). | Same visit at 2 days, or keep two strength exposures | merged `planner/BothLayout.kt` |
| 14 | O10 | Any lift past 14 days of layoff drops an older adult to ×0.75 and calibrating. Trials show gains held through 12 weeks off in 65 to 75-year-olds. | ×0.85 with ⌈⅔⌉ sets from 15 days past recurrence; ×0.75 and recalibrate past 42 | merged `progression/Progression.kt` |
| 15 | C03 | Trained Strength users at 3 or fewer days default to the same visit, which is where the only measurable interference sits. | Separate days when days allow, for Strength with A year or more | merged `planner/SetupAnswers.kt`; PR 7 screen |
| 16 | R50 | An interval day can shrink to one round, below the 15 min of work that made intervals beat continuous running. | Floor at the rounds giving at least 15 min of work, else an easy run | merged `planner/RunLayout.kt` |
| 17 | S08 | The sprint intro opens acceleration at 40 m. With steps every two counted sessions it stays under Haugen's 100 m session floor for about 13 sessions (arithmetic). | About 100 m (for example 5 × 20 m); carry caution by effort, not volume | merged `planner/SprintLayout.kt` |
| 18 | S15, S17 | Plyometrics run once a week, on Sprint A only, as low-intensity vertical work (pogos, A-skips) that grows by adding reps. | Two exposures a week, about 40 contacts each for novices; progress by exercise, not reps | merged `SprintLayout.kt`; PR #586 (rep growth); 5c-ii |
| 19 | S19 | Nordic 2 × 4–6 once a week (8 to 12 reps) builds strength but not fascicle length, and the dose never rises. | Reach about 30 reps a week by weeks 6 to 8 | 5c-ii or a later progression PR |
| 20 | R48 | Run strength blocks carry no plyometrics, though most effective runner programs include them. | Add a plyometric item at the low end of 30 to 228 contacts a session | merged `planner/RunLayout.kt` |
| 21 | R32, R33 | Tapers cut less than the 41 to 60% band that improved performance. Performance cost only. | ≤10K ×0.5; half ×0.6 then ×0.45; marathon about ×0.75/0.55/0.4 | merged `progression/Taper.kt` |
| 22 | R36 | ×1.05 still leaves about half of marathoners finishing slower than the checked time, and more than half from 5K or 10K anchors. | LABEL AS PRACTICE; copy says so; later add weekly mileage | PR 7 copy |
| 23 | S25 | Hand-timed gains of 0.10 to 0.14 s show as personal bests, though they sit inside measured timer noise. | Hand SAME band about 0.15 s | merged `records/SprintComparison.kt` |
| 24 | S33 | One 40 yd or 60 m rep is the least reliable sprint test there is. | Two reps for 40 yd and 60 m; one for 100 to 400 m | PR #586 |
| 25 | L08 | Back-offs at 90% of the TOP end about 6 reps short of failure. Wasted minutes, little strength cost. | About 95% of the TOP | merged `progression/Scheme.kt` |
| 26 | L40 | 1.25% upper-body steps sit below ACSM's 2% floor. Moot on most loads because of step rounding; matters for microplate users. | Upper 2% | merged `progression/SlotRule.kt` |
| 27 | R09 | The user clamp allows +25% per session, but the run cap clips runs above 10% anyway. Visible and untested. | Clamp ceiling 10% for run families | merged `progression/CardioMeasures.kt` |

## Lift progression sits inside ACSM 2009 except calibration and the effort words

Most of the lift engine falls inside published convention once ACSM 2009 is read in full. Its increment rule is "a 2–10% … increase in load … when the individual can perform the current workload for one to two repetitions over the desired number **on two consecutive training sessions**." The stand graded it Evidence B and applied it to novice, intermediate and advanced lifters alike ([ACSM 2009](https://pubmed.ncbi.nlm.nih.gov/19204579/)). That makes the experienced cadence of two (L37) ACSM's own rule rather than an invention, and the 10% cap (L41) its exact ceiling. The novice cadence of one is faster than ACSM and follows novice linear programs. ACSM 2026 sets no increment or cadence at all: it accepts progression by perceived-exertion scales and says "there is insufficient evidence to quantify exact RIR and perceived exertion targets" ([Currier et al. 2026](https://pmc.ncbi.nlm.nih.gov/articles/PMC12965823/)). Rest on Strength mains is now supported. **Rests over 2 min are "required to maximize strength gains in resistance-trained individuals"** ([Grgic 2018](https://pubmed.ncbi.nlm.nih.gov/28933024/)), and 3 min beat 1 min for squat and bench 1RM in trained young men ([Schoenfeld 2016](https://pubmed.ncbi.nlm.nih.gov/26605807/)). ACSM 2026's null finding on rest came mostly from novices. Epley's 10-rep limit is supported too: "no more than 10 repetitions should be used in linear equations to estimate 1RM" ([Reynolds 2006](https://pubmed.ncbi.nlm.nih.gov/16937972/); [Mayhew 2008](https://pubmed.ncbi.nlm.nih.gov/18714230/)).

Self-reported effort is accurate enough for the success rule, though not for the calibration jump. Across 12 studies and 414 people, lifters **underpredict reps to failure by 0.95 reps (95% CI 0.17 to 1.73)**. Accuracy is much better on sets of 12 or fewer reps, training status has no effect, and the between-person SD is 1.45 reps ([Halperin 2022](https://pubmed.ncbi.nlm.nih.gov/34542869/)). Trained lifters calling 1 or 3 RIR on the bench were off by an absolute 0.65 reps ([Refalo 2024](https://pubmed.ncbi.nlm.nih.gov/37967832/)). Far from failure the error balloons: **5.15 reps when calling 5 RIR** on high-rep squats ([Zourdos 2021](https://pubmed.ncbi.nlm.nih.gov/30747900/)). That pattern supports the one-rep tolerance in the success rule (L32). It also explains why "Easy" can safely be one bucket for everything at 4 RIR and above, which is how the RIR-based RPE scale itself groups "4–6 RIR" ([Helms 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC4961270/)). And it is why ×1.25 is wrong at the end of calibration. By Epley arithmetic, one rep of reserve is worth about 2.5% of load in the 5 to 12-rep range. So ×1.25 lands on 3 RIR only if the "Easy" set really had about 12.5 reps left (5-rep target) or 13.8 (10-rep target). On low-rep calibration sets, attentive lifters call within about a rep, so a true 4 to 6 is the common case. The nearest convention, the NSCA 1RM warm-up, steps up 5 to 10% upper body and 10 to 20% lower body between warm-up sets ([HPRC, attributing the NSCA protocol](https://www.hprc-online.org/articles/one-rep-max-for-strength); secondary). No study maps everyday words like "easy" or "hard" to RIR. The word mapping (L30) can only be practice, and the app's own logs are the only way to calibrate it.

Three rules conflict with that arithmetic. L28's ×1.25 jump is the worst, because it lands on New lifters. L42's Easy-session doubling is safe at a 2-RIR target, where "Easy" guarantees at least 2 spare reps (about 5%). At a 3-RIR target it guarantees only one (about 2.6%), so the novice lower-body double of 10% overshoots. L08's back-offs at 90% of a TOP × 5 at 2 RIR (about 81% 1RM) fall to about 73% 1RM, which leaves about 6 RIR. That is outside ACSM 2026's 80% load and 2 to 3 RIR effort marks. The cost is low because strength gains are flat across a wide RIR range ([Robinson 2024](https://pubmed.ncbi.nlm.nih.gov/38970765/)), but the minutes are wasted. L40's 1.25% upper increment is below ACSM's 2% floor. It also double-counts Latella's upper-versus-lower asymmetry, which is in absolute kilograms (squat 20.2 to 25.4 kg a year against bench 10.5 to 12.8; [Latella 2022](https://pubmed.ncbi.nlm.nih.gov/35019902/)). In percent terms, trained men gained about 8 to 9% on the bench against 10 to 12% on the squat over 8 weeks ([Helms 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5877330/)). In practice the at-least-one-step rounding swallows the difference on most loads, so this matters mainly to microplate users. The light-week rules sit inside what competitive athletes report: deloads of **6.4 ± 1.7 days every 5.6 ± 2.3 weeks**, cutting sets, reps and load ([Rogerson 2024](https://pubmed.ncbi.nlm.nih.gov/38499934/)). A one-week full stop cost trained lifters some lower-body strength ([Coleman 2024](https://pubmed.ncbi.nlm.nih.gov/38274324/)), which favors a light week over a week off.

Epley's individual error is now quantifiable from Nuzzo's 952 reps-to-failure tests: SD 2.51 reps at 80% 1RM. Bench values run about 4, 9 and 14 reps at 90, 80 and 70%; leg press about 9, 13 and 19 ([Nuzzo 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC10933212/)). By the researchers' arithmetic that is about **±6% of 1RM per SD**. Epley overestimates e1RM by about 15% from a 13-rep leg-press set (arithmetic from Nuzzo). Equations underestimate deadlift 1RM by 9 to 14% in untrained students (LeSuer 1997, as reported in [Eston & Evans 2009](https://pmc.ncbi.nlm.nih.gov/articles/PMC3761544/)). Because the app turns an e1RM back into a load on the same exercise at similar reps, much of that error cancels.

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| L01 | Split by days: full body at 1–3 days and for General; upper/lower at 4–6; Muscle at 6 Push/Pull/Legs ×2 | KEEP | — | Pelland 2026, doi:10.1007/s40279-025-02344-w, PMID 41343037 (frequency "compatible with negligible effects" on size); ACSM 2026, doi:10.1249/MSS.0000000000003897 | Young adults, 79% male (Pelland); mostly novices (ACSM) | Moderate |
| L02 | One REST after the third training entry at 4+ days | LABEL AS PRACTICE | — | None found | — | None |
| L03 | Every chosen main appears once per rotation as MAIN | KEEP (preference) | — | Not re-audited; first review's affect evidence (Oliveira 2015, PMC4523714) | Mixed | Moderate for affect |
| L04 | Strength and Muscle add a SECONDARY exposure; General and 65+ none | KEEP | — | Pelland 2026 (strength 12.72% at fractional frequency 1 vs 17.32% at 2); ACSM 2026 (≥2 sessions a week for strength) | As L01 | Moderate |
| L05 | SECONDARY: Strength 2 × 5 at 3 RIR; Muscle 2 × 8–12 at 2 RIR | LABEL AS PRACTICE | — | ACSM 2009, doi:10.1249/MSS.0b013e3181915670, PMID 19204579 (ranges contain them) | Healthy adults | Practice |
| L06, L10, L12–L17 | Goal reps and RIR (TOP × 5 at 2 RIR ≈ 81%; 3 × 5 at 3; Muscle 6–10 / 8–12 / 10–15; General 2 × 8–15; isolation at 1 RIR) | KEEP | — | ACSM 2026 (≥80% 1RM for strength; 2–3 RIR); ACSM 2009 (6–12RM size, 8–12RM novices); Robinson 2024, PMID 38970765; Refalo 2023, PMID 36334240 | Mostly novices (ACSM 2026); RIR estimated in meta-regression (Robinson) | Strong (load), Moderate (RIR) |
| L07 | STRENGTH ramps 60% × 5, 80% × 3 | LABEL AS PRACTICE | — | Iversen 2021, doi:10.1007/s40279-021-01490-1 (specific warm-up useful above 80% 1RM); no source for the percentages | Narrative review | Practice |
| L08 | Back-offs 2 sets at 90% of TOP × 5 | CHANGE | About 95% of TOP (≈4 RIR) | Epley arithmetic against ACSM 2026's ≥80% and 2–3 RIR | — | Practice |
| L09 | PROTOTYPE scheme: ramps 55/70/80/90%, one back-off 75% × 8 | LABEL AS PRACTICE | — | Not re-audited | — | None |
| L11 | Not built: 3 → 2 RIR after six exposures | LABEL AS PRACTICE | — | Helms 2016, PMC4961270 (novices practice rating before RIR drives load); "six" has no source | — | Practice |
| L18 | A New lifter never below 2 RIR | LABEL AS PRACTICE | — | Zourdos 2016, PMID 26049792; Ormsbee 2019, PMID 28301439 (novices under-rate effort at a true 1RM) | College-aged men | Weak |
| L19 | One 2-set CORE slot every lift day | LABEL AS PRACTICE | — | US PAG 2018 names the abdomen a major group; no dose | — | Practice |
| L20 | Biceps and triceps as one superset slot | LABEL AS PRACTICE | — | None | — | None |
| L21 | Day skeletons (FB, Upper, Lower, Push, Pull patterns) | LABEL AS PRACTICE | — | ACSM 2026: four regions (upper/lower × push/pull), or six with horizontal/vertical, "sufficient to target major muscle groups" | Healthy adults | Practice |
| L22, L23 | Accessory pick by catalog rank; default mains | LABEL AS PRACTICE | — | None (product choices) | — | None |
| L24 | Opening load via Estimate; a history set with no rir reads as 2 RIR | LABEL AS PRACTICE | — | Reynolds 2006 supports seeding from ≤10 reps; "null reads 2" has no source | — | Moderate (seeding), None (null) |
| L25 | Epley only when r + k ≤ 10 | KEEP (VERIFY → SUPPORTED) | — | Reynolds 2006, doi:10.1519/R-15304.1, PMID 16937972; Mayhew 2008, doi:10.1519/JSC.0b013e31817b02ad, PMID 18714230; Nuzzo 2024, doi:10.1007/s40279-023-01937-7 | Adults 18–69 (Reynolds); untrained young women (Mayhew); 7,289 people, 66% male (Nuzzo) | Moderate |
| L26 | Empty-bar floor 45 lb / 20 kg | LABEL AS PRACTICE | — | None | — | Practice |
| L27 | Dumbbell 10 lb, machine/cable/EZ 20 lb, kettlebell 18 lb floors | LABEL AS PRACTICE | — | None | — | None |
| L28 | Calibration: rir ≥ 4 → next set ×1.25 rounded up, at least two steps | CHANGE | Easy (4–6 left) → ×1.10; "very easy / warm-up feel" → ×1.25; one-step minimum | Epley arithmetic; NSCA 1RM warm-up +5–10% upper, +10–20% lower (HPRC, secondary); Refalo 2024, PMID 37967832; Zourdos 2021, PMID 30747900 | Trained adults (RIR data) | Practice |
| L29 | Calibration ends at rir ≤ 3; × 0.9 when r + k > 10 and reps fall short | LABEL AS PRACTICE | — | NSCA post-miss −2.5–5% upper, −5–10% lower (HPRC, secondary) | — | Practice |
| L30 | Easy 4, Good 2, Hard 1, Max 0; unrated counts as on target | LABEL AS PRACTICE + CHANGE | Show each word's rep count; add a 3-RIR word or split Easy | Helms 2016; Zourdos 2016; Zourdos 2021; Halperin 2022, PMID 34542869 | Mixed; trained men (Zourdos 2021) | Practice (no study maps words to RIR) |
| L31 | Reps-left scale 0–5 | LABEL AS PRACTICE | — | Helms 2016 groups 4–6 RIR as one band | — | Practice |
| L32 | Success: target reps and rir ≥ target − 1 | KEEP | — | Halperin 2022 (−0.95 reps); Refalo 2024 (absolute error 0.65 ± 0.78) | Mixed trained and untrained; trained bench | Moderate |
| L33, L36, L38 | Effort-miss holds; slow cadence after two stalls in 42 days; step back after 4 successes, max 3 | LABEL AS PRACTICE | — | None | — | None |
| L34 | Two rep misses at one load → −5% (editable 0–10%) | KEEP as PRACTICE | — | StrongLifts convention: ~10% after three misses (program author) | — | Practice |
| L35 | Coarse-step stall → −1 rep, floor 3 | LABEL AS PRACTICE | — | None | — | None |
| L37 | Cadence 1 for New and Under a year; 2 for A year or more and user-built | KEEP (INVENTED → PRACTICE) | — | ACSM 2009 (two consecutive sessions, Evidence B); Steele 2023, PMID 35591809; Latella 2020, PMID 32865942 | All levels (ACSM); 14,690 adults aged 48 ± 11 (Steele); powerlifters | Practice (category B) |
| L39 | Novice increments 5% lower, 2.5% upper | KEEP as PRACTICE | — | ACSM 2009 (2–10%, lower for small muscle mass) | — | Practice |
| L40 | A year or more and user-built: 2.5% lower, 1.25% upper | CHANGE | Upper 2%; lower stays 2.5% | ACSM 2009 (2% floor); Helms 2018, PMC5877330 | Trained young men | Practice |
| L41 | Increment at least one step, at most 10% | KEEP (VERIFY → SUPPORTED) | — | ACSM 2009, read in full | — | Practice (consensus ceiling) |
| L42 | All-Easy session doubles the increment | CHANGE | No doubling on 3-RIR targets; keep it on 2-RIR targets | Epley arithmetic | — | Practice |
| L43 | A Max set blocks a load increase | KEEP as PRACTICE | — | None | — | Practice |
| L44 | Double progression in a range | KEEP as PRACTICE | — | Plotkin 2022, PMID 36199287 | Lifters with ≥1 year, 8 weeks | Weak |
| L45, L46 | Coarse step becomes Range(t, t + 4); bodyweight +1 rep to 20 | LABEL AS PRACTICE | — | Halperin 2022 (accuracy collapses above 12 reps); Zourdos 2021 | — | Practice |
| L47 | Carried load by strict majority of WORK rows | LABEL AS PRACTICE | — | Not re-audited | — | None |
| L48 | Shared success credit across same-exercise, same-rep slots | LABEL AS PRACTICE | — | None. With cadence 2 the fastest experienced rate is about 2.5%/week lower and 1.25%/week upper (arithmetic), at or above trained men's ~1.3–1.5%/week squat and ~1.1%/week bench (Helms 2018) | Trained men | None |
| L49 | Strength MAIN rest 180 s | KEEP (VERIFY → SUPPORTED) | — | Grgic 2018, doi:10.1007/s40279-017-0788-x, PMID 28933024; Schoenfeld 2016, PMID 26605807; ACSM 2009 (≥2–3 min core lifts) | Trained lifters; young trained men | Moderate |
| L50–L52 | 120 s secondary and Muscle main; 90 s accessory and General; 60 s isolation and core | KEEP | — | Singer 2024, PMID 39205815 (no hypertrophy difference past 90 s); ACSM 2009 (1–2 min assistance) | Mostly untrained | Moderate to weak |
| L53 | RAMP 90 s, BACKOFF 120 s, unruled buckets | LABEL AS PRACTICE | — | None | — | None |
| L54–L57 | Light cycle: 60% of sets, ×0.9 load, one rotation long, every 6 rotations (4–8), reactive triggers | KEEP as PRACTICE | — | Rogerson 2024, PMID 38499934; Coleman 2024, PMID 38274324 | Competitive strength and physique athletes (survey); trained young adults | Practice (descriptive) |
| L62 | Rough day reverses that Finish's changes | LABEL AS PRACTICE | — | Not re-audited | — | None |
| L77 | Default reps and RIR for user-built and converted slots | LABEL AS PRACTICE | — | None | — | None |
| L78 | Load steps: bar 5 lb, dumbbell 5 lb, stack 10 lb (2.5/2/5 kg) | LABEL AS PRACTICE | — | None | — | Practice |

## Weekly set floors now trace to sources; the cap of 20 is no plateau

Every weekly set floor can now be tied to a source, and none of them needed to change. The pivotal paper is Pelland 2026. Its full preprint shows that **counting a synergist set as half a set fit better than total or direct counting**, by Bayes factors in the strong range. The authors also call their direct/indirect calls "not wholly objective", and 0.5 was the only fraction tested, not a value fitted from the data ([Pelland 2026](https://pubmed.ncbi.nlm.nih.gov/41343037/); [preprint](https://sportrxiv.org/index.php/server/preprint/view/460/967)). The same preprint takes apart the most quoted number in the first review. For hypertrophy it finds diminishing returns with **"no clear plateau"**. Each detectable step costs about 6 more weekly sets between 5 and 10, about 8.5 between 11 and 18, and about 10.75 between 19 and 29, and the authors warn that "few studies have explored ~25+" sets. For strength the curve is reciprocal: the minimum effective dose is 1 fractional set a week, and nothing is consistently detectable past about 5. The "plateau near 18 to 20 sets" is ACSM 2026's reading of Pelland ([Currier et al. 2026](https://pmc.ncbi.nlm.nih.gov/articles/PMC12965823/)), not Pelland's own result. So the cap of 20 (L68) stays, but as an efficiency and time ceiling, cited to the tier slope and to [Baz-Valle 2022](https://europepmc.org/article/PMC/PMC8884877). In that analysis, 12 to 20 weekly sets matched more than 20 for quadriceps and biceps in young trained men, though not for triceps.

The floors fit those numbers. Muscle's 10 for New and Under-a-year lifters is ACSM 2026's "≥10 sets/muscle group/wk". Muscle's 12 for experienced lifters sits at the bottom of Baz-Valle's 12 to 20. General's 4 is Pelland's minimum effective hypertrophy dose. Strength's 6 is derived: ACSM's 2 to 3 sets a session at 2 or more sessions a week gives 4 to 6 sets of the lift, and more than 5 weekly sets per exercise beat 5 or fewer (ES 0.18; [Ralston 2017](https://europepmc.org/article/PMC/PMC5684266)). The caveat is that these sources count sets of a lift while the app counts sets per muscle, and Pelland's sample was young (mean age 25) and 79% male. The one-working-set floor on main lifts (L75) rests on single sets *to failure*, 2 to 3 times a week, raising 1RM by 12.09 kg in trained men ([Androulakis-Korakakis 2020](https://pubmed.ncbi.nlm.nih.gov/31797219/)). The app's floor set runs at 2 to 3 RIR, which is easier than anything that trial tested. The guidelines name groups, not muscles. WHO says "all major muscle groups on 2 or more days a week" ([Bull et al. 2020](https://europepmc.org/article/PMC/PMC7719906)), and the US guidelines list legs, hips, back, abdomen, chest, shoulders and arms. The floor list covers all of those except the abdomen and, outside Muscle, the arms. The core slot (L19) covers the abdomen, and half credit from presses and pulls likely covers the arms at a health dose.

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| L63 | Strength floor 6 sets per floor muscle | KEEP (INVENTED → SUPPORTED, derived) | — | ACSM 2026 (2–3 sets/session, ≥2 sessions); Ralston 2017, doi:10.1007/s40279-017-0762-7, PMC5684266; Pelland 2026 (pooled strength median 6) | Novice and intermediate men (Ralston); young, mostly male (Pelland) | Moderate (per lift, not per muscle) |
| L64 | General floor 4 | KEEP | — | Pelland 2026 (minimum effective dose 4 fractional sets) | Young, 79% male | Moderate |
| L65 | Muscle floor 10 (New, Under a year) | KEEP | — | ACSM 2026 (≥10 sets/muscle/week); Pelland tier 5–10 | Mostly novices | Moderate |
| L66 | Muscle floor 12 (A year or more) | KEEP | — | Baz-Valle 2022, doi:10.2478/hukin-2022-0017, PMC8884877 | Trained men 18–35 | Moderate to weak (7 RCTs) |
| L67 | Floor muscles; none for side/rear delts, calves, core | LABEL AS PRACTICE | — | WHO 2020, doi:10.1136/bjsports-2020-102955; ACSM 2026; US PAG 2018 | Adults | Practice |
| L68 | Cap of 20 weekly sets, never relaxed | KEEP 20, LABEL AS PRACTICE; fix the citation | — (cite as an efficiency ceiling, not a plateau) | Pelland 2026 tiers; Baz-Valle 2022 | Young trained men | Practice |
| L69 | Fill and trim mechanics, 5-set slot cap | LABEL AS PRACTICE | — | None | — | None |
| L70 | Secondary muscle counts 0.5; ramps never count | KEEP | — | Pelland 2026 (fractional counting best fit) | Young, 79% male | Moderate |
| L71 | Muscle credit table | LABEL AS PRACTICE | — | Pelland: classification "not wholly objective" | — | Practice |
| L72 | Plyometric credits (pogo calves 0.5; bounds, box jumps quads and glutes 0.5) | LABEL AS PRACTICE | — | Not re-audited (Grgic 2021 not read) | — | None |
| L73 | Each major muscle 2+ exposures; 2+ strength days cover every major muscle | KEEP | — | WHO 2020 (strong); ACSM 2026 | Adults and older adults | Strong |
| L74 | Floors give way on rotations with a ShortSession day | LABEL AS PRACTICE | — | None | — | None |
| L75 | Never below one working set per main lift | KEEP, with an effort caveat | — | Androulakis-Korakakis 2020, doi:10.1007/s40279-019-01236-0, PMID 31797219 (sets to failure); Pelland 2026 (strength MED 1 set) | Resistance-trained men | Moderate |
| L76 | Dose-ladder step order, 80% × 3 ramp, 120 s MAIN cap | LABEL AS PRACTICE | — | ACSM 2026 (target lift early); Pelland (first sets worth most) | — | Practice |

## Strength holds about three weeks off, so the 65+ recalibration overshoots

Detraining is slower than the app's caution implies, in both younger and older adults. Trained men lost no leg-press strength over 2 weeks off ([Hwang 2017](https://pubmed.ncbi.nlm.nih.gov/28328712/)). Elite rugby and football players held strength for up to 3 weeks, then lost a mean **14.5% over layoffs averaging 7.2 ± 5.8 weeks** ([McMaster 2013](https://pubmed.ncbi.nlm.nih.gov/23529287/)). Three-week breaks every six weeks matched continuous training over 24 weeks in young men ([Ogasawara 2013](https://pubmed.ncbi.nlm.nih.gov/23053130/)). Regain is fast everywhere it has been measured. Untrained adults who stopped for 10 weeks regained faster than continuous trainers and ended level with them ([Halonen 2024](https://pubmed.ncbi.nlm.nih.gov/39364857/)). Bosquet's meta-analysis confirms a dose-response by duration, and a larger effect after 65, but its often-quoted "no loss up to 28 days" and its age-specific effect sizes are still not in any text the researchers could read ([Bosquet 2013](https://pubmed.ncbi.nlm.nih.gov/23347054/)). No trial in any population compares restart loads. Every restart factor is therefore practice. The adult bands (L59, L60) are cheap insurance, not a response to measured loss. The ×0.85 can cite McMaster as about the average loss after one to two months off.

The 65+ rule is where the evidence pushes back. In 65 to 75-year-olds, knee-extension gains were **"maintained equally well in young and older men and women during 12 wk of detraining"**. 1RM fell only 14 ± 2% by week 31 ([Lemmer 2000](https://pubmed.ncbi.nlm.nih.gov/10949019/)). Adults aged 65 to 83 lost 17.6% only after 24 weeks off, and regained it in 12 ([Taaffe 2009](https://europepmc.org/article/PMC/PMC2756799)). Across 15 RCTs in middle-aged and older adults, high-intensity groups kept their gains after 4 to 6 weeks off (SMD 0.71; [Yang 2022](https://pubmed.ncbi.nlm.nih.gov/34510028/)). A ×0.75 recalibration at 15 days past recurrence is far more cautious than any measured loss. The first review's restart table, which put users over 65 on full re-seeding "at any length", has no support in these trials. If the owner wants the stricter rule for confidence, it should stay and be labeled practice. The detraining data do not require it.

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| L58 | Layoff = days since the lift's last session − expected recurrence | LABEL AS PRACTICE | — (say in the design that bands count from the expected next session) | Hwang 2017, doi:10.1519/JSC.0000000000001807, PMID 28328712; McMaster 2013, doi:10.1007/s40279-013-0031-3, PMID 23529287; Ogasawara 2013, PMID 23053130 | Trained young men; elite football codes | Practice |
| L59 | ≤3 normal; 4–7 hold; 8–14 scaled; 15–42 scaled, fewer sets; >42 recalibrate | LABEL AS PRACTICE | — | As L58 (no loss through ~3 weeks; decay from 5–16 weeks) | As L58 | Moderate (loss timing), Practice (bands) |
| L60 | ×0.95; ×0.85 with ⌈⅔⌉ sets; ×0.75 and calibrating | LABEL AS PRACTICE | — | McMaster 2013 (14.5% mean loss) for ×0.85; Lemmer 2000, Taaffe 2009 show ×0.75 exceeds measured losses | As above; 65–83 | Practice |
| L61 | After an on-target scaled opening, reopen at the pre-layoff load | KEEP as PRACTICE | — | Halonen 2024, doi:10.1111/sms.14739, PMID 39364857; Ogasawara 2013; Taaffe 2009, doi:10.1159/000182084 | Untrained adults 32 ± 5; young men; 65–83 | Moderate (fast regain), Practice (one session) |
| O10 | 65+: layoff over 14 days → ×0.75 and recalibrate | CHANGE | ×0.85 with ⌈⅔⌉ sets from 15 days past recurrence; ×0.75 and recalibrate past 42 days; or keep and LABEL AS PRACTICE | Lemmer 2000, doi:10.1097/00005768-200008000-00021, PMID 10949019; Yang 2022, doi:10.1123/japa.2020-0493, PMID 34510028; Taaffe 2009; Bosquet 2013, doi:10.1111/sms.12047 (direction only) | 65–75 (Lemmer); middle-aged and older (Yang) | Moderate (gains held), Practice (factors) |

## Older adults get the right lifting dose, a token balance dose and a heavy carry

The 65+ lifting prescription now rests on primary text. Borde's meta-analysis of 25 RCTs in healthy adults with a mean age of 65 or more gave the largest strength effects at **70 to 79% 1RM**, 2 sessions a week, **2 to 3 sets per exercise and 7 to 9 reps**. Strength was best at 4.0 s between reps and 60 s between sets (two studies only); size was best at 2.5 s between reps and 120 s between sets ([Borde 2015](https://europepmc.org/article/PMC/PMC4656698)). That supports 2 sets of 6 to 10 at 3 RIR (about 70 to 77% 1RM by Epley), 120 s of rest, and a floor of 6 sets (2 to 3 sets × 2 sessions = 4 to 6). It corrects the first review's recalled "4 to 6 s between reps".

Balance is a different story. WHO strongly recommends multicomponent activity emphasizing balance and strength on **3 or more days a week**, because "high-certainty evidence demonstrates that balance and functional exercises reduce the rate of falls" ([Bull et al. 2020](https://europepmc.org/article/PMC/PMC7719906)). In 108 RCTs of adults over 60, balance and functional exercise cut the rate of falls by 24% (RaR 0.76; [Sherrington 2019](https://europepmc.org/article/PMC/PMC6360922)). The doses behind those effects dwarf the app's 2 × 30 s a side. Effective balance programs ran **3 sessions and 91 to 120 minutes a week** ([Lesinski 2015](https://europepmc.org/article/PMC/PMC4656699)), and the largest fall reductions came from programs that challenged balance for **more than 3 hours a week** ([Sherrington 2017](https://pubmed.ncbi.nlm.nih.gov/27707740/)). Static single-leg stance with no progression is unlikely to count as challenging. A two-day plan also misses WHO's day count. Matching trial minutes inside a lifting session may not be feasible. Whatever the app does, its copy must not suggest the block delivers the fall-prevention dose. The carry has no basis at all. No trial prescribes farmer's carry loads for untrained older adults. The one biomechanics study found dynamic balance preserved with one-hand loads of 2 to 10% of body mass ([PMID 42413170](https://europepmc.org/article/MED/42413170)). Whether the catalog's 50 lb means per hand or in total is not stated in code. Either way, it was the adult default, never chosen for this path.

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| O01 | 65+ path from profile age ≥ 65 | KEEP | — | WHO 2020, PMC7719906 | Older adults | Strong |
| O02 | Mains STRAIGHT 6–10 at 3 RIR, never ramped (≈70–77% 1RM) | KEEP | — | Borde 2015, doi:10.1007/s40279-015-0385-9, PMC4656698 (70–79% best); ACSM 2026 (no evidence for exact RIR) | Healthy adults, mean age ≥65 | Moderate (load), Practice (3 RIR) |
| O03 | Every 65+ slot 2 sets | KEEP (VERIFY → SUPPORTED) | — | Borde 2015 (2–3 sets, 7–9 reps, 2 sessions/week) | As O02 | Moderate |
| O04 | No SECONDARY exposures on the 65+ path | LABEL AS PRACTICE | — | Borde 2015 (2 sessions/week best; no source drops a second exposure) | As O02 | Practice |
| O05 | 65+ MAIN rest 120 s | KEEP | — | Borde 2015 (120 s best for size; 60 s for strength from two studies) | As O02 | Weak |
| O06 | 65+ weekly set floor 6 | KEEP (now sourced) | — | Borde 2015 (2–3 sets × 2 sessions) | As O02 | Moderate |
| O07 | Balance block opens every lifting, run-strength and sprint-lift day | CHANGE | Balance on ≥3 days a week whatever the lifting day count (for example a short home item on non-lifting days) | WHO 2020 (strong); US PAG 2018 ("about three sessions a week"); Lesinski 2015, doi:10.1007/s40279-015-0375-y, PMC4656699 | Community-dwelling adults ≥60/65 | Strong (≥3 days) |
| O08 | Single-leg stance 2 × 30 s a side, 30 s rest | LABEL AS PRACTICE; record it as a token dose | — (progress the challenge: eyes closed, narrower stance, head turns, reaching, tandem walk, as practice) | Lesinski 2015 (91–120 min/week); Sherrington 2017, doi:10.1136/bjsports-2016-096547, PMID 27707740 (>3 h/week); Sherrington 2019, CD012424.pub2 | Adults ≥60, mean 76, 77% women (Sherrington 2019) | Practice |
| O09 | Farmer's carry 2 × 20 s at the catalog's 50 lb | CHANGE | Open at about 10% of body mass per hand (else the lightest pair); calibrate by effort; check whether 50 lb is per hand | Human Movement Science 2026, PMID 42413170 (2–10% body mass) | 31 community-dwelling older adults | Weak (one biomechanics study) |

## The run cap holds; easy runs that never grow do not

The single-run cap is the best-supported rule in the running plan, and the full paper backs its exact number. In 5,205 runners followed over 588,071 GPS-logged sessions, the reference band was "regression … or up to 10% progression" over the longest run in the previous 30 days. Spikes above it raised the overuse injury rate: **>10–30% HRR 1.64 (95% CI 1.31–2.05), >30–100% 1.52 (1.16–2.00), >100% 2.28 (1.50–3.48)**. Week-to-week ratios showed nothing, and the acute:chronic ratio was inversely associated ([Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)). Three caveats come with it. The cohort was experienced (median 9.5 years of running) and 77.9% male, so novices are not represented. Even a 1 to 10% rise carried an HRR of 1.19 (95% CI −10% to +57%, not significant), so 110% is the edge of the reference band, not a safe zone. And the exposure was distance, which makes the code's seconds fallback an extrapolation. Sessions less than 15 minutes apart were merged, which matches the app counting WORK plus RECOVERY. The authors' own advice, "may consider using a session-specific cut-off below 10%", fits a 110% ceiling exactly.

The rules most likely to hurt runners are not the cap. Ruled easy runs return `repeat()` (code-checked). Easy runs are most of a runner's volume, and "Just cardio fitness" is nothing but easy runs, so the default plan never progresses. Weekly distance, runs per week and longest run all track marathon finish time across 137 cohorts ([Doherty 2020](https://pubmed.ncbi.nlm.nih.gov/31704026/)). The run/walk ladder's table itself is good. It starts and ends where Couch-to-5K does, and it smooths C25K's week-5 jump from 8 to 20 minutes, the jump drop-outs blamed for quitting and injury. In the one C25K outcome study only **27.3% completed, 64.5% dropped out, and a previous injury raised injury odds sevenfold (OR 7.56)** ([Relph 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10487403/)). The problem is the advance rule. Rating-free advancement makes the app's ladder faster than C25K (27 sessions), Start to Run (6 weeks; [Fokkema 2019](https://pubmed.ncbi.nlm.nih.gov/29934211/)) and GRONORUN (8 weeks). And longer programs were not safer: 13 weeks under the 10% rule gave 20.8% injured against 20.3% on 8 weeks ([Buist 2008](https://pubmed.ncbi.nlm.nih.gov/17940147/)). So the case is not for a longer ladder. It is for not letting the ladder run faster than published programs when the user gives no rating.

Tapers have the right length but cut too little. A two-week taper with volume "exponentially decreased by 41–60%", intensity kept, was best across 27 studies ([Bosquet 2007](https://pubmed.ncbi.nlm.nih.gov/17762369/)). In 14 endurance studies, **only the 41–60% band improved time trials significantly (SMD −0.77)** ([Wang 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10171681/)). Recreational marathoners on strict three-week tapers finished a median 2.6% faster ([Smyth & Lawlor 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8506252/)). The app's ≤10K taper (×0.6) cuts 40%, the half's first week cuts 30%, and the marathon schedule averages a 37% cut, all below that band. These carry a performance cost only. Riegel is well calibrated to the half. For the marathon it ran a **median 10:09 too fast, and at least 19:47 too fast for a quarter of runners**. The study's primary exponent was 1.07, not the app's 1.06 ([Vickers & Vertosick 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC5000509)). By the researchers' arithmetic, k = 1.06 × 1.05 equals k = 1.07 × about 1.043 from a half anchor, ×1.035 from a 10K and ×1.028 from a 5K. It roughly fixes the median from a half and under-corrects from shorter anchors. The talk-test words now have a primary source: the equivocal stage reflects the first ventilatory threshold and the negative stage the respiratory compensation threshold ([Bok 2022](https://pubmed.ncbi.nlm.nih.gov/35507232/)). The 2-minute test-interval recovery also has one now: 6 × 4 min reps were no faster with 4 min than with 2, and trained men chose 118 s on their own ([Seiler & Hetlelid 2005](https://pubmed.ncbi.nlm.nih.gov/16177614/)).

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| R01 | Mostly easy: 1 quality at 3–4 days, Q2 at 5–6 for Some or Racing | KEEP | — | Campos 2022, doi:10.1055/a-1559-3623, PMID 34749417; Rosenblat 2025, doi:10.1007/s40279-024-02149-3 (summary level) | Middle- and long-distance runners; 348 athletes, 150 recreational | Moderate |
| R02, R03 | Rotation rows; REST after the long run | LABEL AS PRACTICE | — | None | — | None |
| R04 | Easy, Good, Hard as talk-test stages (internal RPE bands) | KEEP the words (VERIFY → SUPPORTED); LABEL the RPE numbers AS PRACTICE | — | Bok 2022, doi:10.1007/s40279-022-01690-3, PMID 35507232; Vieira 2022, PMC11266803 | Healthy and athletic (Bok); mostly coronary patients (Vieira) | Moderate |
| R05 | Run cap ≤110% of the family's 30-day longest | KEEP (VERIFY → SUPPORTED) | — | Frandsen 2025, doi:10.1136/bjsports-2024-109380, PMID 40623829 | 5,205 runners, mean age 45.8, 77.9% male, median 9.5 years running | Moderate (one exploratory cohort) |
| R06 | Cap mechanics (WORK + RECOVERY; metres else seconds; rounding) | LABEL AS PRACTICE, except WORK + RECOVERY, which matches Frandsen | — | Frandsen 2025 methods (sessions <15 min apart merged) | As R05 | Practice; seconds form is an extrapolation |
| R07 | Setup answer stands in until a finished session or 30 days | LABEL AS PRACTICE | — | Frandsen's 30-day window | As R05 | Practice |
| R08 | Ladder sessions exempt from the cap | LABEL AS PRACTICE | — | Ladder sessions stay about 22–32 min across all levels (arithmetic from `RunWalkLadder.LEVELS`) | — | Practice |
| R09 | +5% per finished session (1–25% clamp) for long runs and converted cardio | LABEL AS PRACTICE; CHANGE the clamp | Clamp ceiling 10% for run families | Frandsen 2025 (≤10% band); Buist 2008, doi:10.1177/0363546507307505, PMID 17940147 | Experienced runners; novices | Practice |
| R10 | Easy = minutes − 5 − 25 on strength days, ≥20 min, ≤75% of stored long run | LABEL AS PRACTICE (fix through R11) | — | Garber 2011, doi:10.1249/MSS.0b013e318213fefb, PMID 21694556 (≥20–30 min/day is a health dose, not a run length) | Adults | Practice |
| R11 | Ruled easy runs repeat; Just cardio never progresses | CHANGE | Grow easy runs by time on full success (5%) up to the session minutes, under R05; for novices keep weekly growth ≤30% over 2 weeks and ideally <20% a week early | Doherty 2020, doi:10.1016/j.jsams.2019.09.013, PMID 31704026; Fokkema 2020, doi:10.1111/sms.13725; Nielsen 2014, doi:10.2519/jospt.2014.5164, PMID 25155475; Damsted 2019, doi:10.2519/jospt.2019.8541, PMID 30526231 | Marathon cohorts; Dutch recreational runners; 874 novices; 261 half-marathon runners | Moderate (volume tracks performance), Weak (growth limits) |
| R12, R16 (distance ends) | Half opens 18 km, stops 21 km; marathon opens 28 km, stops 32 km | KEEP | — | Fokkema 2020 (half longest run >21 km faster; marathon <25 km slower, >35 no better than 30–35) | 556 half and 441 marathon runners | Moderate (observational) |
| R12, R16 (time caps) | 2 h half, 3 h marathon caps | LABEL AS PRACTICE | — | Fokkema's mean 5:41/km makes ~29 km ≈ 2 h 45 min (arithmetic) | As above | Practice |
| R13 | Base long run opens at 10,000 m or 60 min | LABEL AS PRACTICE | — | None | — | None |
| R14, R17 | Ultra opens and stops (50K 30 km/3.5 h; 50 mi; 100K/100 mi 4 h → 6 h) | LABEL AS PRACTICE | — | None new (first review: weak convention) | — | Practice |
| R15 | Middle distance long run 60 min | LABEL AS PRACTICE | — | Elite descriptive only | Elite | Practice |
| R18 | VO2max 5 → 6 × 3 → 4 min, equal recovery | KEEP | — | Seiler & Hetlelid 2005, doi:10.1249/01.mss.0000177560.18014.d8, PMID 16177614; Wen 2019, PMID 30733142 (first review) | Nine well-trained men; adults | Moderate |
| R19–R21, R23 | Threshold, tempo, race-pace, sharpening and test-interval ranges | LABEL AS PRACTICE | — | Not re-researched | — | Practice |
| R22 | Sharpening recovery 4 min | LABEL AS PRACTICE | — | None | — | None |
| R24 | Test-interval recovery 2 min | KEEP (now sourced) | — | Seiler & Hetlelid 2005 (2 min ≈ best; self-chosen 118 ± 23 s) | Well-trained men | Weak |
| R25 | Test reps at 95% of goal-pace time | LABEL AS PRACTICE | — | None | — | None |
| R26–R28 | Aim rounding; one unit every two counted sessions; what counts | LABEL AS PRACTICE | — | None (design mechanics) | — | None |
| R29, R30 | Quality kind by event; fitness tests' long entry is easy | LABEL AS PRACTICE | — | First review's event table | — | Practice |
| R31 | Taper length 10 / 14 / 21 days | KEEP | — | Bosquet 2007, doi:10.1249/mss.0b013e31806010e0, PMID 17762369; Wang 2023, doi:10.1371/journal.pone.0282838, PMID 37163550; Smyth & Lawlor 2021, doi:10.3389/fspor.2021.735220, PMID 34651125 | Competitive athletes; endurance athletes; >158,000 recreational marathoners | Moderate |
| R32 | ≤10K and tests ×0.6; marathon/ultra ×0.8/0.6/0.5 | CHANGE | ≤10K ×0.5; marathon about ×0.75/0.55/0.4 (mean cut ~43%) | Bosquet 2007 (41–60%); Wang 2023 (only 41–60% significant, SMD −0.77) | As R31 | Moderate |
| R33 | Half ×0.7 (14–8 days out), ×0.5 (last 7) | CHANGE | ×0.6 then ×0.45 (mean cut ~47%) | As R32; no half-specific trial | As R31 | Moderate (band), Practice (half split) |
| R34 | Taper scales WORK only; intensity kept | KEEP | — | Bosquet 2007 (intensity and frequency kept) | Competitive athletes | Moderate |
| R35 | Riegel exponent 1.06 | KEEP for ≤ half marathon | — | Vickers & Vertosick 2016, doi:10.1186/s13102-016-0052-y, PMC5000509 (calibrated at 10K and half; primary k 1.07, 1.06 as sensitivity) | 2,303 recreational runners, 61% male | Moderate |
| R36 | Marathon goal check ×1.05; ultras none | LABEL AS PRACTICE; soften copy | — (later: add weekly mileage, Vickers Model 1; equations in a supplement not retrieved) | Vickers & Vertosick 2016 (median −10:09; 25th percentile −19:47) | As R35 | Moderate (the miss), Practice (×1.05) |
| R37 | Gates: 10 km base, 21 km half, 30 km or a marathon | LABEL AS PRACTICE | — | Fokkema 2020 (thresholds describe faster finishers; no injury association) | As R12 | Practice (consistent with observational data) |
| R38 | Ladder done at 30 min or 5 km; time and week forms | KEEP 30 min; LABEL other forms AS PRACTICE | — | Relph 2023, doi:10.3390/ijerph20176682, PMC10487403 (C25K ends at 30 min) | 110 adults, 81.8% women | Practice |
| R39 | Phase start; never refuses an event | LABEL AS PRACTICE | — | — | — | Practice |
| R40 | Minimum weeks: ladder 8, base 6, each build 8 | LABEL AS PRACTICE | — | Buist 2008 (8 vs 13 weeks equal injury); C25K 9 weeks, Start to Run 6, GRONORUN 8 | Novices | Practice |
| R41 | Ladder level 1: 60 s run / 120 s walk × 8 | KEEP | — | Relph 2023 (C25K week 1: 60/90 × 8; app is gentler) | As R38 | Practice |
| R42 | Ladder levels 2–12 | KEEP, LABEL AS PRACTICE (adapted from C25K) | — | Relph 2023 (same start and end; app's longest bout never rises more than 60%) | As R38 | Practice |
| R43 | Easy or unrated → next level; Good repeats; Hard or Pain → down | CHANGE | Unrated repeats; consider ≥2 sessions per level | Relph 2023 (drop-outs: "progress too quickly"); Videbæk 2015, PMID 25951917 (17.8 vs 7.7 injuries per 1,000 h) | Novices | Weak (no trial of per-session advance) |
| R44 | Ladder start from the longest run's time | LABEL AS PRACTICE | — | None | — | None |
| R45 | Past running injury → one level lower | KEEP | — | Relph 2023 (previous injury OR 7.56, 2.06–27.75) | As R38 | Weak |
| R46 | Ladder 3 sessions a week | KEEP | — | Relph 2023 (C25K is 3 a week) | As R38 | Practice |
| R47–R49 | Strength blocks on run plans | See the concurrent table | — | — | — | — |
| R50 | Intervals drop rounds down to one to fit minutes | CHANGE | Floor at the rounds giving ≥15 min of work, else swap to an easy run | Wen 2019, PMID 30733142 (only ≥15 min of work beat continuous; from the first review, not re-read) | Adults | Moderate |
| R51 | Event constants and defaults | KEEP | — | Definitions | — | — |
| R52 | Not built: novice weekly distance ≤30% over two weeks | KEEP as planned; label it exploratory | — | Nielsen 2014 (HR 1.59, 0.96–2.66, p = .07, distance-related injuries only; overall null) | 874 novices | Weak |
| R53, R54 | Not built: marathon-pace segments, practice tests, strides, long run at 25–30% of weekly volume | LABEL AS PRACTICE | — | First review's event table | — | Practice |

## The sprint week schedules max velocity inside the Nordic recovery window

No study covers this app's sprint users: recreational adults training for 40 yards to 400 meters. The evidence comes from youth and team-sport athletes, recreationally active men in Nordic trials, and elite coaching practice, so every threshold below is practice-grade even where a number has a source. Haugen's integration of science and best practice is the only source for session shapes. Most of the app's ranges sit inside its Table 2: acceleration 10 to 50 m with 100 to 300 m a session; max velocity 10 to 30 m flying with 50 to 150 m a session; sprint-specific endurance 80 to 150 m at more than 95%, with 8 to 30 minutes of rest ([Haugen 2019](https://pmc.ncbi.nlm.nih.gov/articles/PMC6872694/)). Its untrained examples match the app's max-velocity opening (10 m flying after a 20 m build-up, about 4 min of rest). For sprint endurance they give "2–3 × 100-m sprints with 10-min recovery" and **"1–2-min recovery … for every second spent on maximal sprinting"**. That makes the app's 480 s an elite floor applied to the slowest users. Alcaraz's ">160 m per session, about 2,680 m a program, 2 to 3 times a week, at least 6 weeks" is now confirmed from the abstract. But it describes resisted-sled programs in team-sport men that did not beat unresisted controls ([Alcaraz 2018](https://pubmed.ncbi.nlm.nih.gov/29926369/)). It is not evidence about a novice's dose.

The day order is where someone gets hurt. After 4 × 10 Nordics, high-speed horizontal force in a 30 m sprint stayed down for up to 48 h, and the authors advise separating Nordic sessions from demanding running **by at least 72 hours** ([Alonso-Fernandez 2024](https://pubmed.ncbi.nlm.nih.gov/34748450/)). After a first bout of 24 Nordic reps, trained men's eccentric strength stayed down through day 3. A second bout four weeks later cost only day 1 ([Coratella 2025](https://pubmed.ncbi.nlm.nih.gov/40280182/)). So the worst interference falls on a user's first Nordic sessions, which is exactly when a new 3-day user meets the row that puts Lower + Nordic right before Sprint B. Haugen's example weeks put strength the day after a sprint day and tempo or rest the day before. Rest entries are rotation slots, not calendar days, so even the 4-day layout's wraparound (Lower + Nordic, then the next rotation's Sprint A) can land on consecutive days.

The weekly cap in PR #586 creates the spike it was meant to prevent. Team-sport GPS data link hamstring strain to transient high-speed running above the athlete's own norm: **OR 6.44 (2.99–14.41) for the week before injury** ([Duhig 2016](https://eprints.qut.edu.au/221358/)), with the strongest association within 7 to 14 days ([Ruddy 2018](https://pubmed.ncbi.nlm.nih.gov/27884865/), AUC 0.63, individual utility "limited"). Under-exposure also raised risk, in a U-shaped curve ([Malone 2017](https://pubmed.ncbi.nlm.nih.gov/27554923/)). The compounding allowance (`after ≤ before × 1.15^(1 + held)`) holds the long-run average near 15% a week but lets one week jump by 60 to 100% after a few holds. No study tests any percentage cap for sprinting. The nearest sourced number is Nielsen's >30% band in novice distance runners, which was not significant overall. A per-week ceiling near 30% is therefore the least-bad anchor, labeled as practice. Detraining sets the reset threshold. Sprint speed decays slowly, about 1.5 to 3% over a 6-week off-season in professional soccer players ([Koundourakis 2014](https://pmc.ncbi.nlm.nih.gov/articles/PMC3929557/)). But Nordic-built fascicle length reverses **within 2 weeks** of stopping, while eccentric strength holds about 4 ([Presland 2018](https://pubmed.ncbi.nlm.nih.gov/29572976/)), and Haugen calls the period after a break vulnerable. Fourteen days fits that; the coded 21 days per sprint day does not.

The Nordic's evidence is now verified, and it is weaker than the first review's headline. Across 15 studies and 8,459 athletes the pooled RR was **0.49 (0.32–0.74)** ([van Dyk 2019](https://pubmed.ncbi.nlm.nih.gov/30808663/)). Restricted to RCTs that randomized to the Nordic, it was **0.59 (0.27–1.29), prediction interval 0.10–3.29**: "inconclusive", and soccer-only at best ([Impellizzeri 2021](https://opus.lib.uts.edu.au/handle/10453/151354)). Strength is the solid outcome. As few as 48 reps in 6 weeks raised eccentric strength, but low volumes did not lengthen fascicles ([Behan 2022](https://pubmed.ncbi.nlm.nih.gov/35176727/)). A 42-study meta-regression found no volume-strength relation and suggests **29 to 64 reps a week after a progressive introduction** ([Franke 2025](https://pubmed.ncbi.nlm.nih.gov/40991853/)). The app's 8 to 12 reps a week is a right first exposure that never grows. Plyometric moderators favor more than 80 combined jumps a session, at least 15 sessions inside 10 weeks, high intensity and horizontal jumps ([de Villarreal 2012](https://pubmed.ncbi.nlm.nih.gov/22240550/)). The app's once-a-week pogos and A-skips are low-intensity vertical work, and at one exposure a week the 15-session mark takes 15 weeks. Timing data settle two smaller rules. The "0.22 to 0.26 s" hand-timing bias is Mann 2015's figure, with a **smallest worthwhile difference of 0.14 s hand-timed** ([Mann 2015](https://pubmed.ncbi.nlm.nih.gov/25785707/)). Typical error falls by √n trials, and short sprints need repeats ([Haugen & Buchheit 2016](https://martin-buchheit.net/wp-content/uploads/2015/11/haugen-buchheit-sprint-monitoring-sports-med-review-in-press.pdf)).

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| S01 | 4-day: Sprint A + plyos, Lower, Rest, Sprint B, Lower + Nordic; 5 adds Tempo, 6 Upper | KEEP, LABEL AS PRACTICE; flag the wraparound | — | Haugen 2019, doi:10.1186/s40798-019-0221-0, PMC6872694 (Table 3) | Elite coaching practice | Practice |
| S02 | 3-day row: A + plyos, R, Lower + Nordic, B, R | CHANGE | A + plyos, Lower, R, B + Nordic, R | Alonso-Fernandez 2024, doi:10.1080/14763141.2021.1992493, PMID 34748450; Coratella 2025, doi:10.1055/a-2595-3622, PMID 40280182; Haugen 2019 Table 3 | 24 men; 13 trained men; elite practice | Weak (small trials), consistent with practice |
| S03 | Two sprint days at least one rest entry apart | KEEP, LABEL AS PRACTICE | — (copy: "leave a day between sprint days") | Haugen 2019 (≥48 h) | Elite practice | Practice |
| S04 | Event mapping (40 yd–100 m: accel + max velocity; 200–400 m: max velocity + sprint endurance) | KEEP, LABEL AS PRACTICE | — | Haugen 2019 | Elite practice | Practice |
| S05 | Acceleration 4 → 8 × 10 → 30 m, 1 min per 10 m | KEEP, LABEL AS PRACTICE | — | Haugen 2019 Table 2; Haugen & Buchheit 2016, doi:10.1007/s40279-015-0446-0 (20 m reps on 60 s without fatigue) | Junior soccer players | Practice |
| S06 | Non-intro acceleration opens 8 × 20 m = 160 m | KEEP, LABEL AS PRACTICE | — | Haugen 2019 (100–300 m); Alcaraz 2018, doi:10.1007/s40279-018-0947-8, PMID 29926369 | Team-sport men (sled programs) | Practice |
| S07 | Intro if no all-out sprint in a month or a hamstring strain in 12 months | KEEP, LABEL AS PRACTICE | — | Petersen 2011, doi:10.1177/0363546511419277, PMID 21825112 (recurrence 45.8 vs 7.1 per 100 player-seasons) | Danish male soccer players | Practice |
| S08 | Intro opens acceleration 4 × 10 m = 40 m, max velocity 3 × 10 m | CHANGE (acceleration); LABEL max velocity AS PRACTICE | Acceleration about 100 m (e.g. 5 × 20 m), caution carried by effort (build-ups, then full effort) | Haugen 2019 (100 m floor; untrained 20 m example; gradual familiarization); Malone 2017, doi:10.1016/j.jsams.2016.08.005, PMID 27554923 | Elite practice; 37 elite Gaelic footballers | Practice |
| S09 | Max velocity 3 → 5 × 10 → 20 m flying, 240 s | KEEP, LABEL AS PRACTICE | — | Haugen 2019 untrained example | Elite practice | Practice |
| S10 | Sprint endurance 3 → 4 × 100 → 150 m, 480 s | CHANGE | Rest ≥600 s, scaling with rep time; intro opens 2 × 100 m | Haugen 2019 (2–3 × 100 m on 10 min untrained; 1–2 min per second) | Elite practice | Practice |
| S11 | Tempo 6 × 100 → 300 m, 60 s walks, Good band | LABEL AS PRACTICE | — | Haugen 2019 (1,000–2,000 m, 1–3 min; 10-rep examples) | Elite practice | Practice |
| S12 | 600 s build-up warm-up before maximal blocks | LABEL AS PRACTICE | — | None for 10 min | — | Practice |
| S13 | Sprint sessions skip the run cap | KEEP | — | Run cap is a distance-running rule (Frandsen 2025) | — | Practice |
| S14 | 40–80 plyometric contacts per session | KEEP, LABEL AS PRACTICE | — | de Villarreal 2009, doi:10.1519/JSC.0b013e318196b7c6, PMID 19197203 (>50); de Villarreal 2010 (>40, snippet); de Villarreal 2012, doi:10.1519/JSC.0b013e318220fd03, PMID 22240550 (>80) | Mostly young team-sport athletes | Practice |
| S15 | Plyometrics on Sprint A only, opening 65 contacts | CHANGE | Two exposures a week wherever ≥2 lower or sprint days, about 40 contacts each for novices | de Villarreal 2012 (≥15 sessions in <10 weeks); Haugen 2019 Table 3 (two plyo days) | As S14 | Weak |
| S16 | Plyometric slots at rir 4, 60 s rest | LABEL AS PRACTICE | — | None (quality judged by intensity and contact time, Haugen 2019) | — | None |
| S17 | Pogo +1 rep a counted session to 20 (65 → 80) | CHANGE | Progress by exercise intensity and direction (pogo, A-skip → hops, bounds → box or low drop jumps), not reps | de Villarreal 2009, 2012; Park 2025, PMID 40910931 | Team-sport athletes; 17 taekwondo athletes | Weak |
| S18 | Nordic or a regression on one lift day a week | KEEP (VERIFY → supported for strength, contested for injury) | — | van Dyk 2019, doi:10.1136/bjsports-2018-100045, PMID 30808663; Impellizzeri 2021, J Clin Epidemiol 140:111–124; Petersen 2011; Behan 2022 | Mostly soccer players | Moderate (strength), Weak-contested (injury) |
| S19 | Nordic 2 × 4–6; run plans 2 × 6 | KEEP as the opening; CHANGE the progression | About 30 reps a week by weeks 6–8 (e.g. 3 × 6–10 once, or 2 × 6–8 twice) | Franke 2025, doi:10.1519/JSC.0000000000005184, PMID 40991853; Behan 2022, doi:10.1123/ijspp.2021-0276, PMID 35176727 | 42 studies, mixed athletes; 40 recreationally active men | Low certainty (Franke) |
| S20, S21 | Sprint lift days and Strength-rule mains | KEEP, LABEL AS PRACTICE | — | Haugen 2019; Seitz 2014 not re-read | — | Practice |
| S22 | Sprint accessories 3 × 8–12 at 2 RIR | LABEL AS PRACTICE | — | Haugen 2019 (early-preparation hypertrophy block) | — | Practice |
| S23 | Order: balance, plyos, lifts, Nordic; sprint block first | KEEP | — | Haugen 2019 ("sprints before strength") | — | Practice |
| S24 | Compare only within distance, method and start | KEEP | — | Mann 2015, doi:10.1519/JSC.0000000000000941, PMID 25785707; Mayhew 2010, doi:10.1519/JSC.0b013e3181c08860, PMID 20072055; Hetzler 2008, doi:10.1519/JSC.0b013e318185f36c | NCAA football players; trained runners | Strong (measurement logic) |
| S25 | HAND within 0.10 s of the best reads SAME (`HAND_SAME_CENTIS = 10`, strict) | CHANGE | About 0.15 s | Mann 2015 (smallest worthwhile difference 0.14 s hand); Hetzler 2008 (absolute error 0.15–0.16 s); Mayhew 2010 (0.19 s spread between timers) | As S24 | Moderate |
| S26 | 40 yd stored as 37 m | KEEP | — | Arithmetic (36.576 m); never compared across distances | — | — |
| S27 | No sprint light week | LABEL AS PRACTICE | — | Haugen 2019 (2:1 or 3:1 is coaching practice) | Elite practice | Practice |
| S28 | A range steps every two counted sessions | LABEL AS PRACTICE | — | None | — | None |
| S29 | Weekly max-speed metres ≤ before × 1.15^(1 + held) | LABEL 15% AS PRACTICE; CHANGE the compounding | No single week above about 30% over the last | Duhig 2016, BJSM 50:1536 (OR 6.44); Ruddy 2018, doi:10.1136/bjsports-2016-096777, PMID 27884865; Nielsen 2014 (nearest number) | Elite AFL players; novice distance runners | Weak |
| S30 | Gap reset when days since the day's last session − 7 ≥ 14 (21 days) | CHANGE | 14 days since the last maximal sprint session of any kind | Presland 2018, doi:10.1111/sms.13085, PMID 29572976; Haugen 2019 | 20 recreationally active men; elite practice | Practice |
| S31 | Intro = three counted sessions per sprint day | LABEL AS PRACTICE | — | Coratella 2025 (repeated-bout effect fits a short intro) | 13 trained men | Practice |
| S32 | Time trial every fifth rotation, never in the intro | KEEP, LABEL AS PRACTICE | — | Haugen & Buchheit 2016 (smallest worthwhile change ~1% vs single-trial error ~2%) | — | Practice |
| S33 | Time trial = one rep at the goal distance | CHANGE | Two reps with full recovery for 40 yd and 60 m; one rep for 100–400 m | Haugen & Buchheit 2016 (error falls with √n); Mann 2015 (two-trial protocol) | — | Practice |
| 5c Q4 | Tempo outside the weekly cap | KEEP, LABEL AS PRACTICE | — | Haugen 2019 (tempo 60–70%, 24 h turnaround) | Elite practice | Practice |

## Same visit suits most users; trained lifters and one split are exceptions

Concurrent training costs size and maximal strength nothing measurable. Across 43 studies with strength-only controls, maximal strength SMD was −0.06 and hypertrophy −0.01. **Explosive strength fell (SMD −0.28), and only within the same session, defined as 20 minutes or less between modes (−0.31)**. The penalty was gone at 3 hours apart. Same session, same day and different days did not differ for maximal strength or size ([Schumann 2022](https://pmc.ncbi.nlm.nih.gov/articles/PMC8891239/)). Lifting first added 6.91% lower-body dynamic strength ([Eddens 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5752732/)), or 3.96 kg ([Murlasits 2018](https://pubmed.ncbi.nlm.nih.gov/28783467/)), with VO2max unchanged. That supports putting the primary goal first (C01), having no cardio cap (C02) and the same-visit default at 3 or fewer days for general and size users (C03). The exception is trained Strength users. The first review found a lower-body 1RM penalty in trained lifters (ES −0.35) only in same-session designs (Petré 2021, PMC8053170; not re-read here). For them, separate days are the better default when the days allow. The other weak spot is the 1+1 separate-day split (C04): a lift-primary user on two days gets one strength day a week, under the strong WHO and ACSM floor of two.

For runners, strength training improved running economy by 2 to 8%. "The majority of studies that demonstrated improvements … scheduled ST 2–3 times per week". One session a week maintained gains in season. The effective programs used heavy loads (above 70% 1RM, 3 to 10 reps) and usually plyometrics, at **30 to 228 foot contacts a session** ([Blagrove 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5889786/)). That supports two strength blocks a week under Strength rules (R47, C06, C07), placed on easy days. It also makes the missing plyometrics in run strength blocks (R48) the main gap. No study sets a 25-minute block (R49) or a minimum easy run (C05's 20 minutes). WHO counts bouts of any length, and ACSM's vigorous floor is 20 min a day ([Garber 2011](https://pubmed.ncbi.nlm.nih.gov/21694556/)). Three 20-minute easy runs give 60 moderate minutes a week, so the app should never imply that a lift-first Both plan meets WHO's 150.

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| C01 | Primary goal first in a shared session | KEEP | — | Eddens 2018, doi:10.1007/s40279-017-0784-1, PMID 28917030; Murlasits 2018, doi:10.1080/02640414.2017.1364405, PMID 28783467 | Healthy adults | Moderate |
| C02 | No cardio minutes cap | KEEP | — | Schumann 2022, doi:10.1007/s40279-021-01587-7, PMC8891239 | 1,090 healthy adults | Strong to moderate |
| C03 | Together: separate days at 4+ days, same visit below | CHANGE (one case) | Separate days whenever days allow for a Strength sub-goal with A year or more; keep the default for others | Schumann 2022 (explosive −0.31 same session); Petré 2021, PMC8053170 (ES −0.35, same-session designs; not re-read) | Healthy adults; trained lifters | Weak-contested |
| C04 | Separate-day splits 1+0, 1+1, 2+1, 2+2, 3+2, 3+3 | CHANGE (1+1) | At 2 days, same visit, or keep two strength exposures for lift-primary users | WHO 2020 (≥2 strength days, strong); Blagrove 2018 (1 session/week maintains) | Adults | Strong (floor) |
| C05 | Lift first, same visit: lift side planned with minutes − 20; 20 min easy run fallback | KEEP 20 min, LABEL AS PRACTICE | — | Jakicic 2019, doi:10.1249/MSS.0000000000001933, PMC6527142 (bouts of any length); Garber 2011 (vigorous ≥20 min); Doma 2017, PMID 28702901 | Adults | Practice |
| C06 | Cardio first, same visit: run plan with two strength blocks | KEEP 2 blocks | — | Blagrove 2018, doi:10.1007/s40279-017-0835-7, PMC5889786 | Runners with ≥6 months, aged 17–45 | Moderate (benefit), inference (dose) |
| C07 | Run and Sprint plans use Strength lift rules | KEEP, LABEL AS PRACTICE | — | Blagrove 2018 (>70% 1RM, 3–10 reps) | As C06 | Practice |
| C08 | Both + sprint: sprint layout; Which first and Together not asked | LABEL AS PRACTICE | — | Schumann 2022 (separation matters more than order for power) | Healthy adults | Practice |
| R47 | Run plans carry 0–2 strength blocks (default 2) on easy or quality days | KEEP default 2; never place a block right after an interval or threshold session | — | Blagrove 2018 (>3 h after high-intensity running) | As C06 | Moderate |
| R48 | Run strength block: 65+ balance, SECONDARY squat or hinge, single-leg, calf, Nordic or knee flexion, core; 2 sets; no plyometrics | CHANGE | Add a plyometric item at the low end of 30–228 contacts a session; contents otherwise practice | Blagrove 2018 | As C06 | Moderate |
| R49 | 25 min reserved for a strength block | LABEL AS PRACTICE | — | Blagrove 2018 protocols (e.g. 4 × 4 half-squats) fit; no study sets a length | As C06 | Practice |

## The 45-second set misjudges heavy, high-rep, per-side and timed sets

The session estimate decides whether a day fits, so it decides when the dose ladder cuts accessories, second exposures and main sets. Its flat 45 s a set holds for a bilateral set of 10 to 12 reps and misses most others. A consensus review puts volitional reps at **about 2 to 5 s of time under tension** ([Wilk 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8310485/)). Measured concentrics at volitional tempo run 0.61 to 1.43 s depending on load ([Trybulski 2022](https://pmc.ncbi.nlm.nih.gov/articles/PMC9331330/)). At 2 to 4 s a rep, the work in a set is about 10 to 20 s for 5 reps and 40 to 80 s for 20 (arithmetic). So 45 s runs long on heavy sets and short on high-rep ones. It is furthest off for per-side sets (two sets of work back to back) and for timed holds and carries longer than 45 s, which are common in run strength blocks and older-adult days. Rest dominates the total, but a 30 s error per set over 15 to 20 sets still moves a 60-minute estimate by about 7 to 10 minutes (arithmetic). That is enough to change a ladder cut. The cool-down that #574 suggests is not budgeted at all. Typical programs "often exceed an hour" once warm-up and stretching are included ([Iversen 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8449772/)). No time-motion study measures whole-set duration or transitions in recreational gyms, so the setup allowance stays practice. The 6:00 per km pace for distance sets with no time aim is plausible, but many beginners run easy at 7:00 to 8:00, so the user's logged pace should replace it once any run exists. No primary source for typical easy pace was found.

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| G01 | A rotation, not weekdays; a missed session is never doubled | KEEP | — | Kaushal & Rhodes 2015, PMID 25851609 (first review; not re-audited) | 111 new gym members | Moderate |
| G02 | Estimate = 5 min warm-up + 45 s a set + rest; superset counts once; cardio at planned time | CHANGE | Set time = setup (about 10 s, practice) + reps × 3 s; × sides for per-side sets; timed sets at planned duration; keep 300 s warm-up | Wilk 2021, doi:10.1007/s40279-021-01465-2, PMC8310485; Trybulski 2022, doi:10.5114/biolsport.2022.106160, PMC9331330; Nóbrega 2018, PMID 29481458 | Trained men (Trybulski, Nóbrega); consensus review | Weak (per-rep time), Practice (setup) |
| G03 | Distance sets without a time aim at 0.36 s/m (6:00 per km) | LABEL AS PRACTICE | — (prefer logged pace once a run exists) | None found | — | None |
| G04 | Days 1–6; minutes 30–90; defaults 3 days, 60 min, Full gym, New | LABEL AS PRACTICE | — | WHO 2020 (3 days meets ≥2 strength days) | — | Practice |
| G05 | Equipment presets | LABEL AS PRACTICE | — | None (product) | — | None |

## Warm-ups help a little, cool-downs not at all, and neither earns a claim

All three citations in issue #574 check out on their own claims. Warm-up improved performance in **79% of criteria across 32 high-quality studies** ([Fradkin 2010](https://pubmed.ncbi.nlm.nih.gov/19996770/)). Static stretching cost 1.1% when held under 60 s per muscle and 4.6% at 60 s or more, while dynamic stretching gave +1.3%. Neither static stretching nor PNF had a clear effect on injury, and dynamic stretching has no injury data ([Behm 2016](https://pubmed.ncbi.nlm.nih.gov/26642915/)). An active cool-down is "largely ineffective" for recovery, soreness and injury ([Van Hooren & Peake 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5999142/)). The issue misattributes one claim, though: that review does not say post-training stretching improves flexibility. Thirty-second holds matched 60 s for hamstring flexibility over 6 weeks ([Bandy 1997](https://pubmed.ncbi.nlm.nih.gov/9327823/)). Range-of-motion gains depend on weekly stretching time, at least 5 min per muscle a week ([Thomas 2018](https://pubmed.ncbi.nlm.nih.gov/29506306/)), and ACSM recommends 60 s per exercise on at least 2 days a week ([Garber 2011](https://pubmed.ncbi.nlm.nih.gov/21694556/)). The app's 30 s per muscle on 2 to 4 days falls below both. That is fine while the cool-down makes no flexibility claim. For lifting specifically, a general warm-up adds little once main lifts are ramped. Neither a specific nor a 10-minute aerobic warm-up changed reps at 80% 1RM ([Ribeiro 2014](https://pubmed.ncbi.nlm.nih.gov/25153744/)), and the one 1RM gain (+8.4%) came from a 20-minute bike warm-up ([Abad 2011](https://pubmed.ncbi.nlm.nih.gov/21544000/)). The 2-minute general item is a time-saving compromise, not a dose from evidence.

| id | Rule as implemented | Verdict | New value | Primary sources | Population | Grade |
|---|---|---|---|---|---|---|
| W01 | 120 s general item, skipped when the cardio block has its own WARMUP; then 4–5 dynamic moves by ranked muscle credit | LABEL AS PRACTICE | — | Fradkin 2010, doi:10.1519/JSC.0b013e3181c643a0, PMID 19996770; Behm 2016, doi:10.1139/apnm-2015-0235, PMID 26642915; Ribeiro 2014, PMID 25153744; Iversen 2021 | Mostly athletic; 15 men (Ribeiro) | Moderate (some warm-up helps), Practice (dose) |
| W02 | Reps or seconds per dynamic move | LABEL AS PRACTICE | — | None | — | None |
| W03 | 30 s stretches, 180–300 s total | KEEP 30 s per stretch; LABEL the total AS PRACTICE | If ever framed as building flexibility: 2 × 30 s per muscle (60 s) | Bandy 1997, doi:10.1093/ptj/77.10.1090, PMID 9327823; Thomas 2018, doi:10.1055/s-0044-101146, PMID 29506306; Garber 2011 | 93 adults with tight hamstrings | Moderate |
| W04 | Fixed muscle lists for cardio-only days | LABEL AS PRACTICE | — | Van Hooren & Peake 2018, doi:10.1007/s40279-018-0916-2, PMC5999142 (use the muscles worked) | — | Practice |
| W05 | Estimate budgets warm-up but no cool-down | CHANGE | Add the 3–5 min cool-down to the estimate when the switch is on, or say the minutes cover warm-up and training only | None (promise-keeping, not evidence) | — | — |

## Where the changes land

**Already merged code.** Most of the changes land here. In `progression/Progression.kt`: the calibration jump (L28), the Easy-session doubling (L42), the ladder's unrated advance (R43), easy-run growth (R11), the 65+ layoff path (O10), and the interval round floor (R50, via `planner/RunLayout.kt`). In `progression/Effort.kt` and the effort row (`ui/day/WorkoutSetScreen.kt`, `strings.xml`, which currently shows the bare words "Easy", "Good", "Hard" and "Max"): the effort-word anchors and a 3-RIR word (L30). In `progression/Scheme.kt`: the back-off percentage (L08). In `progression/SlotRule.kt`: the upper-body increment (L40). In `progression/CardioMeasures.kt`: the run-family clamp (R09). In `progression/Taper.kt`: the taper factors (R32, R33). In `progression/RunRange.kt`: the sprint-endurance rest (S10). In `planner/SprintLayout.kt`: the 3-day row (S02), the intro acceleration opening (S08), the second plyometric exposure (S15) and the 4-day wraparound flag (S01). In `planner/RunLayout.kt`: plyometrics in run strength blocks (R48). In `planner/DayLayout.kt` and `library/ExerciseLibrary.kt`: the carry load (O09), plus the catalog check on whether 50 lb means per hand. In `records/SprintComparison.kt`: `HAND_SAME_CENTIS` from 10 to about 15 (S25). In `planner/BothLayout.kt`: the 1+1 split (C04). In `planner/SetupAnswers.kt`: the Together default for trained Strength users (C03). In `planner/SessionBudget.kt`: the per-set time and the cool-down budget (G02, W05). The S02 change is the one to ship first. It needs no new model, and it removes the one schedule the evidence ties directly to hamstring force loss before max-velocity running.

**The unmerged PR #586 (5c-i, sprint progression).** Four of its lead decisions conflict with this audit, and all four are cheaper to fix before merge than after. First, the compounding cap `after ≤ before × 1.15^(1 + held)` should become a per-week ceiling near 30% with finer filling units (S29). Second, the gap reset, "days since the day's last session minus its 7-day recurrence ≥ 14", should become 14 days since the last maximal sprint session of any kind (S30). Third, the time trial should be two reps for 40 yd and 60 m and stay one rep for 100 to 400 m (S33). Fourth, the test that pins pogo growth from 65 to 80 contacts by reps should give way to exercise progression in 5c-ii (S17). The PR's remaining choices hold as labeled practice: steps every two counted sessions (S28), a three-session intro (S31), tempo outside the cap, and the run-cap exemption (S13). Its design revision should say so.

**Unbuilt PRs.** 4b-ii (wiring cardio rules into Finish and phase gates) should carry the minutes ceiling for growing easy runs (R11) and pin the unrated-repeats ladder rule (R43) in its Finish tests. 4b-iii should build the novice two-week 30% rule (R52) as the companion limit for easy-run growth, labeled exploratory. 5c-ii (bounds, then box jumps) is where plyometric progression by exercise lands (S17), and the natural home for the Nordic progression to about 30 reps a week (S19). PR 6 (lift setup screens and copy) owns the 65+ balance-days answer (O07), the copy for the token balance dose (O08), the effort-word explanation that pairs with L30, the deferred New-lifter 3 → 2 RIR step (L11), and the volume, Epley and layoff copy listed below. PR 7 (run, sprint and Both screens, phone timer) owns the Together default's screen (C03), the run-cap, taper and marathon goal-check copy (R05, R32, R36), the Nordic copy (S18), and the personal-best and time-trial noise copy (S25, S32).

## Claims the app must not make

These limits apply to every string in `strings.xml`, to the PR 6 and PR 7 screens, and to the design doc's user-facing rationale. The right-hand column is acceptable wording, offered as practice.

| Claim to avoid | Why | Source | Acceptable instead |
|---|---|---|---|
| The Nordic "halves hamstring injuries" | The RCT-only pooled RR is 0.59 (0.27–1.29), prediction interval 0.10–3.29; nearly all trials are soccer | [Impellizzeri 2021](https://opus.lib.uts.edu.au/handle/10453/151354); [van Dyk 2019](https://pubmed.ncbi.nlm.nih.gov/30808663/) | "Builds hamstring strength; likely helpful, low risk" |
| The run cap "prevents injury", "keeps you safe", or that ≤110% is a safe zone | One exploratory cohort of experienced, mostly male runners; a 1–10% rise still showed HRR 1.19 (ns); observational | [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380) | "Keeps each run within 10% of your longest run this month" (no outcome claim) |
| The seconds form of the cap rests on evidence, or applies equally to beginners | The study measured distance only and could not analyze novices | Frandsen 2025 | Label it an extrapolation in the design |
| The balance block meets the fall-prevention dose, or "prevents falls" | Effective programs ran 91–120 min/week or >3 h/week of challenging balance; the block is about 2 min | [Lesinski 2015](https://europepmc.org/article/PMC/PMC4656699); [Sherrington 2017](https://pubmed.ncbi.nlm.nih.gov/27707740/) | "A short balance warm-up; more balance work on other days helps" |
| Weekly sets "plateau at 18–20" | ACSM's paraphrase; Pelland reports no clear plateau | [Pelland 2026](https://pubmed.ncbi.nlm.nih.gov/41343037/) | "Past about 20 sets, each gain costs many more sets" |
| A lift-first Both plan (e.g. 3 × 20 min easy) meets the weekly aerobic guideline | 60 moderate minutes against WHO's 150 | [Bull et al. 2020](https://doi.org/10.1136/bjsports-2020-102955) | Show logged minutes against 150 without saying the plan meets it |
| "Your marathon goal is realistic" from the ×1.05 check | About half of runners finish slower than a ×1.05 Riegel time from a half anchor, more than half from 5K or 10K anchors | [Vickers & Vertosick 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC5000509) | "About half of runners finish slower than this" |
| Run/walk or the ladder "prevents injury" | No outcome data for the method; C25K cohort: 19% injured | [Relph 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10487403/) | Describe it as an on-ramp |
| A taper is "proven" to make you faster | Recreational marathon data are observational; trial data are from competitive athletes | [Smyth & Lawlor 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8506252/); [Bosquet 2007](https://pubmed.ncbi.nlm.nih.gov/17762369/) | "Cuts volume and keeps intensity before race day" |
| A hand-timed gain under about 0.15 s is a personal best, or a 5-week time trial shows real change | Smallest worthwhile difference 0.14 s hand-timed; single-trial error (~2%) exceeds the smallest worthwhile change (~1%) | [Mann 2015](https://pubmed.ncbi.nlm.nih.gov/25785707/); [Haugen & Buchheit 2016](https://martin-buchheit.net/wp-content/uploads/2015/11/haugen-buchheit-sprint-monitoring-sports-med-review-in-press.pdf) | "Same as your best" inside the band |
| The sprint weekly limit or 40–80 contacts is "safe" or "evidence-based" | No study tests a sprint cap; contact moderators come from youth and team-sport athletes | [Duhig 2016](https://eprints.qut.edu.au/221358/); [de Villarreal 2012](https://pubmed.ncbi.nlm.nih.gov/22240550/) | No rationale line, or "a gradual build" |
| The cool-down "speeds recovery", "reduces soreness" or "prevents injury"; the stretches "improve flexibility" at their current dose | Active cool-down largely ineffective; 30 s per muscle on 2–4 days is below 60 s on ≥2 days and ≥5 min/week | [Van Hooren & Peake 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5999142/); [Garber 2011](https://pubmed.ncbi.nlm.nih.gov/21694556/); [Thomas 2018](https://pubmed.ncbi.nlm.nih.gov/29506306/) | "A few easy stretches to finish" |
| The warm-up "prevents injury" | No clear injury effect of static stretching; no injury data for dynamic | [Behm 2016](https://pubmed.ncbi.nlm.nih.gov/26642915/) | "Gets you moving before the first set" |
| Effort words are an accurate RIR measure | No study maps everyday words to RIR; far from failure the error is ~5 reps | [Zourdos 2021](https://pubmed.ncbi.nlm.nih.gov/30747900/); [Helms 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC4961270/) | Show the rep count each word stands for |
| An estimated 1RM is "your max" | ±6% of 1RM per SD between people; leg press overestimates and deadlift underestimates | [Nuzzo 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC10933212/) | "Estimate" |
| A layoff "lost you X% strength", or 65+ users "need to rebuild" after two weeks | No loss is predicted through ~3 weeks; older adults held gains through 12 weeks off | [McMaster 2013](https://pubmed.ncbi.nlm.nih.gov/23529287/); [Lemmer 2000](https://pubmed.ncbi.nlm.nih.gov/10949019/) | "Opening a little lighter after a break" |
| Light weeks are "required" or work "every 4th week" | Descriptive survey data only | [Rogerson 2024](https://pubmed.ncbi.nlm.nih.gov/38499934/) | "A planned lighter week" |

## Where this audit confirms, corrects or contradicts the first review

The first review's core findings stand: RIR feedback, the single-run cap, mostly easy cardio, tapers that cut volume, and same-visit training being fine for most users. Its list of figures to verify is now mostly resolved. Its misattributions are the ones most likely to have leaked into design text.

| Item in the first review | This audit | Outcome |
|---|---|---|
| Hand timing "about 0.22 to 0.26 s faster", cited to Hetzler (also in the folklore table) | The figure is Mann 2015 (81 NCAA Division I players). Hetzler 2008 found a mean bias of −0.04 to −0.05 s and absolute error of 0.15–0.16 s; Mayhew 2010 found 0.31 s | **Misattributed** |
| Hand-timed changes under "about 0.1 s" are noise | Smallest worthwhile difference 0.14 s hand-timed; absolute error 0.15–0.16 s | **Contradicted** (use ~0.15 s) |
| ACSM 2009's "about 40% untrained to 2% elite" average gains | Not in the ACSM 2009 full text; likely ACSM 2002 or Kraemer & Ratamess 2004 (unchecked) | **Misattributed** |
| Hypertrophy "returns flattening near 18 to 20" weekly sets (BLUF and body) | ACSM 2026's paraphrase; Pelland reports diminishing returns with no clear plateau and few studies past ~25 sets | **Corrected** |
| The "two consecutive sessions" rule found only in NSCA exam-prep summaries | ACSM 2009 states it for every training level (Evidence B) | **Corrected** (primary source exists) |
| Latella's kg asymmetry justifies ~2.5% upper vs ~5% lower | Latella is absolute kg; percent gaps are smaller (Helms 2018: ~8–9% bench vs 10–12% squat) | **Contradicted in framing** |
| Borde 2015: 2–3 sets, 7–9 reps, 2 sessions, "4 to 6 s between reps" | 2–3 sets, 7–9 reps, 2 sessions confirmed; 4.0 s between reps (strength), 2.5 s (size); 60 s rest for strength (two studies), 120 s for size | **Corrected** |
| "Beginners can use RIR"; training status has no influence | Holds on average (Halperin β −0.006), but accuracy "tended" to improve with experience (Steele 2017) and novices under-rate effort near a true max | **Qualified** |
| Grgic 2018 >2 min rest for trained strength [verify] | Confirmed from the abstract | **Confirmed** |
| Nuzzo 2024 values "should not be quoted" | Extracted: bench 4/9/14 reps at 90/80/70%; leg press 9/13/19; SD 2.51 reps at 80% | **Resolved** |
| Epley formulas and "10% or more" individual error from Wikipedia | Epley's ≤10-rep limit confirmed (Reynolds 2006, Mayhew 2008); cite ~±6% per SD from Nuzzo instead of Wikipedia | **Confirmed, re-sourced** |
| Schoenfeld 2017's 0.37% per set, from a secondary summary | 0.37% per added weekly set confirmed in the abstract | **Confirmed** |
| Pelland's square-root and reciprocal curves, from a secondary summary | Confirmed in the preprint | **Confirmed** |
| Indirect sets count 0.5 | Confirmed as best fit; 0.5 was the only fraction tested | **Confirmed, with caveat** |
| Bosquet 2013 "no loss up to 28 days"; older g 0.76 vs younger 0.31 | Still not in any readable text (paywalled) | **Still unverified** |
| Restart table: re-seed "any length for users over 65" | Lemmer 2000, Taaffe 2009 and Yang 2022 show older adults hold gains for weeks to months | **Contradicted** |
| A short balance block on lifting days satisfies WHO for 65+ | Misses WHO's 3 days on 2-day plans; dose far below effective trials | **Contradicted** |
| Frandsen 2025 figures from a BJSM blog, no DOI or CIs | Full paper read: HRR 1.64 / 1.52 / 2.28 with CIs; cohort experienced and 77.9% male; 1–10% rise HRR 1.19 (ns); sessions <15 min apart merged | **Confirmed, with new caveats** |
| Relph quit rates 48.2% and 64.5% from a blog | In the primary paper | **Confirmed** |
| Talk test valid against both thresholds, from a search summary | Bok 2022: equivocal stage ≈ VT1, negative stage ≈ respiratory compensation threshold | **Confirmed** |
| Riegel exponent 1.06 "well calibrated up to the half" | Calibrated at 10K and half, but Vickers' primary analysis used k = 1.07, with 1.06 as a sensitivity analysis; marathon median miss −10:09, 25th percentile −19:47 | **Confirmed, detail corrected** |
| Bosquet 2007 "8 to 14 days" and "about 3%" | Abstract confirms a 2-week taper and a 41–60% cut; the paraphrases stay secondary; Wang 2023 adds that only the 41–60% band was significant | **Partly confirmed** |
| Alcaraz 2018: ≥160 m a session, ≥6 weeks | Confirmed from the abstract, but the sled groups did not beat controls and the subjects were team-sport men | **Confirmed, narrowed** |
| de Villarreal 2012 ">80 jumps, ≥15 sessions" [verify] | Confirmed from the abstract | **Confirmed** |
| van Dyk RR 0.49; Impellizzeri RR 0.59 (0.27–1.29) [verify] | Both confirmed with CIs; only five of van Dyk's 15 studies randomized to the Nordic | **Confirmed** |
| Cuthbert g 2.12 and 2.28; Behan 48 reps [verify] | Confirmed; Behan adds that low volume did not lengthen fascicles | **Confirmed** |
| Wilson 2012 correlation values [verify] | Confirmed against the abstract | **Confirmed** |
| Two lower sessions a week, each opening with 40–80 plyometric contacts | Supported (de Villarreal 2012; Haugen Table 3); the code departs (Sprint A only) | **Confirmed; code departs** |
| Sprint reset "after two or more weeks off" | Supported as practice (Presland 2018); the code waits 21 days per sprint day | **Confirmed; code departs** |
| Test intervals with "equal recovery" | 2 min now sourced (Seiler & Hetlelid 2005) | **Refined** |
| Issue #574 cites Van Hooren & Peake for post-training stretching improving flexibility | That review does not say it; the sources are Bandy 1997, Thomas 2018, Garber 2011 | **Misattributed** (in the issue, not the review) |
| Rosenblat 2025; Seitz 2014; Petré 2021; Wen 2019 | Not re-read in this pass | **Unchanged** |

## What no study tests

These are the questions behind the practice labels. For each, the app's own logs are the only data that can answer it for this app's users.

| Open question | Rows affected | What the app can use instead |
|---|---|---|
| How everyday effort words map to reps in reserve | L30, L31, L32, L42 | Rated sets followed by a rep miss or a Max, per user |
| What first-load jump finds a working weight without a failed set | L28, L29 | Calibration sets followed by Max or a rep miss |
| What miss count and reset size work best | L33–L36 | Stall and reset histories |
| What restart load after a layoff works in any population; short layoffs after long-term training in older adults; illness or injury layoffs | L58–L61, O10 | First-session RIR after a scaled opening |
| Weekly set doses for women, adults over 40, and trained lifters above ~25 sets | L63–L68 | — (Pelland's sample was 79% male, mean age 25) |
| Per-muscle floors against per-pattern coverage for health outcomes | L67, L71 | — |
| Fall outcomes from a balance dose of a few minutes on 2–3 days | O07, O08 | — |
| Carry loads and safety in older beginners | O09 | Effort ratings on the carry |
| Per-session long-run growth; an easy-to-long ratio; a 20-min easy minimum; a half-specific taper; per-session effort-gated ladders against weekly ones; phase minimum lengths | R09–R11, R33, R40, R42–R43 | Finish rates and Pain flags by ladder level |
| Whether a 110% cap suits novices, or works by time | R05, R06 | — (Frandsen could not run subgroups) |
| A weekly-mileage term for the marathon check | R36 | Equations exist in Vickers' supplement, not yet retrieved |
| Any sprint dose, progression cadence or weekly cap (10, 15 or 20%) in recreational adults; warm-up length before maximal sprints | S05–S12, S28–S31 | Session times and Pain flags after steps |
| A heavy lower-body session the day before max velocity in recreational adults; a Nordic injury RCT outside soccer | S02, S18 | — |
| Plyometric rest periods and a contact cap for adult beginners; plyometrics for recreational sprinters | S14–S17 | — |
| Sprint light-week frequency | S27 | — |
| Hand-timing error over 100–400 m from a standing start; smartphone-video precision | S25, S33 | Paired timings of the same rep |
| Whole-set duration and transition time in recreational gyms; typical recreational easy pace | G02, G03 | Logged set and session durations; logged pace |
| A minimum effective easy-run length; 25 min against longer strength blocks for runners; aerobic-first against strength-first within a session (Schumann did not report it) | C05, R49, C01 | — |
| Warm-up dose per dynamic move; a 2-min general warm-up plus moves against ramp sets alone | W01, W02 | — |

Several sources were read only at abstract or secondary level and should be checked before copy quotes them. They are: Bosquet 2013's duration bands and age effects; Bosquet 2007's Table 2 (whether its percent cut means the taper mean or the final week, which the R32/R33 values assume is the mean); Pelland's published tier numbers (taken from the September 2024 preprint); Behan 2022's per-group sets; Petersen 2011's weekly protocol; Fragala 2019 (the NSCA older-adult statement, paywalled); McGowan 2015; the Alcaraz correction; LeSuer 1997, Wood 2002 and the DiStasio thesis; Nóbrega's self-selected tempo of about 1.5 s per phase; and the clip-at-creation behavior behind R11, which was inferred from code paths rather than traced end to end.

## Conclusion

The audit changes what "evidence-based" can honestly mean for this planner. Only a handful of its numbers come from a trial or a meta-analysis that tested something close to them: the run cap, Epley's limit, strength rest, the set floors, Borde's older-adult dose, taper lengths and the public-health floors. Everything else is convention, and much of it is good convention, inside ACSM 2009, Haugen's tables or C25K's shape. The defensible product stance is not "science says" but "inside what the evidence and established programs allow, and labeled where it is a choice". That points the design doc toward a practice label on most rows and away from rationale copy.

The errors that matter are mostly not wrong numbers. They are mechanisms that turn a sound average into a bad moment for one user: an allowance that saves up a spike, a jump sized for a rating that sits far from failure, an unrated session read as easy, a rest slot that is not a rest day, an easy run whose first length becomes its last. Those failure modes show up in the app's own logs before any study could find them. The most useful follow-up to this audit is therefore instrumentation: count calibration sets that end in Max, ladder levels passed without a rating, and weeks where max-speed metres jump, and let those counts decide which practice labels should become tested rules.

## References

Lifting, effort and rest

- ACSM (2009). Progression models in resistance training for healthy adults. Med Sci Sports Exerc 41(3):687–708. doi:10.1249/MSS.0b013e3181915670; PMID 19204579.
- Currier BS, et al. (2026). Resistance training prescription for muscle function, hypertrophy, and physical performance in healthy adults: ACSM Position Stand. Med Sci Sports Exerc. doi:10.1249/MSS.0000000000003897; PMC12965823.
- Halperin I, et al. (2022). Sports Med 52:377–390. doi:10.1007/s40279-021-01559-x; PMID 34542869.
- Refalo MC, et al. (2024). J Strength Cond Res 38(3):e78–e85. doi:10.1519/JSC.0000000000004653; PMID 37967832.
- Remmert JF, et al. (2023). Percept Mot Skills. doi:10.1177/00315125231169868; PMID 37036795.
- Zourdos MC, et al. (2021). J Strength Cond Res 35(2S):S158–S165. doi:10.1519/JSC.0000000000002995; PMID 30747900.
- Zourdos MC, et al. (2016). J Strength Cond Res 30(1):267–275. doi:10.1519/JSC.0000000000001049; PMID 26049792.
- Ormsbee MJ, et al. (2019). J Strength Cond Res 33(2):337–345. PMID 28301439.
- Steele J, et al. (2017). PeerJ 5:e4105. PMID 29204323; PMC5712461.
- Helms ER, et al. (2016). Strength Cond J 38(4):42–49. PMC4961270.
- Helms ER, et al. (2018). Front Physiol 9:247. PMC5877330.
- Gearhart RF, et al. (2009). J Strength Cond Res. PMID 19387373.
- Hickmott LM, et al. (2022). Sports Med Open 8:9. PMID 35038063.
- Larsen S, et al. (2021). PeerJ. PMID 33520457.
- Latella C, et al. (2022). Med Sci Sports Exerc. doi:10.1249/MSS.0000000000002858; PMID 35019902.
- Latella C, et al. (2020). J Strength Cond Res. PMID 32865942.
- Steele J, et al. (2023). Res Q Exerc Sport. doi:10.1080/02701367.2022.2070592; PMID 35591809.
- Plotkin D, et al. (2022). PeerJ. PMID 36199287.
- Reynolds JM, Gordon TJ, Robergs RA (2006). J Strength Cond Res 20(3):584–592. doi:10.1519/R-15304.1; PMID 16937972.
- Mayhew JL, et al. (2008). J Strength Cond Res. doi:10.1519/JSC.0b013e31817b02ad; PMID 18714230.
- LeSuer DA, et al. (1997). J Strength Cond Res 11(4):211–213. doi:10.1519/00124278-199711000-00001 (via Eston & Evans 2009, PMC3761544).
- Nuzzo JL, et al. (2024). Sports Med 54:303–321. doi:10.1007/s40279-023-01937-7; PMC10933212.
- Robinson ZP, et al. (2024). Sports Med. doi:10.1007/s40279-024-02069-2; PMID 38970765.
- Refalo MC, et al. (2023). Sports Med. PMID 36334240.
- Grgic J, et al. (2018). Sports Med 48:137–151. doi:10.1007/s40279-017-0788-x; PMID 28933024.
- Schoenfeld BJ, et al. (2016). J Strength Cond Res 30(7):1805–1812. PMID 26605807.
- Singer A, et al. (2024). Front Sports Act Living. PMID 39205815.
- Rogerson D, et al. (2024). Sports Med Open. PMID 38499934.
- Coleman M, et al. (2024). PeerJ. PMID 38274324.
- HPRC (US DoD). One-rep max for strength (NSCA protocol, secondary). https://www.hprc-online.org/articles/one-rep-max-for-strength
- StrongLifts 5×5. How to overcome failure (program author). https://stronglifts.com/stronglifts-5x5/failure/

Volume, layoffs and older adults

- Pelland JC, et al. (2026). Sports Med 56(2):481–505. doi:10.1007/s40279-025-02344-w; PMID 41343037; preprint SportRxiv 460.
- Schoenfeld BJ, Ogborn D, Krieger JW (2017). J Sports Sci 35(11):1073–1082. doi:10.1080/02640414.2016.1210197; PMID 27433992.
- Baz-Valle E, et al. (2022). J Hum Kinet 81:199–210. doi:10.2478/hukin-2022-0017; PMC8884877.
- Ralston GW, et al. (2017). Sports Med 47(12):2585–2601. doi:10.1007/s40279-017-0762-7; PMC5684266.
- Androulakis-Korakakis P, et al. (2020). Sports Med 50(4):751–765. doi:10.1007/s40279-019-01236-0; PMID 31797219.
- Bull FC, et al. (2020). WHO 2020 guidelines. Br J Sports Med 54:1451–1462. doi:10.1136/bjsports-2020-102955; PMC7719906.
- US Department of Health and Human Services (2018). Physical Activity Guidelines for Americans, 2nd ed.
- Bosquet L, et al. (2013). Scand J Med Sci Sports 23(3):e140–e149. doi:10.1111/sms.12047; PMID 23347054.
- Hwang PS, et al. (2017). J Strength Cond Res 31(4):869–881. doi:10.1519/JSC.0000000000001807; PMID 28328712.
- McMaster DT, et al. (2013). Sports Med 43(5):367–384. doi:10.1007/s40279-013-0031-3; PMID 23529287.
- Halonen EJ, et al. (2024). Scand J Med Sci Sports 34(10):e14739. doi:10.1111/sms.14739; PMID 39364857.
- Ogasawara R, et al. (2013). Eur J Appl Physiol. PMID 23053130.
- Lemmer JT, et al. (2000). Med Sci Sports Exerc 32(8):1505–1512. doi:10.1097/00005768-200008000-00021; PMID 10949019.
- Taaffe DR, et al. (2009). Gerontology. doi:10.1159/000182084; PMC2756799.
- Yang et al. (2022). J Aging Phys Act 30(3):552–566. doi:10.1123/japa.2020-0493; PMID 34510028.
- Muscle size after cessation in older adults (2022). Int J Environ Res Public Health. PMC9657634.
- Sherrington C, et al. (2019). Cochrane CD012424.pub2; PMC6360922.
- Sherrington C, et al. (2017). Br J Sports Med. doi:10.1136/bjsports-2016-096547; PMID 27707740.
- Lesinski M, et al. (2015). Sports Med 45(12):1721–1738. doi:10.1007/s40279-015-0375-y; PMC4656699.
- Borde R, Hortobágyi T, Granacher U (2015). Sports Med 45(12):1693–1720. doi:10.1007/s40279-015-0385-9; PMC4656698.
- Loaded walking and dynamic balance in older adults (2026). Hum Mov Sci. PMID 42413170.
- Fragala MS, et al. (2019). J Strength Cond Res 33(8):2019–2052. doi:10.1519/JSC.0000000000003230; PMID 31343601 (not read).

Running

- Frandsen JSB, et al. (2025). Br J Sports Med 59(17):1203–1210. doi:10.1136/bjsports-2024-109380; PMID 40623829; PMC12421110.
- Nielsen RO, et al. (2014). J Orthop Sports Phys Ther 44(10):739–747. doi:10.2519/jospt.2014.5164; PMID 25155475.
- Buist I, et al. (2008). Am J Sports Med. doi:10.1177/0363546507307505; PMID 17940147.
- Damsted C, et al. (2019). J Orthop Sports Phys Ther 49(4):230–238. doi:10.2519/jospt.2019.8541; PMID 30526231.
- Damsted C, et al. (2018). Int J Sports Phys Ther 13(6):931–942. PMID 30534459.
- Ramskov D, et al. (2018). BMJ Open Sport Exerc Med 4:e000333.
- Videbæk S, et al. (2015). Sports Med 45(7):1017–1026. PMID 25951917; PMC4473093.
- Garber CE, et al. (2011). Med Sci Sports Exerc 43(7):1334–1359. doi:10.1249/MSS.0b013e318213fefb; PMID 21694556.
- Doherty C, et al. (2020). J Sci Med Sport 23(2):182–188. doi:10.1016/j.jsams.2019.09.013; PMID 31704026.
- Fokkema T, et al. (2020). Scand J Med Sci Sports 30:1692–1704. doi:10.1111/sms.13725; PMC7496388.
- Fokkema T, et al. (2019). J Sci Med Sport 22(1):106–111. doi:10.1016/j.jsams.2018.06.003; PMID 29934211.
- Fokkema T, et al. (2019). INSPIRE trial. Br J Sports Med. PMC6900232.
- Relph N, et al. (2023). Int J Environ Res Public Health 20:6682. doi:10.3390/ijerph20176682; PMC10487403.
- Bosquet L, et al. (2007). Med Sci Sports Exerc 39(8):1358–1365. doi:10.1249/mss.0b013e31806010e0; PMID 17762369.
- Wang Z, et al. (2023). PLoS One. doi:10.1371/journal.pone.0282838; PMID 37163550; PMC10171681.
- Smyth B, Lawlor A (2021). Front Sports Act Living. doi:10.3389/fspor.2021.735220; PMID 34651125.
- Vickers AJ, Vertosick EA (2016). BMC Sports Sci Med Rehabil 8:26. doi:10.1186/s13102-016-0052-y; PMC5000509.
- Rosenblat MA, et al. (2025). Sports Med 55:655–673. doi:10.1007/s40279-024-02149-3 (summary level).
- Campos Y, et al. (2022). Int J Sports Med. doi:10.1055/a-1559-3623; PMID 34749417.
- Seiler S, Hetlelid KJ (2005). Med Sci Sports Exerc 37:1601–1607. doi:10.1249/01.mss.0000177560.18014.d8; PMID 16177614.
- Bok D, Rakovac M, Foster C (2022). Sports Med. doi:10.1007/s40279-022-01690-3; PMID 35507232.
- Vieira et al. (2022). Talk test systematic review. PMC11266803.
- Wen D, et al. (2019). J Sci Med Sport 22:941–947. PMID 30733142 (from the first review).

Sprint, plyometrics and Nordic

- Haugen T, Seiler S, Sandbakk Ø, Tønnessen E (2019). Sports Med Open 5:44. doi:10.1186/s40798-019-0221-0; PMC6872694.
- Haugen T, Buchheit M (2016). Sports Med 46(5):641–656. doi:10.1007/s40279-015-0446-0.
- Alcaraz PE, et al. (2018). Sports Med. doi:10.1007/s40279-018-0947-8; PMID 29926369.
- Sáez de Villarreal E, et al. (2009). J Strength Cond Res 23(2):495–506. doi:10.1519/JSC.0b013e318196b7c6; PMID 19197203.
- Sáez de Villarreal E, et al. (2012). J Strength Cond Res 26(2). doi:10.1519/JSC.0b013e318220fd03; PMID 22240550.
- Sáez de Villarreal E, et al. (2010). Does plyometric training improve strength performance? (snippet level). https://ro.ecu.edu.au/ecuworks/6329
- Ramírez-Campillo R, et al. (2013). J Strength Cond Res. doi:10.1519/JSC.0b013e318280c9e9; PMID 23254550.
- Ramírez-Campillo R, et al. (2018). Sports Med. PMID 29470823; (2020) Scand J Med Sci Sports. PMID 32034819.
- Kons RL, et al. (2023). Sports Med Open. PMID 36625965.
- Zhao et al. (2025). Front Physiol. PMID 41030316; PMC12477254.
- Park et al. (2025). J Sports Med Phys Fitness. PMID 40910931.
- Malone S, et al. (2017). J Sci Med Sport 20:250–254. doi:10.1016/j.jsams.2016.08.005; PMID 27554923.
- Freeman BW, et al. (2019). J Sports Med Phys Fitness. PMID 31332988.
- Duhig S, et al. (2016). Br J Sports Med 50:1536–1540. https://eprints.qut.edu.au/221358/
- Ruddy JD, et al. (2018). Br J Sports Med. doi:10.1136/bjsports-2016-096777; PMID 27884865.
- van Dyk N, Behan FP, Whiteley R (2019). Br J Sports Med 53(21):1362–1370. doi:10.1136/bjsports-2018-100045; PMID 30808663.
- Impellizzeri FM, McCall A, van Smeden M (2021). J Clin Epidemiol 140:111–124. https://opus.lib.uts.edu.au/handle/10453/151354
- Petersen J, et al. (2011). Am J Sports Med. doi:10.1177/0363546511419277; PMID 21825112.
- Behan FP, et al. (2022). Int J Sports Physiol Perform 17(4):646. doi:10.1123/ijspp.2021-0276; PMID 35176727.
- Presland JD, et al. (2018). Scand J Med Sci Sports. doi:10.1111/sms.13085; PMID 29572976.
- Cuthbert M, et al. (2020). Sports Med. PMID 31502142; PMC6942028.
- Franke et al. (2025). J Strength Cond Res 39(10):1088–1104. doi:10.1519/JSC.0000000000005184; PMID 40991853.
- Alonso-Fernandez D, et al. (2024). Sports Biomech. doi:10.1080/14763141.2021.1992493; PMID 34748450.
- Coratella G, et al. (2025). Int J Sports Med. doi:10.1055/a-2595-3622; PMID 40280182.
- Koundourakis NE, et al. (2014). PLoS One. PMID 24586293; PMC3929557.
- Mayhew JL, et al. (2010). J Strength Cond Res. doi:10.1519/JSC.0b013e3181c08860; PMID 20072055.
- Mann JB, et al. (2015). J Strength Cond Res. doi:10.1519/JSC.0000000000000941; PMID 25785707.
- Hetzler RK, et al. (2008). J Strength Cond Res 22(6):1969–1976. doi:10.1519/JSC.0b013e318185f36c.

Concurrent training, session time, warm-up and cool-down

- Schumann M, et al. (2022). Sports Med 52:601–612. doi:10.1007/s40279-021-01587-7; PMC8891239.
- Eddens L, van Someren K, Howatson G (2018). Sports Med 48(1):177–188. doi:10.1007/s40279-017-0784-1; PMID 28917030.
- Murlasits Z, Kneffel Z, Thalib L (2018). J Sports Sci 36(11):1212–1219. doi:10.1080/02640414.2017.1364405; PMID 28783467.
- Wilson JM, et al. (2012). J Strength Cond Res 26(8):2293–2307. doi:10.1519/JSC.0b013e31823a3e2d; PMID 22002517.
- Petré H, et al. (2021). Sports Med 51:991–1010. PMC8053170 (from the first review).
- Doma K, Deakin GB, Bentley DJ (2017). Sports Med. doi:10.1007/s40279-017-0758-3; PMID 28702901.
- Blagrove RC, Howatson G, Hayes PR (2018). Sports Med 48(5):1117–1149. doi:10.1007/s40279-017-0835-7; PMC5889786.
- Jakicic JM, et al. (2019). Med Sci Sports Exerc. doi:10.1249/MSS.0000000000001933; PMC6527142.
- Gillen JB, et al. (2016). PLoS One. PMC4846072.
- Iversen VM, et al. (2021). Sports Med. doi:10.1007/s40279-021-01490-1; PMC8449772.
- Wilk M, Zajac A, Tufano JJ (2021). Sports Med. doi:10.1007/s40279-021-01465-2; PMC8310485.
- Trybulski R, et al. (2022). Biol Sport. doi:10.5114/biolsport.2022.106160; PMC9331330.
- Nóbrega SR, et al. (2018). J Strength Cond Res 32(9):2419. doi:10.1519/JSC.0000000000002493; PMID 29481458.
- Fradkin AJ, Zazryn TR, Smoliga JM (2010). J Strength Cond Res. doi:10.1519/JSC.0b013e3181c643a0; PMID 19996770.
- Behm DG, et al. (2016). Appl Physiol Nutr Metab. doi:10.1139/apnm-2015-0235; PMID 26642915.
- McGowan CJ, et al. (2015). Sports Med. doi:10.1007/s40279-015-0376-x; PMID 26400696 (abstract only).
- Ribeiro AS, et al. (2014). Percept Mot Skills. PMID 25153744.
- Abad CCC, et al. (2011). J Strength Cond Res 25(8):2242. doi:10.1519/JSC.0b013e3181e8611b; PMID 21544000.
- Wilson, Nunes & Blazevich (2025). J Sport Health Sci. doi:10.1016/j.jshs.2025.101024; PMC12357318.
- Van Hooren B, Peake JM (2018). Sports Med 48(7):1575. doi:10.1007/s40279-018-0916-2; PMC5999142.
- Bandy WD, Irion JM, Briggler M (1997). Phys Ther 77(10):1090. doi:10.1093/ptj/77.10.1090; PMID 9327823.
- Thomas E, et al. (2018). Int J Sports Med. doi:10.1055/s-0044-101146; PMID 29506306.
- Kaushal N, Rhodes RE (2015). J Behav Med. PMID 25851609 (from the first review).
