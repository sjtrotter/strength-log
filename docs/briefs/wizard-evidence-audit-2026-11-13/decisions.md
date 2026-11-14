# Evidence audit: lead decisions after the council

Date 2026-11-13. Inputs: `report.md` (research synthesis), the council's corroboration (Opus checked sources, 7/10; Sol checked reasoning, arithmetic and code facts, 6/10). Rule: a number ships only as **evidence** (a cited source gives it, for this population) or as **practice** (a stated product or coaching choice, with no evidence claim in copy). Where the evidence is thin, the default is the safer choice.

## Changes, in order

| # | Rows | Decision | Basis | Where |
|---|---|---|---|---|
| 1 | L28 | Calibration step is bounded by the reps the lifter reports left: the next load is the Epley load that leaves at least the slot's target RIR, counting "Easy" as exactly 4 left (never more); one equipment step minimum, no two-step floor. | Practice (council: ×1.10 still overshoots). Safety-first for new lifters. | merged Progression (user-facing now) |
| 2 | L42 | The Easy-session double applies only when the doubled step, by Epley from the session's reported reps left, still leaves the slot's target RIR. | Practice (council). | merged Progression (user-facing now) |
| 3 | R43 | An unrated run/walk ladder session repeats; it never advances. | Practice; Relph 2023 drop-outs blamed fast progression. | merged Progression (planner-only today) |
| 4 | R11 | Easy runs grow by time on a full success, up to the session minutes and the run cap, at most about 20% a week for novices. | Practice, guided by Nielsen 2014 and Damsted 2019 (weak). | merged Progression + 4b-ii |
| 5 | S29/S32/S33 | Sprint volume grows in small units so no step raises the week's max-speed metres by more than 15% without compounding; time trials count toward the week's exposure; a trial whose metres would exceed the cap waits. | Practice (no study tests a sprint cap; Duhig 2016 and Malone 2017 show spikes and under-exposure both raise risk). | PR #586 rework |
| 6 | S02 | 3-day sprint row: A + plyos, Lower, R, B + Nordic, R (no max-velocity day right after Nordic + heavy legs). | Precaution: the recovery studies used 3 to 5 times the app's Nordic dose. | merged SprintLayout |
| 7 | S10 | Sprint-endurance rest at least 600 s, flat; the intro opens at 2 × 100 m. | Practice from Haugen 2019's untrained example (no scaling with rep time). | merged RunRange |
| 8 | S30 | Sprint gap: reset 14 days after the last maximal sprint of any kind. | Practice (no fascicle rationale; the two trials disagree). | PR #586 rework |
| 9 | S25/S33 | Hand-timed "same" band 0.15 s; time trial is the best of two reps at 40 yd and 60 m, one rep at 100 to 400 m. | Practice (Mann 2015, Hetzler 2008; aggregation = best rep). | PR #586 rework / 5a follow-up |
| 10 | O09 | 65+ carry opens at the lightest dumbbell pair the user has and calibrates by effort like any loaded set. | Practice (no data; 50 lb per hand removed). | merged DayLayout/library |
| 11 | O10 | 65+ layoff: users who trained under about 8 weeks before the break keep the ×0.75 restart and recalibration; longer-trained users get ×0.85 with fewer sets from 15 days, recalibrating after 42. | Practice informed by Lemmer 2000, Yang 2022 (history-dependent). | merged Progression |
| 12 | O07 | 65+ balance on 3 or more days a week regardless of lifting days. | Evidence: WHO 2020 (strong). | PR 6 design |
| 13 | G02/W05 | Session estimate: setup + reps × 3 s per set (× 2 for per-side sets; timed sets at their length; both superset lifts), plus the cool-down when its switch is on. | Evidence-informed estimate (Wilk 2021; transitions are practice). | merged SessionBudget |
| 14 | C03/C04 | No layout leaves a lift-primary plan with fewer than two strength days a week; trained Strength users default to separate days when days allow. | Evidence: WHO 2020 two days (strong); Schumann 2022. | merged BothLayout/SetupAnswers |
| 15 | R32/R33 | Tapers deepen to 41 to 60% average reduction (half ×0.6 then ×0.45; 10K and shorter ×0.5; marathon 0.75/0.55/0.4). | Weak (Wang 2023, Bosquet 2007). | merged Taper |

## Kept, relabeled as practice (no evidence claim in copy)

R50 (shortened interval days keep their fewer rounds; no 15-minute floor), S08 (acceleration opening as is), S15/S17/S19 (plyometric and Nordic progressions as specced), L08 (back-offs at 90%), L40 (increments), L37 (cadence), L32 (one-rep tolerance), L63/L64/O06 (set floors as derived defaults), R09 (+5% long run), R10 floor, C05 (20-minute reserve), W01 to W04 (warm-up doses), the 20-set cap (efficiency ceiling, not a plateau).

## Owner decision (2026-11-13)

- **L30, effort words: no change.** The owner chose words so lifters never have to count reps left; showing counts would undo that. The engine already reads each word as a band with a one-rep tolerance, so "Good" is on target for a 3-left slot and "Hard" holds the load, which is the intended response for a novice training closer to failure than planned. The word-to-RIR mapping is labeled practice.

## Copy constraints (adopted from the report)

The app never claims a limit prevents injury (the run cap, the sprint cap), never says the Nordic "halves hamstring injuries", never implies the 65+ balance block meets the fall-prevention dose, and never says a cool-down speeds recovery.

## Citation fixes

Hand-timing bias is Mann 2015; Pelland reports no clear plateau for hypertrophy and a functional plateau for strength; Yang 2022's "maintained" means above baseline; Sherrington's 3 h a week is total exercise; Ralston's ES 0.18 is ≥10 vs ≤5 sets; "40% to 2%" in ACSM 2009 is unverified (paywalled); add the missing identifiers listed by the council.
