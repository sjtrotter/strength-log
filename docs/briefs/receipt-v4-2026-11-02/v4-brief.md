# Design brief: the workout receipt, v4

Read-only on the repo (/home/betty/Projects/strength.log, main). Do not edit the repo, commit, run builds,
or start subagents. American English. Write your files only into your own output directory named below.

## The product and the moment

exercise.log is a paid, local-only Android + Wear OS training tracker: strength sets, bodyweight reps, timed
holds, and cardio (runs, intervals, rides, rows). Several plans can run side by side. When the lifter taps
Finish, the workout screen is replaced by a **receipt**: what they just did, any personal records, a note,
and Done. They're standing in the gym, sweaty, phone in one hand, glancing for 5-20 seconds, maybe sharing a
screenshot. Done returns to Today, which lists each plan's next session.

Read first: DESIGN.md, CLAUDE.md "Design principles", docs/design/copy-style.md if present, the current
receipt `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceiptScreen.kt` (+ `SessionReceipt.kt`).
Theme: near-black surfaces (background #0D0D0F, surface #16161A), text #F2F2F0 / #9A9AA2, per-day accent
(day A #C1440E, B #2D5A3D, C #B8860B, D #1F4E5F), condensed display numerals (Barlow Condensed), Material 3
components themed, no baseline purple, no emoji, no gradients, no wall-of-cards.

## Where v3 landed (read both, they are the starting point)

- Opus v3 (the owner's preferred base): plan /tmp/claude-1000/-home-betty-Projects-strength-log/1010bc9d-b050-4516-a3e5-76158e4e091d/scratchpad/receipt-v3/opus/plan.md,
  mockup /tmp/claude-1000/-home-betty-Projects-strength-log/1010bc9d-b050-4516-a3e5-76158e4e091d/scratchpad/receipt-v3/opus/mockup.html
- Sol v3: /tmp/claude-1000/-home-betty-Projects-strength-log/1010bc9d-b050-4516-a3e5-76158e4e091d/scratchpad/receipt-v3/sol/plan.md and mockup.html
- Research: /tmp/claude-1000/-home-betty-Projects-strength-log/1010bc9d-b050-4516-a3e5-76158e4e091d/scratchpad/receipt-research/findings.md

## The owner's feedback on v3 (verbatim, lightly trimmed)

- "i think we keep it focused on the workout we just finished? the user is gonna see what's next when they
  return to the home screen right? although a reminder isnt necessarily a bad thing. if we keep it, i kinda like
  the opus #2 option, with the line actually delineating the break." (Opus frame (b): Up next + Done pinned in a
  bottom bar, a hairline above it, the list scrolling under it.)
- "maybe only other plans left for that day ... if we complete a plan for the day, i wouldn't really do the next
  workout from that plan 'next' ... so maybe if there's no other plans left pending for 'today' then we can
  replace it with a 'good job! all plans done for today!' message or something? maybe with a green checkmark
  (circled maybe?) wording can probably be improved."
- "Overall i like Opus build for the receipt; it looks cleaner. but i do like some things from sol: like the
  superset format, where each exercise in the superset is listed under a heading, and then expanded it is more
  clear i think."
- "for exercises selected in the ramp-top-backoff: only the top set matters, because it's built that way on
  purpose. I don't think we need to list all the individual ramps and the backoffs."
- "for supersets (and the other more 'accessory' lifts) ... maybe we score it against the plan kinda? i'm not so
  sure because we don't want to make the user feel bad if they back off the stuff in the plan during the workout"
- "my idea is that each 'type' of workout could be represented differently. I do like the opus version for
  cardio much better than Sol's. One thing i like about Sol's version is that it is indenting underneath to make
  it clear that the expanded sections belong underneath the heading, that's one weakness in the Opus version."
- "i'm still unsure and would like to see more renders with this feedback ... taking into account that each
  'type' of exercise could be represented differently."

## What to design

Start from Opus v3. Change it as follows, and show the open questions as side-by-side variants.

1. **One representation per exercise type.** The types that exist today:
   - **Ramp/top/back-off main lift** (the plan marks it main; ramps are warmups, back-offs follow the top set).
     The row is about the top set: label it as the top set, not "best of N". Decide whether it expands at all;
     if it does, what is worth showing (for example the top set against last time's top set) without listing
     ramps and back-offs.
   - **Straight sets** (accessories: the same prescription for N sets, e.g. 3 x 8-12). Here the sets are the
     story. Show the sets compactly (e.g. "35 x 10, 10, 8") in the collapsed row or on expand; you decide.
   - **Superset** (two exercises alternating rounds). Sol's group heading with each exercise listed under it,
     indented; expanded content indented under its own heading.
   - **Bodyweight reps** (e.g. pull-ups 12, 10, 9) and **timed holds** (plank 1:15, 1:10).
   - **Cardio**: keep Opus v3's cardio treatment (warm-up / work block / cool-down; "Fastest of 6"); use the
     words "Faster PR" and "Farther PR" (a 400 m PR is a time, not a pace).
   Expanded content must be visibly indented under its heading (owner's point about Sol).
2. **Against the plan, without scolding.** For straight sets and supersets, show at least two variants:
   (V1) no plan comparison at all, just what was done; (V2) a quiet plan reference that states facts and never
   judges (no red, no "missed", no fail icon; e.g. "3 x 8-12 planned" as supporting text, or the planned target
   shown only on expand). Say which you recommend and why. A lift done heavier or with more sets than planned
   must read as fine too.
3. **Bottom of the screen.** "Add a note" stays above the pinned bar. Pinned bar per Opus frame (b), with a
   hairline above it. Show both variants: (W1) no Up next at all, just Done; (W2) "Still today" listing only
   other plans that have no finished session today (proposed rule: a plan counts as done for today once any of
   its sessions finished today), and when none are left, a completion line with a circled check in a green
   that fits the dark palette (not baseline Material green). Write the completion copy plainly; no exclamation
   marks, no praise words, per the copy rules (the owner said "wording can probably be improved").
4. **PR labels** stay as Opus v3 (non-interactive tonal Surface, words only): Weight PR, Rep PR, Hold PR, Faster
   PR, Farther PR. Mark the PR set when expanded, with "up from" the old best on that set (from Sol).
5. Headline stays the session name ("Squat focus, done.").

## Sample session (use these exact numbers)

Day A "Squat focus" of the plan "Four-day strength", 58 minutes, accent #C1440E. Unit lb.
1. Barbell Back Squat (main): ramp 135x5, 165x3, 190x2, 210x1; top 245x5; back-off 185x8, 185x8. Last session's
   top set 240x5; previous best 235x5 -> weight PR.
2. Superset, planned 3 x 5 bench / 3 x 8 row: Barbell Bench Press 185x5, 185x5, 185x4 / Barbell Row 155x8,
   155x8, 155x8. No PRs.
3. Dumbbell Curl, planned 3 x 8-12: 35x10, 35x10, 35x8. Previous best 35x9 -> rep PR (35x10).
4. Pull-up, planned 3 x max: 12, 10, 9. No PR.
5. Plank, planned 2 holds: 1:15, 1:10. Previous best 1:10 -> hold PR.
6. Face Pull: skipped (planned 3 x 12-15).
Other plans: "5K build" (its next run "Intervals", not done today) and "Mobility" (finished a session this
morning). So W2 lists only "Intervals, 5K build". Also draw the W2 state where nothing is left today.
Note: none yet.

Cardio frame: Track run, 34 minutes, plan "5K build" session "Intervals": warm-up 10:00; 6 x 400 m at 1:41,
1:38, 1:44, 1:40, 1:39, 1:45 with 90 s recovery each; cool-down 5:00. Previous fastest 400 m 1:42 -> faster PR
(1:38). Add a distance-run block (its own small frame): "Easy run", planned 30:00, covered 5.6 km;
previous farthest in a 30-minute run 5.4 km -> farther PR.

## Deliverables

1. `plan.md` (under 900 words): each exercise type's collapsed and expanded form; the plan-comparison variants
   and your pick; the bottom-bar variants and your pick; every on-screen string verbatim; TalkBack strings for
   a main lift, a superset, a straight-set row and the completion line.
2. `mockup.html`: one self-contained HTML file, phone frames 412 px wide, dark, tokens above. Frames:
   (a) strength receipt collapsed, V1 + W2 (one plan left);
   (b) same receipt with Squat, the superset and Curl expanded;
   (c) V2 variant of the straight-set and superset rows (can be a partial frame showing just those rows);
   (d) bottom bar W1, and W2 with nothing left today (partial frames are fine);
   (e) cardio receipt (intervals + the distance-run block).
   Rows open and close on tap. Label each frame with a one-line caption. No external assets except Google Fonts.
3. End `plan.md` with "Choices for the owner" (at most 3, each with your recommendation).
