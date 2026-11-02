# Receipt v3 (Opus seat)

One job: confirm what was saved and what comes next.

## Top to bottom

```
                                       [Share]
Squat focus, done.
Four-day strength
Monday, Nov 2, 58 min
     Barbell Back Squat     235 × 5  245 × 5  v
     Best of 3 sets  [Weight PR]
A1   Barbell Bench Press            185 × 5  v 
     Best of 3 sets
A2   Barbell Row                    155 × 8
     Best of 3 sets
     Dumbbell Curl           35 × 9  35 × 10  v
     Best of 3 sets  [Rep PR]
     Plank                      1:10  1:15  v
     Best of 2 sets  [Hold PR]
     Face Pull                      Skipped    
     3 sets planned
     Add a note
--- pinned ---
Up next
Upper                      Four-day strength
Intervals                  5K build
[                Done                ]
```

Plan names show only with 2+ active plans. Rows are M3 `ListItem`s on the background, no cards or dividers. A 40 dp left gutter holds notation (A1, A2, set labels), so names and set values share one edge.

## Collapsed row

Trailing: one number in Barlow Condensed `titleLarge` and a rotating `KeyboardArrowDown`. Supporting text names the number: "Best of 3 sets". Best follows the PR rule over non-warmup sets, so the number shown is the set the PR check judged. One set reads "1 set"; a short lift reads "Best of 2 sets, 3 planned".

On a PR row the previous best sits struck before the new value, which turns accent text (`accentEmphasis`). Strike and roll-in run once, as today; reduced motion and restore show the final frame.

## Expanded row

`ListItem` + `AnimatedVisibility(expandVertically)`, M3 motion tokens, several rows open at once, state in `rememberSaveable`. Every set prints with its workout label in the gutter: R1 to R4 muted, TOP, B/O, or 1, 2, 3 (`kindLabelsForKinds`). The PR set's value turns accent text with "PR" after it; on a tie within the session the first set carries it.

A superset opens into rounds: a header "A1  A2", then "1  185 × 5  155 × 8" per round.

## PR label

"Weight PR", "Rep PR", "Hold PR", "Pace PR" (faster over a set distance), "Distance PR" (farther in a set time). Word only, in the supporting line; the headline row is full at 412 dp.

Component: a non-interactive `Surface`, 4 dp corners, `labelMedium`, day accent at about 20 percent, `brightHex` text (4.6:1 on days A and B). `Badge` is a count or dot on an icon and every chip is a button by spec; either would announce an action that does nothing. The tonal fill leaves Done the only solid accent.

## Supersets

A1/A2, the lifter convention, on superset halves only; a second superset is B1/B2. Dropping "Day A" (choice 2) ends the letter clash.

## Note, Up next, Done

The note closes the list, so it always reads above Up next. A saved note shows three lines over "Edit note", cardio included.

Up next and Done are pinned in the `bottomBar`, so Done stays under the thumb on an eight-lift day. Up next is Today's list after Done, frozen at Finish, session name with plan trailing. No successor hides the block.

## States

- No PRs: no labels, strikes or haptic.
- Partial: short and skipped rows as above.
- All skipped, if Finish allows it: "Squat focus, skipped.", every row muted, no Share.
- Cardio: the activity ("Track run", `titleMedium`) heads the list, and rows are blocks: "Warm-up" 10:00, "6 × 400 m" with "Fastest of 6", "Cool-down" 5:00. Work opens to each interval, the PR marked, then "Recovery 1:30 each". A shortened or skipped interval prints "Shortened" or "Skipped"; the line becomes "Fastest of 5, 6 planned".

## TalkBack

One node per header row, `stateDescription` "Collapsed"/"Expanded", click label "Show sets"/"Hide sets".

- "Barbell Back Squat, weight PR, 245 × 5, up from 235 × 5, best of 3 sets"
- "Superset. Barbell Bench Press, 185 × 5, best of 3 sets. Barbell Row, 155 × 8, best of 3 sets"
- "Face Pull, skipped, 3 sets planned"
- "Top set, 245 × 5, PR", "Round 1, Barbell Bench Press 185 × 5, Barbell Row 155 × 8"
- "6 × 400 m, pace PR, 1 minute 38 seconds, up from 1 minute 42 seconds, fastest of 6"
- "Up next: Upper, Four-day strength. Intervals, 5K build"

## Every string

`%1$s, done.` `%1$s, skipped.` `%1$s, %2$d min` `Best of %d sets` `1 set` `Best of %1$d sets, %2$d planned` `%d sets planned` `Skipped` `Weight PR` `Rep PR` `Hold PR` `Pace PR` `Distance PR` `PR` `A1` `A2` `R1` `TOP` `B/O` `Fastest of %d` `Farthest of %d` `Fastest of %1$d, %2$d planned` `Warm-up` `Cool-down` `Recovery %s each` `Shortened` `Add a note` `Edit note` `Up next` `Done` `Share` `Collapsed` `Expanded` `Show sets` `Hide sets` `Superset` `Round %d` `up from %s`

## Choices for the owner

1. Up next with several plans: every plan's next, as Today shows, or only this plan's. Recommend every plan. The next decision may be the run, and the receipt matches the screen Done opens.
2. Headline: "Squat focus, done." with the plan below, or keep "Day A, done.". Recommend the session name. It follows your "not day x" call, reads right with several plans, and frees letters for A1/A2. "Finish day A" would change to match.
3. PR icons: word only, or an icon per kind inside the label. Recommend word only. Five icons are five meanings to learn, and at label size they read as decoration.
