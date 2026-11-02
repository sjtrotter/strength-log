# The workout receipt, v4

Opus v3 remains the base: session headline, sparse rows, passive tonal PR Surfaces, note and pinned Done. Use themed M3 ListItems, Buttons and AnimatedVisibility. Records stay frozen at Finish. All requested references were read; copy-style.md is absent. Repo stays untouched.

## Representations and exact copy

Common strength header: “Squat focus, done.” / “Four-day strength” / “Monday, Nov 2, 58 min” / “Weights in lb”. Share’s accessible name: “Share workout”. All strength numbers use lb; × separates weight from reps. Repeated reps share the preceding weight; changing weights print separately.

| Type | Collapsed | Expanded, indented beneath its heading |
|---|---|---|
| Main | “Barbell Back Squat”; “Top set”; “245 × 5”; “Weight PR” | “Top set” “245 × 5”; “Weight PR”; “up from 235 × 5”. Then “Last session’s top set” “240 × 5”. No ramps or back-offs. |
| Superset | “Superset”; “3 rounds”; indented “A1” “Barbell Bench Press” “185 × 5, 5, 4”; “A2” “Barbell Row” “155 × 8, 8, 8” | One group toggle. Beneath **each exercise**, another indentation and “Round 1”, “Round 2”, “Round 3”: bench “185 × 5”, “185 × 5”, “185 × 4”; row “155 × 8” each. |
| Straight | “Dumbbell Curl”; “35 × 10, 10, 8”; “Rep PR” | “Set 1” “35 × 10”; “Rep PR”; “up from 35 × 9”. “Set 2” “35 × 10”; “Set 3” “35 × 8”. Mark only the first tied best. |
| Bodyweight | “Pull-up”; “12, 10, 9 reps” | “Set 1” “12 reps”; “Set 2” “10 reps”; “Set 3” “9 reps”. |
| Hold | “Plank”; “1:15, 1:10”; “Hold PR” | “Hold 1” “1:15”; “Hold PR”; “up from 1:10”. “Hold 2” “1:10”. |
| Skipped | “Face Pull”; “Skipped” | No disclosure. |

Several rows can remain open. Each detail moves 16 px inward with a quiet vertical rule; superset exercise headings already sit 24 px inside the group. Full-width headers provide at least 48 dp targets. PR labels are words only, never buttons. Previous-best text belongs to its PR set; last-session context is separate. No score, completion fraction, volume or estimated max.

## Plan variants

**V1**, frames a/b: only actual work, including the bench’s four-rep final round. No plan comparisons.

**V2**, frame c: identical collapsed rows; expansion adds “3 × 5 planned” beneath bench, “3 × 8 planned” beneath row, and “3 × 8–12 planned” beneath Curl. Recommend V2: the target is available deliberately, without crowding the glance. These are original prescriptions, never pass/fail thresholds. Heavier work, additional sets and fewer reps keep identical styling; every completed set remains visible. No red, deficit language or target-based PRs.

## Note and bottom variants

“Add a note” ends the scrolling body above the hairline. Saved text precedes “Edit note”. Editor strings: “Workout note”, “Note”, “Cancel”, “Save”.

**W1:** pinned “Done” only. Recommend W1 for focus; Today already lists the plans.

**W2:** pinned “Still today”, then “Intervals” with “5K build”, then “Done”. Snapshot other active plans at Finish. Exclude any plan with any finished session on that device-local calendar date, including partial sessions. Thus Four-day strength and Mobility are excluded. Never advance this receipt to another Four-day strength session. With none remaining, replace the reminder with a silent circled check and “All plans done for today.” Use completion green #3E8E5A, normal text color. Both W variants retain the hairline and navigation inset; Done returns to Today. The note scrolls clear of the bar.

## Cardio copy

“Intervals, done.” / “5K build” / “Monday, Nov 2, 34 min”; “Track run”. “Warm-up” “10:00”; expandable “6 × 400 m”, “Fastest of 6”, “1:38”, “Faster PR”; “Cool-down” “5:00”. Indented “Interval 1”, “Interval 2”, “Interval 3”, “Interval 4”, “Interval 5”, “Interval 6”: “1:41”, “1:38”, “1:44”, “1:40”, “1:39”, “1:45”. Interval 2: “Faster PR”, “up from 1:42”. “Recovery 1:30 each” means all six recoveries. Note and W1 follow. Day B accent assumes Opus’s sample assignment; production uses the actual day.

Small separate frame: “Easy run, done.” / “5K build” / “Monday, Nov 2”; “30:00 run”, “5.6 km”, “Farther PR”. Expansion: “30:00 planned”; “30:00 run” “5.6 km”; “Farther PR”; “up from 5.4 km in 30:00”.

## TalkBack

- Main: “Barbell Back Squat. Top set, 245 pounds for 5 reps. Weight PR, up from 235 pounds for 5 reps.”
- Superset: “Superset, 3 rounds. A one, Barbell Bench Press, 185 pounds for 5, 5, and 4 reps. A two, Barbell Row, 155 pounds for 8, 8, and 8 reps.”
- Straight: “Dumbbell Curl. 35 pounds for 10, 10, and 8 reps. Rep PR, up from 35 pounds for 9 reps.”
- Completion: “All plans done for today.”

Headers announce “Collapsed” / “Expanded”, with “Show details” / “Hide details”. Rules, chevrons and check are silent. Reduced motion expands immediately.

Study-only strings: “The workout receipt”, “v4”; captions “(a) V1 + W2, one plan left”, “(b) V1, top set and sets open”, “(c) V2, plan reference on expand”, “(d1) W1, Done only”, “(d2) W2, all plans done today”, “(e1) Intervals, work block open”, “(e2) Easy run, distance in 30:00”. Feedback: “Prototype: Done returns to Today.” / “Prototype: Share opens the Android share sheet.”

## Choices for the owner

1. Recommend V2: prescriptions only on expand, without evaluation.
2. Recommend W1: keep the receipt about this workout; W2 remains a visible alternative.
3. Recommend expandable main lifts for last-session context and PR provenance, without ramps or back-offs.
