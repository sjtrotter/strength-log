# Design brief: the workout receipt, v3

Read-only on the repo (main). Do not edit the repo, commit, run builds,
or start subagents. American English. Write your files only into your own output directory named below.

## The product and the moment

strength-log (being renamed exercise.log) is a paid, local-only Android + Wear OS training tracker:
strength sets, bodyweight reps, timed holds, and cardio (runs, intervals, rides, rows). Several plans can run
side by side. When the lifter taps Finish, the workout screen is replaced by a **receipt**: what they just did,
any personal records, a note, what's next, and Done. They're standing in the gym, sweaty, phone in one hand,
probably glancing for 5–20 seconds, maybe sharing a screenshot. Done returns to Today.

Read first: DESIGN.md (tokens, type, the M3-first rule, copy rules), CLAUDE.md §"Design principles",
docs/design/copy-style.md if present, and the current receipt
`app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceiptScreen.kt` (+ `SessionReceipt.kt`).
Theme: near-black surfaces (background #0D0D0F, surface #16161A), text #F2F2F0 / #9A9AA2, per-day accent
(day A #C1440E, B #2D5A3D, C #B8860B, D #1F4E5F, E #3C4E78, F #8B4356, G #6B6A2C), condensed display numerals
(Barlow Condensed), Material 3 components themed, no baseline purple, no emoji, no gradients, no
wall-of-cards.

## What the owner said (verbatim excerpts)

- On the council's v2 (one row per lift showing its best set, M3 Badge "PR"): "now it feels too plain? ... just
  showing the top set for that exercise/best one kinda doesn't make sense, especially if it's not 'explained' on
  the receipt at all that it's the best for the day"
- "is there some notation that bodybuilders use for supersets, like ... list the main lift, then underneath lead
  the second lift with a 'section' mark ... instead of just 'with'"
- "maybe each exercise could be a heading, able to be dropped-down to see each set you did?"
- "it makes more sense to me to put the 'add a note' above the 'next' day notation"
- "now that we are changing how different plans interact, maybe we should not say 'day x' ... that should be the
  next workout instead, next compatible plan session"
- On PRs: "is there a mechanism we can use to tell which TYPE of PR it is? maybe we can add an icon to denote it
  (or label)." Earlier: "use a chip with PR or something ... that would look cleaner and pop a little."

## Settled (don't relitigate)

- A PR is the best completed non-warmup set for that exercise, beating every earlier one. Weighted: heavier
  weight wins, then more reps at the same weight (so a PR is either a **weight** PR or a **rep** PR). Bodyweight:
  more reps. Holds: longer. Cardio, per activity and planned target: **faster** for a set distance, **farther**
  for a set time. A lift's first session and ties are never PRs. Warmups never count. No e1RM, no volume.
- Skipped lifts stay on the receipt, muted. Rows are frozen at Finish. TalkBack must still hear "up from 235 × 5".
  One success haptic when any PR lands.
- Research findings: `research-findings.md`
  (A1/A2 is the lifter convention for supersets; apps name the PR kind in a word; Hevy marks the exact PR set;
  M3 Badge is a count/dot and chips are interactive; material3 here is 1.4.0, so an expandable row is
  `ListItem` + `AnimatedVisibility`, announcing expanded/collapsed).
- Earlier council (v2) files, for what was already argued: the v2 council notes (not archived)

## The sample session (use these exact numbers)

Day A "Squat focus" of the plan "Four-day strength", 58 minutes, accent #C1440E. Unit lb.
1. Barbell Back Squat: ramp 135×5, 165×3, 190×2, 210×1; top 245×5; back-off 185×8, 185×8. Previous best 235×5
   → **weight PR**.
2. Superset: Barbell Bench Press 185×5, 185×5, 185×4 / Barbell Row 155×8, 155×8, 155×8. No PRs. Bench planned 3,
   all done.
3. Dumbbell Curl 35×10, 35×10, 35×8. Previous best 35×9 → **rep PR** (35×10).
4. Plank 1:15, 1:10. Previous best 1:10 → **longer hold PR**.
5. Face Pull: skipped (planned 3 sets).
Next: the same plan's day B "Upper". Another plan, "5K build", is also on Today; its next run is "Intervals".
Note: none yet.

Second frame, a cardio receipt: Track run, 34 minutes, plan "5K build" day "Intervals": warmup 10:00; 6 × 400 m
WORK at 1:41, 1:38, 1:44, 1:40, 1:39, 1:45 with 90 s recovery each; cooldown 5:00. Previous fastest 400 m 1:42 →
**faster PR** (1:38). Next: "5K build: Long run".

## Deliverables

1. `plan.md` (under 900 words): the screen top to bottom; what a collapsed exercise shows and how it says what
   its number is; what expanding shows and how the PR set is marked; the PR label (word, icon, or both; which
   M3 component, and why it's not a misused chip/badge); superset notation; note placement; the "next" line
   wording for several plans; where Done sits on a long day; states (no PRs, all skipped, partial day, cardio);
   TalkBack strings; every on-screen string verbatim.
2. `mockup.html`: one self-contained HTML file, phone frames 412 px wide, dark, using the tokens above:
   (a) strength receipt, everything collapsed, (b) the same with Squat and the superset expanded,
   (c) the cardio receipt. Real numbers from the sample. No external assets except Google Fonts.
3. End `plan.md` with "Choices for the owner" (at most 3, each with your recommendation).
