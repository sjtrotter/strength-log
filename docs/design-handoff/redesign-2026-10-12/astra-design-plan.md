## Revision, 12 Oct (after owner review)

- Kept Today's day index with the text column beside it, “Your place is here.” and “Last trained 4 Aug, 69 days ago.” Removed the warm-up instruction; all coach states now report a situation rather than direct the lifter.
- Kept Today / Log / Plan in the Material 3 bottom NavigationBar. Added its rationale and the screen-by-screen Compose contract below.
- Kept standard M3 Checkbox controls and the ModalBottomSheet editor. Removed the ACTUAL heading. Each exercise header now places Goal and, when known, Last in one right-aligned column.
- Replaced the permanent two-row rest dock with one fixed-height bottom bar: a temporary countdown pill at the left, its −15 s / +15 s / Skip controls inside, and Finish beside it. Saved-set counts move to the app bar. Idle has no pill or message; “Rest over” appears briefly at expiry.
- **Item 6: retain the flat ledger.** A single `AnimatedContent` within an exercise-keyed `LazyColumn` item can be inexpensive and does not inherently cause scroll jank. But replacing the tall body with one line still changes its measured height and moves following content after a tick; keys preserve identity, not screen coordinates. It therefore breaks this design's stationary-list contract, even without scroll-linked state or application measurement callbacks. Frame 10 shows the owner's alternative later in the workout, with one checked Checkbox at the right of each completed summary. It is a comparison, not the selected implementation. No compensating scroll or custom layout is proposed.
- Removed four-day assumptions: derive tabs, reveal rows, counts and next-day copy from the program; the generator accepts 2–6 days and the shared accent source cycles A–G. The pictured lifter still has four days.
- Named library components and plain compositions for every surface, including auxiliary routes. Removed the earlier custom-chart exception: Progress keeps the same measurements in standard labelled rows. Preserved the warm-paper theme, smoothness contract and the embedded Barlow font and licence byte for byte. The mock now contains ten dark 412 × 892 frames with component captions and no remote font request.

### Brief

Subject: strength.log, a native training companion whose plan survives an unreliable week.  
Person: a lifter around forty, training to stay strong and healthy.  
Job: make the next move clear, record it with one tick, and give the work an ending.  
Feeling: composure, including after a long absence.  
Voice: a quiet coach who states what is true and leaves the lifter in charge.

### Tokens

The visual idea is **the day index**: one oversized condensed letter on an earth-coloured rectangle, flush with the left edge of the content. It identifies the piece of training in your hands. It is large at the beginning and ending, small while doing the work, and filed beside the date in Log. Everything around it is ordinary, well-spaced type and rules. There are no textures, ornamental charts or new brand illustrations.

The table shows the neutral roles and this lifter’s A–D accents. The accent source is `DayAccentColors`, not this table: E is `#3C4E78`, F `#8B4356`, G `#6B6A2C`; use its `hex(index)`, `onAccentHex(index)` and `count` so accent cycling is independent of rotation length. `WizardAnswers` requires `daysPerWeek in 2..6`; the seven available accents do not make the wizard a seven-day generator.

| Family | Values | Job |
| --- | --- | --- |
| Ground | Background `#0D0D0F`, surface `#16161A`, rule `#2A2A30` | Canvas, temporary editing surfaces, structural rules |
| Ink | Primary `#F2F2F0`, secondary `#9A9AA2` | Text, control boundaries, selected checks, keyboard focus |
| Clay / A | `#C1440E` | Day A index and its session action |
| Forest / B | `#2D5A3D` | Day B index and its session action |
| Ochre / C | `#B8860B` | Day C index and its session action |
| Tide / D | `#1F4E5F` | Day D index and its session action |

Use the shared on-accent colour: primary Ink over A, B and D–G; Background over C. Raw day colours never carry small foreground text. A day colour means a day, not selected settings, editability or success. Checks use a light fill with a dark check; shape and spoken state carry completion. The existing error semantic remains available for destructive dialogs; it is not another decorative accent. Borders at `#2A2A30` divide content, while actual checkbox boundaries and focus rings use secondary Ink. Keep the existing warm-paper theme and System/Light/Dark preference: its `#F1EFEA` background, `#FAF9F6` surface, `#D8D4CC` rule, `#1B1B1E` primary text and `#5C5C64` secondary text map to the same roles. This deliverable depicts dark only.

| Type role | Face, weight, size / line in sp | Use |
| --- | --- | --- |
| Day index | Barlow Condensed 700, 116/116; compact 26/32 | The one expressive move; always accompanied by a spoken day name |
| Statement | Barlow Condensed 600, 38/40 | Full body, Workout saved, Your rotation |
| Heading | Barlow Condensed 600, 26/30 | Exercise and section names |
| Logged value | Barlow Condensed 600, 32/36 | Logged load; reps 26/30, same baseline |
| Body | Platform sans 400, 16/23 | Coach sentences, navigation, setting names |
| Reference | Platform sans 400, 14/20 | Goal, Last, dates, units, explanations, set kinds |
| Action | Platform sans 600, 16/20 | Buttons; never tracked capitals |

Use tabular numerals, sentence case and no added tracking. There is no ACTUAL label. The HTML uses its existing data-embedded OFL Barlow with the original font declarations and licence unchanged; the remote stylesheet link is removed so opening the artifact is offline too. Android keeps its bundled Barlow files.

Use a 4 dp rhythm, 20 dp screen gutters, 12 dp between related lines, 24 dp between sections, and 32 dp before a new subject. The 412 × 892 mock includes a 28 dp system status area and 24 dp gesture inset. Controls have at least 48 dp hit targets; primary actions are 56 dp high, with 8 dp corners. Set rows are 56 dp at ordinary font scale. Exercise boundaries are rules with 20 dp inset, not six rounded boxes; a temporary sheet alone uses the raised surface and 20 dp top corners. Home destinations share the standard 80 dp M3 NavigationBar for Today, Log and Plan, above the gesture inset. Workout and setup flows replace it with task controls. Large text may increase row heights once for that configuration, never as a consequence of scrolling, ticking, or editing a value. Longer names wrap; lower-priority content scrolls rather than shrinking text.

### Layouts

Read the supplied morning review first, then the current screenshots, spec §8, design handoff, direction review, strings and Compose builders/screens. The current screenshots supersede the review's older statement that only Today was photographed. Files 08 and 12 actually show Today; their filenames are not evidence of Setup or charts. Ignore the operating system's video notifications as app UI.

The owner’s review supersedes the morning prohibition on even exploring collapse: the flat ledger remains the chosen design, and frame 10 makes the requested fold available for comparison. Preserve recording and rotation semantics. The screenshot and seeded fourth ramp are 110 × 3; the supplied mock dataset is 110 × 5. The mock retains that requested logged value without changing the domain seeder.

The information architecture has three ordinary destinations: Today is the next session, Log is the record, Plan is the continuing rotation and its assumptions. Settings is reached from Today's labelled Settings action, and by a secondary link in Plan. It owns units, appearance, rest behaviour, Health Connect, backups and licences. Creating an exercise belongs to Plan and the in-workout exercise picker. Replacing the plan belongs to Plan and retains its destructive confirmation. Back always returns to the invoking destination; editing uses a sheet over its source. Starting a workout hides global navigation and leaves a labelled Today exit that preserves progress. No accounts or new data collection are introduced. Material 3’s “Navigation bar” guidance recommends a bottom bar for three to five top-level destinations on compact screens, so Today / Log / Plan follows current guidance rather than “old Android” thinking (documentation recalled from memory, not fetched).

**Today.** A `Row` keeps the large day index and the Full body text column side by side. “Your place is here.” acknowledges the return, followed only by “Last trained 4 Aug, 69 days ago.” Use that return heading when history exists and the gap is at least fourteen local dates; otherwise “Day D is next.” An active session takes priority with “Your workout is in progress.” These factual states derive from existing history and session state. They do not claim readiness or prescribe a training change. Six lift names and their counts follow. After lifting has its own compact row. Start day D stays above navigation. Plan owns the rotation overview.

```text
| Today                         Settings |
| +----------+                           |
| |          | Next in your rotation     |
| |    D     | Full body                 |
| |          | 6 exercises, 21 sets      |
| +----------+                           |
| Your place is here.                    |
| Last trained 4 Aug, 69 days ago.       |
| Overhead press (barbell)        6 sets |
| Bulgarian split squat          3 sets |
| Seated leg curl                3 sets |
| Pull-up / chin-up              3 sets |
| Cable lateral raise            3 sets |
| Plank / side plank             3 sets |
| After lifting                Intervals|
| Easy 5 min, then 4–6 rounds ...        |
| [            Start day D            ] |
|       Today         Log         Plan  |
```

**Workout, at rest.** A compact day index sits in a fixed app bar with Today, Full body, the saved-set count and the workout menu. The title slot is a `Row` of index and `Column(Text(title), Text(count))`. Fixed M3 day tabs retain arbitrary day selection; D is selected and its accessible label also says it is next. Use `PrimaryScrollableTabRow` + `Tab` on both Workout and Plan for the actual program days, including six. An override shows “Day D is next in your rotation” in the reserved header line; finishing follows the existing advance rule. Nothing responds to scroll offset.

The exercise header is `Row(Text(name, weight = 1f), Column(horizontalAlignment = Alignment.End))`; that column contains “Goal 120 lb” and immediately below it “Last 120 lb × 5”. Last is omitted when no prior set exists, never invented. Per-hand units wrap inside the same right-aligned column. Goal is a reference, never an input. Exercise options move to an `IconButton` beside the Add set `TextButton` below the rows. Two warm-ups are checked in frame 02; 95 × 5 is next. A themed `Surface` fill distinguishes the current row. Every set remains available in any order, with a standard M3 `Checkbox`. Finished rows stay in place with readable secondary text.

The bottom bar is a single `Surface` + `Row`, 72 dp above the gesture inset at ordinary font scale. At the left, a small rounded `Surface` contains a `Row` of condensed countdown `Text` and three `TextButton`s: −15 s, +15 s, Skip. Finish is an adjacent `FilledTonalButton`, becoming the day-coloured `Button` labelled Done once all sets are checked. At 412 dp the pill is 252 dp wide, the gap 8 dp and Finish 112 dp; the three text actions each retain a 48 dp target. At rest expiry only the pill content briefly reads “Rest over” for two seconds, then leaves; Skip removes it immediately. With no rest running there is no pill or idle line. Finish keeps its position and the bar keeps its height, so the ledger never resizes as the pill enters or leaves. Large-text configurations can use a taller pill with `Column(Text, Row(TextButton × 3))` beside Finish; choose that height from font configuration, never from running/idle state.

Rest starts from a checked set and continues while editing or visiting Today. The app bar shows “2 of 21 saved” (or “9 of 21 saved” later), never in a second dock row. At zero, Finish is disabled and its app-bar count says “No sets saved yet.” A partial finish uses an `AlertDialog` with the completed count. Done commits and advances to the program’s next day, A for this example. Keep screen awake, Clear today's ticks and Edit day D live in the workout menu.

```text
| < Today      [D] Full body          ...|
|                 2 of 21 saved         |
|             A     B     C    [D]       |
|             Next in your rotation     |
| Overhead press             Goal 120 lb|
| (barbell)             Last 120 lb × 5 |
| Warm-up 1          65 lb × 5       [x] |
| Warm-up 2          85 lb × 5       [x] |
| Warm-up 3          95 lb × 5       [ ] |
| Warm-up 4         110 lb × 5       [ ] |
| Top set           120 lb × 5       [ ] |
| Back-off           90 lb × 8       [ ] |
| Add set                           ... |
|---------------------------------------|
| Bulgarian split squat      Goal 35 lb |
|                            each hand  |
|                        Last 35 lb × 8 |
| Set 1              35 lb × 10      [ ] |
|=======================================|
| (1:29  −15 s  +15 s  Skip)   [Finish] |
```

**Finished exercise comparison, frame 10.** Later in the same workout, overhead press and split squat account for 9 of 21 saved sets. The alternative replaces each finished exercise with a single summary: exercise name, “6 sets” or “3 sets”, and ONE checked `Checkbox` at the right, aligned with the set checkbox column. There is no leading dot. A `TextButton` for the summary opens its sets for inspection; the aggregate `Checkbox(checked = true, onCheckedChange = null)` reports completion and does not clear six sets. Individual changes still use their own checkbox or editing sheet. If selected later, use one keyed `LazyColumn` item per exercise, a `Column` body and one `AnimatedContent(targetState = collapsed)` inside that item; stable exercise `contentType`, no `animateItem`, scroll listener, measurement callback or compensating scroll. This is ordinary Compose, and can be smooth in frame-time terms, but either the default size transition or an immediate size replacement moves following rows. Reserving the full old height would erase the fold’s space benefit. The selected flat list preserves the stronger stationary geometry promise.

**Workout, editing.** Tap the value pair once to open an M3 ModalBottomSheet already containing weight and reps steppers. The underlying row receives a selection outline without changing height. The sheet is one set's editor, with no competing finish action. Changes persist immediately; Back to workout dismisses rather than pretending to save. Each stepper combines M3 IconButtons and a numeric OutlinedTextField; tapping the number supports direct input with the existing validation and unit rounding. A TOP weight change updates the existing warm-up and back-off values together; reps, work/extra rows and partner tracks keep their current values. Explain that relationship only here: “Warm-ups and back-off follow this weight.” Announce the update once to accessibility, without staggered flashes. For an ordinary set the helper reads “Only this set changes.” Plates stay available in this sheet, not in inserted helper rows. Remove set is in the sheet's More menu, with Undo and the existing minimum-one-set rule. Add set is the quiet 48 dp row at the end of each exercise, not a dashed rectangle.

```text
| < Today      [D] Full body          ...|
|             A     B     C    [D]      |
| Overhead press (barbell)              |
| Warm-up 1          65 lb x 5       [x]|
| Warm-up 2          85 lb x 5       [x]|
| Warm-up 3          95 lb x 5       [ ]|
| Warm-up 4         110 lb x 5       [ ]|
| [Top set          120 lb x 5]      [ ]|
|---------------------------------------|
| Edit top set             More  Close  |
| Overhead press (barbell)              |
| Weight (lb)             Reps          |
| [-] 120 [+]             [-] 5 [+]      |
| Warm-ups and back-off follow this     |
| weight.                               |
| 35 lb + 2.5 lb on each side            |
| [          Back to workout           ] |
```

**How this stays smooth.** Use a fixed M3 TopAppBar with no scrollBehavior or nestedScroll connection, a fixed tab/header area, one ordinary LazyColumn, and a fixed single-row bottom bar. Flatten exercise headers, set rows and Add set rows into items keyed by immutable exercise-instance ID plus persisted set ID, with distinct contentType values. Use fixed minimum geometry for each content type at the chosen font scale. Do not key by index, label, tick state, values or totals. Hoist set state so eviction cannot recreate it. No animateContentSize, AnimatedVisibility size transitions, animateItem placement, collapsing cards, pinned lazy headers, parallax, scroll-linked alpha, per-frame geometry collection, or completion-triggered auto-scroll. Keep the selected sheet outside list measurement, with fixed body insets and adjust-nothing behaviour behind its keyboard. Ticking, undoing a tick, receiving timer updates and cascading numbers only redraw the affected content; the list does not change shape. Isolate timer recomposition to the pill; its absence never changes the bottom bar’s height. Only explicit structural edits such as Add set, Remove set or Swap can change list contents, immediately and without item animation; retain the first visible item by stable key and offset. Changing days restores that day's list position. The cost is more manual scrolling, an intentional trade for a physically steady instrument. Superset rounds remain grouped, with both tracks visible and one checkbox per round; rep-only and timed sets use the same row with the irrelevant weight column absent. Frame 09 demonstrates rep-only and timed rows; superset rounds use a Column of the two labelled tracks within the same row and a single trailing Checkbox.

**Receipt.** Commit first, then replace the entire workout with an opaque receipt. The next workout never shows through a scrim. The same large D index now accompanies Workout saved and Full body. “21 sets recorded. That counts.” recognizes ordinary work. A compact six-line exercise record answers what was saved, followed by the actual top set with its exercise name, not an unqualified Strongest trophy. A plain next-day line says Day A, full body. Add a note and Share sit above Back to Today. A partial session says Workout saved and “9 of 21 sets recorded. That counts.”; it never says complete. Do not invent a rotation number or declare a completed rotation solely because D finished: overrides and sparse historical data make that unreliable. This release makes the day a complete unit; a future rotation-complete claim requires verified coverage of the same program version.

```text
| strength.log                          |
| +----------+                          |
| |    D     | Workout                  |
| |          | saved.                   |
| +----------+ Full body                |
| 12 Oct 2026                           |
| 21 sets recorded. That counts.        |
| Overhead press (barbell)       6 sets |
| Bulgarian split squat         3 sets |
| Seated leg curl               3 sets |
| Pull-up / chin-up             3 sets |
| Cable lateral raise           3 sets |
| Plank / side plank            3 sets |
| Top set                              |
| Overhead press (barbell) 120 lb x 5   |
| Next time                            |
| [A] Day A, full body                  |
| Add a note                     Share |
| [           Back to Today           ] |
```

**Log.** Open on Sessions, not charts. M3 tabs give Sessions and Progress distinct jobs. Session rows have an earth-coloured day index, the date as the primary text, Full body underneath, and a readable set count. These rows open a session document with the same exercise list as the receipt, with Share, Note, Edit recorded sets and Delete session in their proper scope. Show the real pre-workout records: C on 4 Aug with 21 sets, B on 28 Jul with 24, A on 17 Jul with 21. Bodyweight 230 lb lives inside details. No fabricated October workouts or trend. A sentence below the records reads “The next workout is still day D.” Calendar moves behind Browse by month; it is a record finder, never a scheduler. Progress contains the existing per-lift trajectories and volume with explicit measured labels, goal references and sparse-data states. To meet the no-bespoke-code decision, present those existing trajectory and volume measurements as dated ListItems and Row/Column/Text tables; use a labelled LinearProgressIndicator only for a defined goal fraction, never a custom chart plot. Health Connect and bodyweight-update administration move to Settings and Plan respectively; imported workouts remain available under Sessions with their source labels.

```text
| Log                                  |
| [Sessions]                Progress    |
| Your training, kept.                 |
| 2026                                 |
| [C] 4 Aug                  21 sets  > |
|     Full body                        |
| [B] 28 Jul                 24 sets  > |
|     Full body                        |
| [A] 17 Jul                 21 sets  > |
|     Full body                        |
| Browse by month                    > |
|                                     |
| The next workout is still day D.     |
|       Today         Log         Plan |
```

**Setup / Settings and Plan.** The old Setup becomes two readable summaries, not a form spread over a longer scroll. Plan begins Your rotation, the continuity sentence and a `PrimaryScrollableTabRow` of `n = program.days.size` tabs; selecting D shows the six real exercises and Edit day D. Your goals is a ruled list with Squat 230 lb, Bench press 190 lb, Deadlift 305 lb, Overhead press 120 lb. It says “Goals are a reference. Sets are your record.” Below it, Training details summarizes bodyweight 230 lb, age 39, intermediate experience, balanced strength and muscle, and cardio. Tap a detail to edit it; the live goal preview stays beside those edits and no action reseeds the living log. Exercise library and Make a new plan follow. Settings uses conventional M3 ListItems: Units/Pounds, Appearance/Match phone, Rest timers/Phone and watch, Keep screen awake/Off, Health Connect/Not connected, Backup and restore, About strength.log. Only preferences confirmed in the screenshots get concrete values; backup has “Check backup settings,” not an invented successful backup.

Backup opens on its actual last result or “Choose a backup folder.” It offers Back up everything, Restore a backup, Export workout history and Import workout history. JSON and CSV remain supporting descriptions. Restore previews scope and confirms replacement before mutation; CSV keeps its matching preview and conflict decisions. Rebuilding a plan keeps its separate confirmation and history guarantee. Those functions are relocated, not removed. Warm-paper appearance stays a first-class choice.

```text
| < Today                    Settings  |
| Make it yours.                       |
| Units                       Pounds > |
| Appearance             Match phone > |
| Rest timers        Phone and watch > |
| Keep screen awake              [off] |
|                                      |
| Your data                            |
| Health Connect        Not connected >|
| Backup and restore                   |
| Check backup settings              > |
|                                      |
| About strength.log                 > |
| Training changes live in Plan.       |
| [              Open Plan           ] |
```

**Wizard, final step.** Use the index vocabulary to reveal `n = generatedProgram.days.size` ordered rows in a keyed LazyColumn. Render every generated day, normally 2–6; four is only this lifter’s example. The headline is Your rotation; the promise is “Miss a day. Keep your place.” Each row uses its actual day title (Full body in this example). The first says Start here; the last says Then back to A, deriving A from the first day’s ID. Counts and any “four days” wording come from `n` with plural resources. Neither D nor four is an end-of-rotation condition. The goals are reference rows with units, followed by the factual “Your place stays saved.” The final action is Use this plan, which saves the generated program and opens Today without starting or logging a workout. Back stays available to change answers. A remake returns through the replacement confirmation before commit; it does not silently replace a plan. Earlier steps keep defaults, accept a recommendation first, and place specialist choices behind Change. The mock depicts the same four-day, 230/190/305/120 goal basis; it does not claim the already-customized Day A exercise is necessarily a squat.

```text
| Back                         8 of 8  |
| Your rotation                        |
| Miss a day. Keep your place.          |
| [A] Full body             Start here |
| [B] Full body                        |
| [C] Full body                        |
| [D] Full body         Then back to A |
| Your goals                       lb  |
| Squat                           230  |
| Bench press                     190  |
| Deadlift                        305  |
| Overhead press                  120  |
| Goals are a reference.               |
| Sets are your record.                |
| Your place stays saved.              |
| [             Use this plan        ] |
```

### Compose contract

The component inventory below is the implementation boundary: use Material 3 public APIs and plain Compose layout primitives. A named layout is a function composing these pieces, not a new widget, custom drawing routine, gesture recognizer or measurement policy. HTML simulates these controls for review; its CSS/JavaScript is not Android implementation code. This follows `CLAUDE.md` design principle 5; the owner’s stricter no-bespoke-code instruction also removes the earlier chart exception.

| Screen / state | Material 3 and Compose APIs; exact compositions |
| --- | --- |
| Shared shell | `Scaffold`, fixed `TopAppBar(scrollBehavior = null)`, `SnackbarHost` / `Snackbar`. Today / Log / Plan use `NavigationBar` + three `NavigationBarItem`s, each with a library `Icon` and `Text`. No `nestedScroll` connection. |
| Today, frame 01 | `LazyColumn` with `key` and `contentType`; hero `Row(Surface(Text(day)), Column(Text(status), Text(title), Text(count)))`; coach `Column(Text, Text)`; exercise summary `Row(Text, Text)` + `HorizontalDivider`; cardio `ListItem`; start `Button`; Settings `TextButton`; shared navigation. |
| Workout ledger, frames 02 and 09 | Fixed `TopAppBar` title `Row(Surface(Text(day)), Column(Text(title), Text(savedCount)))`, menu `IconButton` + `DropdownMenu` / `DropdownMenuItem`; `PrimaryScrollableTabRow` + `Tab`; status `Text`; flat `LazyColumn` of keyed header / set / add-set items with distinct `contentType`. Header `Row(Text(name), Column(Text(goal), Text(last)))`. Set `Surface` + `Row(Text(kind), TextButton { Row(Text(load), Text(unit), Text(reps)) }, Checkbox)` + `HorizontalDivider`. Rep-only or timed rows omit weight. Supersets put two labelled value Rows in a Column. Add-set item `Row(TextButton, IconButton)`. |
| Workout bottom bar, all workout frames | `Surface` + `Row`; running pill `Surface(shape = MaterialTheme.shapes.extraLarge)` + `Row(Text(countdown), TextButton, TextButton, TextButton)`; `FilledTonalButton` Finish / `Button` Done. Idle leaves a weighted `Spacer`, no placeholder text. Expiry uses `Text("Rest over")` inside the same pill for two seconds. No draining custom hairline. Partial finish / clear checks use `AlertDialog`; failed persistence uses `Snackbar`. |
| Set editor, frame 03 | `ModalBottomSheet` with standard scrim, sheet state and drag handle. `Column` contains title/exercise `Text`, close `TextButton`, more `IconButton` + `DropdownMenu`, and two stepper Columns. Each stepper is `Row(IconButton, OutlinedTextField, IconButton)` with numeric `KeyboardOptions`, unit label, error text and domain validation. Helper and plates are `Text`; dismiss action `Button`. `Snackbar` offers Undo after removal. The list has no inline text fields. |
| Receipt, frame 04 | Opaque `Scaffold`; hero reuses `Row(Surface(Text(day)), Column(Text(title), Text(dayTitle)))`. Keyed `LazyColumn`, summary `Row(Text, Text)` + `HorizontalDivider`, date and acknowledgement `Text`, next-day `ListItem` with `Surface(Text(day))`, note/share `TextButton`s and return `Button`. Note uses `ModalBottomSheet` + `OutlinedTextField`; system share uses the existing platform flow. Whole receipt body may use `animateFloatAsState` alpha for the one post-commit fade. |
| Log / session details, frame 05 | `PrimaryTabRow` + `Tab` for Sessions / Progress, keyed `LazyColumn`, session `ListItem` with leading `Surface(Text(day))`, supporting title `Text`, trailing count `Text` + `Icon`; `HorizontalDivider`, navigation. Month browsing uses `DatePicker` in `DatePickerDialog`. Details reuse receipt rows; `ModalBottomSheet` edits a recorded set/note; `DropdownMenu` and `AlertDialog` handle delete. Progress uses measured date/value `ListItem`s or `Row`/`Column`/`Text`; optional labelled `LinearProgressIndicator(progress = { fraction })` for a real goal, no new chart renderer. |
| Settings, frame 06 | `Scaffold`, `TopAppBar`, keyed `LazyColumn`, `ListItem`, `HorizontalDivider`, `Switch`, `TextButton`, `OutlinedButton`. Units/appearance use `AlertDialog` + `RadioButton` rows; timer detail uses `ListItem` + `Switch` and validated `OutlinedTextField`. Backup / import previews use `ListItem`s, `Button`s, `LinearProgressIndicator` for actual work, `AlertDialog` for replacement, `Snackbar` for results. Existing system folder/file pickers and Health Connect permissions stay platform flows. About/licences are `Text` in a scrollable `Column`. |
| Plan, frame 07 | Shared `Scaffold` / navigation; `PrimaryScrollableTabRow` + `Tab` over program days; keyed `LazyColumn`; day/exercise `ListItem`s or compact `Row(Text, Text)` + `HorizontalDivider`; goals `Column(Row(Text, Text))`; edit `TextButton`; destination `ListItem`s. Training details use `ModalBottomSheet`, `OutlinedTextField`, `RadioButton` / `FilterChip` choices and a live goal `Column(Text)`; exercise picker uses searchable `OutlinedTextField` + keyed `ListItem`s; creating an exercise uses `OutlinedTextField`s and `Button`. Replace plan uses `AlertDialog`. |
| Wizard final, frame 08 | `Scaffold`, fixed `TopAppBar`, Back `TextButton`, step `Text`; keyed `LazyColumn` of `n` day rows, each `Row(Surface(Text(day)), Text(actualTitle), Text(sequenceHint))` + `HorizontalDivider`; goal `Row(Text, Text)` items; factual closing `Text`; `Button` Use this plan. Earlier steps use `RadioButton`, `FilterChip`, `Checkbox`, `OutlinedTextField`, `LinearProgressIndicator` for step progress, and `TextButton` / `Button` Back/Next. No fixed four-row reveal. |
| Fold comparison only, frame 10 | Same workout shell and pill. One `LazyColumn` item keyed by exercise-instance ID with `contentType = "exercise"`, containing one `AnimatedContent` around expanded `Column` versus summary `Row(TextButton { Row(Text(name), Text(count)) }, Checkbox(checked = true, onCheckedChange = null))`. That aggregate check is read-only. The summary action reveals individual sets; it never bulk-clears the exercise. Not included in the selected flat implementation. |

**State and ownership.** Google’s “State and Jetpack Compose”, “Where to hoist state” and “Unidirectional data flow” guidance: the route obtains a `ViewModel`, collects its `StateFlow` with `collectAsStateWithLifecycle`, and passes immutable UI state plus event callbacks down. Persist ticks and valid edits immediately through existing domain/repository operations. Draft text and selected set ID survive recreation via `SavedStateHandle` where needed; ephemeral sheet presentation can use `rememberSaveable`. Do not keep a second source of truth in a lazy item. Existing builders own counts, next-day rules, unit formatting and goal/cascade calculations. Hoisting presentation state does not authorize reseeding the program.

**Lazy identity.** “Lists and grids”: provide `key` and `contentType` on every heterogeneous `LazyColumn` item. Use exercise-instance ID + persisted set ID, plus stable suffixes for header and Add set. Never use position, weight, completed count or checked state as identity. Use saveable primitive keys; keep `rememberLazyListState` per day and restore it on deliberate day changes. Headers and set rows remain separate lazy items in the selected design; the exercise-sized comparison item is explicitly a different tradeoff. Font-scale changes can increase minimum geometry; ticking and new numeric values cannot.

**Performance.** “Follow best practices”, “Stability in Compose” and “Strong skipping”: defer state reads to the smallest consumer; collect/read countdown state inside the pill rather than rebuilding the entire workout every second. Use lambda modifiers only when they move a needed read to layout/draw; there is no scrolling effect to drive here. Use `derivedStateOf` only when frequent input changes produce a less-frequent Boolean result, such as a locally derived eligibility state; do not wrap each formatted number or duplicate ViewModel-derived counts. Never write state after reading it in the same composition (no backwards writes). Use immutable, stable UI models and stable callbacks, retain strong skipping where supported by the compiler, and do not mark mutable models `@Stable` merely to silence diagnostics. No `onGloballyPositioned`/`onSizeChanged` geometry loop, `animateContentSize`, lazy placement animation, pinned headers, or reads of scroll position for styling.

**Effects and timer.** “Side-effects in Compose”: `LaunchedEffect(restId, phase)` handles the brief expiry presentation and one-shot announcements; `rememberUpdatedState` supplies the latest callback to an ongoing effect without restarting it on recomposition. Time comes from the existing rest deadline, not a decremented counter in a composable. Store/persist timer state according to existing session rules; on return, derive remaining time from that deadline. Use a rest ID so an old expiry cannot hide a newly started rest. `LaunchedEffect` keyed to the successful commit ID can trigger navigation/receipt feedback once. No persistence, navigation, coroutine launch or state write in the composable body. The implementation and review mock both remove the pill at idle; the mock only simulates time, never writes a session.

**Theme and numerals.** “Material Design 3 in Compose”, “Typography” and “Style text”: express the tokens through `MaterialTheme.colorScheme`, `typography` and `shapes`, with `TopAppBarDefaults`, `NavigationBarItemDefaults`, `TabRowDefaults`, `CheckboxDefaults`, `ButtonDefaults`, `OutlinedTextFieldDefaults` and other component `*Defaults`. Preserve their built-in interaction/state layers and semantics. Read day fill/on-fill from `DayAccentColors`; keep the entire warm-paper colour scheme and System/Light/Dark preference. **FontFeatureSettings** means the OpenType setting here: Compose exposes `TextStyle(fontFeatureSettings = "tnum")`, not a new custom numeral component. Apply it to loads, reps, counts, dates and countdowns with bundled Barlow for the condensed roles.

**Navigation.** “Navigation with Compose”, “Type safety in Kotlin DSL and Navigation Compose” and “Save and manage navigation state”: use `@Serializable` type-safe routes for Today, Log, Plan, workout/day, receipt and settings; pass IDs, not whole session objects. Bottom-bar navigation uses `launchSingleTop = true`, `popUpTo(navController.graph.findStartDestination().id) { saveState = true }`, and `restoreState = true`. Derive selection from the current destination hierarchy; keep each destination’s scroll/selection state. Workout and wizard replace the global bar with their task controls. Today exit preserves the current session.

**Accessibility.** “Accessibility in Compose”, “Semantics” and “Minimum touch target sizes”: keep every actionable target at least 48 dp, including ±15 s, small menus and steppers. Give icon-only actions a contextual `contentDescription`; use `null` for decorative icons whose adjacent text already names them. A set row uses merged semantics for its kind/value description; its two separate actions remain independently reachable: value `TextButton` opens the sheet, standard `Checkbox` toggles completion. Do not turn the entire row into a toggle as well as an editor. For a label+checkbox-only setting or aggregate choice, `Modifier.toggleable(role = Role.Checkbox)` owns the action and the child `Checkbox(onCheckedChange = null)` avoids duplicate targets. Announce saved state and a TOP cascade once, never every countdown second. The read-only folded check is grouped into its summary’s “completed, 6 sets” semantics, not announced as a bulk-clear action. Keep text legible and allow wrap/scroll at large font scale.

**Insets.** “Set up edge-to-edge” and “Window insets in Compose”: use the activity’s existing `enableEdgeToEdge`, `Scaffold` content `WindowInsets`, component insets and the supplied `innerPadding` with `consumeWindowInsets`. Apply status/navigation safe areas exactly once. No hard-coded native 28/24 dp insets: those numbers only describe the mock’s system furniture. Keep the sheet outside lazy measurement; let `ModalBottomSheet` handle IME insets in its own window, without attaching `imePadding` to the ledger or resizing its body behind the keyboard. Verify that contract against the project’s sheet version.

**Previews and acceptance.** “Preview your UI with composable previews”: use `@PreviewLightDark` and `PreviewParameter` data for 2-, 4- and 6-day rotations, empty/history/returning Today, zero/partial/all sets, idle/running/expired rest, rep-only/timed/superset rows, long names and large fonts. The HTML remains dark only. Inspect TalkBack traversal and 48 dp targets. On the device, record the first visible stable key/offset before and after tick, TOP cascade, timer change and sheet dismissal; no scroll offset should drive UI state. Native performance acceptance remains future implementation work, not a claim made by this mock.

All Google documentation titles in this section are cited from memory, including the Material 3 “Navigation bar” and “Tabs” guidance; no network lookup or claim of checking the latest library release was made. API names describe the intended library implementation, not a claim that the current repository already uses them.

### Copy

The following is the complete copy contract for the pictured surfaces, including Plan, accessory and fold-comparison frames and the states described above. Quoted old copy is from strings.xml unless marked builder, catalog or screenshot. “New” means no existing line; “removed” means its information is supplied elsewhere, not merely hidden under a synonym. Numbers, dates, units and counts remain data substitutions. Sentence case is deliberate even where the old resource contains capital letters.

| Old | New |
| --- | --- |
| “TODAY”; “LOG”; “SETUP” | “Today”; “Log”; “Settings” |
| “Settings”; “Back” | “Settings”; “Back” (retained); workout return is “Today” |
| New navigation destination | “Plan” |
| “NEXT IN ROTATION” (builder) | “Next in your rotation” |
| “IN PROGRESS” (builder) | “Workout in progress” |
| “READY TO FINISH” (builder) | “Ready to finish” |
| “DAY D · FULL BODY” (builder/screenshot) | Day index “D”; title “Full body”; spoken “Day D, full body” |
| “6 LIFTS · 21 SETS” (builder) | “6 exercises, 21 sets” |
| “incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads” (program) | Removed from these surfaces; actual exercise names communicate the plan more accurately after substitutions |
| “LAST SESSION 69 DAYS AGO · 0 THIS MONTH” (plural resource) | “Your place is here.” / “Last trained 4 Aug, 69 days ago.”; the warm-up instruction is removed |
| “LAST SESSION”; “Aug 4, 2026 · 21 sets · Conventional Deadlift 185” (builder) | Removed from Today; the record is available in Log as “4 Aug”, “Full body”, “21 sets”; the deadlift stays in session details |
| New active/ordinary coach states | “Your workout is in progress.” / “Day D is next.” (derive the day) |
| “START DAY D”; “CONTINUE — 9 OF 21 SETS”; “FINISH DAY D” | “Start day D”; “Continue day D” with “9 of 21 sets saved” in the summary; “Finish day D” |
| “Overhead Press (Barbell)” (catalog) | “Overhead press (barbell)” |
| “Bulgarian Split Squat” (catalog) | “Bulgarian split squat” |
| “Seated Leg Curl” (catalog) | “Seated leg curl” |
| “Pull-Up / Chin-Up” (catalog) | “Pull-up / chin-up” |
| “Cable Lateral Raise” (catalog) | “Cable lateral raise” |
| “Plank / Side Plank” (catalog) | “Plank / side plank” |
| “6 sets”; “3 sets” | Retained; use correct singular “1 set” |
| “CARDIO FINISHER”; “HARD · Cardio — intervals” | “After lifting”; “Intervals” |
| “Lift first, then run. 5 min easy, then 4–6 × 2 min hard / 2 min easy.” | “Easy 5 min, then 4–6 rounds: 2 min hard, 2 min easy.” |
| “START”; “STOP” (cardio) | “Start intervals”; “Stop intervals” |
| “ELAPSED · %1$s”; “%1$s · %2$s left”; “OVERRUN”; “LOGGED · %1$s” | “Elapsed %1$s”; “%1$s, %2$s left”; “Time complete”; “Saved %1$s” |
| “ROTATION” | “Your rotation” in Plan; duplicate Today strip removed |
| “Day %1$s” | “Day %1$s” retained as spoken tab/index name; visible tabs use the program’s n day IDs (A–D in this example) |
| “OVERRIDE · SUGGESTED NEXT: DAY D” | “Day D is next in your rotation.” |
| “Edit day” | “Edit day D” with actual viewed letter |
| New icon descriptions | “Workout options”; “Exercise options”; “More set options” |
| “DAY D · 2 OF 21” | “Full body” beside index D; count moves to “2 of 21 saved” in the app bar |
| “MAIN”; “+1 WARM-UP” | Remove badges; preserve the program’s warm-up hint in exercise options as “Warm-up suggested” |
| “GOAL”; new actual-column label | “Goal 120 lb” at the right; ACTUAL label removed |
| “/hand” | “each hand”: “Goal 35 lb each hand” |
| “First log” (builder) | “No earlier sets” in exercise history, not a persistent row heading |
| “Last time: 35×8” | “Last 35 lb × 8”, directly under Goal and right-aligned |
| “Best: %1$s” | “Heaviest logged: %1$s” in exercise history; no standing trophy label |
| “R1”, “R2”, “R3”, “R4”, “TOP”, “B/O”, “1” (builder) | “Warm-up 1”, “Warm-up 2”, “Warm-up 3”, “Warm-up 4”, “Top set”, “Back-off”, “Set 1” |
| Numeric set expressions | “65 lb × 5”, “85 lb × 5”, “95 lb × 5”, “110 lb × 5”, “120 lb × 5”, “90 lb × 8”, “35 lb × 10”, “90 lb × 10”, “6 reps”, “15 lb × 10”, “45 s”; units follow the setting |
| “GOAL 6 reps”; “GOAL 45s” (builder) | “Goal 6 reps”; “Goal 45 s” |
| “+ ADD WEIGHT”; “− REMOVE WEIGHT” | “Add weight”; “Remove added weight” in set/exercise options |
| “+ ADD SET”; “+ ADD ROUND” | “Add set”; “Add round” |
| “SET REMOVED”; “UNDO”; “Undo remove set” | “Set removed”; “Undo”; “Restore removed set” |
| “Expand”; “Collapse”; expanded/collapsed state labels | Removed from the selected flat workout; comparison summary action is “View completed sets” |
| New edit sheet | “Edit top set” or “Edit warm-up 3” / “Edit set 1”; “Close”; “Weight (lb)”; “Reps”; “Hold (seconds)”; “More”; “Back to workout” |
| “Change the TOP set — ramp & back-off recalculate.” | “Warm-ups and back-off follow this weight.” (only in TOP editor) |
| New ordinary-set helper | “Only this set changes.” |
| “Plates: %1$s a side”; “Plates: empty bar” | “35 lb + 2.5 lb on each side”; “Empty bar” (derived, sheet only) |
| “NEW RAMP” | “Warm-ups and back-off updated.” (single accessibility announcement) |
| “Decrease weight”; “Increase weight”; “Decrease reps”; “Increase reps”; “Decrease hold”; “Increase hold” | Retain as descriptions, append exercise and set context; visible controls are “−” and “+” |
| New row accessibility | “Edit overhead press, warm-up 3, 95 pounds, 5 reps”; checkbox “Mark overhead press warm-up 3 done”; checked state “Done”, action “Mark not done” |
| “REST”; “−15”; “+15”; “SKIP”; “REST OVER” | “Rest”; “−15 s”; “+15 s”; “Skip”; “Rest over” (two seconds at expiry, then pill leaves) |
| Morning rest idle line | Removed; no pill or idle message |
| New fold comparison | “6 sets” / “3 sets”; spoken “Overhead press, completed, 6 sets”; action “View completed sets”, then “Hide completed sets” once expanded |
| New exercise reference | “Last 120 lb × 5” when that prior set exists |
| “NOTHING LOGGED”; “FINISH · 2 OF 21 SETS”; “DONE — ADVANCE TO DAY A” | “No sets saved yet”; “2 of 21 saved” in app bar plus “Finish”; “Done” with “Day A is next” in the same reserved status area |
| “Finish with %1$d of %2$d sets?” | “Finish with %1$d of %2$d sets?” (retained) |
| “The day is recorded as it stands and the rotation moves on.” | “Your checked sets will be saved. Day A will be next.” |
| “FINISH”; “KEEP GOING” | “Finish”; “Keep going” |
| “KEEP ON”; “Clear today's checkmarks” | “Keep screen awake”; “Clear today's ticks” |
| “DAY D COMPLETE”; “DAY D ENDED · 9 OF 21” (builder) | “Workout saved.” in both; count distinguishes completion |
| “SETS”; “21” / partial count | “21 sets recorded. That counts.” / “9 of 21 sets recorded. That counts.” |
| “STRONGEST”; “120×5” | “Top set”; “Overhead press (barbell)”; “120 lb × 5” (select the recorded main TOP, not the heaviest arbitrary exercise) |
| “NEXT”; “DAY A · FULL BODY” | “Next time”; “Day A, full body” |
| “ADD A NOTE”; “NOTE”; note “DONE” | “Add a note”; “Note”; “Back to receipt” |
| “SHARE”; “BACK TO TODAY” | “Share”; “Back to Today” |
| New receipt identity/date | “strength.log”; “12 Oct 2026” (completion date, not a scheduled date) |
| New Log title/tabs | “Your training, kept.”; “Sessions”; “Progress” |
| “Aug 4, 2026”; “Jul 28, 2026”; “Jul 17, 2026” (builder) | Group “2026”, then “4 Aug”, “28 Jul”, “17 Jul”; year stays in accessibility labels |
| “21 sets”; “24 sets”; “Full Body” | “21 sets”; “24 sets”; “Full body” |
| “BW 230” | “Bodyweight 230 lb” inside session details |
| “CALENDAR” | “Browse by month” |
| “TRAJECTORY”; “VOLUME” | “Your main lifts”; “Work recorded” inside Progress, followed by explicit lift, load and weekly-set labels |
| New Log continuation | “The next workout is still day D.” (derive from saved pointer) |
| “FROM OTHER APPS”; “EDIT”; edit “DONE” | “From other apps”; “Edit recorded sets”; “Finish editing” |
| “DELETE SESSION”; “Delete this session?”; “Its sets leave the log, the trajectories and Health Connect.” | “Delete session”; “Delete this session?”; “This removes the session from your record, progress and Health Connect.” |
| “DELETE”; “KEEP”; “SESSION DELETED”; “UNDO” | “Delete”; “Keep session”; “Session deleted”; “Undo” |
| New Settings statement | “Make it yours.” |
| “DISPLAY”; “Display weights in kilograms” | Remove group caption; row “Units” with “Pounds” or “Kilograms” |
| “Theme”; “System”; “Match this phone.”; “Dark”; “Light”; “Warm paper and ink.” | “Appearance”; “Match phone”; “Match this phone.”; “Dark”; “Light”; “Warm paper and ink.” |
| “REST TIMER”; “Rest timer on watch”; “Rest timer on phone” | “Rest timers”; “Watch”; “Phone”; summary “Phone and watch” when both are on |
| “Bodyweight · timed”; “RESET DEFAULTS” | “Bodyweight and holds”; “Reset rest times” |
| “DATA” | “Your data” |
| “Connect Health Connect”; “Connected · publishing” | “Health Connect”; “Sharing workouts”; disconnected value “Not connected” |
| “Share your workouts and see sessions logged by other apps. On-device only — nothing leaves your phone.” | “Share workouts with other apps on this phone.” in Health Connect details; permissions explained before connecting |
| “DATA / BACKUP” | “Backup and restore”; supporting “Check backup settings” when status has not been fetched |
| “ABOUT”; “OSS LICENSES” | “About strength.log”; “Open-source licences” inside it |
| New Plan link | “Training changes live in Plan.”; “Open Plan” |
| “TRAINING” | “Training details” in Plan |
| “YOUR MAIN-LIFT GOALS” | “Your goals” with “lb” or “kg” beside the heading |
| “Barbell Back Squat”; “Barbell Bench Press”; “Conventional Deadlift”; “Overhead Press (Barbell)” | “Squat”; “Bench press”; “Deadlift”; “Overhead press” in the main-goal list; keep full names in exercise details |
| Goal values “230”; “190”; “305”; “120” | Retained, with column unit “lb” and each accessible value announcing pounds |
| “Rotation, not calendar — days advance when you finish them, not on a schedule. The weights you log are your living record; GOALs are a reference, not a rule.” | “Miss a day. Keep your place.” plus “Goals are a reference. Sets are your record.” |
| “Bodyweight (lb)”; “Age”; “Experience level”; “Intermediate”; “Balanced strength + muscle” | “Bodyweight”; “Age”; “Experience”; “Intermediate”; “Balanced strength and muscle”; values “230 lb” and “39” retained in Training details |
| “+ CREATE CUSTOM EXERCISE”; “RE-RUN SETUP WIZARD” | “Exercise library” then “Create exercise”; “Make a new plan” |
| “Re-run setup wizard?”; “This replaces your current program from scratch. Your workout history isn't touched.” | “Replace your plan?”; “This replaces your current plan. Your workout history stays.” |
| “Re-run”; “Cancel” | “Make a new plan”; “Keep current plan” |
| “Full backup”; “CSV history” | “Back up everything”; “Export workout history”; companion actions “Restore a backup” and “Import workout history” |
| “STEP 8 OF 8”; “Your rotation” | “8 of 8”; “Your rotation” |
| “Missing a day never skips the next workout.” | “Miss a day. Keep your place.” |
| “DAY A”, “DAY B”, “DAY C”, “DAY D” | n visible day indices with accessible full names; actual day titles (“Full body” here) |
| New wizard sequence hints | “Start here” on first row; “Then back to A” on last, with first-day ID derived |
| “BACK”; “START” (wizard final) | “Back”; “Use this plan” |
| Existing product maxim | “Your place stays saved.” on the final step; factual coach copy |

Pluralize all counts. Keep the date locale and the lifter's units; the examples deliberately use the supplied pounds and dates. Add no motivational rotation ordinal, PR, duration, backup timestamp or training trend without data. The HTML's system clock/battery are frame furniture, not app copy. Captions beneath frames describe the design and do not ship in the product.

### Motion

The one orchestrated moment is finishing the day. Once persistence succeeds, replace the workout with the opaque receipt; retain the completed day's index, and fade the receipt body in as one group over 180 ms. No number rolls, row stagger, background preview of Day A, or trophy cascade. With reduced motion the receipt appears immediately. A failed write leaves the workout and its ticks intact, with “Couldn't save the workout. Try again.” in an M3 Snackbar; it never performs the ceremony. Add that as new failure copy, with “Try again” as its action.

A tap gets the standard themed M3 state layer and normal haptic feedback for a tick. Checks change in place. A sheet uses the standard M3 transition; the list beneath stays still. Changing a value replaces its text immediately; the TOP cascade gets one spoken confirmation, not a visual procession. The fold comparison adds no transition to the selected design. Rest expiry/removal changes only the pill inside its fixed bar. Tabs change content without sliding the whole exercise list. No entrances on first composition or lazy-item re-entry. None of these behaviours require a scroll listener. HTML demonstrates tick/edit/cascade in isolation; it simulates the temporary rest countdown and expiry, but does not persist a real session or emulate every destination.

### Principles

The day has a visible identity before, during and after the work.  
The actual set speaks louder than the calculated goal.  
The coach acknowledges absence without measuring guilt or inventing readiness.  
The workout stays physically still while its record changes.  
An ordinary saved session earns a clear ending.

### Self-check

| Generic default | Decision after revision |
| --- | --- |
| Tracked-capital eyebrows | No. Small sentence case references do not carry the hierarchy. Goal and Last use sentence case in the right-aligned reference column; the extra ACTUAL heading is gone. |
| Middle-dot meta strings | No. Dates, names and counts have positions; prose uses sentences. The multiplication sign remains inside set notation because it materially improves scanning. |
| “WORD — fragment” labels | No. Actions say what happens: Start day D, Finish, Use this plan. |
| Identical rounded cards everywhere | No. Exercise sections, ruled session rows and settings ListItems have different purposes. Temporary editing uses a standard sheet; the running rest uses a small Surface pill. M3 is a component library, not a mandate to put every fact in a card. |
| One accent on every control | No. Day colours come from the shared seven-accent source; this four-day example uses A–D, with no four-day design limit. Settings, steppers, checks, tabs and secondary actions are neutral. Only the current day's index and primary session action spend its colour. |
| Big number with little label tiles | No dashboard tiles. Large weight numerals remain inline with reps, a named set and its checkbox; they are ledger values whose edit action opens the sheet. The large day letter is the single deliberate signature and describes a position, not a metric. |
| Scattered entrance animations | No. One whole-receipt fade after successful commit. Standard tap and sheet feedback serve their controls. |

Verification for this revision: the acceptance target is ten dark 412 × 892 frames, 1 CSS px = 1 dp, all 21 rows in both full-workout ledgers, component captions on every frame, and byte-identical embedded font declarations and licence. Local structural and interaction checks passed for ten frames and captions, all 21 full-ledger rows, the changed copy, right-aligned header markup, app-bar counts, sheet editing, TOP cascade, independent rep edits, fold inspection, Skip, timed expiry and protection against stale expiry hiding a new rest. SHA-256 checks confirmed all three embedded font declarations and the licence are unchanged. JavaScript syntax passed. Chromium rendering was attempted offline but blocked at launch by the environment sandbox (`setsockopt: Operation not permitted`); these checks therefore do not claim a rendered visual review. Native scrolling and TalkBack performance have not been measured; implementation acceptance must verify stable key/offset through tick, TOP edits, timer updates and sheet dismissal, including large text. The comparison intentionally demonstrates the different geometry of folded exercises; it is not a claim of the selected flat ledger’s behaviour.

Before finishing, I took off the miniature A/B/C/D rotation rail from Today. It repeated the day index and borrowed the appearance of workout tabs without sharing their behaviour. The Plan destination now provides the overview; Today can simply begin. I also resisted a progress ring and a rotation-complete stamp: neither was needed to make the work count.

### Cohesion

The current build feels assembled because each screen promotes its own machinery: Today lists coverage, the workout exposes editors, Log leads with chart categories, and Setup displays choices before the user has decided to change anything. In that build the same day colour means several things and the small caption keeps doing the work of a heading. This design makes those screens successive encounters with one object: the day waiting, the day being done, the day saved, the day filed. Its index stays recognisable, its numbers keep their roles, its controls behave consistently, and its coach speaks once when there is something useful to say. Plan and Settings give the remaining features an intelligible home. Goal and Last form one quiet reference column; the temporary rest pill and the flat ledger keep attention on the current set. Frame 10 acknowledges the accomplishment offered by folding while making its movement tradeoff visible. All of these arrangements use the same Material 3 vocabulary. The result should feel composed because the product has made decisions about attention, not because more of it moves.
