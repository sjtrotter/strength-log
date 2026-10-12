# strength.log redesign — Fable's design plan, revision 2 (12 Oct, after the owner's review)

## What changed in revision 2

The owner looked at both proposals side by side and decided. Applied here:

- **The day index** replaces the bare letter and the rotation band. A letter on
  its accent block (Astra's hero), with the title and the one-line facts beside
  it, not below. Large on Today and the receipt, 32 dp in the workout bar,
  44 dp beside sessions in Log and days in Plan. The band is gone: it assumed
  a fixed-length rotation and it was the one hand-drawn thing in the design.
- **Bottom navigation bar**: Today / Log / Plan. Settings sits behind an icon
  on Today and a text action on Plan. This is current Material 3 guidance
  (a navigation bar for three to five top-level destinations, each with its
  own back stack), not "old Android": the old pattern was the drawer or
  top-level tabs. A bottom bar is also what the hub-from-Today design was
  quietly reinventing with links.
- **The coach states a fact and stops.** "Last trained 4 Aug, ten weeks ago.
  Your loads are where you left them." No instruction ("start with the
  warm-up"), no advice ("leave a set if you need to").
- **Standard Material 3 Checkbox** on every set row, green only when checked.
  No stylised ticks, no "ACTUAL" column label.
- **Goal and last time stack at the right** of each exercise header
  ("Goal 120" over "Last 120 × 5"); the left is the name alone.
- **Rest pill** stays as proposed: present only while a rest runs, countdown
  in condensed numerals, −15 / +15 / skip inside, beside Finish.
- **Editing goes through a ModalBottomSheet.** The list is a ledger; tapping a
  value opens the sheet with the weight and reps steppers, the plates line,
  the cascade note for the top set, and Remove set in its menu. No inline
  stepper band. Plates leave the list so every row is the same height.
- **Finished lifts fold** to name, one summary line, and one checked checkbox
  at the right where the row checkboxes were (the owner's refinement). The
  fold is an `AnimatedContent` swap on one keyed item; see the Compose
  contract for why that keeps the smoothness promise.
- **Rotations are 2 to 6 days.** Nothing assumes four: Plan and the wizard
  list one row per day, copy derives the count, day index colours come from
  `DayAccentColors` (A to G, cycling).
- **No bespoke components.** Every element maps to a Material 3 library
  composable or a plain composition of Row / Column / Text / Surface. The
  table in the Compose contract is the inventory.
- Still open, now the only places this proposal differs from Astra's: set
  notation (R1 / TOP / B/O kept here, spelled out there), a day selector in
  the workout (none here; another day starts from Plan), receipt headline,
  whether to build a rotation counter.

## The brief, restated

A paid, local, no-account Android strength tracker for a ~40-year-old who
trains to stay strong, not to set records, on a schedule that falls apart.
Its job: say what the next move is, let the lifter confirm what they did
with almost no taps, and show that it counted. The feeling is composure.
The narrator is a quiet coach with one opinion: own it, don't grind it.

Fixed identity (CLAUDE.md §5): near-black #0D0D0F, surfaces #16161A, the
day accents (A terracotta C1440E, B green 2D5A3D, C ochre B8860B, D teal
1F4E5F, E to G for longer rotations), Barlow Condensed for numerals and
display, restrained copy, Material 3 as the component library, a warm-paper
light theme.

## Tokens

### Color

| Token | Hex | Rule |
|---|---|---|
| Background | #0D0D0F | the page |
| Surface | #16161A | the navigation bar, the sheet, the rest pill |
| Border | #2A2A30 | hairlines between rows; no outlined cards on training surfaces |
| Text | #F2F2F0 | words and numerals; also the letter on A, B, D, E, F, G indexes |
| Text secondary | #9A9AA2 | the coach's sentence, meta, unchecked checkbox stroke |
| Text faint | #6B6B73 | notation (R1, TOP), the last-time line, fold summaries |
| Day accent | A to G | exactly two uses per screen: the day index and the primary action; plus the next-set rule in the workout |
| Day accent, bright | brightHex(day) | the only form the accent takes as text (next-set numerals) |
| Done | #3E8E5A | checked checkboxes only; never a button |
| Neutral action | Text on Surface #1D1D22 | administration screens use no day accent |

The day index on C (ochre) carries ink #0D0D0F; the others carry Text. That
is `onDayAccent`, already in the theme.

### Type

| Role | Face | Size/line | Use |
|---|---|---|---|
| Day index, large | Condensed 700 | 104 in a 112 × 128 block | Today, receipt |
| Day index, medium | Condensed 700 | 38 in 44 × 52 | Log rows, Plan rows, wizard |
| Day index, small | Condensed 700 | 26 in 32 × 36 | the workout app bar |
| Statement | Condensed 600 | 34/36 | "Day D, done.", "Your rotation" |
| Title | Condensed 700 | 24/28 (30 beside the large index) | exercise names, section names |
| App bar title | Condensed 600 | 22 | Today, Log, Plan, Settings, Full body |
| Numeral | Condensed 700, tnum | 30/30 set values; 34 receipt | |
| Numeral small | Condensed 600, tnum | 22/24 | Log top sets, goals |
| Body | Sans 400 | 16/23 | the coach's sentence, list rows |
| Meta | Sans 400 | 14/20, secondary | dates, counts, "Next in your rotation" |
| Notation | Condensed 500 | 13/16, caps, +0.5 | R1 TOP B/O, the only caps allowed |
| Button | Condensed 600 | 19/20, sentence case | "Start day D" |
| Nav label | Sans 500 | 12 | Today, Log, Plan |

No tracked-caps eyebrow anywhere. A label appears only where a number cannot
speak for itself ("Goal 120", "Last 120 × 5").

### Layout

20 dp gutter, 8 dp grid, left-aligned everything. Training surfaces (Today,
workout, receipt) have no cards: content sits on the page, rows are divided
by hairlines. Set rows are 56 dp, all of them, so the list's geometry never
depends on which row is next. Cards appear nowhere; the sheet, the rest pill
and the navigation bar are the only raised surfaces.

## Screens

### Today

```
┌────────────────────────────────────┐
│ Today                           ⚙  │  TopAppBar
│ ┌────────┐ Next in your rotation   │
│ │        │ Full body               │  index 112×128 + column beside it
│ │   D    │ 6 exercises, 21 sets    │
│ └────────┘                         │
│ Last trained 4 Aug, ten weeks ago. │  the coach's one fact
│ Your loads are where you left them.│
│ Overhead press             6 sets  │  ListItem rows, hairlines
│ Bulgarian split squat      3 sets  │
│ …                                  │
│ Then run: 5 min easy, 4 to 6 × …   │
│ ┌────────────────────────────────┐ │
│ │          Start day D           │ │  Button, accent, 56 dp
│ └────────────────────────────────┘ │
│   ▣ Today      ≡ Log      ↻ Plan   │  NavigationBar
└────────────────────────────────────┘
```

The sentence is derived, never stored, and absent when there is nothing to
say: returning after a gap of two weeks or more; a session in progress
("Pick up where you left off"); otherwise nothing. It states; it does not
instruct.

### Workout, performing

```
┌────────────────────────────────────┐
│ ‹ [D] Full body  9 of 21         ⋮ │  TopAppBar, fixed; small index
│ ▬▬▬▬▬▬▬▬▬▬▬▬▬░░░░░░░░░░░░░░░░░░░░░ │  LinearProgressIndicator, 2 dp
│ Overhead press            Goal 120 │
│                        Last 120 × 5│  reference stack, right-aligned
│ R1   65 × 5                     ☑  │  done: quiet numerals, green box
│ R2   85 × 5                     ☑  │
│ ▌R3  95 × 5                     ☐  │  next: accent rule + bright numerals
│ R4  110 × 3                     ☐  │
│ TOP 120 × 5                     ☐  │
│ B/O  90 × 8                     ☐  │
│ Add a set                          │  TextButton
│ ───────────────────────────────    │
│ Bulgarian split squat   Goal 35 a hand
│ ┌────────┐ ┌──────────────────────┐│
│ │ 1:30   │ │     Finish day D     ││  rest pill (while resting) + Button
│ │−15 +15 skip│                    ││
│ └────────┘ └──────────────────────┘│
└────────────────────────────────────┘
```

The checkbox is the only control on a row. Tapping the value opens the
sheet. The menu holds Keep screen on, Clear today's checks, Edit day D,
Do a different day. Another day is also reached from Plan.

### Workout, editing a set

```
│ (list behind, scrimmed; R3 row outlined, same height)                     │
│ ┌────────────────────────────────┐ │  ModalBottomSheet
│ │            ▬▬▬▬                │ │  drag handle
│ │ Ramp set 3                   ⋮ │ │  title + menu (Remove set)
│ │ Overhead press                 │ │
│ │ ⊖ ┌Weight, lb┐ ⊕   ⊖ ┌Reps┐ ⊕   │ │  IconButton + OutlinedTextField + IconButton
│ │   │   95     │       │ 5  │     │ │
│ │ 25 a side on the bar.          │ │
│ │ Only this set changes.         │ │  for TOP: "Warm-ups and back-off follow this weight."
│ │ ┌────────────────────────────┐ │ │
│ │ │            Done            │ │ │
│ └────────────────────────────────┘ │
```

Every change persists as it happens (spec: write on every mutation); Done
only dismisses. Tapping the number in the field types it with the numeric
keyboard, the existing validation and unit rounding.

### Later in the workout

```
│ Overhead press                  ☑  │  fold: name, summary, one checked box
│ 65×5, 85×5, 95×5, 110×3, 120×5, 90×8
│ Bulgarian split squat           ☑  │
│ 35×10, 35×10, 35×10                │
│ …                                  │
│ Plank                     Goal 45 s│  the open lift
│ ▌1  45 s                        ☐  │
│ Then run                           │
│ 5 min easy, then 4 to 6 rounds …   │
│ [ Start the run ]                  │  OutlinedButton
```

A lift folds when its last set is checked; tapping the fold reopens it
(and unchecking any set keeps it open). The fold is one item's content
swapping, not a height animation of the list.

### Receipt

```
│ ┌────────┐ Day D,                  │
│ │   D    │ done.                   │
│ └────────┘ Full body, 12 Oct       │
│ First session back in ten weeks,   │
│ and the loads held across the day. │
│ Sets                           21  │
│ Top set       120 × 5 overhead press
│ Next             Day A, full body  │
│ Add a note                         │
│ [ Share ]        [ Back to today ] │
```

The receipt is its own destination that replaces the workout (no scrim
over the next day). No rotation-complete claim until the domain can make
one.

### Log

Sessions first: ListItem rows with the medium day index leading, title and
date, the top set trailing. Tap a row to open the session. "By lift" in the
app bar opens the trajectories. The month grid stays under the list.
Health Connect leaves this screen for Settings.

### Plan (was Setup)

One row per day of the rotation (two to six), the next one marked; tap a
day to see or edit it, or to start that day instead of the next. Goals as
numerals on the page. The training assumptions (bodyweight, age, level,
emphasis, cardio) as rows that open their editors. "Make a new plan" as an
outlined button, with its existing confirmation.

### Settings

A plain list with values on the right: units, theme, keep screen on, rest
timer (phone, watch, lengths), Health Connect, backup, export, custom
exercises, licenses, version. No day accent.

### Wizard, last step

"Your rotation", one row per day with its index and the main-lift goal on
the right, "Start day A". The step header is "8 of 8", not "STEP 8 OF 8".

## Compose contract

This is what the implementer builds from. Nothing below is bespoke; where a
piece is a composition, its parts are named.

| Screen | Material 3 / Compose APIs |
|---|---|
| App shell | `Scaffold` per destination; `NavigationBar` + `NavigationBarItem` (Today, Log, Plan) shown only on those three; Navigation Compose with type-safe routes, `saveState` / `restoreState` on the bar, `launchSingleTop` |
| Day index | `Surface(color = dayAccent(i), contentColor = onDayAccent(i), shape = MaterialTheme.shapes.small)` holding one `Text` in the display style; three sizes by `Modifier.size` |
| Today | `TopAppBar` (title, Settings `IconButton`); `LazyColumn` of `ListItem` with `HorizontalDivider`; `Button` above the bar |
| Workout | `TopAppBar` with **no** `scrollBehavior`; a 2 dp `LinearProgressIndicator` under it; one `LazyColumn` with `key` = exercise-instance id / set id and `contentType` = header / set / add / fold; set row = `Row` { `Text` notation, `Text` value with `clickable`, `Checkbox` } with `Modifier.toggleable(role = Role.Checkbox)` on the row and `semantics(mergeDescendants = true)`; Add a set = `TextButton`; bottom bar = `Row` { rest pill, `Button` }; rest pill = tonal `Surface(shape = CircleShape)` { `Text` countdown, three `TextButton`s }, present via `AnimatedVisibility` outside the list; menu = `IconButton` + `DropdownMenu` |
| Editing | `ModalBottomSheet`; stepper = `IconButton` − / `OutlinedTextField(keyboardOptions = Number)` / `IconButton` +; helper `Text`; `DropdownMenu` (Remove set); `Button` Done |
| Fold | `AnimatedContent(targetState = folded)` inside the exercise's keyed item; folded state = `ListItem` (headline, supporting summary, trailing `Checkbox(checked = true)`) |
| Receipt | `Scaffold`; day index; `Column` of `ListItem`; `OutlinedButton` Share, `Button` Back to today; default Navigation Compose transition |
| Log | `TopAppBar` with `TextButton` action; `LazyColumn` of `ListItem` (leading day index, trailing numerals); month grid = `LazyVerticalGrid` of `Text` |
| Plan | `TopAppBar`; `LazyColumn` of `ListItem` rows; `OutlinedButton` Make a new plan; existing `AlertDialog` for the confirmation |
| Settings | `TopAppBar` with navigation `IconButton`; `ListItem` rows with trailing `Text` or `Switch` |
| Wizard | `TopAppBar`; `ListItem` rows; `OutlinedButton` Back, `Button` Start day A |
| Feedback | `Snackbar` via `SnackbarHostState` for Set removed / Undo and save failures |

Practices, by name, from Google's own guidance (cited from memory; no
network in this session):

- **Architecture and state** ("Guide to app architecture", "State and
  Jetpack Compose", "State hoisting"): unidirectional data flow; one
  `StateFlow<UiState>` per screen ViewModel, collected with
  `collectAsStateWithLifecycle`; screen composables take state and lambdas,
  never a ViewModel below the route level; UI state that must survive
  process death goes through `SavedStateHandle` or Room (CLAUDE.md data
  principles), never a bare ViewModel field.
- **Lists** ("Lists and grids"): `LazyColumn` with stable `key`s from
  persisted ids (never index, never values that change on tick) and
  `contentType` per row kind so the slot reuse pool matches; no nested
  scrollables; no `animateItem` on the workout list.
- **Performance** ("Jetpack Compose performance", "Follow best practices",
  "Stability in Compose"): UI models are immutable data classes of
  primitives and `ImmutableList`s so strong skipping applies; defer state
  reads to the phase that needs them (the countdown reads its `State` inside
  the `Text`, not in the bar's parent); `derivedStateOf` for "n of 21";
  no backwards writes (ticking a set does not write state the list is
  reading in the same composition); `remember` for anything computed from
  arguments; baseline profile kept current.
- **Scrolling** (the #260 constraint): nothing reads `LazyListState` scroll
  offset to drive layout; no `TopAppBarScrollBehavior`, no
  `nestedScroll` connection, no `animateContentSize` on list items, no
  completion-triggered `animateScrollToItem`. The fold is a content change
  inside one item whose key does not change; `AnimatedContent` runs a
  crossfade on that item's content; the list repositions once, in the next
  frame, as it would for any content change, and the item being folded is
  the one the thumb just released, not one above the viewport.
- **Side effects** ("Side-effects in Compose"): the rest countdown is a
  `LaunchedEffect` keyed on the rest id; `rememberUpdatedState` for
  callbacks captured by long-lived effects; haptics via
  `LocalHapticFeedback` on tick.
- **Material 3 theming** ("Material Design 3 in Compose"): the identity
  lives in `MaterialTheme(colorScheme, typography, shapes)` and component
  defaults (`CheckboxDefaults.colors(checkedColor = done)`,
  `TopAppBarDefaults.topAppBarColors`, `NavigationBarDefaults`,
  `ListItemDefaults.colors` with the page background,
  `ButtonDefaults.buttonColors(containerColor = dayAccent)`); no
  component is re-drawn to escape its defaults; the guardrail test from the
  Material migration keeps it that way.
- **Navigation** ("Navigation with Compose", "Navigation bar" in the
  Material 3 guidelines): three top-level destinations in a `NavigationBar`
  with independent back stacks; the workout, receipt, settings, session and
  wizard are detail destinations that hide the bar; predictive back enabled
  in the manifest.
- **Accessibility** ("Accessibility in Compose"): 48 dp minimum targets
  (the `Checkbox` has them; the value `Text` gets `Modifier.minimumInteractiveComponentSize`);
  `contentDescription` on every icon button; the set row merges its
  descendants so TalkBack reads "Overhead press, ramp set 3, 95 pounds by 5,
  not done"; the day index has a `contentDescription` of "Day D"; colour is
  never the only signal (the next set also has the rule and the bright
  numerals, the done set also has the check).
- **Insets and edge-to-edge** ("Display content edge-to-edge"):
  `enableEdgeToEdge()` in the activity; `Scaffold` applies
  `contentWindowInsets`; the bottom bar and the sheet pad with
  `WindowInsets.navigationBars`; nothing else handles insets by hand.
- **Text**: tabular numerals via `fontFeatureSettings = "tnum"` in the
  display and numeral text styles in the theme; `Text` with
  `maxLines`/`overflow` where a name can run long.
- **Previews and tests** ("Preview your UI with composable previews",
  "Testing your Compose layout"): `@PreviewLightDark` plus a font-scale
  preview on every screen with a `PreviewParameterProvider` for the
  resting, mid-session and folded states; semantics-based UI tests for
  tick, fold, sheet open/close and the smoothness invariant (first visible
  item key and offset unchanged after tick, fold and sheet dismiss).

## Motion

Everything answers a tap: the checkbox's own check animation, the sheet's
standard slide, the fold's crossfade, the navigation transition into the
receipt. No entrance animations, no number rolls, no cascade procession
(the TOP cascade gets one announcement: "Warm-ups and back-off updated").
Reduced motion: `AnimatedContent` and the sheet follow the system animator
scale, so nothing extra is needed.

## Principles

1. One accent object per screen (the day index) and one accent action.
   Nothing else is coloured.
2. Words in sentences, numbers in condensed. No eyebrows. No dot-joined
   meta strings. No "WORD — fragment".
3. The list is a ledger. Perform in it; edit over it.
4. The coach states one derived fact per screen, or nothing.
5. Built from the library. If a thing needs a custom composable, the
   design is wrong, not the library.
6. Nothing moves with scroll.

## Self-check against the generic defaults

- Near-black + accent: pinned by the brief, and it is up to seven earth
  accents with meaning (a day), not one acid accent. Keep.
- Giant letter as hero: earned because A/B/C/D is the product's own
  vocabulary (watch dial, widget, calendar already use it). On a block
  rather than bare, it reads as an index tab, not a drop cap.
- Tracked caps / middle dots / em-dash labels: retired. Set notation keeps
  caps because it is notation; the fold summary uses commas.
- Wall of cards: none. The only raised surfaces are the sheet, the rest
  pill and the navigation bar, each of which is a different kind of thing.
- Scattered motion: none; everything answers a tap.
- Copy: sentence case, active, the same verb through a flow ("Start day D"
  → "Finish day D" → "Day D, done.").
