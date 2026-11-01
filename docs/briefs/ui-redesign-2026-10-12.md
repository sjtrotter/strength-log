# UI redesign — two proposals and the decisions they need (2026-10-12)

Current phone UI (4.5b): `WorkoutSetScreen` replaces the workout pager; lift navigation uses the Day sheet.
The feel review (2026-08-24) shipped in full and the direction review
(2026-08-25) named the missing coaching loop. The owner, this morning:

> "i'd like you to work in tandem with Astra to improve the design of the
> app … raise my original concerns about it not feeling like an app … this
> was originally designed as a workout tracker first and we are kinda
> trying to make it look nice and be fully featured."

and, mid-session, on the current build:

> "the workout screen is CLUNKY again, no smooth scrolling, all
> jarring/jagged. ensure whatever design we choose later makes sure that
> scrolling is smooth."  → filed as #260.

This document is the design decision record. Nothing in it is implemented.

## Inputs

- 22 screenshots of the real app on the owner's Pixel 7 (main @ 805ac90,
  walked by the owner; the collector only ran `screencap`). Today, the
  workout at eight points, the receipt, Log, Setup. Kept in the session
  scratchpad; the two proposal mocks were built from them.
- `ui-review-2026-10-12-astra-raw.md` — Codex (gpt-6-astra, xhigh) read-only
  design review of the current UI, per screen and cohesive.
- Fable's design pass (frontend-design skill): plan + HTML mock.
- Astra's design pass: plan + HTML mock, independent of Fable's (it saw its
  own review and the screenshots, not Fable's plan).
- Both mocks are published side by side as the artifact "strength.log
  redesign" (link in the session summary; a copy of each HTML file sits in
  `docs/design-handoff/redesign-2026-10-12/`).

## What the screenshots settle

The owner's "not an app" is visible without reading code:

- **Today is a readout.** Seven tracked-caps captions on one screen (TODAY,
  NEXT IN ROTATION, 6 LIFTS · 21 SETS, CARDIO FINISHER, ROTATION, LAST
  SESSION, LAST SESSION 69 DAYS AGO · 0 THIS MONTH). The app has noticed a
  ten-week absence and prints it as a stat line.
- **The emphasis line speaks the generator's language**: "incline press ·
  vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads"
  sits at bodyLarge under the headline.
- **The workout is a control panel.** Every row is `− 65 + − 5 + ☐`; six
  identical cards; the read-only GOAL numeral (34sp, accent) is the biggest
  thing on each card while the actual weight is 28sp. 140dp of chrome before
  the first card. The bottom bar carries three things.
- **Setup is an orange form.** Selected choices are solid terracotta slabs;
  day-A orange is the app-wide primary, so the accent means nothing there.
- **Log leads with analytics** (three trajectory tiles, a one-bar volume
  chart) and a Health Connect pitch; the lifter's sessions are below.
- **The receipt is the best surface** and still a ledger (SETS / STRONGEST /
  NEXT) with nothing said about the session.

## Where the two reviewers agree (the diagnosis)

Independently, Astra's review and Fable's plan reach the same six points.
This is the strongest signal in the document.

1. **Hierarchy is assigned wrong.** Reference and classification (GOAL,
   captions, taxonomy) outrank the current action and the lifter's own
   numbers. Captions carry the structure; size and placement should.
2. **Performing must be the default; editing the exception.** A set at rest
   is a value and a tick. Steppers appear on touch (spec §1.5 holds: still
   one tap away).
3. **Plan / workout / record / app settings need separate homes.** Setup
   splits into Program and Settings; Log becomes the record, sessions first;
   Health Connect and backup are administration.
4. **Today must interpret, not report.** One derived sentence from a quiet
   coach: the return after a gap, a rotation closing, loads holding. Never
   stored, never a streak.
5. **One vocabulary.** Sentences for choices, consequences and progress;
   compact notation (R1, TOP, B/O, 90×8) only inside the set grid.
6. **Colour must mean one thing.** Day accent = a training day (identity +
   the one primary action); Done green = completed work only (today it also
   paints Edit/Add/Swap/Import); accent *text* uses the bright-lift variant
   (raw teal on near-black is recessive, visible in the Day D shots).

Astra adds four that Fable's plan did not have and accepts:

7. The wizard should make *accepting the recommendation* the authored path;
   split/anchor/deadlift-variant/equipment are specialist arbitration.
8. Day-edit and custom-exercise flows start from the catalog's taxonomy
   ("pick a pattern", 19 pattern cards before "how is it tracked"); they
   should start from the exercise the lifter wants.
9. The receipt's STRONGEST is a load scoreboard; the NEW RAMP cascade
   ceremony is the app's biggest animation and it celebrates progression in
   a maintenance product. Completion needs a coherent meaning: a normal
   session, a partial one, a closed rotation, and a new high, in that order
   of frequency.
10. Backup should lead with "is my training protected", not file formats.

## Fable's proposal (summary; the mock is the spec)

The identity stays. The hierarchy moves from captions to three things:
size, one accent object per screen, one sentence.

- **The day mark.** The rotation letter at 112sp in the day's bright accent
  is the hero of Today and the receipt, and the 30sp anchor of the workout
  bar. A/B/C/D is already the app's vocabulary (watch, widget, calendar).
- **The rotation band.** Four segments, 4dp, in the day accents, filled as
  days close this rotation, the next one capped. Replaces the A B C D
  chips, the ROTATION caption and the workout's day tab strip. It is the
  one recurring graphic device and the one orchestrated motion (it fills on
  the receipt).
- **Today**: band · "Rotation 7" · mark · "Full body" · the coach's
  sentence · the lifts as an index (name, set count) · cardio as a sentence
  · "Last time, 4 Aug: 21 sets, deadlift 185." · **Start day D**.
- **Workout**: fixed sticky bar (mark, title, "9 of 21", edit) + 2dp
  progress hairline; exercises are sections separated by hairlines, no
  cards; rows are `R3  95 × 5  ○` at 30sp; the next set carries the only
  accent (3dp left rule, bright numerals) and the plate line; tapping a
  value opens that row's steppers inline; finished exercises fold to one
  summary line with a green dot; the run is a section; the bar is rest
  pill (while resting) + **Finish day D**. Keep-on moves into the edit sheet.
- **Receipt**: band fills · mark · "Day D, done." · sentence ("First session
  back in ten weeks, and the loads held across the day. That closes
  rotation 7.") · ledger (21 sets · 120 × 5 overhead press · Next: Day A)
  · Share / **Back to today**.
- **Record**: sessions first (mark, title, date, strongest set), calendar,
  "By lift" rows that open a lift's trajectory. Volume chart dropped.
- **Program** (goals as numerals on the page, bodyweight/age/level/emphasis/
  cardio as value rows, "Rebuild the rotation") and **Settings** (units,
  theme, keep-on, rest timer, Health Connect, backup, export, custom
  exercises, licenses). No day accent on either.
- **Copy**: sentence case throughout; same verb through the flow (Start day
  D → Finish day D → Day D, done.); no eyebrows, no dot-joined meta, no
  "WORD — fragment" buttons. Caps survive only as set notation.
- **Scroll smoothness is a design constraint** (#260): nothing on the
  workout screen is driven by scroll offset; rows are stable keyed items;
  collapse is a one-item content swap, not an animated height.

Risks Fable names on its own plan: the giant letter can read as an
editorial drop cap if it gets any ornament (keep it plain); the handoff of
2026-07 pinned `labelSmall` caps everywhere, so this reverses a documented
decision; "Rotation 7" needs a rotation counter the domain doesn't have.

## Astra's proposal (summary; `docs/design-handoff/redesign-2026-10-12/astra-design-plan.md` is the full plan with a complete copy table)

Astra calls its one expressive move **the day index**: the rotation letter
on a solid accent block, 116sp on Today and the receipt, 26sp in the
workout bar, filed beside the date in Log. Everything else is ordinary
well-spaced type and rules.

- **IA**: three ordinary destinations on a quiet M3 bottom bar, Today /
  Log / Plan; Settings behind Today's labelled action. Plan owns the
  rotation (day tabs, the six exercises, Edit day D, goals, training
  details, exercise library, "Make a new plan").
- **Today**: index · "Next in your rotation" · "Full body" · "6 exercises,
  21 sets" · "Your place is here." · "Last trained 4 Aug, 69 days ago.
  Start with the warm-up. Own each rep." · the lifts · "After lifting:
  Intervals" · **Start day D**. The return line fires only when history
  exists and the gap is 14+ days; otherwise "Take the next set as it
  comes."; an active session says "Pick up where you left off."
- **Workout**: fixed app bar (Today, index, Full body, menu) + fixed M3 day
  tabs (kept; override reads "Day D is next in your rotation"); exercises
  as ruled sections with GOAL on the left and an ACTUAL column; rows are
  "Warm-up 3  95 lb × 5  ☐" (notation spelled out); the current row has a
  narrow ink edge; finished rows stay put. **No collapse at all**, automatic
  or manual (Astra reads today's smoothness constraint as overriding spec
  §8's collapse). Tapping a value opens an **M3 bottom sheet** with both
  steppers, "Warm-ups and back-off follow this weight." for TOP and the
  plate line; remove-set is in the sheet's menu. The dock is always two
  rows: rest (−15 s / +15 s / Skip, "Rest starts after a set." when idle)
  and "2 of 21 saved" + **Finish day**.
- **Receipt**: the workout is replaced by an opaque page (no scrim, no
  preview of Day A behind it): index · "Workout saved." · date · "21 sets
  recorded. That counts." · the six-line exercise record · the actual TOP
  set with its lift ("not an unqualified Strongest trophy") · "Next time:
  Day A, full body" · Add a note / Share / **Back to Today**. Partial
  sessions say "9 of 21 sets recorded. That counts." It explicitly refuses
  to invent a rotation number or declare a rotation complete until the
  domain can verify coverage of the same program version.
- **Log**: Sessions tab first ("Your training, kept."), rows = index, date,
  Full body, set count; Progress tab holds trajectories and volume with
  measured labels; calendar behind "Browse by month"; Health Connect to
  Settings, bodyweight prompt to Plan.
- **Settings**: M3 ListItems (Units, Appearance, Rest timers, Keep screen
  awake, Health Connect, Backup and restore, About). **Wizard** ends on
  "Your rotation" as four ordered rows ("Start here" … "Then back to A"),
  the goals, "Own it, don't grind it." and **Use this plan** (saves without
  starting a workout).
- **Smoothness contract** (worth lifting verbatim into the implementation
  brief whichever design wins): fixed TopAppBar without scrollBehavior, one
  LazyColumn with items keyed by exercise-instance + set id and distinct
  contentTypes, fixed minimum geometry per content type, no
  animateContentSize / AnimatedVisibility size transitions / animateItem /
  pinned headers / completion auto-scroll; timer recomposition isolated to
  the dock; structural edits change list contents immediately with the
  first visible item retained by key.
- **Motion**: one moment, the receipt body fading in as a group over 180 ms
  after a successful commit; failure leaves the workout intact with a
  snackbar. No number rolls, no stagger, no cascade procession (TOP change
  gets one accessibility announcement).
- **Copy**: a complete old → new table for every string on the pictured
  surfaces, sentence case, catalog names lower-cased ("Overhead press
  (barbell)"), "MAIN" / "+1 WARM-UP" badges removed, "Best:" becomes
  "Heaviest logged" inside exercise history only.

Astra flags, honestly, that it could not render its HTML (no browser in
its sandbox); Fable rendered it in headless Firefox and it is sound.

## Where the two proposals differ (the owner's choices)

| | Fable | Astra |
|---|---|---|
| The mark | bare letter, bright accent | letter on an accent block |
| Rotation device | the band (fills on the receipt) | none; Plan's day tabs |
| Day tabs in the workout | removed | kept, fixed |
| Set notation | R1 / TOP / B/O kept | Warm-up 1 / Top set / Back-off |
| Editing a value | inline stepper band | M3 bottom sheet |
| Finished exercises | fold to a summary line | never collapse |
| Bottom bar | rest pill only while resting | always two rows |
| Navigation | hub from Today | Today / Log / Plan bottom bar |
| Rotation close | "Rotation 7 complete" (needs a counter) | wait until verifiable |
| Receipt headline | "Day D, done." | "Workout saved." |

## Decisions for the owner

1. **Narrator.** Does the app get a voice that says one derived sentence on
   Today and the receipt? (Both proposals assume yes; it is the single
   largest change in feel.)
2. **Captions.** Retire tracked-caps eyebrows and dot-joined meta strings
   app-wide, keeping caps only for set notation. This reverses the 2026-07
   handoff's `labelSmall` convention.
3. **The day selector.** Fable removes the workout's A/B/C/D tab strip (the
   band on Today is the rotation; another day is reached from Today). Keep
   the strip, or let it go?
4. **Cards.** No cards on training surfaces (Today, workout, receipt);
   cards only for discrete objects. Yes/no.
5. **The cascade ceremony.** Keep NEW RAMP as the biggest moment, or demote
   it under the rotation close?
6. **IA.** Program + Settings replace Setup; Log becomes Record with
   sessions first and charts behind lifts; Health Connect moves to Settings.
7. **Which proposal, or which parts of each.** Then the chosen design is
   written as a handoff (tokens, per-screen contract, copy table) and
   implemented screen by screen, Today and the workout first.

## What the chosen design needs from the domain

- A rotation counter (rotation N, days completed in it) — derived from
  session history or stored with the program pointer.
- Derivation rules for the coach's sentence: days since last session with a
  threshold for "returning"; rotation closed on the last day; "loads held"
  when every main's TOP matched or beat last time. One sentence, first rule
  that fires, otherwise nothing.
- A plain-language name per day's emphasis for Program (the taxonomy line
  stays in the generator, out of the lifter's view).

## Round 2 — the owner's decisions (2026-10-12, afternoon)

The owner reviewed the side-by-side page and decided screen by screen. Both
designers then revised their own proposal in place (revision 2 of each mock
and plan in `docs/design-handoff/redesign-2026-10-12/`), so the comparison
page now shows two designs that agree on these:

| Decision | Chosen | From |
|---|---|---|
| The mark | the day index: letter on an accent block, text column beside it (not below) | Astra |
| Today's coach line | keep "last trained"; drop "Start with the warm-up. Own each rep." (reads as condescending); the coach states a fact, never instructs | owner |
| Navigation | Today / Log / Plan bottom `NavigationBar`; Settings behind Today and Plan | Astra |
| Set rows | standard Material 3 `Checkbox`; no stylised ticks; no "ACTUAL" column label | owner |
| Exercise header | goal at the right, "Last …" directly under the goal, not under the name | Fable + owner |
| Rest timer | small pill, only while a rest runs, countdown + −15/+15/skip, beside Finish | Fable |
| Editing a set | `ModalBottomSheet`; the list is a ledger, editing always goes through the sheet | Astra |
| Finished exercises | owner conflicted; leaning to the fold, refined: the row checkboxes collapse into ONE checked checkbox at the right of the summary line (not a dot at the left); acceptable if it is standard Compose (`AnimatedContent` on one keyed item) | owner |
| Rotation length | 2 to 6 days (`WizardAnswers`), accents A–G (`DayAccentColors`); nothing may assume four | owner |
| Bespoke code | none: every element maps to an M3 library composable or a plain composition; both plans now carry a "Compose contract" naming the APIs per screen and the Google guidance followed (UDF/state hoisting, lazy keys + contentType, performance best practices, M3 theming, Navigation Compose, accessibility, insets, previews) | owner |

Consequences accepted in the revisions: Fable's rotation band is retired
(it assumed a fixed rotation length and was the one hand-drawn element);
Astra's permanent two-row dock is retired; plates move into the edit sheet
so every set row is the same height.

Still open after round 2 (the remaining differences on the page):

1. **Fold or flat** for finished exercises (see above; both mocks show the
   owner's fold so it can be judged).
2. **Set notation**: R1 / TOP / B/O (Fable) or Warm-up 1 / Top set /
   Back-off (Astra).
3. **A day selector in the workout**: none, another day starts from Plan
   (Fable), or tabs under the app bar (Astra; `PrimaryScrollableTabRow` for
   six days).
4. **Receipt headline**: "Day D, done." or "Workout saved."
5. **Rotation counter**: build now or wait until the domain can verify
   coverage. Neither revised design depends on it any more.
6. **Narrator and captions** (decisions 1 and 2 of the first round) are
   implicitly accepted by the choices above; confirm.

## Exploration — the workout as cards you swipe (12 Oct, evening)

After revision 2 the owner asked whether the workout should be a card per exercise that
the lifter swipes through (skip a busy station), with the sets left shown as a count or a
row of dots, so the phone mirrors the watch and might inform it. Both designers explored
it from the same brief (`docs/design-handoff/redesign-2026-10-12/astra-pager-brief.md`),
on top of revision 2's decisions. Mocks: `fable-pager.html`, `astra-pager.html`; side by
side: `pager-compare.html`; write-ups: `fable-pager-exploration.md`,
`astra-pager-exploration.md`.

Two variants. **A**: one `Card` per lift in a `HorizontalPager`, all its set rows inside,
a segment row (one per lift) above. **B**: one set in focus at display size, the lift's
sets as segments, one logging action, the full list behind a sheet; the watch's grammar.

Both recommend **B** over A and over the ledger if the phone is to mirror the watch. A
keeps the list's density and its overflow problem while giving up the overview. B removes
the vertical list from the workout screen entirely (the #260 mechanism cannot recur),
buys the largest numeral in the app, and shares vocabulary with the wrist: day segments =
the overview's exercise ring, set segments = the round ring, the focused prescription and
its action = the disc, swipe left = next exercise. Neither proposes changing the watch.

Where the two differ: Fable logs with a `Button` ("Log 95 × 5") and lets the next card
peek (contentPadding); Astra keeps a standard `Checkbox` as the logging target, no peek
(a named next-exercise control instead), and adds an Exercises sheet of names with counts
because six anonymous segments cannot say which lift is done. Fable makes filled segments
tappable to open that set's sheet; Astra keeps segments as indicators only. Astra puts
the selected exercise id in `SavedStateHandle` (reconciled after add/remove, falling back
to the first unfinished lift); Fable relies on the saveable pager state plus the same
derivation. Both: `rememberPagerState`, `key` per exercise id, `beyondViewportPageCount
= 1`, `PagerDefaults.flingBehavior`, a `Row` of `Box` indicator from Google's "Pager in
Compose" doc, no horizontal gesture inside a page, edits only in `ModalBottomSheet`.

Open: whether to take B over the revision-2 ledger at all; peek or no peek; Button or
Checkbox as the log action; whether segments are tappable; the Exercises sheet.

### Decision (12 Oct, evening): variant B, Fable's version

The owner: "i like variant b, your version and it isn't even close. that looks like a pro
workout app to me." So the workout becomes Fable's B: one lift per page in a
HorizontalPager, one set in focus at display size, the lift's sets as segments, a Button
"Log 95 × 5", "All sets" in a sheet, the next card peeking, filled segments tappable to
reopen that set's sheet. Fold-vs-flat is moot (no ledger). Taking the rest of Fable's
calls with it: R1 / TOP / B/O notation, no day selector in the workout, "Day D, done." on
the receipt, rotation counter deferred. Astra's Exercises sheet (names + saved/total,
tap to jump) is kept as the day-overview behind the segment caption; it is a
ModalBottomSheet of ListItems, nothing bespoke. The handoff is
`docs/design-handoff/redesign-2026-10-12/HANDOFF.md`.

### Implementation notes (12 Oct, late)

PR #261 landed Today, the navigation bar, a minimal Plan and the day index. The workout pager
(variant B) followed as PR 2. Two things changed against the handoff during its review, both for
Material's 48 dp touch targets: the day and set **segments are indicators, not buttons** (six to
eight of them cannot each be 48 dp wide on a 360 dp phone; navigation is the swipe, the Exercises
sheet behind the caption, and the next-lift button; corrections go through All sets or the focus
numeral), and the **rest pill** is a time button that opens a small menu for 15 s less / 15 s more,
beside Skip, instead of three tiny text buttons. The card's content also scrolls inside itself in
a window too short to hold it (411 dp landscape); on a phone in portrait nothing scrolls, and no
chrome is ever scroll-linked. `SetFormatter` in `:domain` gained a default-off spaced option so
the workout's "95 × 5" and the Log's compact "95×5" come from one formatter.
