# Running-plan rules (inventory §3, R01–R54): evidence audit

Scope: every INVENTED, VERIFY and PRACTICE row in the run section of `inventory.md`, with depth on R05, R09, R10, R11, R42, the tapers (R31–R33), intensity distribution (R01/R29), interval recoveries (R18/R24), phase minimum weeks (R40), the marathon Riegel margin (R36) and the readiness gates (R37/R38). The first review (`wizard-evidence-2026-11-08/report.md`) was read first. Its `[verify]` items for Frandsen 2025, the Relph 2023 quit rates, the talk test and Vickers & Vertosick are resolved below from primary text.

Read in full text: Frandsen 2025 (the published PDF from the SDU repository), Ramskov 2018 (the PDF), Relph 2023 (the PDF), Vickers & Vertosick 2016 (PMC), Fokkema 2020 (Europe PMC full text), Smyth & Lawlor 2021 (Europe PMC full text) and Wang 2023 (Europe PMC full text). Read as abstract only: Bosquet 2007, Nielsen 2014, Buist 2008, Damsted 2019, Damsted 2018, Fokkema 2019 (×2), Videbæk 2015, Seiler & Hetlelid 2005, Campos 2022, Rosenblat 2025 (via search summary), Doherty 2020, Garber 2011 (ACSM), Bok 2022 and Vieira 2022.

Code was checked for two behaviors: the ladder treats an unrated session as Easy (`Progression.kt`: `val word = effort ?: CardioEffort.EASY`), and ruled easy runs return `repeat()`.

---

## 1. What does the evidence say for each rule, and what is the verdict?

### Takeaway
The single-run cap (R05) holds up on the full paper: KEEP at 110%, with tighter wording about who was studied and what was measured. The rules that most need to change are R11 (easy runs never grow), the ladder's "unrated counts as Easy" (R43), and the taper factors that sit below Bosquet's 41–60% band (R33, and the averages of R32). Most other run numbers are coaching practice that no trial tests. They should be labeled that way and not presented as evidence.

### Cited Findings

**R05: the run cap at 110% of the longest run in the last 30 days (VERIFY → verified)**
- Citation: Schuster Brandt Frandsen J, et al., "How much running is too much? Identifying high-risk running sessions in a 5200-person cohort study," *Br J Sports Med* 2025;59(17):1203–1210, DOI 10.1136/bjsports-2024-109380, PMID 40623829, PMC12421110. Full text read from the published PDF — [SDU repository PDF](https://findresearcher.sdu.dk/ws/files/295073070/1203.full.pdf); [PMC12421110](https://pmc.ncbi.nlm.nih.gov/articles/PMC12421110); [PubMed 40623829](https://pubmed.ncbi.nlm.nih.gov/40623829/)
- Design: an exploratory prospective cohort ("As the present study was exploratory, no sample size or power was calculated"). It included 5,205 runners from 87 countries, mostly Europe and North America. Mean age 45.8 (SD 10.4); 77.9% male; mean BMI 24.2; **median running experience 9.5 years (IQR 4–20)**. Follow-up was a median of 80 sessions per runner, 588,071 sessions in total. Distance came from Garmin GPS; injuries came from weekly questionnaires — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Exposure definition: the distance of a single session divided by the longest single session in the 30 days before the current running day. Sessions with **less than 15 min of pause between them were merged into one session, including interval and other high-intensity sessions**. The exposure was time-varying — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Categories: (1) **reference = "regression (running less than the longest run), or up to 10% progression"**; (2) small spike, >10% to 30%; (3) moderate spike, >30% to 100%; (4) large spike, >100%; (5) not calculable. The authors say the 10% cut-off was chosen "given the significance of the '10% rule' in running communities" and the 30% cut-off from weekly-progression studies — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Outcome: 1,820 runners (35.0%) reported a running-related injury, of which 1,311 (72.0%) were overuse (the main outcome). Analysis used a multistate Cox model adjusted for age, BMI, sex, previous problems and running experience — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Overuse injury, adjusted hazard rate ratios (Table 1): small spike **1.64 (95% CI 1.31–2.05)**; moderate spike **1.52 (1.16–2.00)**; large spike **2.28 (1.50–3.48)**. The abstract gives p=0.01 for the small spike while Table 1 gives p<0.01 — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Traumatic injury, adjusted: 1.56 (1.07–2.26), 1.63 (1.06–2.49) and 1.79 (0.84–3.80), the last not significant — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- ACWR (overuse, adjusted): >10–30% 0.94 (0.79–1.12); >30–100% 0.87 (0.75–1.02); >100% **0.75 (0.59–0.96)**, an inverse association. Week-to-week ratio (overuse): 0.97 (0.81–1.16), 0.88 (0.76–1.02) and 0.91 (0.78–1.07), all null — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Increases inside the 10% band are not risk-free. A 1–10% progression carried a 19% higher rate than regression or up to 1% (95% CI −10% to +57%, p=0.22). This was reported in the text, not in a table — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- The authors warn against compounding: with a 30-day longest of 10 km, running 11, 12.1 and 13.3 km in the same week "may still be considered excessive due to insufficient recovery time" — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Stated limitations: injuries were self-reported; distance "may not be the optimal measure of load"; low injury numbers meant no subgroup advice (previous injury, weekly mileage, age, BMI); "the observational nature of the study design limits the robustness of the causal claims"; randomized trials or target-trial emulation are needed — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- The authors' recommendation: clinicians "may consider using a session-specific cut-off below 10%" — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)

**R52 (not built) and the weekly-progression evidence that bears on R05 and R09**
- Nielsen 2014 (*JOSPT* 44(10):739–747, DOI 10.2519/jospt.2014.5164, PMID 25155475): an exploratory 1-year cohort of 874 **novice** runners with GPS; 202 were injured. Across the <10%, 10–30% and >30% groups there was **no significant difference in overall injury**. For "distance-related" injuries, >30% vs <10% gave **HR 1.59 (95% CI 0.96–2.66, p=.07)**. The authors still advise novices to progress "by less than 30% per week over a 2-week period" — [PubMed 25155475](https://pubmed.ncbi.nlm.nih.gov/25155475/)
- Buist 2008 (*Am J Sports Med*, DOI 10.1177/0363546507307505, PMID 17940147): an RCT of 532 **novice** runners preparing for a 4-mile (6.7 km) race. A 13-week graded program following the 10% rule had an injury incidence of **20.8%**; the standard 8-week program had **20.3%** (χ² p=.90). Injury meant a complaint that restricted running for at least 1 week — [PubMed 17940147](https://pubmed.ncbi.nlm.nih.gov/17940147/)
- Damsted 2019 (*JOSPT* 49(4):230–238, DOI 10.2519/jospt.2019.8541, PMID 30526231): a cohort of 261 runners preparing for a half marathon over 14 weeks; 21.5% were injured. At day 21, runners who increased weekly distance by **20–60% vs <20%** had a risk difference of **22.6% (95% CI 0.9–44.3%)**. There was no difference at days 56 and 98 — [PubMed 30526231](https://pubmed.ncbi.nlm.nih.gov/30526231/)
- Damsted 2018 systematic review (*IJSPT* 13(6):931–942, PMID 30534459, PMC6253751): only four studies qualified, and the authors call the evidence that a sudden change in load raises injury risk "very limited" — [PMC6253751](https://pmc.ncbi.nlm.nih.gov/articles/PMC6253751)
- Ramskov 2018, Run Clever RCT (*BMJ Open SEM* 4:e000333): 839 enrolled and 447 analyzed recreational runners (1–3 runs a week in the past 6 months). Both arms ran 3×/week in 4-week blocks, with **+23% weekly volume in the first week of each block and −10% in the last**. After 8 weeks of preconditioning, they had 16 weeks of either intensity progression or volume progression. The volume arm's cumulative injury was 28.0% at 16 weeks; the 16-week risk difference was −14.0% (95% CI −36.9% to 8.9%), so no difference — [Ramskov 2018 PDF](https://vbn.aau.dk/ws/files/284775170/e000333.full.pdf); [BMJ Open SEM](https://bmjopensem.bmj.com/content/4/1/e000333)
- Videbæk 2015 (*Sports Med*, PMID 25951917, PMC4473093): injuries per 1,000 h of running were **17.8 (95% CI 16.7–19.1) for novices vs 7.7 (6.9–8.7) for recreational runners** — [PMC4473093](https://pmc.ncbi.nlm.nih.gov/articles/PMC4473093/)

**R09: the A16 increase of +5% per finished session (INVENTED)**
- No trial or cohort tests a per-session growth rate for long runs. The nearest data are the rules above: Frandsen's reference band (≤10% over the 30-day longest), Buist's graded 10%-a-week program (no fewer injuries than a faster 8-week program) and Damsted's <20% a week — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380); [Buist 2008](https://pubmed.ncbi.nlm.nih.gov/17940147/); [Damsted 2019](https://pubmed.ncbi.nlm.nih.gov/30526231/)

**R10: easy-run length and R11: easy runs never grow (both INVENTED)**
- No study sets an easy-run length relative to the long run or a 20 min floor. The ACSM position stand gives volume targets of ≥30 min/day of moderate exercise (≥150 min/week) or ≥20 min/day of vigorous exercise (≥75 min/week). It also says "gradual progression of exercise intensity and volume may reduce the risks of exercise" — [Garber 2011, MSSE, PMID 21694556](https://pubmed.ncbi.nlm.nih.gov/21694556/)
- Weekly volume tracks performance. In a meta-regression of 85 studies and 137 marathon cohorts, average weekly distance, runs per week, longest run and hours per week each had a negative association with finish time (R² 0.38–0.81) — [Doherty 2020, JSAMS, PMID 31704026](https://pubmed.ncbi.nlm.nih.gov/31704026/). Half-marathon runners above 32 km/week finished faster, by β −4.19 min (95% CI −6.52 to −1.85) — [Fokkema 2020, PMC7496388](https://pmc.ncbi.nlm.nih.gov/articles/PMC7496388/)

**R42 and R41/R43–R46: the run/walk ladder (INVENTED or PRACTICE)**
- The NHS Couch-to-5K, as delivered in Relph 2023's Table 1: three runs a week for 9 weeks, each after a 5-min brisk walk. Week 1: 1 min run + 1.5 min walk ×8. Week 2: 1.5 + 2 ×6. Week 3: (1.5 run, 1.5 walk, 3 run, 3 walk) ×2. Week 4: 3, 5, 3, 5 min runs with 1.5–2.5 min walks. **Week 5: 5 min ×3 → 8 min ×2 → 20 min continuous.** Week 6: 5/8/5, then 10 ×2, then 25 min. Weeks 7–9: 25, 28 and 30 min continuous — [Relph 2023, IJERPH 20:6682, DOI 10.3390/ijerph20176682, PMC10487403](https://pmc.ncbi.nlm.nih.gov/articles/PMC10487403/); [UCLan PDF](https://clok.uclan.ac.uk/48883/1/ijerph-20-06682.pdf)
- Relph 2023's outcomes (n=110, 81.8% female, mean age 47.1): **27.3% completed**. Drop-out was **48.2% by the midpoint and 64.5% by the end**; 74.6% of drop-outs left before the midpoint. Injuries numbered 21 (19%), and previous injury gave **OR 7.56 (95% CI 2.06–27.75)**. Injury was the reason in 11 of 15 interviewed drop-outs (73%). Drop-outs "perceived the Couch-to-5k programme to progress too quickly, especially in week 5, where the sustained running time increases from 5 min, to 8 min, to 20 min". Runners who missed sessions "felt unable to return … as the programme had progressed too much". The authors call for "more flexible designs" — [Relph 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10487403/). This resolves the first review's `[verify]` on the 48.2% and 64.5% quit rates: they are in the primary paper.
- Fokkema 2019 (*JSAMS* 22(1):106–111, DOI 10.1016/j.jsams.2018.06.003, PMID 29934211): 774 participants in Start to Run, a **6-week** Dutch novice program. **29.5% had stopped running within 26 weeks**; injury was the main reason (48% of those who stopped). Being female (OR 1.74) and being unsure about continuing (OR 2.06) predicted stopping — [PubMed 29934211](https://pubmed.ncbi.nlm.nih.gov/29934211/)
- Buist 2008: an 8-week standard novice program and a 13-week graded one had the same injury incidence (20.3% vs 20.8%) — [PubMed 17940147](https://pubmed.ncbi.nlm.nih.gov/17940147/)

**R31–R34: tapers**
- Bosquet 2007 (*MSSE* 39(8):1358–1365, DOI 10.1249/mss.0b013e31806010e0, PMID 17762369): 27 studies of competitive athletes. The best strategy was a **2-week taper (overall effect 0.59 ± 0.33)** with volume **"exponentially decreased by 41–60%" (0.72 ± 0.36)**, intensity unchanged (0.33 ± 0.14) and frequency unchanged (0.35 ± 0.17) — [PubMed 17762369](https://pubmed.ncbi.nlm.nih.gov/17762369/)
- Wang 2023 (*PLoS One*, DOI 10.1371/journal.pone.0282838, PMID 37163550, PMC10171681): 14 studies of endurance athletes, using Bosquet's coding of volume cuts (≤20, 21–40, 41–60, ≥60%) and durations (≤7, 8–14, 15–21, ≥22 days). Only the **41–60% cut was significant for time-trial performance: SMD −0.77 (95% CI −1.23 to −0.30)**. The ≤20%, 21–40% and ≥60% subgroups "were smaller and did not reach significance". Only four data sets were in the 21–40% band, and most data sets used ≤7-day tapers. Conclusion: "a ≤21-day taper, in which training volume is progressively reduced by 41–60%" — [PMC10171681](https://pmc.ncbi.nlm.nih.gov/articles/PMC10171681/)
- Smyth & Lawlor 2021 (*Front Sports Act Living*, DOI 10.3389/fspor.2021.735220, PMID 34651125, PMC8506252): over 158,000 recreational marathoners. A **strict 3-week taper** was associated with a median finish-time saving of **5 min 32.4 s (2.6%)** against a minimal taper. In regression, a strict 3-week taper cut 4.16 min, a strict 2-week taper 3.67 min and a strict 4-week taper also 3.67 min. Only about 31% of runners used strict tapers. "Strict" means consecutive down weeks right before race day; the paper does not prescribe per-week percentages — [PMC8506252](https://pmc.ncbi.nlm.nih.gov/articles/PMC8506252/)
- In Smyth's data the runners averaged 3.6 runs a week, 39–43 km a week and about 23–24 weeks of training data. Mean finish times were 4:09:44 for women and 3:41:36 for men — [Smyth & Lawlor 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8506252/)

**R35/R36: Riegel and the marathon ×1.05 margin (VERIFY → verified)**
- Vickers & Vertosick 2016 (*BMC Sports Sci Med Rehabil* 8:26, DOI 10.1186/s13102-016-0052-y, PMC5000509): 2,303 recreational runners (61% male, median 30 miles a week; marathoners a median of 40). The primary analysis used **k = 1.07**, with 1.06 and 1.08 as sensitivity analyses. Riegel was well calibrated at 10 km (p=0.9) and the half (p=0.3) but not the marathon (p<0.0001). Marathon residuals: **median −10:09** (the prediction was too fast); **25th percentile −19:47**; 5th percentile −36:17. Their Model 1 adds typical weekly mileage to the Riegel-predicted velocity; Model 2 fits a personal k from two races. Both cut the error (MSE 380.7 for Riegel vs 227.6 and 208.3), and the 25th-percentile residual fell to −9:55 and −7:31. Using k=1.08 helped only slightly. The equations are in Additional file 1, which was not retrieved — [PMC5000509](https://pmc.ncbi.nlm.nih.gov/articles/PMC5000509)

**R01/R29: intensity distribution (SUPPORTED/PRACTICE, rechecked)**
- Rosenblat 2025 (*Sports Med* 55:655–673, DOI 10.1007/s40279-024-02149-3): an individual-participant network meta-analysis of 13 studies and 348 athletes (150 recreational). Polarized vs pyramidal showed no difference for VO2max (SMD −0.06) or time trial (SMD −0.05). In the recreational vs competitive subgroup the difference was SMD −0.63, and recreational athletes may do better on pyramidal — [Univ. of Stirling record](https://www.stir.ac.uk/research/hub/publication/2095779) (summary via search; full text not read)
- Campos 2022 (*Int J Sports Med*, DOI 10.1055/a-1559-3623, PMID 34749417): a systematic review of 20 articles. Middle- and long-distance runners did better with "high-volume at low-intensity (≥70% of overall training volume) and low-volume at threshold and HIIT (≤30%)" than with threshold-heavy distributions — [PubMed 34749417](https://pubmed.ncbi.nlm.nih.gov/34749417/)

**R18/R24: interval recoveries**
- Seiler & Hetlelid 2005 (*MSSE* 37:1601–1607, DOI 10.1249/01.mss.0000177560.18014.d8, PMID 16177614): nine well-trained men (VO2max 71 mL/kg/min) ran 6 × 4 min with 1, 2 or 4 min of recovery. Speed rose from 14.4 to 14.7 km/h between 1 and 2 min, with **no further gain at 4 min**. Work VO2 was highest at 2 min (66.2 vs 65.1 and 64.9), and peak RPE was slightly lower at 4 min. Self-selected recovery averaged **118 ± 23 s**. Conclusion: recovery of 1 to 4 min had "limited impact", and about 120 s of active recovery is a reasonable balance — [PubMed 16177614](https://pubmed.ncbi.nlm.nih.gov/16177614/)

**R04: effort words as talk-test bands (VERIFY → verified)**
- Bok, Rakovac & Foster 2022 (*Sports Med*, DOI 10.1007/s40279-022-01690-3, PMID 35507232) is a narrative review in healthy and athletic populations. It finds "the TT and RPE appear to be valid measures of both the ventilatory threshold and the respiratory compensation threshold". The **equivocal TT stage and RPE 10–11 (6–20 scale) reflect VT1; the negative TT stage and RPE 13–15 reflect the respiratory compensation threshold** — [PubMed 35507232](https://pubmed.ncbi.nlm.nih.gov/35507232/)
- Vieira 2022 systematic review: 10 studies and 414 subjects, mostly with coronary disease. The last positive TT stage did not differ from VT1 in workload, VO2 or heart rate — [PMC11266803](https://pmc.ncbi.nlm.nih.gov/articles/PMC11266803/)

**R12/R16/R37/R38: long-run ends and readiness gates**
- Fokkema 2020 (*Scand J Med Sci Sports* 30:1692–1704, DOI 10.1111/sms.13725, PMC7496388) studied 556 half-marathon and 441 marathon runners (median experience 5 years; median 3 runs a week).
  - Half marathon: training **>32 km/week (β −4.19 min)** and a **longest run >21 km (β −3.87 min, 95% CI −6.31 to −1.44)** went with faster finishes. The half group averaged 29.9 km/week, a 19.3 km longest run and a 2:00:05 finish.
  - Marathon: **<40 km/week went with slower finishes (β +6.33 min)** and >65 km with faster ones (β −14.09). A **longest run <25 km went with slower finishes (β +13.44 min, 95% CI 5.34–21.55)**, and **>35 km was no better than 30–35 km**. The marathon group averaged 43.6 km/week, a 29.1 km longest run, a 5:41/km training pace and a 4:17:54 finish.
  - **"No associations between training characteristics and RRIs were identified."** The injury rate was 48.2% for half and 55.1% for marathon runners. The design is observational and possibly confounded by talent.
  - [PMC7496388](https://pmc.ncbi.nlm.nih.gov/articles/PMC7496388/)
- Doherty 2020: in a meta-analysis of 137 marathon cohorts, 7.27% (95% CI 6.09–8.65%) did not finish — [PubMed 31704026](https://pubmed.ncbi.nlm.nih.gov/31704026/)
- Fokkema 2019 BJSM (INSPIRE RCT): among 2,378 recreational runners registered for 5 km to marathon events, 37.5% vs 36.7% had a new running injury. The online prevention program had no effect (OR 1.08, 95% CI 0.90–1.30) — [PMC6900232](https://pmc.ncbi.nlm.nih.gov/articles/PMC6900232/)

### Inferences

**Verdict table.** "PRACTICE" means: keep the number, label it coaching practice in the design and copy, and make no evidence claim.

| id | rule (short) | verdict | basis |
|---|---|---|---|
| R01 | mostly easy; 1 Q at 3–4 days, Q2 at 5–6 | KEEP | Campos 2022 (≥70% low); Rosenblat 2025 (pyramidal ≈ polarized; recreational lean pyramidal). With one Q in 3–4 runs, the week's time is already about 80% or more easy. |
| R02 | rotation rows | LABEL AS PRACTICE | No study tests session order. |
| R03 | rest after the long run | LABEL AS PRACTICE | None found. |
| R04 | Easy/Good/Hard = talk-test stages | KEEP (word mapping); LABEL internal RPE numbers AS PRACTICE | Bok 2022: TT is valid for VT1 and RCT in healthy people; Easy = positive stage, Good = equivocal (≈VT1 to RCT), Hard = negative (above RCT). The 0–10 numbers have no direct conversion from Bok's 6–20 anchors (RPE 10–11 at VT1, 13–15 at RCT). |
| **R05** | cap at 110% of the 30-day longest | **KEEP 110%** (VERIFY → SUPPORTED, moderate, one exploratory cohort) | Frandsen 2025: the reference band is "regression or ≤10%". Small spike HRR 1.64 (1.31–2.05), moderate 1.52 (1.16–2.00), large 2.28 (1.50–3.48); week-to-week null; ACWR inverse. Caveats for the design: (a) mostly male, experienced runners (median 9.5 y), so novices are not represented; (b) a 1–10% rise still carried HRR 1.19 (−10% to +57%, ns), so 110% is the edge of the reference band, not a safe zone; (c) distance only, so the code's **seconds fallback is an extrapolation**; (d) sessions <15 min apart were merged, so counting WORK + RECOVERY (R06) matches the paper; (e) the authors warn against compounding 10% steps within a week. Copy must not claim it prevents injury. |
| R06 | cap mechanics | LABEL AS PRACTICE, except "WORK and RECOVERY both count", which matches Frandsen's whole-session distance | Frandsen 2025 methods |
| R07 | setup answer stands in for 30 days | LABEL AS PRACTICE | The 30-day window matches Frandsen; the stand-in itself is design. |
| R08 | ladder exempt from cap | LABEL AS PRACTICE (defensible) | Total ladder session time stays about 22–32 min across all 12 levels (computed from `RunWalkLadder.LEVELS`), so a time cap would bind only at L8→L9 (1,320 → 1,920 s, +45%). Frandsen has no novice data. |
| **R09** | +5% per finished session (long runs, converted days) | **LABEL AS PRACTICE (conservative)**; CHANGE the user clamp ceiling from 25% to 10% for run families | No study tests per-session rates. 5% sits inside Frandsen's reference band, and a long run once per rotation is about 5% a week, half of Buist's graded 10% (which gave no fewer injuries). Above 10% the cap already clips run families, so 25% only misleads. For non-run converted days there is no injury evidence at all. |
| **R10** | easy = minutes − 5 − 25 (strength), ≥20, ≤75% long run | **LABEL AS PRACTICE**, and fix the interaction with R11 (below) | No source for 20 min or 75%. ACSM's ≥20–30 min/day is a health dose, not a run-length rule (Garber 2011). The 75% is frozen against the long run *stored at creation*. |
| **R11** | easy runs never grow; Just cardio never progresses | **CHANGE**: grow easy runs by time on full success (the A16 step, 5%) up to the session-minutes ceiling, under R05. For novices, keep weekly totals within ≤30% over 2 weeks (Nielsen 2014) and ideally <20% a week early on (Damsted 2019). | Volume links to performance (Doherty 2020; Fokkema 2020). The bigger failure is mechanical: an easy run clipped by R05 at creation (110% of a short 30-day longest) repeats at the clipped length forever, so a returning runner who picked 45 min but recently ran 15 min gets about 16.5 min easy runs indefinitely. |
| R12 | half 18 km/2 h, marathon 28 km/3 h opens | PRACTICE, consistent with Fokkema 2020 | Half longest run >21 km and marathon 25–35 km went with faster finishes |
| R13 | base 10 km/60 min | LABEL AS PRACTICE | No source |
| R14, R17 | ultra opens and stops; 3.5 h | LABEL AS PRACTICE | No new evidence; ultra data weak (first review) |
| R15 | middle distance 60 min | PRACTICE | Elite descriptive only |
| R16 | stops: half 21 km/2 h, marathon 32 km/3 h | KEEP distance ends (Fokkema 2020: >21 km half; marathon >35 km no better than 30–35); LABEL time caps AS PRACTICE | 29 km at Fokkema's mean 5:41/km is about 2 h 45 min, so a 3 h cap is in range |
| R18 | VO2max equal recovery | KEEP | Seiler & Hetlelid 2005: 1–4 min recovery barely matters for 4-min reps; 4 min lowers RPE. Wen 2019 (first review) for ≥2 min reps. |
| R19–R21, R23 | quality session ranges | PRACTICE (unchanged from first review) | Not re-researched; no new primary evidence |
| R22 | sharpening recovery 4 min | LABEL AS PRACTICE | None found |
| **R24** | test-interval recovery 2 min | **KEEP**, now with a source | Seiler & Hetlelid 2005: 2 min ≈ optimal for 4-min reps in trained men; self-chosen recovery was 118 s. For 400 m reps (~1.5–2 min) a 2-min rest is at least equal recovery. |
| R25 | test reps at 95% of goal-pace time | LABEL AS PRACTICE | None found |
| R26–R28 | rounding, step unit, what counts | LABEL AS PRACTICE (design mechanics) | No literature would test these |
| R29, R30 | quality kind by event; tests have no long run | PRACTICE | Unchanged |
| R31 | taper lengths 10/14/21 days | KEEP | Bosquet 2007 (2 weeks best); Wang 2023 (≤21 d); Smyth 2021 (strict 3-week best for marathon, 4.16 vs 3.67 min) |
| R32 | ≤10K ×0.6; marathon ×0.8/0.6/0.5 | **CHANGE** ≤10K to ×0.5 (a 50% cut) and marathon to about ×0.75/0.55/0.4 (mean cut about 43%), or LABEL the current values AS PRACTICE | ×0.6 is a 40% cut, the top of the 21–40% band, which was not significant in Wang 2023. The marathon schedule averages 0.633, a 37% cut, below Bosquet's 41–60%. |
| **R33** | half ×0.7 then ×0.5 | **CHANGE TO ×0.6 (14–8 days out) then ×0.45 (last 7)**, a mean cut of about 47%, inside Bosquet's 41–60% band | ×0.7 is 30% in week one; across the 14 days the mean cut is 40%, just outside the band. No half-specific trial exists. |
| R34 | taper scales volume, keeps intensity | KEEP | Bosquet 2007 (intensity kept: 0.33; frequency kept: 0.35) |
| R35 | Riegel exponent 1.06 | KEEP for ≤ half marathon | Vickers 2016 calibrated at 10K and half (primary k=1.07; 1.06 tested as sensitivity) |
| **R36** | marathon goal check ×1.05 | **LABEL AS PRACTICE ("corrects the median runner") and soften the copy**; a better option is to add a weekly-mileage term (Vickers Model 1, equations in the paper's supplement) | Vickers' median miss was 10:09 too fast and a quarter missed by ≥19:47. At finishes of about 3:45–4:15 (Smyth, Fokkema), 10 min is about 4–4.5%. The code's k=1.06 then ×1.05 equals k=1.07 × about 1.043 from a half anchor, ×1.035 from a 10K and ×1.028 from a 5K (factor (D2/D1)^0.01). So ×1.05 roughly fixes the median from a half and under-corrects from 5K/10K anchors, and about 1 in 4 runners will still finish more than ~4% slower than the checked time. |
| R37 | gates: 10 km, 21 km, 30 km or a marathon | PRACTICE, consistent with Fokkema 2020 | Fokkema's thresholds describe faster finishers with no injury association; observational |
| R38 | ladder done at 30 min or 5 km; time and week forms | KEEP 30 min (it is the C25K endpoint, Relph Table 1); LABEL the time and week forms AS PRACTICE | |
| R39 | phase start; never refuses an event | PRACTICE | |
| **R40** | min weeks: ladder 8, base 6, each build 8 | **LABEL AS PRACTICE**; ladder 8 matches published programs (C25K 9 weeks, GRONORUN standard 8, Start to Run 6) | Buist 2008: 8 vs 13 weeks had equal injury, so longer was not safer. Base 6 + half 8 + marathon 8 = 22 weeks from base to marathon, close to the ~23–24-week blocks in Smyth's data. It only drives a DateTooClose warning. |
| R41 | level 1 = 60 s run / 120 s walk ×8 | KEEP (gentler than C25K week 1, 60/90 ×8) | Relph Table 1 |
| **R42** | ladder levels 2–12 | **KEEP the table, LABEL AS PRACTICE adapted from C25K** | Same start (1 min runs) and end (30 min continuous) as C25K. The longest bout rises smoothly (60→90→120→180→240→300→480→600→900→1,200→1,500→1,800 s, never more than +60%), whereas C25K jumps 8 → 20 min in week 5. That is the jump Relph's drop-outs blamed for quitting and injury. |
| **R43** | Easy or unrated → next level; Good repeats; Hard/Pain → down | **CHANGE: an unrated session should repeat, not advance**; consider at least 2 sessions per level | The code treats a missing rating as Easy. A user who never rates goes from 1-min runs to 30 min continuous in **12 sessions (4 weeks at 3/week)**. That is 2.25× faster than C25K's 27 sessions, and faster than Start to Run (6 weeks) and GRONORUN (8 weeks). Relph's drop-outs called C25K itself "too quick", and novices are injured at 2.3× the recreational rate (Videbæk). Effort-gated advance is reasonable; rating-free advance is not. |
| R44 | ladder start from the longest run's time | LABEL AS PRACTICE | |
| R45 | past injury → one level lower | KEEP | Relph 2023: previous injury OR 7.56 (2.06–27.75) |
| R46 | ladder 3 sessions/week | KEEP | C25K is 3/week (Relph Table 1) |
| R47–R49 | strength blocks | Not re-researched here (concurrent/strength scope) | |
| R50 | intervals drop rounds down to one | **CHANGE**: floor at the round count giving ≥15 min of work, or swap to an easy run | Wen 2019 (first review: only ≥15 min of work beat continuous training). Not re-read here. |
| R51 | event constants | KEEP (definitions) | |
| R52 | novice ≤30% over 2 weeks (not built) | KEEP as planned; label it exploratory | Nielsen 2014: HR 1.59 (0.96–2.66), p=.07, for distance-related injuries only; null overall. Frandsen found week-to-week ratios null in experienced runners. |
| R53, R54 | not built; practice | PRACTICE | |

### Gaps
- Bosquet 2007's full Table 2 (effect sizes by volume band, duration band, and step vs progressive) was not retrievable; Semantic Scholar and BMJ returned 403. Whether Bosquet's "% reduction" means the taper-mean or the final-week cut is unconfirmed. The R32/R33 verdicts assume the taper-mean.
- Vickers & Vertosick's Model 1 and Model 2 equations sit in Additional file 1, which was not retrieved, so no mileage coefficient can be given. The median marathon time of their sample was not captured, so the % conversion uses Smyth and Fokkema finish times.
- Rosenblat 2025 was read via search summary and the Stirling record, not the full text.
- The Start to Run program's week-by-week structure was not found, only its length (6 weeks).
- No study tests a per-session long-run growth rate, an easy-to-long-run ratio, a 20-min easy minimum, a half-marathon-specific taper, a run/walk ladder against C25K, or phase minimum lengths.
- Frandsen could not test subgroups (novices, previously injured runners, high mileage), so whether 110% suits beginners is unknown.

---

## 2. What do established programs with outcome data use?

### Takeaway
Published beginner programs (C25K, Start to Run, GRONORUN) run 6 to 9 weeks at three sessions a week and advance weekly, not per session. Their completion and injury outcomes are poor: 27% finished C25K in one cohort, and 20–30% of novices were injured or stopped in the others. Longer, gentler progression has not been shown to cut injury. For half and marathon, observational data from about 1,000 Dutch runners and 158,000 app users give long-run and taper targets the app already roughly matches.

### Cited Findings
- **NHS Couch-to-5K**: 9 weeks, 3 runs a week, each after a 5-min walk. It starts at 1 min run / 1.5 min walk ×8 and ends at 30 min continuous. Week 5 jumps the longest bout from 5 to 8 to 20 min. Modified delivery in 110 adults: 27.3% completed, 64.5% dropped out, 19% were injured — [Relph 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10487403/)
- **Start to Run (Netherlands)**: a 6-week novice program with 774 participants. 29.5% had stopped running by 26 weeks, injury being the main reason (48%) — [Fokkema 2019 JSAMS](https://pubmed.ncbi.nlm.nih.gov/29934211/)
- **GRONORUN**: 532 novices training for a 6.7 km race. An 8-week standard program had 20.3% injured; a 13-week 10%-rule program had 20.8% — [Buist 2008](https://pubmed.ncbi.nlm.nih.gov/17940147/)
- **Run Clever**: 3 runs a week; 8-week common preconditioning, then 16 weeks of progression in 4-week blocks (+23% in week 1, −10% in week 4); 28% cumulative injury in the volume arm, with no difference between arms — [Ramskov 2018](https://vbn.aau.dk/ws/files/284775170/e000333.full.pdf)
- **Half and marathon (INSPIRE cohort)**: half runners averaged 29.9 km/week and a 19.3 km longest run; marathoners 43.6 km/week and a 29.1 km longest run, at a median of 3 runs a week. Faster finishers ran >32 km/week and a >21 km long run (half), or ≥40 km/week and a ≥25 km long run (marathon). There was no injury association — [Fokkema 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7496388/)
- **Marathon tapers in practice**: almost 90% of recreational marathoners used 2- or 3-week tapers, about 69% of them "relaxed". A strict 3-week taper was associated with a 2.6% faster median finish — [Smyth & Lawlor 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8506252/)
- **Half-marathon preparation cohort**: 14 weeks; a >20% weekly rise in the first 3 weeks raised the injury risk difference by 22.6% — [Damsted 2019](https://pubmed.ncbi.nlm.nih.gov/30526231/)
- **Injury rates by runner type**: novices 17.8 vs recreational runners 7.7 injuries per 1,000 h — [Videbæk 2015](https://pmc.ncbi.nlm.nih.gov/articles/PMC4473093/)

### Inferences
- The app's ladder (R41/R42) matches C25K's start and end and smooths its worst jump. The material difference is the advance rule: per session, with unrated counting as Easy, against C25K's per week. That makes the app's fastest path through the ladder 12 sessions against C25K's 27.
- The app's 8-week ladder minimum, 3 sessions a week and "repeat without penalty" all sit inside published practice. GRONORUN shows that simply making a beginner program longer does not cut injuries, so there is no evidence case for lengthening the ladder. The case is for not letting it run faster than the published programs without a rating.
- The half and marathon long-run ends (R16) and gates (R37) agree with Fokkema's thresholds. Fokkema's marathoners trained on a median of 3 runs a week, which supports the app's 3–4-run defaults for novice marathoners.
- None of these programs prescribes per-session growth of 5% or 10%. Weekly growth in the studied programs ranged from 10% (GRONORUN graded) to +23% block steps (Run Clever), with no injury difference between faster and slower arms in either RCT.

### Gaps
- No beginner-program study compares per-session, effort-gated advancement with fixed weekly progression.
- No published marathon program with injury and completion outcomes was found that reports its long-run progression rate. Only observational training summaries (Fokkema, Smyth, Doherty) were found.

---

## 3. Which rules would most mislead a real runner?

### Takeaway
Ranked by harm: (1) R11 with R10 and R05 freezes every easy run at its creation length, so the default "Just cardio fitness" plan and every runner's weekly volume stall. (2) R43 lets a beginner who never rates sessions finish the ladder in four weeks. (3) R05 is right as a ceiling but would mislead if presented as a safe zone or applied by time for novices. (4) R36's marathon check still leaves about a quarter of runners 20+ minutes slower than told. (5) The tapers (R32/R33) cut less than the evidence's best band. (6) R50 can collapse an interval day to a single rep.

### Cited Findings
- Volume (weekly distance, runs per week, longest run) tracks marathon performance across 137 cohorts — [Doherty 2020](https://pubmed.ncbi.nlm.nih.gov/31704026/); half runners above 32 km/week finished about 4 min faster — [Fokkema 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7496388/)
- Beginner programs already lose most participants, and drop-outs blame fast progression and injury — [Relph 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10487403/); novices are injured at 17.8 vs 7.7 per 1,000 h — [Videbæk 2015](https://pmc.ncbi.nlm.nih.gov/articles/PMC4473093/)
- 1–10% single-run increases still showed HRR 1.19 (ns); the cohort was experienced and mostly male; distance only; observational — [Frandsen 2025](https://doi.org/10.1136/bjsports-2024-109380)
- Riegel's marathon prediction was too fast by a median of 10:09, and by 19:47 or more for a quarter of runners — [Vickers & Vertosick 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC5000509)
- Only 41–60% volume cuts improved endurance time trials significantly (SMD −0.77) — [Wang 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10171681/); [Bosquet 2007](https://pubmed.ncbi.nlm.nih.gov/17762369/)
- Only interval protocols with ≥15 min of work beat continuous training — [Wen 2019, PMID 30733142](https://pubmed.ncbi.nlm.nih.gov/30733142/) (from the first review; not re-read)

### Inferences
1. **R11 (+R10, R05): highest harm.** These rules touch every runner and are the whole plan for the default sub-goal. The R05 cap clips the opening easy run against a short recent longest; R11 then repeats it unchanged, and R10's 75% ceiling is fixed against the stored long run. Three effects follow:
   - A user who chose 45–60 min sessions can be held at about 15–25 min easy runs indefinitely.
   - Weekly volume grows only through the one long run.
   - Just cardio fitness never progresses at all until 4b-iii.

   Fix: grow easy runs on success toward the minutes ceiling, under R05 and the novice 2-week limit.
2. **R43, unrated advances.** It touches every New runner who skips the effort prompt. A fast ladder is the failure mode the only C25K outcome study reports. Fix: an unrated session repeats.
3. **R05 framing.** The number is right; the risks are in the claims and the extrapolations:
   - Do not call ≤110% "safe" or injury-preventing.
   - Label the seconds fallback and its use for novices as extrapolations.
   - Keep the cap a distance rule wherever distance exists.

   Frandsen's own wording ("may consider … below 10%") supports exactly a 110% ceiling.
4. **R36.** A runner told "your goal is realistic" by a ×1.05 check from a 5K or 10K anchor gets less margin than the median miss (about 2.8–3.5% vs about 4%+). The copy should say "about half of runners finish slower than this". Adding weekly mileage (Vickers Model 1) is the evidence-backed upgrade.
5. **R33/R32.** These carry performance cost only, with no injury risk. The half's first-week 30% cut and the marathon's 37% mean cut sit in the band that did not reach significance.
6. **R50.** A one-rep interval day is below the work dose that made intervals worth doing (Wen 2019).
7. **R09.** The 25% clamp is moot for runs because R05 clips it, but it is visible and untested. The 5% default itself is conservative and harmless, just slow: about 14 long runs to double a long run.

### Gaps
- Whether the R05 cap truly clips easy runs at plan creation for typical users depends on `RunSessions.kt` / `RunCap.kt` call order. This was inferred from the inventory ("at start and when stepping") and from `Progression.kt` (EASY → `repeat()`), not traced end to end through plan generation.
- Whether the 20-min easy floor can push a 30–45 min strength-day session over its stated minutes was not checked in code.
