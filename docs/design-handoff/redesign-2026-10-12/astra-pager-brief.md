# Exploration round — the workout as swipeable exercise cards (2026-10-12, evening)

Current phone UI (4.5b): `WorkoutSetScreen` replaces the workout pager; lift navigation uses the Day sheet.
You are the same design lead who wrote `astra-design-plan.md` and `astra-mock.html` in this
directory (read both first; they are yours, revision 2, already reviewed by the owner). Do NOT
edit those two files. This round is an EXPLORATION of one idea the owner just raised, and your
deliverables are two NEW files in this directory:

- `pager-exploration.md` — your assessment and plan (800–1500 words).
- `pager-mock.html` — a self-contained mock in exactly the style and markup conventions of your
  `astra-mock.html` (same `.phone` frames at 412×892, same `<figure class="specimen" id="…">`
  + `<figcaption>` with a `<span class="built-from">` line, same tokens, same embedded offline
  Barlow block copied verbatim so the file stands alone). The page will be cut into per-frame
  iframes by id, so keep the ids listed below exactly.

Work in the directory you are started in (outside the repo). The repo at
/home/betty/Projects/strength.log is read-only for you: read anything, write nothing. Useful
reads: `CLAUDE.md` §5, `docs/briefs/wear-dial-v3.md` (the watch), `wear/src/main/kotlin/cloud/
trotter/log/strength/wear/ui/DialState.kt` and `DialCopy.kt`, the phone workout screen under
`app/src/main/kotlin/cloud/trotter/log/strength/ui/`. No network, no Gradle, no adb.

## The owner's idea, in their words

> what if instead of a list of exercises, we do a card per exercise that the user can swipe?
> the remaining rows of an exercise left could be represented in a different way (like a set
> #/# or something? or a row of dots?) and the user can swipe thru the exercises if that
> station isn't open or something. can you explore that? because then the app would better
> mirror the watch (and maybe could inform the watch design)

## What the watch does today (so the phone can mirror it honestly)

From `wear-dial-v3.md`: the watch has exactly two faces. The **overview face** is a dial: an
outer cycle ring (one segment per program day), an **exercise ring with one segment per lift**
(done segments fill green), and a disc that reads START or CONTINUE over "SET n OF m". The
**workout face** shows ONE set at a time: READY (the prescribed weight × reps, tap the disc to
log), LIFTING, REST (countdown), REST_OVER, TIMED_HOLD, DAY_DONE. **Swipe left at a set's start
(READY / REST_OVER) cycles to the next exercise, wrapping**; swipe right goes back to the
overview. The crown adjusts the number. There is no list on the watch at all.

So "mirror the watch" means: one exercise in view, one set in focus, progress as a ring/row of
segments, and a horizontal swipe that moves between exercises.

## What to explore (both variants, then recommend one or neither)

**Variant A — exercise card, all its sets.** A `HorizontalPager`, one page per exercise. The
page shows the exercise name, Goal / Last at the right (as decided), and ALL of its set rows in
your decided row grammar (value + standard `Checkbox`). Progress across the DAY is a row of
small segments or dots above the pager (one per exercise, done ones filled), or a
`PrimaryScrollableTabRow` of exercise names synced to the pager (a documented pattern). The set
rows inside a card still fold / complete as before.

**Variant B — one set in focus, the watch's grammar.** One page per exercise; the page shows
the CURRENT set large (weight × reps, unit), Goal / Last beside it, a single primary action
(a `Button` "Log 95 × 5" or a large `Checkbox` row), and the exercise's sets as a row of
segments ("Set 3 of 6" plus six small segments, three filled) rather than a list. Earlier sets
are reachable (tap a segment, or a "Sets" `TextButton` that opens a `ModalBottomSheet` list).
This is the closest mirror of the watch.

For each variant show: performing (mid-exercise), the edit sheet open, a finished exercise's
card, and the day-progress indicator with 6 exercises and one done. Keep the day index, the
rest pill (only while resting) and the Finish button as decided.

Frame ids, exactly: `a-perform`, `a-done`, `b-perform`, `b-edit`, `b-done`. Add `b-rest` if
you want to show the rest state on the card. Five to six frames total.

## Questions the exploration must answer (in `pager-exploration.md`)

1. **What is gained.** No vertical scrolling at all on the workout screen (issue #260, the
   jank, becomes structurally impossible); a bigger numeral; one thumb; the "station is busy,
   skip ahead" swipe; the same mental model on wrist and phone.
2. **What is lost.** The ledger's overview of the whole day; the fold-as-accomplishment; seeing
   the next lift while finishing this one. How does each variant give the overview back (an
   indicator, the receipt, a "Sets" sheet, a long-press)? Be honest about whether a 6-exercise
   dot row carries enough information.
3. **Accidental input.** A horizontal swipe near a checkbox, the rest pill, or a stepper: what
   the pager's snap and touch-slop give for free, and what the design must still avoid (no
   horizontal gestures inside a page).
4. **Process death and the "write on every mutation" rule.** The current page is UI state; say
   where it lives (`rememberPagerState` is saveable; or derive it from the first unfinished
   exercise and only override while the lifter is swiping).
5. **Could this inform the watch.** If the phone adopts B, what vocabulary could the two share
   (the segment row = the exercise ring; the primary action = the disc; swipe left = next
   exercise). If A, say what the watch would borrow. Say if nothing should change on the watch.
6. **Compose contract**, the owner's standing rule (no bespoke code): `HorizontalPager` +
   `rememberPagerState` + `key` + `beyondViewportPageCount`, `PagerDefaults.flingBehavior`,
   `contentPadding` for a peek of the next card (or not, say why), `PrimaryScrollableTabRow`
   synced via `pagerState.currentPage` and `animateScrollToPage`, and how the day-progress
   indicator is a plain `Row` of `Box`es (Google's own Pager doc shows exactly that indicator)
   or a `LinearProgressIndicator`. Cite Google's doc titles from memory and say so.
7. **Your recommendation**: A, B, or keep the ledger (revision 2). One paragraph, honest. You
   may recommend a hybrid only if you can name every component it is built from.

## Constraints that still hold from revision 2

Day index, Today / Log / Plan bar (hidden in the workout), standard checkboxes, Goal right with
Last under it, rest pill only while resting, bottom-sheet editing, rotation of 2–6 days, no
bespoke code, no caps eyebrows, no "flavor text" that instructs the lifter. Use this lifter's
real day D (overhead press 65/85/95/110/120×5 then 90×8 back-off; Bulgarian split squat 35×10
×3; seated leg curl 90×10 ×3; pull-up 6 ×3; cable lateral raise 15×10 ×3; plank 45 s ×3; then
the run). No personal data exists in this directory.

When done, your final message is the full text of `pager-exploration.md`.
