# Planner and progression rules: inventory for the evidence audit

Date: 2026-11-13. Base: `origin/main` at `0c4e774b` (#585, PR 5b). Read-only inventory; nothing here changes a rule.

## How to read this

**Sources.** Design = `docs/briefs/wizard-design-2026-11-08.md` (§ numbers and "rev N" for its revisions 1 to 31). Review = `docs/briefs/wizard-evidence-2026-11-08/report.md` and its `notes/`. Lead decisions = the "Lead answers" and D-numbered decisions in the PR specs in the lead's scratchpad `fix/` folder (`spec-pr3a.md`, `spec-pr3b.md`, `spec-pr4.md`, `spec-pr4b.md`, `spec-pr5.md`, `spec-pr5b.md`, `spec-pr5c.md`, `muscles-pr3.md`, `spec-574.md`, `spec-keepon.md`). `spec-keepon.md` holds no training rule (screen and buzz behavior only), so it has no rows.

**Code paths** are relative to `domain/src/main/kotlin/cloud/trotter/log/strength/domain/` unless they start with `data/`. "Not built" marks a rule the design or a lead answer settled but that is not on main (PR 4b-ii/iii, PR 5c, PR 6); it is listed because the plan will depend on it.

**Status.**
- **SUPPORTED**: a study or position stand the review read gives this number or a range that contains it.
- **VERIFY**: the number rests on a source the review flags `[verify]` (or design §7 lists): recalled, secondary, snippet-level, or abstract-only.
- **PRACTICE**: the review itself labels it practice, coaching convention, a heuristic it recommends, or its own synthesis.
- **INVENTED**: no source gives it. Chosen to fit the minutes, to mirror another rule, as a placeholder, or as a product decision. A plausible number with no source is still INVENTED, and so is a number the design labels "practice" when the review gives no such convention.

Where a rule mixes a supported idea with an invented number, the row is split, or the status follows the number the code actually applies.

## 1. Lift

| id | domain | rule as implemented | where it came from | evidence in the review | status |
|---|---|---|---|---|---|
| L01 | lift | Split by days: full body at 1 to 3 days and for General fitness at every count; upper/lower at 4 to 6 (5 days: U, L, U, L, FB); Muscle at 6 days: Push, Pull, Legs ×2. `planner/DayLayout.kt:50-57` | §3.1.1 "Split by days" | ACSM 2026: hypertrophy unaffected by frequency at equal volume, split is a preference (moderate) | SUPPORTED |
| L02 | lift | Rotation starts Lower; with 4+ training days one REST follows the third training entry; letters run through REST. `planner/DayLayout.kt:97-105` | Lead answer, spec-pr3a Q1: "one REST follows the third training entry" | none (review supports a rotation, not where its rest goes) | INVENTED |
| L03 | lift | Every chosen main appears once per rotation as MAIN, even when its day cannot fit. `planner/DayLayout.kt:62-70` | Invariant 2; rev 26 | Review: "the user chooses what they do" (moderate for affect) | SUPPORTED |
| L04 | lift | Strength and Muscle add a SECONDARY exposure of the same lift on the next day that hosts its pattern; General and 65+ get none. `planner/DayLayout.kt:71-79` | §3.1.1 "Second exposure"; spec-pr3a D2 | "each main barbell lift twice a week for strength goals" (moderate) | SUPPORTED |
| L05 | lift | SECONDARY dose: Strength 2 × 5 at 3 RIR; Muscle 2 × 8–12 at 2 RIR. `planner/LiftRules.kt:26,32,34`; `progression/SlotRule.kt:74-75` | §3.1.1 "Second exposure of a main" | none for these numbers | INVENTED |
| L06 | lift | Strength main, A year or more: RAMP_TOP_BACKOFF with TOP × 5 at 2 RIR (5 + 2 = 7RM, about 81% 1RM by Epley). `planner/LiftRules.kt:21-25,32`; `progression/SlotRule.kt:74` | §3.1.1 "Main scheme, A year or more" | ACSM 2026: strength needs ≥80% 1RM (strong); about 2 RIR, range 1–3 (moderate) | SUPPORTED |
| L07 | lift | `Scheme.STRENGTH` ramps 60% × 5 and 80% × 3 before the TOP. `progression/Scheme.kt:16` | §3.1.1 | none (warm-up sets; review gives no ramp) | INVENTED |
| L08 | lift | `Scheme.STRENGTH` back-offs: 2 sets at 90% of TOP × 5. `progression/Scheme.kt:16` | §3.1.1 | none for 90% or the count (ACSM's "2 to 3 sets per exercise" plateau is itself [verify]) | INVENTED |
| L09 | lift | `Scheme.PROTOTYPE` for converted and user-built ramped slots: ramps 55/70/80/90% × 5,5,5,3, one back-off 75% × 8. `progression/Scheme.kt:10-13`; `progression/SlotRule.kt:68` | Prototype formula kept for conversion (§3.1.5, case 16) | none | INVENTED |
| L10 | lift | Strength main, New or Under a year: STRAIGHT 3 × 5 at 3 RIR. `planner/LiftRules.kt:28,32,46` | §3.1.1; spec-pr3a D5 | "5 to 8 reps for the first weeks" (strong); beginners start at 2–4 RIR (moderate/practice) | SUPPORTED |
| L11 | lift | Not built: the step from 3 RIR to 2 (Strength) or to 1–2 (Muscle) after six exposures. New and Under-a-year mains stay at 3 RIR. | §3.1.1 "for six exposures, then 2"; spec-pr3a Q4 (deferred) | "Starting at 2 to 4 RIR and tightening to 1 to 3 after a few weeks" (practice); "six" has no source | PRACTICE |
| L12 | lift | Muscle main, A year or more: STRAIGHT 3 × 6–10 (Range 6..10) at 2 RIR. `planner/LiftRules.kt:28,33`; `progression/SlotRule.kt:75` | §3.1.1 | Size: 6–15 reps (strong); 1–2 RIR compounds (moderate) | SUPPORTED |
| L13 | lift | Muscle main, New or Under a year: 3 × 8–12 at 3 RIR. `planner/LiftRules.kt:34,46` | §3.1.1; spec-pr3a D5 | 8–15 reps (strong); 2–4 RIR untrained (moderate) | SUPPORTED |
| L14 | lift | General fitness main: 2 × 8–15 at 3 RIR. `planner/LiftRules.kt:27,35`; `progression/SlotRule.kt:76` | §3.1.1 | 8–15 at moderate load, 1–2 sets per exercise (strong); 2–3 RIR (moderate) | SUPPORTED |
| L15 | lift | Strength accessories: 3 × 8–12 at 2 RIR. `planner/LiftRules.kt:28,36`; `progression/SlotRule.kt:74` | §3.1.1 "Accessories" | "an 8 to 12 style range on accessories" (practice) | PRACTICE |
| L16 | lift | Muscle accessories: 3 × 10–15; isolation at 1 RIR, others 2. `planner/LiftRules.kt:28,37`; `progression/SlotRule.kt:75` | §3.1.1; Opus S5 | 6–15, 15–30 on isolation (strong); 0–2 RIR isolation (moderate) | SUPPORTED |
| L17 | lift | General accessories: 2 × 10–15 at 2 RIR. `planner/LiftRules.kt:27,37`; `progression/SlotRule.kt:76` | §3.1.1 | 8–15 (strong); 2–3 RIR (moderate) | SUPPORTED |
| L18 | lift | A New lifter never gets a target below 2 RIR, on any slot, permanently. `planner/LiftRules.kt:47` | Invariant 7; spec-pr3a D5 | "Beginners should not see a near-failure set in their first weeks" (practice) | PRACTICE |
| L19 | lift | One CORE slot of 2 sets on every lift day, rotating anti-extension, anti-rotation, flexion. `planner/DayLayout.kt:178`; `planner/LiftRules.kt:26` | spec-pr3a D3: "§3.1.1 has no core dose row" | none | INVENTED |
| L20 | lift | Biceps and triceps isolation run as one superset slot. `planner/DayLayout.kt:139-147` | §3.1.1 "Isolation rotates as today" | "supersets of non-competing muscles are a valid time saver" (practice) | PRACTICE |
| L21 | lift | Day skeletons: FB fills knee, hip, horizontal press, pull, then side delt / arms / calf by turn; Upper H-push, H-pull, V-push, V-pull, side or rear delt, arms; Lower squat, hinge, single-leg, knee flexion, calf; Push and Pull lists. `planner/DayLayout.kt:151-177` | Spec §6.3 carried over; spec-pr3a D4 | none | INVENTED |
| L22 | lift | Accessory pick: first by catalog `subRank`, never main-capable, a chosen main, already on the day, or the loaded side of a pair. `planner/DayLayout.kt:184-192` | spec-pr3a D8 (mirrors the old `bestAccessory`) | none | INVENTED |
| L23 | lift | Default mains: back squat, trap-bar deadlift, bench, overhead press, barbell row, else the first available by rank. `planner/Planner.kt:82-97` | §2.2 Your lifts default; spec-pr3a D1 | none (choice is a preference) | INVENTED |
| L24 | lift | Opening load from a recent number or the last working set through Estimate; a history set with no rir reads as 2 RIR. `progression/SlotSeeding.kt:67-78`; `planner/Planner.kt:148-150` | §3.1.1 Opening loads; Opus S7 | Seeding from a stated ≤10-rep lift (moderate); "null reads as 2" has no source | INVENTED |
| L25 | lift | Epley: e1RM = w × (1 + (r + k)/30) only when r + k ≤ 10; load for t reps at j RIR = e1RM / (1 + (t + j)/30). `progression/Estimate.kt:3-12` | §3.1.1 Estimate | Epley at ≤10 reps (moderate); the formulas and the ±10% individual error come from Wikipedia [verify]; design §7 | VERIFY |
| L26 | lift | Empty-bar floor (45 lb / 20 kg) for barbell and trap bar, calibrating on. `progression/SlotSeeding.kt:85-86` | §3.1.1 Opening loads | "start a new lifter with the empty bar" (practice) | PRACTICE |
| L27 | lift | Other floors: dumbbell 10 lb / 5 kg, machine, cable and EZ bar 20 lb / 10 kg, kettlebell 18 lb / 8 kg. `progression/SlotSeeding.kt:87-90` | §3.1.1 Opening loads | Review says "a light dumbbell, or the lowest machine plates"; the numbers have no source | INVENTED |
| L28 | lift | Calibration: a set at rir ≥ 4 makes the next set × 1.25 rounded up and at least two steps heavier; still Easy at the end → last × 1.25, still calibrating. `progression/Progression.kt:480-484` | §3.2 Calibrating; Opus S6 | "add weight in small steps across warm-up sets until ... 3 to 4 RIR" (practice, weak); 25% and two steps have no source | INVENTED |
| L29 | lift | Calibration ends on the first set at rir ≤ 3: Estimate when r + k ≤ 10; else that load if reps ≥ target, otherwise × 0.9. `progression/Progression.kt:469-479` | §3.2 Calibrating; rev 2 R2-12 | Epley as L25; the × 0.9 has no source | INVENTED |
| L30 | lift | Effort words map to stored RIR: Easy 4 (and any rir ≥ 4), Good 2, Hard 1, Max 0; an unrated or watch set counts as on target. `progression/Effort.kt:4-12`; `progression/Progression.kt:455` | §3.2 Effort words; owner Q7 | Halperin 2022: people underpredict by about 1 rep (moderate); "easy, right, grinder or missed" as a fallback (practice). The 4/2/1/0 mapping has no source | INVENTED |
| L31 | lift | Reps-left scale offers 0 to 5. `progression/EffortScale.kt:10` | §4.2 | none | INVENTED |
| L32 | lift | Success: every working set reaches target reps and rir ≥ rule.rir − 1. `progression/Progression.kt:164-169` | §3.2 Success | Review: raise load when sets hit target "at or above target RIR"; the one-rep tolerance is the design's reading of Halperin's ~1-rep underprediction | PRACTICE |
| L33 | lift | Effort miss: all reps made but a set at rir < rule.rir − 1; two in a row hold the load and slow cadence by one (max 3); effort never lowers a load. `progression/Progression.kt:167,221-228` | §3.2 Miss row; rev 2 R2-5 | Miss = "RIR worse than target by two or more"; hold (practice) | PRACTICE |
| L34 | lift | Rep miss holds; two rep misses in a row at the same load lower it by `dropPct` 5%, rounded down to the step (editable 0 to 10%). `progression/Progression.kt:191-215`; `progression/SlotRule.kt:46` | §3.2 Miss; owner Q2 | "After a second consecutive miss at the same load, reduce that lift alone by about 5%" (practice) | PRACTICE |
| L35 | lift | A stall with a coarse step or bodyweight drops target reps by 1, floor 3. `progression/Progression.kt:202-210` | §3.2 Miss | "or drop 1 to 2 reps for a session" (practice); floor 3 has no source | PRACTICE |
| L36 | lift | A second stall within 42 days slows cadence by one (max 3). `progression/Progression.kt:195-198` | §3.2 Miss | "two resets of the same lift within about six weeks: change the cadence" (practice) | PRACTICE |
| L37 | lift | Base cadence: increase after 1 success for New and Under a year, after 2 for A year or more; 1 on user-built and converted plans. `planner/LiftRules.kt:55`; `progression/SlotRule.kt:67` | §3.2 Cadence; spec-pr3a D6 | Progress slows with training age (moderate); the review says to slow cadence "when the lift stalls, rather than by the calendar". Starting experienced lifters at 2 has no source | INVENTED |
| L38 | lift | Cadence never passes 3; four successes in a row at a slowed cadence step it back one, never below the base. `progression/Progression.kt:197,224,401-403` | Rev 3 O3-4; rev 6 A8 | none | INVENTED |
| L39 | lift | Increment for New and Under a year: 5% lower body, 2.5% upper and small muscles. `planner/LiftRules.kt:54`; `progression/SlotRule.kt:66` | §3.1.1 Increment; spec-pr3a D6 | "about 2.5% ... upper-body and small-muscle lifts and about 5% for lower-body lifts" (synthesis, practice); Latella 2022 asymmetry (moderate) | PRACTICE |
| L40 | lift | Increment for A year or more, and for every user-built and converted slot: 2.5% lower, 1.25% upper (half the review's values). `progression/SlotRule.kt:66` | §3.1.1; owner Q15 | none for the halving | INVENTED |
| L41 | lift | Increment rounding: nearest step, at least one step, at most 10% of the load. `progression/Progression.kt:431-433` | §3.1 boundary sentence | ACSM 2009 2–10% when the target is exceeded [verify]; design §7 | VERIFY |
| L42 | lift | Easy session (every working set rir ≥ 4): twice the rounded single increment, still within 10%. `progression/Progression.kt:430-433` | §3.2 Easy session (labeled practice) | none | INVENTED |
| L43 | lift | A working set at rir 0 (Max) blocks any load increase; a range may still gain a rep. `progression/Progression.kt:428` | §3.2 Max | "never add load after a set the user rated as a grinder or 0 RIR" (practice) | PRACTICE |
| L44 | lift | Range progression: below the top, target = min(hi, lowest working reps + 1) at the same load; at hi with every set ≥ hi, add the increment and return to lo. `progression/Progression.kt:421-439` | §3.2 Range | Double progression: weak (one RCT), mechanics practice | PRACTICE |
| L45 | lift | Coarse step: when step ÷ load > 0.10, a Fixed t becomes Range(t, t + 4) and load moves one step only at the top. `progression/Progression.kt:184-188` | §3.1.1; Sol S5 | "Where equipment steps are too coarse ... reps progress first" (practice); 0.10 mirrors the 10% cap; +4 has no source | INVENTED |
| L46 | lift | Bodyweight slots add one rep per success up to 20, then offer the weighted pair. `progression/Progression.kt:410-420` | §3.1.1; case 26 | Review avoids leaning on RIR above about 12 reps; 20 has no source | INVENTED |
| L47 | lift | Carried load: a completed TOP is the anchor; straight sets adopt a load only when a strict majority of planned WORK rows were done there; a lower adopted load clears streaks and earns no increase that session. `progression/Progression.kt:138-161` | §3.2 Carried load; owner decision rev 10 | Self-selected loads built strength in older adults (moderate), but women chose sub-threshold loads (abstract level) | INVENTED |
| L48 | lift | Shared success credit: a successful Finish also credits every other slot in the plan with the same exercise and working-rep target (so a Strength SECONDARY 2 × 5 credits the MAIN TOP × 5). `progression/SlotRule.kt:33-34`; `progression/Progression.kt:398-400`; data layer | Rev 10 caller override | none | INVENTED |
| L49 | lift | Rest, Strength MAIN 180 s. `standards/RestPolicy.kt:69` | §3.1.1 Rest row | ACSM 2026: strength unaffected by rest above or below 1 min; "2 to 3 min, top sets 3 to 5" leans on Grgic 2018 [verify] | VERIFY |
| L50 | lift | Rest, Strength and Muscle SECONDARY 120 s, ACCESSORY 90 s; Muscle MAIN 120 s. `standards/RestPolicy.kt:68-71` | §3.1.1 Rest row | "about 2 min compounds" (moderate); no difference past 90 s (Singer 2024, moderate to weak) | SUPPORTED |
| L51 | lift | Rest, ISOLATION and CORE 60 s (Strength, Muscle, General). `standards/RestPolicy.kt:72,77` | §3.1.1 Rest row | "60 to 90 s isolation" (moderate); Singer: >60 s slightly better for size | SUPPORTED |
| L52 | lift | Rest, General fitness MAIN and ACCESSORY 90 s. `standards/RestPolicy.kt:76` | §3.1.1 Rest row | "60 to 120 s" (moderate) | SUPPORTED |
| L53 | lift | RAMP sets rest 90 s and BACKOFF sets 120 s whatever the role; buckets TOP 180, WORK 90, LIGHT 60 for unruled rows; clamp 0 to 300 s. `standards/RestPolicy.kt:30,34-40,106-108` | User decision 2026-07-20; rev 3 O3-5 | none | INVENTED |
| L54 | lift | Light cycle view: first ceil(n × 0.6) work sets kept (at least 1), loads × 0.9 to the step, ramps unchanged; progression holds. `progression/LightWeek.kt:20-31`; `progression/Progression.kt:125` | §3.2 Light cycle | Deload "cuts sets by 30 to 50%, cuts load about 10%" (practice; Rogerson 2024 survey, Bell 2023 Delphi) | PRACTICE |
| L55 | lift | A light cycle lasts the rotation's training-entry count, falling by one per finished training day. `progression/LightWeek.kt:13-18` | §3.2 Light cycle | "lasts about a week" (practice) | PRACTICE |
| L56 | lift | Planned light week every 6 rotations for A year or more, on by default; none for New and Under a year; editable off or 4 to 8. `planner/Planner.kt:125-126`; `progression/LightWeek.kt:15` | Owner Q10; spec-pr3a | "optional planned light week every 5 to 8 weeks" (practice; survey 5.6 ± 2.3 weeks); novices none | PRACTICE |
| L57 | lift | Reactive light cycle on Automatic plans: stalls on 2+ lifts in a rotation, or Rough day on two consecutive sessions. `progression/LightWeek.kt:13-17`; data layer (rev 11, rev 23) | §3.2 Light cycle | "stalls on two or more lifts, a fatigue or joint-ache flag on two or more sessions" (practice) | PRACTICE |
| L58 | lift | Layoff clock: expected recurrence = ceil(7 × training entries ÷ (days a week × appearances per rotation)); layoff = days since the exercise's last session − expected. `progression/Progression.kt:45-46,59` | Rev 1 C2 | Review measures "days since the lift was last trained"; the recurrence offset has no source | INVENTED |
| L59 | lift | Layoff day bands: layoff ≤ 3 normal; 4–7 hold; 8–14 scaled; 15–42 scaled with fewer sets; > 42 recalibrate. `progression/Progression.kt:60-67` | §3.2 Layoff | Review bands (practice): ≤10 days resume, 11–21 days, 3–8 weeks, > 8 weeks; Bosquet 2013 "no loss up to 28 days" [verify]. Code recalibrates after 6 weeks, not 8, and counts days past recurrence | INVENTED |
| L60 | lift | Layoff factors: × 0.95; × 0.85 with ceil(⅔) working sets; × 0.75 and calibrating. `progression/Progression.kt:63-85` | §3.2 Layoff | Review restart table: 90–95%, 80–90% with about two-thirds of the sets, 70–80% re-seeded ("synthesis, not evidence") | PRACTICE |
| L61 | lift | After a scaled opening on target, next time reopens at the pre-layoff load with streaks unchanged; a rep miss keeps the scaled load. `progression/Progression.kt:171-180` | §3.2 Layoff; rev 2 R2-10 | "return to it within 1 to 2 sessions if RIR comes in on target" (practice) | PRACTICE |
| L62 | lift | Rough day reverses that Finish's changes (no streak or load change). Data layer, rev 23 | §3.2 Rough day; rev 23 | "A miss the user attributes to poor sleep, illness or a short session should not count toward the stall counter" (practice) | PRACTICE |
| L63 | lift | Weekly set floor, Strength: 6 per floor muscle (rotation total). `planner/VolumeBalance.kt:33-37` | §3.1.1 band "6 to 12"; spec-pr3b; owner floors | Review lists weekly sets for strength as "lower priority" and gives no number | INVENTED |
| L64 | lift | Weekly set floor, General fitness: 4. `planner/VolumeBalance.kt:37` | §3.1.1 band "4 to 8" | "1 to 2 sets per exercise on all major muscles" (strong) gives no weekly number | INVENTED |
| L65 | lift | Weekly set floor, Muscle, New and Under a year: 10. `planner/VolumeBalance.kt:39` | §3.1.1 band "10 to 14" | ACSM 2026: "10 or more sets per muscle per week" enhances hypertrophy (moderate number) | SUPPORTED |
| L66 | lift | Weekly set floor, Muscle, A year or more: 12. `planner/VolumeBalance.kt:38` | §3.1.1 band "12 to 18" | "about 10 to 12 untrained, up to about 20 for trained users" (moderate) | SUPPORTED |
| L67 | lift | Floor muscles: quads, hamstrings, glutes, chest, lats, upper back, front delts; Muscle adds biceps and triceps; no floor for side and rear delts, calves, core; a muscle no available entry trains as primary is exempt. `planner/VolumeBalance.kt:21-46` | muscles-pr3.md Minimums ("Claude's call"); spec-pr3b Q1 | none | INVENTED |
| L68 | lift | Cap of 20 weekly sets per muscle on rotation totals, never relaxed; §3.1.1's band tops are not enforced. `planner/VolumeBalance.kt:18,57-73` | Owner "Cap 20 for all"; spec-pr3a Q2 | ACSM's reading of Pelland: plateau near 18–20 weekly sets [verify]; design §7 | VERIFY |
| L69 | lift | Volume fill and trim: grow a straight slot that trains the muscle as primary by one set at a time (fewest sets first, cap 5 a slot), then add a 1-set accessory before core; trim one set at a time from the accessory, isolation or core slot with most sets. `planner/VolumeBalance.kt:19,57-128` | spec-pr3b Q2, Q5 | none | INVENTED |
| L70 | lift | Set counting: primary 1 per working set, secondary 0.5; ramps never count. `library/ExerciseEntry.kt:95-98`; `planner/SetCount.kt:11-22` | §3.1 invariant 5 | Pelland 2026: counting indirect sets as half fit best (cited from the preprint) | SUPPORTED |
| L71 | lift | Muscle credit table: pattern defaults plus exceptions (overhead press front delt + triceps, never chest; trap bar quads and glutes; RDL family hamstrings and glutes). `library/ExerciseLibrary.kt:60-123` | Owner table, muscles-pr3.md; owner Q14 | none (anatomical convention) | INVENTED |
| L72 | lift | Plyometric credits: pogo calves 0.5; bound and box jump quads and glutes 0.5; A-skip and stance none. `library/ExerciseLibrary.kt:124-128` | muscles-pr3.md (cites Grgic 2021, not in the review) | none in the review | INVENTED |
| L73 | lift | Exposure floor: with 2+ days, each major muscle gets 2+ exposures per rotation and 2+ strength days cover every major muscle (pull reuse fallback). `planner/DayLayout.kt:84-96`; invariant tests | Invariants 3, 4 | Two strength days covering all major muscles (strong; WHO 2020, ACSM 2026); each muscle twice a week (moderate) | SUPPORTED |
| L74 | lift | Exposure and set floors give way on rotations with any ShortSession day. Tests; spec-pr3a D9 | §3.1 "minutes always win" | none | INVENTED |
| L75 | lift | Dose floor: the ladder never goes below one working set per main lift. `planner/DoseLadder.kt:38-44` | §3.1 ladder | One hard set 2–3 times a week still raises 1RM (Androulakis-Korakakis 2020, moderate) | SUPPORTED |
| L76 | lift | Dose ladder steps, in order: accessories to one 2-set core slot; drop SECONDARY; mains to 2 straight sets; mains to one ramp at 80% × 3 plus one working set; MAIN rest capped at 120 s; drop core; then OverBudget. `planner/DoseLadder.kt:13,22-64` | §3.1 ladder; owner Q12, Q17 | none for the order or the 80% × 3 ramp or 120 s | INVENTED |
| L77 | lift | Default reps and RIR for user-built and converted slots: Strength 5 (main, secondary) or 10; Muscle 8 or 12; General 10 or 12. `progression/SlotRule.kt:73-83` | §3.2 `SlotRule.defaults`; owner Q15 | none | INVENTED |
| L78 | lift | Load step defaults: bar 5 lb, dumbbell 5 lb, stack 10 lb (2.5 / 2 / 5 kg); step read from the exercise's equipment. `progression/LoadSteps.kt:12-16,42-45` | §2.1 Equipment | "Full gym, 5 lb or 2.5 kg steps" default (practice) | PRACTICE |

## 2. Older adult (65+)

| id | domain | rule as implemented | where it came from | evidence in the review | status |
|---|---|---|---|---|---|
| O01 | older adult | The 65+ path is a modifier on any branch, read from the profile age ≥ 65 until PR 6's setup answer. `progression/Progression.kt:23`; `data/.../TrackerRepository.kt:1010` | §2.1; rev 11 | WHO 2020 sets its older-adult guidance at 65 (strong) | SUPPORTED |
| O02 | older adult | Mains STRAIGHT, Range 6–10 at 3 RIR, never ramped (6 + 3 = 9RM ≈ 77% 1RM, 10 + 3 ≈ 70%). `planner/LiftRules.kt:21,31,48` | §3.1.1 65+ column | Borde 2015: largest strength effects at 70–79% 1RM (moderate); 2–4 RIR, no failure (weak, ACSM caution) | SUPPORTED |
| O03 | older adult | Every 65+ slot is 2 sets: mains 2 × 6–10, accessories 2 × 8–12 at ≥ 3 RIR. `planner/LiftRules.kt:26,36,48` | §3.1.1 65+ column | Borde's "2 to 3 sets, 7 to 9 reps" was recalled from memory [verify] | VERIFY |
| O04 | older adult | No SECONDARY exposures on the 65+ path. `planner/DayLayout.kt:71` | Rev 26 | Review keeps 2 exposures per muscle a week (moderate); dropping second exposures has no source | INVENTED |
| O05 | older adult | 65+ MAIN rest 120 s, written into `rule.restSeconds`. `planner/LiftRules.kt:57` | §3.1.1; spec-pr3a Q3 | "1.5 to 2 min" (weak) | SUPPORTED |
| O06 | older adult | 65+ weekly set floor 6 for every goal. `planner/VolumeBalance.kt:35` | §3.1.1 band "6 to 10"; rev 27 | "About 6 to 10, building up (practice)" | PRACTICE |
| O07 | older adult | A balance block opens every lifting day, run strength block and sprint lift day; no ladder step removes it. `planner/DayLayout.kt:194-208`; `planner/DoseLadder.kt:28`; `planner/RunLayout.kt:70`; `planner/SprintLayout.kt:110` | §3.1.1 Order; rev 7 B6 | WHO 2020: balance and functional strength on ≥ 3 days a week (strong); a short balance block on lifting days satisfies it (review). A 2-day plan gives 2 days, under WHO's 3 | SUPPORTED |
| O08 | older adult | Balance dose: single-leg stance 2 × 30 s each side, 30 s rest. `library/ExerciseLibrary.kt:339`; `planner/LiftRules.kt:40-43` | Owner table; spec-pr3a Q3 (case 36 re-pinned) | WHO gives no dose | INVENTED |
| O09 | older adult | Carry: farmer's carry 2 × 20 s at the catalog's 50 lb, 60 s rest (LIGHT bucket); left out without dumbbells. `planner/DayLayout.kt:199-205`; `library/ExerciseLibrary.kt:322` | Owner table "about 20 m at a walk, 2 x 20 s" | none; the 50 lb load is the catalog's adult default, not chosen for this path | INVENTED |
| O10 | older adult | Layoff over 14 days on the 65+ path → × 0.75 and recalibrate (the New-under-six-months half waits for PR 6). `progression/Progression.kt:63-64` | §3.2 Layoff; rev 11 | Review: "any length for users over 65 or with under 6 months" (practice); older g 0.76 vs younger 0.31 from Bosquet 2013 via a secondary source [verify]; design §7 | VERIFY |

## 3. Run

| id | domain | rule as implemented | where it came from | evidence in the review | status |
|---|---|---|---|---|---|
| R01 | run | Mostly easy: one quality session a week at 3 to 4 days; a second (Q2) only at 5 to 6 days for Some or Racing. `planner/RunLayout.kt:23-30,41-51` | Invariant 9; spec-pr4 Q2 | 75–80% easy; 1 quality in 3-run weeks, 2 at 4+ for experienced (moderate-strong for "mostly easy") | SUPPORTED |
| R02 | run | Run rotation rows by days: E+S; E+S, L+S; E+S, Q+S, L, R; E+S, Q, E+S, L, R; E+S, Q, E+S, Q2, L, R (or E for Q2); E+S, Q, E, Q2, E+S, L, R. `planner/RunLayout.kt:23-30,49-51` | §2.1 table; spec-pr4 Q2 | none for the layout | INVENTED |
| R03 | run | One REST after the long run; no REST between Q and Q2. `planner/RunLayout.kt:23-30` | spec-pr4 Q2(c) | none | INVENTED |
| R04 | run | Three effort words Easy, Good, Hard (talk test; RPE 3–4, 5–7, 8–10 internally, never shown or stored as numbers). `progression/CardioEffort.kt:3-6` | Owner Q16 | Talk test tracks the first ventilatory threshold, mostly cardiac patients (moderate); review bands are 3–4, 5–6, 7–9; "valid against both thresholds" from a search summary [verify] | VERIFY |
| R05 | run | Run cap: a planned session's WORK + RECOVERY total ≤ 110% of the family's longest finished session in the last 30 days, at start and when stepping. `progression/RunCap.kt:12-49`; `progression/Progression.kt:243-247` | §3.2 Run cap | Frandsen 2025 (moderate, one exploratory cohort): figures from a BJSM blog, DOI and CIs not captured [verify]; design §7 | VERIFY |
| R06 | run | Cap mechanics: WORK and RECOVERY both count; compare in metres when both sides have distances, else seconds; limit rounds down to 100 m or 1 min but never below the maximum; intervals drop rounds, a single set is shortened. `progression/RunCap.kt:15-16,45-97` | Rev 2 R2-2, rev 3 S3-1; Sol S6 | none | INVENTED |
| R07 | run | The setup answer stands in for the cap until the first finished session or 30 days after creation. `progression/RunCap.kt:39-41` | §3.2 Run cap; Sol S6 | none | INVENTED |
| R08 | run | Ladder sessions are exempt from the cap. `progression/Progression.kt:244`; `planner/RunLayout.kt:119` | Owner Q13 | none | INVENTED |
| R09 | run | A16 increase: on full success, distance or time + 5% (clamp 1–25%), pace + 1% opt-in; the only growth for long runs and for unruled (converted) cardio days. `progression/CardioMeasures.kt:13-20,50-92`; `progression/Progression.kt:287-305` | A16 (PLAN.md), §3.2 Cardio measure "Practice (A16)" | none | INVENTED |
| R10 | run | Easy runs: minutes − 5 min warm-up − 25 min on a strength day, at least 20 min, never above 75% of the stored long run (whole minutes, at least 1 min). `planner/RunSessions.kt:15-23` | Lead answer spec-pr4 Q3 (D14) | none ("easy runs sit below the long run in every standard plan") | INVENTED |
| R11 | run | Ruled easy runs repeat: no growth. Just cardio fitness (the default run sub-goal) is all easy, so it never progresses until 4b-iii. `progression/Progression.kt:279` | Lead answer spec-pr4b Q2 | none | INVENTED |
| R12 | run | Long run opens: half build 18,000 m or 2 h; marathon build 28,000 m or 3 h. Distance form unless the anchor is time-only. `planner/RunSessions.kt:39-58` | spec-pr4 D5 | Half "build to 18 to 21 km, or about 2 h"; marathon "typically 28 to 32 km, or about a 3 h cap" (moderate observational; time caps weak convention) | PRACTICE |
| R13 | run | Long run opens in base at 10,000 m or 60 min (the base gate reused as the template). `planner/RunSessions.kt:41-55` | spec-pr4 D5 | 10K row: long run "building to about 60 to 90 min" (preference); 10,000 m as a base template has no source | INVENTED |
| R14 | run | Ultra opens: 50K 30,000 m (60%) or 3.5 h; 50 mi 48,280 m (60%) or 4 h; 100K and 100 mi 4 h. `planner/RunSessions.kt:44,50-53` | spec-pr4 D5 | "60 to 80% of race distance for 50K to 50 mi; several 4 to 6 h runs for 100K to 100 mi" (weak, convention); 3.5 h and the 50 mi 4 h form have no source | INVENTED |
| R15 | run | Middle distance long run opens at 60 min, time form only. `planner/RunSessions.kt:41` | spec-pr4 D5 | "about 60 to 75 min" (weak, elite descriptive) | PRACTICE |
| R16 | run | Long run stops: base 90 min; half 21,000 m or 2 h; marathon 32,000 m or 3 h; 50 mi 64,374 m (80%); 100K and 100 mi 6 h; middle distance 75 min; never shrinks a stored end. `planner/RunSessions.kt:111-121`; `progression/Progression.kt:312-329` | spec-pr4b D8 | Review ranges as R12, R14, R15 (moderate observational to weak) | PRACTICE |
| R17 | run | 50K stops at 40,000 m and 3.5 h, whichever first at the recent pace; 50 mi time form stops at 6 h (borrowed from 100K). `planner/RunSessions.kt:116-117` | spec-pr4b D8 ("capped at 3.5 h", lead "calls I made") | 40 km = 80% fits the review; 3.5 h and the 50 mi 6 h have no source | INVENTED |
| R18 | run | VO2max session: 5 → 6 × 3 → 4 min hard, equal recovery. `progression/RunRange.kt:50` | §3.1.2; spec-pr4b D3 | Wen 2019: only ≥2 min reps and ≥15 min of work beat continuous (moderate); "4 to 6 × 3 to 4 minutes hard" | SUPPORTED |
| R19 | run | Threshold 20 → 30 min continuous; tempo 15 → 25 min, both Good band. `progression/RunRange.kt:48-49` | §3.1.2; spec-pr4b D3 | Threshold 20–30 min, tempo 15–25 min ("preference for volumes and reps") | PRACTICE |
| R20 | run | Race pace 6 → 8 × 400 m with 2 min jog (middle distance). `progression/RunRange.kt:51` | §3.1.2 | "6 to 8 × 400 m at mile pace, 2 min jog" (weak, elite practice) | PRACTICE |
| R21 | run | 800 m sharpening in the last 42 days: 3 → 4 × 300 → 500 m at race pace. `progression/RunRange.kt:52`; `planner/RunSessions.kt:89-91` | §3.1.2; spec-pr4b D2 | "3 to 4 × 300 to 500 m at 800 m pace, long recovery, in the last 4 to 6 weeks" (weak) | PRACTICE |
| R22 | run | Sharpening recovery 4 min ("full recovery" set at the low end of the sprint table's 4 to 6 min). `progression/RunRange.kt:52` | spec-pr4 D4 (lead accepted the practice value) | none for 4 min | INVENTED |
| R23 | run | Test intervals 4 → 6 × 400 → 800 m. `progression/RunRange.kt:53` | §3.1.2 | "4 to 6 × 400 to 800 m" (moderate for intervals, preference for exact reps) | PRACTICE |
| R24 | run | Test interval recovery 2 min (borrowed from middle distance). `progression/RunRange.kt:53` | spec-pr4 D4 | Notes give "equal recovery"; 2 min has no source | INVENTED |
| R25 | run | Test rep aims at 95% of goal pace; Cooper reads its score as metres in 720 s; PACER and HAMR have no aim. `progression/GoalPace.kt:7-15`; `planner/RunSessions.kt:81-87` | §3.1.2 "5% faster than goal pace"; spec-pr4 D7 | "slightly faster than goal pace" (moderate intervals); 5% has no source | INVENTED |
| R26 | run | Race-pace aims from the goal time, else a Riegel prediction, rounded once with a 1 s minimum; later steps scale the stored aim per step. `planner/RunSessions.kt:62-79`; `progression/Progression.kt:348-353` | spec-pr4 D7; spec-pr4b Q3 | none for the rounding or per-step scaling (Riegel itself is R33) | INVENTED |
| R27 | run | Quality ranges start at the low end and add one unit (a round first, then 60 s or 100 m per rep) every two counted sessions. `progression/RunRange.kt:28-43`; `progression/Progression.kt:355-376` | §3.1 range sentence; spec-pr4b D3 | none | INVENTED |
| R28 | run | What counts: full success (exact block, every WORK set finished, every aim met); intervals count on any word, threshold and tempo not on Hard; a capped step waits with its credit. `progression/Progression.kt:341,370-375` | §3.1.2; spec-pr4b D4, D5 | none | INVENTED |
| R29 | run | Quality kind by structure: base Q threshold (alternating with VO2max when lone), Q2 VO2max; builds threshold; middle distance race pace then threshold; tests intervals then tempo; Just cardio easy. `planner/RunLayout.kt:34-47`; `planner/RunSessions.kt:107-108` | spec-pr4 D3; spec-pr4b D7 | Event table: threshold and VO2max for 5K/10K; threshold for half and marathon; race pace plus threshold for middle distance; intervals plus tempo for tests (moderate distribution, weak specifics) | PRACTICE |
| R30 | run | Fitness test plans make the long entry an easy run. `planner/RunLayout.kt:61` | spec-pr4 D3 | "Long run: not central" | PRACTICE |
| R31 | run | Taper length: up to 10K and every test 10 days; half 14 days; marathon and ultra 21 days. `progression/Taper.kt:23-36` | §3.2 Phase and taper | 7–10 days to 10K (Haugen 2022), 10–14 days half, about 3 weeks marathon (Smyth 2021) (moderate); Bosquet's "8 to 14 days" is a secondary paraphrase [verify] | SUPPORTED |
| R32 | run | Taper volume: up to 10K and tests × 0.6; marathon and ultra × 0.8, × 0.6, × 0.5 by week. `progression/Taper.kt:23-36` | §3.2 Phase and taper | Bosquet 2007: cut 41–60% (moderate-strong); marathon "e.g. −20–25%, −40%, −60%" (moderate). Code softens the last week to −50% | PRACTICE |
| R33 | run | Half taper: × 0.7 from 14 to 8 days out, × 0.5 in the last 7. `progression/Taper.kt:26-30` | §3.2 Phase and taper | 0.7 is a 30% cut, outside Bosquet's 41–60%; no half-specific source | INVENTED |
| R34 | run | Taper scales WORK only: a single set's end × factor, intervals keep the first ceil(n × factor) rounds; hard flags and aims stay; no sprint taper. `progression/Taper.kt:22,43-60` | §3.2; spec-pr5 D5 | "cut volume and keep intensity" (moderate-strong) | SUPPORTED |
| R35 | run | Riegel T2 = T1 × (D2/D1)^1.06, computed in doubles, rounded once. `progression/Riegel.kt:8,20-21` | §3.2 Goal check | Well calibrated up to the half marathon (Vickers & Vertosick 2016, moderate) | SUPPORTED |
| R36 | run | Marathon goal check × 1.05; ultras get no check. `progression/Riegel.kt:10,16-17`; `planner/RunLayout.kt:165` | §3.2 Goal check | Marathon margin: design §7 lists Vickers & Vertosick [verify]; 1.05 is the design's own margin | VERIFY |
| R37 | run | Phase gates, distance forms: base needs a 10 km run; half build needs 21,000 m; marathon build needs 30,000 m or a logged marathon. `planner/RunPhase.kt:13-20` | §3.1.2 phase table | Half gate "comfortable 5K to 10K running"; marathon gate "comfortable half or about 30+ km a week"; ultra gate "a completed marathon" (moderate observational to weak) | PRACTICE |
| R38 | run | Phase gates, other forms: ladder done at 30 min or 5,000 m; time forms 60 min, 2 h, 3 h; week forms 30 km or 3 h in any 7 × 24 h window in 30 days; the ultra gate's 30 km or 3 h run. `planner/RunPhase.kt:14-18`; `progression/CardioHistory.kt:33-48` | Rev 3 O3-2, S3-4; spec-pr4 Q4; spec-pr4b D10 | none for the time, week-time or 5,000 m forms | INVENTED |
| R39 | run | Phase start: first unmet gate; no anchor → New starts on the ladder, Some or Racing in base; the plan never refuses an event. `planner/RunPhase.kt:22-27` | §3.1.2; spec-pr4 D9 | Fokkema 2020, Videbæk 2015 (moderate, observational) | PRACTICE |
| R40 | run | Minimum weeks per phase: ladder 8, base 6, each build 8; a closer date reports DateTooClose and still generates. `planner/RunPhase.kt:9,34-35`; `planner/RunLayout.kt:161-163` | Lead answer spec-pr4 Q4 | Ladder 8 from "8 to 12 weeks" (practice); base 6 and builds 8 have no source | INVENTED |
| R41 | run | Run/walk ladder level 1: 60 s run / 120 s walk × 8 (24 min). `progression/RunWalkLadder.kt:15` | §3.1.2 ladder table | "about 1 minute running and 1.5 to 2 minutes walking for 20 to 30 minutes" (preference on ratio) | PRACTICE |
| R42 | run | Ladder levels 2 to 12: 90/120 × 8, 120/90 × 7, 180/90 × 6, 240/60 × 6, 300/60 × 5, 480/60 × 3, 600/60 × 2, 900/60 × 2, 20, 25, 30 min continuous. `progression/RunWalkLadder.kt:14-17` | §3.1.2 ("editable table, practice") | none for the steps | INVENTED |
| R43 | run | Ladder moves: Easy or unrated → next level; Good repeats; Hard or Pain → down one (floor 1); level 12 Easy leaves the ladder. `progression/Progression.kt:263-275` | Owner Q16; rev 6 A5 | "advancing only when the current step felt manageable with no pain the next day" (practice); Relph 2023 (moderate) | PRACTICE |
| R44 | run | Ladder start: the highest level whose run interval fits the longest run's time; level 1 without a time. `planner/RunPhase.kt:37-41` | Lead answer spec-pr4 Q4 | none | INVENTED |
| R45 | run | A running injury in the past year starts one level lower (floor 1). `planner/RunPhase.kt:40` | §3.1.2 | "A user with a past running injury starts one level easier" (practice) | PRACTICE |
| R46 | run | Ladder plans use 3 sessions a week whatever the days answer. `planner/RunLayout.kt:48` | §3.1.2 First 5K row | "three sessions a week of easy run/walk" (moderate) | SUPPORTED |
| R47 | run | Run plans carry 0 to 2 strength blocks a week (default 2) on easy or quality days; the long run carries none except the 2-day L+S. `planner/RunAnswers.kt:53`; `planner/RunLayout.kt:23-30,65` | Owner Q9; spec-pr4 Q2 | Strength helps runners (Blagrove 2018, moderate); "the twice-weekly dose is an inference" | PRACTICE |
| R48 | run | Strength block contents: 65+ balance, SECONDARY squat or hinge, a single-leg accessory, a calf (gastroc or soleus), Nordic or knee flexion, a core pattern; 2 sets each under Strength rules; no plyometrics. `planner/RunLayout.kt:69-89` | spec-pr4 D8 | Review: "biased toward heavy compound lower-body lifts and plyometrics" (moderate); the contents and set count have no source | INVENTED |
| R49 | run | 25 min reserved for a strength block in the easy-run arithmetic. `planner/RunSessions.kt:16` | §2.1 "+S is a 25 min strength block" | none (design: "inference for the dose") | INVENTED |
| R50 | run | Intervals drop rounds down to one to fit the minutes; ladder and long runs are never shortened for minutes. `planner/RunLayout.kt:124-131` | spec-pr4 D12 | none | INVENTED |
| R51 | run | Event constants: half 21,097 m; 2-mile 3,219 m; 1.5-mile 2,414 m; defaults 5K, 1500 m, 50K, 2-mile, Just cardio fitness. `planner/RunAnswers.kt:12-33` | §2.2 defaults; spec-pr4 D1 | none (definitions and defaults) | INVENTED |
| R52 | run | Not built (4b-iii): novice weekly distance rises at most 30% over two weeks. | §3.2 Run cap row | Nielsen 2014: >30% over two weeks riskier for novices (moderate) | SUPPORTED |
| R53 | run | Not built (4b-iii): marathon-pace segments in the last 6–8 weeks; practice test 2–3 weeks out; retest every 4–6 weeks; strides 1–2 a week; PACER shuttle repeats; Just cardio's interval after 4–8 consistent weeks. | §3.1.2; spec-pr4b (4b-iii list) | Event table and intervals paragraph (moderate to preference; practice test "preference, no evidence retrieved") | PRACTICE |
| R54 | run | Not built: long run at 25–30% of weekly volume (§3.1.2's 5K-to-10K row); the code has no weekly-volume model. | §3.1.2 | "About 25 to 30% of weekly volume" (preference) | PRACTICE |

## 4. Sprint

| id | domain | rule as implemented | where it came from | evidence in the review | status |
|---|---|---|---|---|---|
| S01 | sprint | 4-day layout: Sprint A + plyos, Lower lifts, Rest, Sprint B, Lower lifts + Nordic; 5 adds Tempo, 6 adds Upper lifts. `planner/SprintLayout.kt:49-57` | §2.1 Sprint column; spec-pr5 B4 | Mirrors Haugen's preparation week (moderate low interference, weak day order) | PRACTICE |
| S02 | sprint | 1 to 3 days: A + plyos + lower; A + plyos + lower, R, B + Nordic + upper; A + plyos, R, Lower + Nordic, B, R. The 3-day row puts Lower + Nordic directly before Sprint B (max velocity for 40 yd to 100 m). `planner/SprintLayout.kt:46-48` | §2.1 Sprint column | Review: "never heavy legs the day before a speed day"; notes: no heavy squats, deadlifts or Nordics the day before a max-velocity session (weak, best practice). The 3-day row contradicts it | INVENTED |
| S03 | sprint | Two sprint days at least one rest entry apart (rest entries are rotation slots, not calendar days). `planner/SprintLayout.kt:47-54` | §3.1.3 Sessions | ≥48 h between intensive sprint sessions (Haugen, consensus) | PRACTICE |
| S04 | sprint | Event mapping: 40 yd, 60 m, 100 m → A acceleration, B max velocity; 200 m, 400 m → A max velocity, B sprint endurance. `planner/SprintLayout.kt:41-43` | Lead answer spec-pr5b Q3 | Review defaults for both event groups (weak, coaching consensus) | PRACTICE |
| S05 | sprint | Acceleration 4 → 8 × 10 → 30 m, recovery 1 min per 10 m. `progression/RunRange.kt:54` | §3.1.3; rev 30 | Haugen 2019 integration: "about 1 minute rest per 10 m" (consensus) | PRACTICE |
| S06 | sprint | Without the intro, acceleration opens at the first step reaching 100 m: 8 × 20 m = 160 m. `planner/SprintLayout.kt:33,66-73` | Lead answer spec-pr5 B3 | "100 to 200 m total for a novice" (consensus); Alcaraz ≥160 m per session [verify] | PRACTICE |
| S07 | sprint | Intro trigger: no all-out sprint in the past month, or a hamstring strain in 12 months. `planner/SprintAnswers.kt:14` | §3.1.3 Intro | Ask both; a strain lengthens the intro (practice) | PRACTICE |
| S08 | sprint | The intro opens every range at its low end: acceleration 4 × 10 m = 40 m, max velocity 3 × 10 m. `planner/SprintLayout.kt:67` | §3.1.3 Intro; spec-pr5 B3 | Review novice volume is 100–200 m; 40 m sits under it with no source | INVENTED |
| S09 | sprint | Max velocity 3 → 5 × 10 → 20 m flying (20–30 m build-up not a set), 240 s recovery. `progression/RunRange.kt:55` | §3.1.3; spec-pr5 B5 | "3 to 5 × 10 to 20 m flying after a 20 to 30 m build-up, 4 to 6 minutes rest" (consensus) | PRACTICE |
| S10 | sprint | Sprint endurance 3 → 4 × 100 → 150 m in 10 m units, 480 s recovery; opens 3 × 100 m; the 60–80 m series is not built. `progression/RunRange.kt:56` | §3.1.3; lead answer spec-pr5b Q3 | "3 to 4 × 100 to 150 m at more than 95% with 8 to 12 minutes rest" (weak) | PRACTICE |
| S11 | sprint | Sprint tempo 6 × 100 → 300 m in 100 m units, 60 s walks, not hard, Good band; only at 5 and 6 days. `progression/RunRange.kt:57`; `planner/SprintLayout.kt:55` | spec-pr5 B5 | Haugen tempo: 100–300 m at 60–70%, 1–3 min rest, 1,000–2,000 m a session; the 6 rounds (600 m opening) and 60 s have no source | INVENTED |
| S12 | sprint | Every maximal sprint block opens with a 600 s WARMUP build-up; the session estimate then drops its own 5 min. `planner/SprintLayout.kt:31,61-64`; `planner/SessionBudget.kt:28` | §3.1.3 Warm-up; spec-pr5b Q4 | Progressive warm-up with build-ups before the first maximal rep (best-practice consensus); 10 min is the design's figure | PRACTICE |
| S13 | sprint | Sprint sessions skip the run cap at creation (`cardioAtStart` still caps them at open until 5c). `planner/SprintLayout.kt:95-96`; `progression/Progression.kt:243-247` | Lead answer spec-pr5b Q5 | none | INVENTED |
| S14 | sprint | Plyometric contacts per session stay within 40 to 80. `planner/SprintLayout.kt:34-35` | §3.1.3; muscles-pr3.md | "40 to 80 plyometric contacts" (moderate for plyometrics); de Villarreal ">80 jumps" [verify] | PRACTICE |
| S15 | sprint | Plyometrics only on Sprint A, opening at pogo 3 × 15 + A-skip 1 × 20 = 65 contacts, intro or not. `planner/SprintLayout.kt:34-35,111-116` | Lead answer spec-pr5 B1, B2; spec-pr5b Q6 | Review puts 40–80 contacts at the start of each of the two lower strength sessions, novices starting about 40; the Sprint-A-only placement and the 65 opening have no source | INVENTED |
| S16 | sprint | Plyometric slots at rir 4 (Easy) with 60 s rest from the dose. `planner/LiftRules.kt:49`; `library/ExerciseLibrary.kt:335-336` | Owner table muscles-pr3.md | none | INVENTED |
| S17 | sprint | Plyometric growth: pogo gains a rep per counted session to 20 under the bodyweight rule (65 → 80 contacts); bounds then box jumps not built (5c-ii). `progression/Progression.kt:410-420` | Lead answer spec-pr5c Q5 | "build from pogos and skips toward bounds over 6 to 10 weeks" (moderate for plyometrics); the rep rule mirrors L46 | INVENTED |
| S18 | sprint | Nordic or a regression on one lift day a week. `planner/SprintLayout.kt:119-124`; `planner/RunLayout.kt:86-89` | §3.1.3 | van Dyk 2019 RR 0.49 and Impellizzeri 2021 RR 0.59 (moderate-contested), both [verify]; design §7 | VERIFY |
| S19 | sprint | Nordic dose 2 × 4–6 (Range 4..6 at 4); another knee-flexion lift keeps its own target at 2 sets; run plans give Nordic 2 × 6 (catalog reps). `planner/SprintLayout.kt:37,119-124`; `library/ExerciseLibrary.kt:186` | §3.1.3 | "2 to 3 sets of 4 to 6 reps (practice)"; Behan 2022 48 reps over six weeks [verify]; design §7 | VERIFY |
| S20 | sprint | Sprint lift days: lower day = main (squat row first, hinge row second) + hinge accessory + single-leg accessory; upper day = mains of rows 2 to 4; no core or calf. `planner/SprintLayout.kt:75-93` | Lead answer spec-pr5b Q2 | Review: squat or trap-bar deadlift, a hinge and a single-leg lift (moderate-strong transfer; Seitz [verify]) | PRACTICE |
| S21 | sprint | Sprint mains use Strength rules (3 × 5 at 3 RIR, or TOP × 5 at 2 RIR for A year or more). `planner/Planner.kt:63`; `planner/LiftRules.kt` | Lead answer spec-pr5b Q1 | "about 3 to 5 sets of 3 to 6 reps" (coaching default under a moderate-strong transfer finding) | PRACTICE |
| S22 | sprint | Sprint accessories use Strength's 3 × 8–12 at 2 RIR. `planner/LiftRules.kt:28,36` | Lead answer spec-pr5b Q1 | Review's sprint lifting is 3–6 reps; 8–12 has no source here | INVENTED |
| S23 | sprint | Order within a day: 65+ balance, plyometrics, lifts, Nordic; on a sprint day the cardio block comes first. `planner/SprintLayout.kt:109-125` | spec-pr5b D6 | "warm-up, sprints, plyometrics, heavy lifts, NHE" (moderate) | SUPPORTED |
| S24 | sprint | Times compare only within the same distance, timing method and start protocol; no conversion between methods. `records/SprintComparison.kt` | §3.1.3; spec-pr5a Q5 | "Time with the same method every time" (strong, measurement logic); hand bias 0.22–0.26 s [verify] | SUPPORTED |
| S25 | sprint | HAND times within 10 hundredths of the best read SAME; equal hundredths read SAME for every method. `records/SprintComparison.kt:22,37` | §3.1.3; spec-pr5a Q4 | Review: "an inference from reported standard deviations, not a published threshold"; on its "no source states them" list | INVENTED |
| S26 | sprint | 40 yd stored as 37 m. `planner/SprintAnswers.kt:4` | spec-pr5 B6 | none (rounding) | INVENTED |
| S27 | sprint | Sprint plans get the lift planned light week only (every 6 rotations, A year or more); no sprint light week. `planner/SprintLayout.kt:144` | spec-pr5b D9 | Sprint coaches use 3:1 or 2:1 loading, a lighter week every third or fourth week (practice) | INVENTED |
| S28 | sprint | Not built (5c): a range steps one unit every two counted sessions (full success, no Pain, tempo not on Hard). | §3.1.3 Progression; spec-pr5c D2, D3 | "add reps, then distance within those ranges" (consensus); the two-session cadence has no source | INVENTED |
| S29 | sprint | Not built (5c): weekly max-speed metres may rise 15% a week, a held step earning another 15% per wait (after ≤ before × 1.15^(1 + held)). | §3.1.3; lead answer spec-pr5c Q1 | Review: "a cap of about 10 to 20% a week is a heuristic"; on its "no source states them" list | INVENTED |
| S30 | sprint | Not built (5c): a gap resets to intro volume when days since the day's last session − 7 ≥ 14 (21 calendar days). | §3.1.3; lead answer spec-pr5c Q2 | "a break of two weeks or more should reset the user to an introductory sprint volume" (practice); the 7-day offset makes it three weeks | INVENTED |
| S31 | sprint | Not built (5c): intro = three counted sessions per sprint day; the first step lands on the fifth counted session. | §3.1.3 "first three sprint weeks"; spec-pr5c Q8 | Review gives no intro length | INVENTED |
| S32 | sprint | Not built (5c): a time trial every fifth rotation replaces Sprint A as a view, never in the intro. | §3.1.3 Timing; spec-pr5 C4 | "a time trial every 4 to 6 weeks at the end of a lighter week" (practice) | PRACTICE |
| S33 | sprint | Not built (5c): the time trial is one rep at the goal distance after the build-up. | Lead answer spec-pr5c Q3 (changed) | Review: "record best of two or three attempts" (practice); one rep has no source | INVENTED |

## 5. Concurrent and Both

| id | domain | rule as implemented | where it came from | evidence in the review | status |
|---|---|---|---|---|---|
| C01 | concurrent | In a shared session the primary goal goes first: lifts then run on lift-primary days, run then strength on run-primary days. `planner/BothLayout.kt:47-96`; `planner/RunLayout.kt:121-122` | §3.1.4 | Eddens 2018, Murlasits 2018: lifting first protects lower-body strength (moderate) | SUPPORTED |
| C02 | concurrent | No cardio minutes cap anywhere. (absence; §3.1.4) | §3.1.4 | Schumann 2022: no support for a minutes-per-week threshold (strong to moderate) | SUPPORTED |
| C03 | concurrent | Together default: separate days at 4+ days, same visit below. `planner/SetupAnswers.kt:65` | §2.2 Both default | none | INVENTED |
| C04 | concurrent | Separate days split primary + secondary as 1+0, 1+1, 2+1, 2+2, 3+2, 3+3, alternating primary first; the run side has no strength blocks. `planner/BothLayout.kt:22-45` | spec-pr4 D10 | none | INVENTED |
| C05 | concurrent | Lift first, same visit, at 45+ min: the lift side is planned with minutes − 20 min (or the ladder session's length); every lift day gets its mapped run; a quality or long run only when it fits the full minutes, else a 20 min easy run. At 30 min, runs go on only where they fit (easy ≥ 20 min). `planner/BothLayout.kt:49-93` | Lead answer spec-pr4 Q6 (D15) | none | INVENTED |
| C06 | concurrent | Cardio first, same visit: the run plan with two strength blocks under the lift sub-goal's rules. `planner/BothLayout.kt:47`; `planner/Planner.kt:60-62` | §3.1.4; spec-pr4 D10 | Endurance primary: 2 strength sessions a week (moderate benefit; dose an inference) | PRACTICE |
| C07 | concurrent | Run and Sprint plans use Strength lift rules; Both uses the lift sub-goal. `planner/Planner.kt:60,63` | Rev 28, rev 31 (lead) | Heavy compound lifting for runners and sprinters (moderate; moderate-strong) | PRACTICE |
| C08 | concurrent | Both with a sprint sub-goal: the sprint layout with the lift sub-goal's mains and rules; Which first and Together not asked. `planner/Planner.kt:61`; `planner/SetupAnswers.kt:59-63` | spec-pr5 B7 | Sprint plus lifting 4-day layout (moderate low interference, weak order) | INVENTED |

## 6. Warm-up and cool-down (#574)

| id | domain | rule as implemented | where it came from | evidence in the review | status |
|---|---|---|---|---|---|
| W01 | warm-up | Warm-up = one 120 s general item (bike or rower with a machine, else jacks or march; easy jog or walk on run days; easy cardio otherwise), skipped when the cardio block opens with its own WARMUP; then 4 to 5 dynamic moves chosen by the day's ranked muscle credit. `routine/Routines.kt:16-38`; `routine/Move.kt:30-33` | spec-574 (lead accepted) | Only the sprint build-up has support (consensus); nothing on lifting warm-ups | INVENTED |
| W02 | warm-up | Dynamic doses: leg swings 10 a side, squats 10, hip hinges 10, high knees and butt kicks 20 s, reverse lunges 6 a side, calf raises 15, Y-T raises 8, arm circles 20, push-ups 8, arm swings 10, scapular push-ups 10, upper-back rotations 6 a side, cat-cow 8, dead bugs 6 a side. `routine/Move.kt:34-48` | spec-574 table | none | INVENTED |
| W03 | warm-up | Cool-down = 30 s stretches (each side counted twice), 180 to 300 s total, chosen by ranked muscles. `routine/Routines.kt:18-19,40-61`; `routine/Move.kt:49-60` | spec-574 | Static stretching "not evaluated in the sources read" | INVENTED |
| W04 | warm-up | Cardio-only days rank fixed muscle lists (run and walk: hamstrings, calves, quads, glutes; ride and elliptical; row); whole-body fallback for custom exercises. `routine/Routines.kt:80-103` | spec-574 | none | INVENTED |
| W05 | warm-up | The session estimate budgets 5 min of warm-up but no cool-down, so the suggested 3 to 5 min cool-down sits outside the minutes the plan was fitted to. `planner/SessionBudget.kt:23` vs `routine/Routines.kt:18-19` | Design invariant 1; spec-574 | none | INVENTED |

## 7. General

| id | domain | rule as implemented | where it came from | evidence in the review | status |
|---|---|---|---|---|---|
| G01 | general | A rotation, not weekdays: the next entry is whatever comes next; a missed session is never doubled. `planner/DayLayout.kt:97-105` (rotation ids) | §2 non-goals; owner | Fixed weekdays are folklore; consistency builds habit (Kaushal & Rhodes 2015, moderate) | SUPPORTED |
| G02 | general | Session estimate: 5 min warm-up + per set 45 s + the resolver's rest; a superset counts once; a cardio block counts its planned time. `planner/SessionBudget.kt:22-35` | §3.1 invariant 1 | none | INVENTED |
| G03 | general | Distance sets without a time aim are estimated at 0.36 s/m (6:00 per km) for fitting and the easy-run limit. `planner/SessionBudget.kt:45-54` | spec-pr4 D12 ("practice", lead accepted) | none | INVENTED |
| G04 | general | Days 1 to 6; minutes 30, 45, 60, 75, 90; defaults Lift, General fitness, 3 days, 60 min, Full gym, New. `planner/SetupAnswers.kt:30-49` | §2.1 | Review's required-question defaults: 3 days, 45 to 60 min, full gym, beginner, general strength plus fitness | PRACTICE |
| G05 | general | Equipment presets (Full gym all 11; Home barbell; Dumbbells; Kettlebells; Machines only; Bodyweight), each with bodyweight. `planner/SetupAnswers.kt:16-23` | Lead answer spec-pr3a Q5 | none | INVENTED |

## Row count by status

| Status | Rows |
|---|---|
| SUPPORTED | 34 |
| VERIFY | 11 |
| PRACTICE | 56 |
| INVENTED | 92 |
| Total | 193 |

## INVENTED and VERIFY rows ranked by effect on a real user's training

Ranked by how many users a rule touches and how much it moves their sets, loads or distances. "Not built" rows rank by the effect they will have once built.

**High**

1. **L30** Effort words to RIR (Easy 4, Good 2, Hard 1, Max 0), INVENTED. Every rated set on every lift is judged through it: success, effort misses, Easy doubling, the Max block.
2. **R05** Run cap at 110% of the 30-day longest, VERIFY. Sets the opening and the ceiling of every run, long run and interval day for every runner.
3. **G02** Session estimate (45 s a set, 5 min warm-up), INVENTED. Decides whether a day fits, so it decides when the dose ladder cuts accessories, second exposures and main sets, and how long easy runs are.
4. **L37** Cadence 2 for A year or more, INVENTED. Halves how often an experienced lifter's load can rise, from the first session, before any stall.
5. **L40** Increments of 2.5% lower and 1.25% upper for A year or more and for every user-built and converted slot, INVENTED. Half the review's values; with L37 it sets the progression rate for every trained, user-built and converted lift.
6. **R10** Easy-run length (minutes − 5 − 25, at least 20 min, at most 75% of the long run), INVENTED. Easy runs are most of a runner's volume.
7. **R11** Ruled easy runs never grow, INVENTED. Just cardio fitness, the default run sub-goal, has no progression at all until 4b-iii; other runners' weekly volume grows only through the long run.
8. **R09** The A16 5% increase per finished session, INVENTED. The growth rate of every long run and of every converted cardio day.
9. **L59** and **L58** Layoff day bands and the recurrence offset, INVENTED. Every lifter who misses sessions; the code recalibrates after 6 weeks past recurrence where the review says 8 weeks since the last session.
10. **R42** Run/walk ladder levels 2 to 12, INVENTED. Every New runner and every First 5K plan spends its first 4 to 12+ weeks on these steps.
11. **L28** Calibration jump × 1.25 and at least two steps, INVENTED. Every lift opened without numbers (every New lifter, every swap) finds its load this way.
12. **O09** The 65+ carry at the catalog's 50 lb for 2 × 20 s, INVENTED. Opens every older adult's lifting day with dumbbells; the load was not chosen for this path.
13. **L63**, **L64** and **L67** Strength floor 6, General floor 4 and which muscles have floors, INVENTED. They add sets and accessory slots to most Strength and General plans.
14. **C05** Both, lift first, same visit: the 20 min reserve and its fallbacks, INVENTED. Every Both user below 4 days by default.
15. **L25** Epley to 10 reps, VERIFY. Every opening load from Recent numbers or history.

**Medium**

16. **L68** Cap of 20 weekly sets, VERIFY. Trims high-day plans.
17. **L08** Two back-offs at 90% for experienced Strength mains, INVENTED.
18. **L05** SECONDARY 2 × 5 at 3 RIR (Strength) and 2 × 8–12 (Muscle), INVENTED.
19. **L48** Shared success credit across same-exercise, same-rep slots, INVENTED. A Strength SECONDARY 2 × 5 shares its success with the MAIN's TOP × 5, which can offset L37's slower cadence (data-layer behavior, not traced here).
20. **R27** and **R28** Quality step unit, two-session cadence and counting words, INVENTED.
21. **L76** Dose-ladder step order, the 80% × 3 ramp and the 120 s rest cap, INVENTED.
22. **L71** Muscle credit table, INVENTED. Drives every floor and cap.
23. **L69** Volume fill and trim mechanics (5 sets a slot), INVENTED.
24. **R13**, **R14**, **R17** Base, ultra and 3.5 h long-run numbers, INVENTED.
25. **O10** 65+ layoff recalibration after 14 days, VERIFY.
26. **O03** 65+ two sets on every slot, VERIFY.
27. **O08** Balance dose 2 × 30 s, INVENTED, and the block runs only on lifting days (2 days on a 2-day plan against WHO's 3).
28. **L49** Strength MAIN rest 180 s, VERIFY.
29. **L41** The 10% increment cap, VERIFY.
30. **L42** Easy-session doubling, INVENTED.
31. **L45** and **L46** Coarse-step reps-first (+4) and the bodyweight cap of 20, INVENTED.
32. **R48** and **R49** Run strength block contents and its 25 min, INVENTED (no plyometrics, which the review names).
33. **S29** Sprint 15% weekly rise, INVENTED (not built).
34. **S15** Plyometrics on Sprint A only, opening at 65 contacts, INVENTED.
35. **S08** Sprint intro at 40 m, INVENTED.
36. **S02** The 3-day sprint row's Lower + Nordic before max velocity, INVENTED (contradicts the review).
37. **R04** Effort words as talk-test bands, VERIFY.
38. **R25** Test aims at 95% of goal pace, INVENTED.
39. **R33** Half taper × 0.7, INVENTED.
40. **C03** Together default by days, INVENTED.

**Low** (shape or default rather than dose, or a narrow set of users): L02, L07, L09, L19, L21, L22, L23, L24, L27, L29, L31, L38, L47, L53, L72, L74, L77, O04, R02, R03, R06, R07, R08, R22, R24, R26, R36, R38, R40, R44, R50, R51, S11, S13, S16, S17, S18, S19, S22, S25, S26, S27, S28, S30, S31, S33, C04, C08, W01, W02, W03, W04, W05, G03, G05.

## Findings the audit should look at first

- **Code that departs from the review's own defaults.** Half taper × 0.7 is a 30% cut (Bosquet: 41–60%); the 3-day sprint row places Lower + Nordic right before max velocity; sprint intro opens at 40 m against 100–200 m; run strength blocks have no plyometrics; test intervals rest 2 min against "equal recovery"; the time trial is one rep against "best of two or three"; sprint plans get no lighter week every 3 to 4 weeks; 65+ recalibrates only after 14 days of layoff against "any length"; layoff recalibration starts after 6 weeks, not 8.
- **Designed but not on main.** New lifters stay at 3 RIR on mains (the "then 2 after six exposures" step is deferred); the novice 30% two-week rule, Just cardio's intervals, strides, practice tests and marathon-pace segments are 4b-iii; all sprint progression is 5c (until then `cardioAtStart` caps sprint days and `cardioAfterSession` would treat them as run ranges, though no production path writes a sprint rule before PR 7).
- **Default run plans do not progress.** Just cardio fitness is all easy runs that repeat (R11).
- **The minutes promise leaves out the cool-down** (W05), and the 45 s per set figure treats a 5-rep and a 15-rep set the same (G02).
- **Older adults.** Balance work lands only on lifting days (2 of WHO's 3 on a 2-day plan), and the carry opens at a 50 lb catalog load.
