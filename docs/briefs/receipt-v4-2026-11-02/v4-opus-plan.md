# Receipt v4 (Opus seat)

Base: Opus v3, header unchanged. Rows are M3 `ListItem`s; trailing values share one right edge (static rows keep the chevron slot empty).

**Indentation.** Whatever belongs to a heading sits 24 dp in, behind a `VerticalDivider` (outlineVariant): set lists, superset halves, and a half's own sets (two steps). Open state in `rememberSaveable`.

**One ceremony.** Strike-and-roll-in only on the main lift's top set and the cardio work block (DESIGN.md: one ceremony). Other PRs get the label, and "up from" on the PR set when open.

## Each type

**Main lift.** Collapsed: "Barbell Back Squat", supporting "Top set" + `Weight PR`, trailing ~~235 × 5~~ **245 × 5**. Does not expand: ramps and back-offs are off by design, and "last time" would grade the lift. Without a PR: "Top set", trailing "240 × 5".

**Straight sets.** Collapsed: "Dumbbell Curl", "3 sets" + `Rep PR`, trailing "35 × 10, 10, 8". A weight prints only where it changes ("45 × 12, 50 × 10, 8"); past half the row width the string replaces "3 sets" in the supporting line. Open: numbered sets, the PR set in accent with "PR, up from 35 × 9".

**Superset.** A static heading "Superset", "3 rounds". Under it, indented, one row per half: gutter "A1"/"A2", name, trailing "185 × 5, 5, 4". Each half opens on its own, and its sets indent under it. A second superset uses B1/B2.

**Bodyweight reps.** "Pull-up", "3 sets", trailing "12, 10, 9". Opens to its sets.

**Timed holds.** "Plank", "2 holds" + `Hold PR`, trailing "1:15, 1:10". Open: "1:15" with "PR, up from 1:10".

**Skipped.** Muted name, trailing "Skipped", no chevron.

**Cardio.** v3 kept. An activity heading ("Track run"), then static "Warm-up" 10:00, "6 × 400 m" with "Fastest of 6" + `Faster PR` and ~~1:42~~ **1:38**, and static "Cool-down" 5:00. Open: intervals 1-6, "1:38" with "PR, up from 1:42", then "Recovery 1:30 each". A time-target block is named for its target, "30:00", with supporting "Distance" + `Farther PR` and ~~5.4 km~~ **5.6 km**. It is static.

## Against the plan

- **V1:** only what was done.
- **V2a:** the target in the supporting line: "3 sets, 3 × 8-12 planned"; superset halves get "3 × 5 planned"; Face Pull gets "3 × 12-15 planned".
- **V2b:** the target as the last line of an open row: "Planned 3 × 8-12". Skipped rows as in V2a.

V2 text is supporting gray, no color or icon; heavier, more sets or fewer reps all read as two facts side by side.

**Pick: V2b.** The glance stays a record of what happened; the comparison is one tap away, under the sets it describes. V2a puts "3 × 5 planned" beside "185 × 5, 5, 4" on every look: a score, whatever the color.

## Bottom bar

"Add a note" ends the scrolling list. Pinned `bottomBar`: an always-drawn `HorizontalDivider`, the reminder, Done in the day accent.

- **W1:** Done only.
- **W2:** "Left for today" lists each other plan on Today with no finished session today: session left, plan right. When none are left: an outlined `check_circle` in done green `#3E8E5A` (4.8:1 on the background) and "All plans done for today." One plan on Today: W2 equals W1 (the headline already says done). Not tappable.

**Pick: W2**, with a caveat (choice 3).

## Strings

`%1$s, done.` `%1$s, %2$d min` `Top set` `%d sets` `%d holds` `%d rounds` `Superset` `A1` `A2` `Skipped` `Weight PR` `Rep PR` `Hold PR` `Faster PR` `Farther PR` `PR, up from %s` `%1$s planned` (V2a) `Planned %1$s` (V2b) `Fastest of %d` `Distance` `Warm-up` `Cool-down` `Recovery %s each` `Add a note` `Edit note` `Left for today` `All plans done for today.` `Done` `Share` `Collapsed` `Expanded` `Show sets` `Hide sets`

## TalkBack

One node per row; openable rows add state and a click label.

- Main lift: "Barbell Back Squat. Top set, 245 pounds for 5 reps. Weight PR, up from 235 pounds for 5 reps."
- Superset: heading "Superset, 3 rounds". Half: "A1, Barbell Bench Press, 3 sets: 185 pounds for 5, 5 and 4 reps", "Collapsed", action "Show sets".
- Straight sets: "Dumbbell Curl, 3 sets: 35 pounds for 10, 10 and 8 reps. Rep PR, up from 35 pounds for 9 reps." In V2b, once open, a node follows: "Planned 3 sets of 8 to 12."
- Bar: "Left for today: Intervals, 5K build." Completion: "All plans done for today." (icon silent).

## Notes on the brief

- The sample data conflicts with `PersonalBests` (weight, then reps). A 240 × 5 last session would make 240 × 5 the best, so the PR would read "up from 240 × 5". The mockup keeps 235; it never shows "last time", so nothing on screen contradicts it.
- Cardio distance is stored in meters (DESIGN.md open question 12); "5.6 km" assumes km display.
- Day B's Done fill sits close to the done green check.

## Choices for the owner

1. **Plan comparison:** V1, V2a, or V2b. Recommend V2b.
2. **Main lift detail:** one line, or open to compare with last time. Recommend one line. Only the top set matters, and "last time" grades it.
3. **The reminder:** W2 or W1. Plans have no weekdays, so a run plan shows under "Left for today" after every lifting day, even when you never run that day. Recommend W2 if you often train two plans in a day, W1 otherwise.
