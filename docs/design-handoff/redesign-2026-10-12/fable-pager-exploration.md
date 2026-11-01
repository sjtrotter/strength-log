# The workout as cards you swipe — Fable's exploration (2026-10-12, evening)

Current phone UI (4.5b): `WorkoutSetScreen` replaces the workout pager; lift navigation uses the Day sheet.
The owner asked, after revision 2: instead of a list of exercises, a card per exercise that
the lifter swipes through, with the remaining sets shown another way (a count, a row of
dots), so a busy station can be skipped by swiping, and so the phone mirrors the watch.
Mock: `fable-pager.html` (two variants, seven frames). Astra's parallel exploration is
`astra-pager-exploration.md` + `astra-pager.html`; the side-by-side is `pager-compare.html`.

## What the watch already does

Two faces (`docs/briefs/wear-dial-v3.md`). The overview is a dial: an outer ring of program
days, an inner ring with one segment per lift that fills green as lifts finish, a disc that
reads Start or Continue over "Set 3 of 6". The workout face shows one set at a time: the
prescribed weight × reps, tap the disc to log, then the rest. Swipe left at the start of a
set moves to the next lift, wrapping. There is no list on the watch. A phone that mirrors it
has one lift in view, one set in focus, progress as segments, and a horizontal swipe.

## Variant A — one card per lift, all its sets

`HorizontalPager`, one page per exercise. The page is a `Card` holding the revision-2 row
grammar (value, standard `Checkbox`, Goal right with Last under it, Add a set). The next card
peeks at the right edge (contentPadding start 20 dp / end 40 dp, pageSpacing 12 dp). Above
the pager a row of segments, one per lift (done green, current day accent), captioned
"Overhead press, 3 of 6 sets" / "Lift 1 of 6". Tapping a segment jumps to that lift. A
finished lift's card does not fold: the name goes quiet, the summary line and one checked
checkbox sit in the header, the rows stay as the record, a `TextButton` names the next lift.
Nothing moves on its own.

## Variant B — one set in focus, the watch's grammar

Same pager. The page shows the lift name and Goal/Last, then the current set at display size
(about 88 sp, day-accent text, tabular numerals), the lift's sets as a row of segments with
"Set 3 of 6" / "110 × 3 next", one `Button` "Log 95 × 5", and a `TextButton` "All sets" that
opens the lift's list in a `ModalBottomSheet` for corrections. Tapping the numeral opens the
revision-2 edit sheet unchanged. After a log the focus moves to the next set and the rest
pill appears in the bottom bar; the Log button is tonal while the rest runs. A finished lift
keeps its top set on the card, all segments green, the summary line, one checked checkbox,
and the primary action names the next lift.

## Gains and costs

| | Ledger (rev. 2) | A, card of rows | B, set in focus |
|---|---|---|---|
| Vertical scrolling | Yes; #260 stays a rule to keep | Only inside a card with more sets than fit | None; #260 cannot recur |
| Overview of the day | Always, by scrolling | Six segments + the peeking card + the receipt | Same, plus the lift's own segments |
| Skipping a busy station | Scroll past; the accent stays on the skipped lift | Swipe or tap a segment | Same |
| Correcting an earlier set | Tap its row | Tap its row | All sets → row → sheet (or tap a filled segment → sheet) |
| The accomplishment | The fold | Green segment + checked card | Six green segments, top set left standing, checked card |
| Numeral size | 30 sp | 30 sp | ~88 sp, readable from the rack |
| Accidental input | none new | pager touch slop + snap: a tap stays a tap, a half-swipe springs back; no horizontal gesture inside a card | same, fewer targets |
| Mirrors the watch | no | half | fully |

## Where the current page lives

`rememberPagerState` is saveable, so after process death the app returns to the same card.
The first unfinished lift is derived from the session, so a fresh open lands there; a swipe
overrides only until the next log. Every checkbox and log still writes on the tap. Nothing
new is held in memory.

## Compose contract for the pager

`HorizontalPager` + `rememberPagerState` (pageCount from the session's exercises), `key` per
exercise id, `beyondViewportPageCount = 1` so the peek is composed, `contentPadding` +
`pageSpacing` for the peek, `PagerDefaults.flingBehavior` (default snap). Segment row: `Row`
of `Box` with `RoundedCornerShape`, each in a 48 dp tall clickable calling
`animateScrollToPage` — the indicator from Google's own Pager doc, with a click added.
`Card`, `Text` (displayLarge, `fontFeatureSettings = "tnum"`), `Button` / `FilledTonalButton`
(while resting) / `TextButton`, `ModalBottomSheet` for editing and All sets. App bar with no
scrollBehavior; nothing reads scroll state.

Guidance followed, by title (from memory): "Pager in Compose"; "Lists and grids"; "Follow
best practices" (no backwards writes, `derivedStateOf` where `currentPage` drives the
caption); "State and Jetpack Compose" and "Architecting your Compose UI"; "Material Design 3
in Compose"; "Accessibility in Compose" (48 dp targets, `mergeDescendants` on the set row,
`contentDescription` per segment naming the lift and its state); "Navigation with Compose".

## Recommendation

Variant B if the phone should mirror the watch; otherwise keep the ledger. A is the weaker
middle: it gives up the overview and keeps the list, paying for both. B pays the overview
cost once and buys the biggest numeral the app has shown, a screen that never scrolls, and
one vocabulary on both devices. B's real risk is the correction path (All sets → row →
sheet); tapping a filled segment to open that set's sheet directly makes it one tap more
than the ledger, not two. Build B with that, keep the ledger's row grammar inside the All
sets sheet so nothing is invented, and let the receipt carry the day's record.

What the watch could borrow from B: the "Next: …" line, and the set segments if the dial
wants a flat progress reading. The phone borrows the ring's segment grammar. Nothing on the
watch needs to change for either variant.
