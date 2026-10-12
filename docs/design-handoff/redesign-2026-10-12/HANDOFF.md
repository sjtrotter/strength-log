# strength.log phone redesign — handoff (decided 12 Oct 2026)

This is the single source for implementing the redesign. It supersedes the two proposals
(`fable-design-plan.md`, `astra-design-plan.md`) and the exploration (`fable-pager-exploration.md`)
where they disagree. Mocks of record: `fable-mock.html` (Today, receipt, Log, Plan, Settings,
wizard) and `fable-pager.html` frames `b-perform`, `b-rest`, `b-edit`, `b-done`, `six` (the workout).
Decision record: `docs/briefs/ui-redesign-2026-10-12.md`.

## Rules that bind every PR

1. **No bespoke components.** Every element is a Material 3 Compose library component or a plain
   composition of `Row`/`Column`/`Box`/`Text`/`Surface`. A custom composable must say in its KDoc
   what M3 lacks (CLAUDE.md §5); the redesign needs none.
2. **Nothing on the workout screen moves with scroll.** No `scrollBehavior` on the app bar, no
   collapsing header, no scroll-linked state. The workout page does not scroll vertically at all.
   This is the design-level fix for #260.
3. **Write on every mutation.** A log, an edit, Add a set, Remove set each write to the DB on the
   tap. UI selection state (which page, which set is open in the sheet) goes through
   `SavedStateHandle`; nothing survives only in a ViewModel field.
4. **Identity lives in the theme.** Near-black surfaces, per-day earth accents, condensed tabular
   numerals, restrained copy. Colour means one thing: the day accent identifies the day and its one
   primary action; green is a completed thing only. No caps eyebrows (caps survive only as set
   notation R1 / TOP / B/O), no dot-joined meta strings, no "flavor text" that instructs.
5. **Rotation is 2–6 days.** Nothing assumes four. `DayAccentColors` cycles A–G.

## Phase 1 — Today and the workout (this handoff's build scope)

### Today

```
[ Settings (gear) ]                                   ← TopAppBar, title empty, one action
┌────┐  Day D
│ D  │  Full body                                      ← day index 112×128 (letter 104 sp) +
└────┘  Last trained 4 Aug, ten weeks ago.               text column beside it (headlineLarge,
        Your loads are where you left them.               then bodyLarge; the coach line is one
                                                          sentence of fact, or absent)
 1  Overhead press                       Goal 120     ← ListItem rows, HorizontalDivider between
 2  Bulgarian split squat               Goal 35 a hand
 …
 [      Start day D      ]                            ← Button, day accent container, above the bar
 [ Today ]  [ Log ]  [ Plan ]                          ← NavigationBar, 3 items
```

- The day index is `Surface(color = dayAccent, shape = RoundedCornerShape(8.dp))` holding one
  `Text` (the letter, display face, 104 sp). On-accent text follows the existing on-accent rule
  (ink over C, primary over the others). Sizes: 112×128 on Today and the receipt; 44×52 in lists;
  32×36 in the workout app bar.
- Coach line: states a fact (gap since last session, loads held) or is absent. Never advice.
- Rest day keeps the existing one-sentence rule (PR #257) in this layout: index, "Rest day", one
  sentence, no list, no Start button.
- Settings is the gear action on Today's app bar (not a bar destination). Log and Plan are
  unchanged in phase 1 except that they gain the NavigationBar (the bar is the Scaffold's
  bottomBar at the graph level; `saveState`/`restoreState`/`launchSingleTop` per "Navigation with
  Compose").
- The workout is its own destination; the bar is hidden there and on the receipt.

### Workout (variant B)

```
[ ← ] [D] Full body   2 of 21                 [ ⋮ ]    ← TopAppBar, no scrollBehavior
 ▬▬▬▬ ▬▬▬▬ ▬▬▬▬ ▬▬▬▬ ▬▬▬▬ ▬▬▬▬                            ← day segments, one per exercise
 Lift 1 of 6                 Next: Bulgarian split squat   ← caption; tap opens Exercises sheet
┌──────────────────────────────────────┐┌──
│ Overhead press         Goal 120      ││      ← Card, next card peeks (contentPadding
│                        Last 120 × 5  ││         start 20 / end 40, pageSpacing 12)
│ RAMP SET 3                            ││
│ 95 × 5                                ││      ← displayLarge ~88 sp, day accent text,
│ ▬▬ ▬▬ ▬▬ ▬▬ ▬▬ ▬▬                     ││         tabular numerals; tap opens the edit sheet
│ Set 3 of 6             110 × 3 next   ││      ← set segments + caption
│ [        Log 95 × 5        ]          ││      ← Button (day accent); FilledTonalButton while
│            All sets                   ││         a rest runs
└──────────────────────────────────────┘└──
 ( 1:30  −15 +15 skip )   [ Finish day D ]      ← rest pill only while resting; Button
```

States (see the mock frames):
- **performing** (`b-perform`): focus is the first unlogged set of the current page's exercise.
- **resting** (`b-rest`): after a log the focus advances; the rest pill appears beside Finish;
  the Log button is tonal until the rest ends or is skipped. The card never becomes a timer.
- **editing** (`b-edit`): tapping the big numeral, or a filled set segment, opens the revision-2
  `ModalBottomSheet`: title "Ramp set 3" / exercise, two steppers (IconButton −, OutlinedTextField
  numeric, IconButton +), plate line ("25 a side on the bar. Only this set changes." / for TOP:
  "Warm-ups and back-off follow this weight."), Done button, overflow menu with Remove set.
- **finished** (`b-done`): all segments green, the top set stays as the numeral (quiet colour),
  summary line "65×5, 85×5, 95×5, 110×3, 120×5, 90×8", one checked `Checkbox` at the right of the
  name, the primary Button names the next lift (calls `animateScrollToPage`).
- **later** (`six`): the run is a page like any other with an OutlinedButton "Start the run".
- **All sets**: `ModalBottomSheet` with the exercise's rows in the revision-2 row grammar
  (notation, value, standard `Checkbox`), tapping a value opens the edit sheet for that set;
  "Add a set" as the last row.
- **Exercises** (from the caption): `ModalBottomSheet` of `ListItem` rows, name + "3 of 6 saved",
  checked `Checkbox` trailing when done; tap jumps to that page. Browsing never logs or skips.
- Rep-only and timed sets read "6" with "reps" unit and "45 s"; no fabricated weight.
- Swap/alternates, timed holds, cardio, partial-session confirmation on Finish, the receipt:
  keep today's behaviour and copy; only the layout changes.

Notation: R1…Rn, TOP, B/O for ramped lifts (caps allowed here only); "Set 1…" for plain sets.
The big numeral's label reads "Ramp set 3", "Top set", "Back-off", "Set 2".

### Compose contract (phase 1)

| Element | Build from |
|---|---|
| Screens | `Scaffold` (edge-to-edge insets), `TopAppBar` with no scrollBehavior |
| Today | `Surface`+`Text` (index), `Column`, `ListItem`+`HorizontalDivider`, `Button`, `NavigationBar`+`NavigationBarItem` |
| Workout pager | `HorizontalPager`, `rememberPagerState(pageCount = { exercises.size })`, `key = { exercises[it].id }`, `beyondViewportPageCount = 1`, `contentPadding = PaddingValues(start = 20.dp, end = 40.dp)`, `pageSpacing = 12.dp`, `PagerDefaults.flingBehavior(state)` |
| Page | `Card` (filled), `Text` displayLarge with `fontFeatureSettings = "tnum"`, `Button` / `FilledTonalButton`, `TextButton` |
| Segments | `Row` of `Box(RoundedCornerShape)` per Google's "Pager in Compose" indicator sample; each inside a 48 dp tall `clickable` with a `contentDescription` naming the item and its state |
| Sheets | `ModalBottomSheet` (edit, All sets, Exercises), `IconButton`, `OutlinedTextField`, `DropdownMenu`, `LazyColumn` with `key`/`contentType` inside the list sheets |
| Rest pill | tonal `Surface` holding `Text` + `TextButton`s |
| Navigation | Navigation Compose, type-safe routes, bar with `saveState`/`restoreState` |

Guidance followed, by title: Pager in Compose; State and Jetpack Compose; Architecting your
Compose UI (UDF, `collectAsStateWithLifecycle`); Follow best practices (no backwards writes,
`derivedStateOf` where `currentPage` drives the caption, stable UI models); Save UI state in
Compose (`SavedStateHandle`); Material Design 3 in Compose (theme + `*Defaults`); Accessibility in
Compose (48 dp, `mergeDescendants`, `Role`); Navigation with Compose; Edge-to-edge.

State rules: the pager position is saveable on its own; the ViewModel also keeps the selected
exercise id in `SavedStateHandle` and reconciles it after Add/Remove set; with no valid selection
the page is the first unfinished exercise. The focused set is derived from the DB's done flags,
never stored. Rest stays deadline-anchored as today.

## Phase 2 (later handoffs): receipt, Log, Plan, Settings, wizard reveal, per `fable-mock.html`.

## Code map (filled from the inventory before each build)

Filled 12 Oct from a read of `main` at 805ac90.

| What | Where today | What the redesign does with it |
|---|---|---|
| Nav graph | `ui/AppNavHost.kt`, string routes in `object Routes` (`today`, `day`, `cardio/{dayId}`, `wizard`, `setup`, `log`, `backup`, `licenses`, `customExercise`) | PR 1 wraps the NavHost in a Scaffold with the NavigationBar (shown on today/log/plan), adds `plan`, renames `cardio/{dayId}` to `day/{dayId}`. Routes stay strings (type-safe routes deferred: one change at a time). |
| Today | `ui/today/{TodayScreen,TodayViewModel,TodayModels,TodayScreenBuilder}.kt` (440/165/57/134 lines): accent bar, caps overline, `dayLine` "DAY B · LOWER", rotation chips, LAST SESSION strip, StartBar | PR 1 rebuilds to index + text column + coach line + ListItem index + Button; drops overline/statLine/rotation/lastSession; adds `dayTitle`, `coachLine`. |
| Day letter | `ui/components/DayBadge.kt` (Log only) | PR 1 replaces with `DayIndex` (Surface + Text, three sizes). |
| Workout | `ui/day/DayScreen.kt` (1847): `DayTabStrip` (PrimaryScrollableTabRow), `MediumTopAppBar` with `exitUntilCollapsedScrollBehavior` (#259), LazyColumn of `ExerciseCard` with inline `SetRow` steppers (`ui/components/SetRow.kt`, `Stepper.kt`), `BottomBar` (bespoke RestPill, DoneButton, KeepScreenOnSwitch), overlays `CascadeScrim`/`SessionReceiptScrim` | PR 2 replaces the body with the pager (variant B); overlays, `DayEditSheet`, `SlotSwapSheet`, cardio execution, cascade and receipt stay. |
| Workout state | `DayUiState` (tabs, exercises: `ExerciseCardState` with rows `SetRowState`, rest, watch), `DayScreenBuilder.markNext` picks the next row; `collapsed`/`collapsedSummary` | PR 2: per-page `focusIndex`, `summaryLine`, `selectedExerciseId` in SavedStateHandle; collapse goes. |
| Writes | `DayViewModel.toggleDone/changeWeight/changeReps/changeSeconds/addSet/removeSet` write through the repo on the tap; rest timer via `PhoneRestTimer` + `PhoneRestRuntime` | Unchanged. |
| Theme | `ui/theme/{Color,Type,Shape,Theme}.kt`; `dayAccent(i)`/`onDayAccent(i)`/`accentEmphasis(i)` from `DayAccentColors` (domain SSOT); Barlow Condensed with `tnum`; `DisplayXl` tops the scale | PR 1 adds `DayIndexLetter`; PR 2 uses `DisplayXl` at 88 sp for the focus numeral. |
| Guardrail | `ui/components/MaterialComponentConventionTest.kt` scans Button/Card/Row/Toggle/Stepper/Divider/Hairline/Rule-suffixed composables for Box+background+border+click; allowlist `SelectionCard`, `SetRow`, `RemoveButton`, `EquipmentFilterRow`, `SheetButton` | No new entries; PR 2 removes `SetRow` from it when the file goes. |
| Tests | Robolectric + compose-ui-test under `app/src/test` (no androidTest in :app); `TodayScreenBuilderTest`, `TodayViewModelWiringTest`, `ChromeTouchTargetTest`, the `ui/day/*Test` set; CI runs `./gradlew build` | Each PR names the tests it changes and which new ones fail on the previous head. |
| Strings | `res/values/strings.xml` (caps labels, "%1$s · %2$d OF %3$d"); kind labels R1/TOP/B/O in `DayScreenBuilder.kindLabelsForKinds` | Sentence case everywhere touched; notation stays in Kotlin. |

Build specs per PR live in the session scratchpad and are quoted in each PR description.
