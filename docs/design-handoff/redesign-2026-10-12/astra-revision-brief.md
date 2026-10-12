# Revision round — strength.log redesign, 2026-10-12 (after the owner's review)

You are the same design lead who wrote `astra-design-plan.md` and `astra-mock.html`
in this directory this morning (read both first; they are yours). The owner has now
looked at your proposal side by side with a second proposal by another designer
("Fable") and has made decisions. Revise YOUR plan and YOUR mock in place to apply
them. Overwrite the two files; keep the embedded offline Barlow and the licence block
exactly as they are. Write nothing else except, optionally, `revision-notes.md`.

Work in the directory you are started in (outside the repo). The repo at
/home/betty/Projects/strength.log is read-only for you: read anything (CLAUDE.md §5,
`domain/src/main/kotlin/cloud/trotter/log/strength/domain/theme/DayAccentColors.kt`,
`domain/.../generator/WizardAnswers.kt`, the Compose screens under
`app/src/main/kotlin/cloud/trotter/log/strength/ui/`), write nothing there. No
network, no Gradle, no adb.

## The owner's decisions (apply all of these)

1. **Today.** Your hero wins: the big letter on the accent block ("day index") with
   the text column beside it, not below. Keep the "Last trained 4 Aug, 69 days ago"
   line. DROP "Start with the warm-up. Own each rep." — the owner found it
   condescending. The coach may state a fact about the situation; it may not
   instruct. "Your place is here." may stay if you still want it.
2. **Navigation.** The owner likes your Today / Log / Plan bottom NavigationBar
   ("keeping it clear how the user can navigate"). Keep it. In the plan, say in one
   sentence why a bottom navigation bar is current Material 3 guidance for three to
   five top-level destinations and not "old Android" thinking.
3. **Workout rows.** Standard Material 3 `Checkbox` for every set (you already do
   this; the owner chose it over stylised ticks). REMOVE the "ACTUAL" column label;
   it does not make sense in the row. In each exercise header, put the goal at the
   right ("Goal 120 lb") and the last-time line directly under the goal, right-
   aligned ("Last 120 lb × 5"), not under the exercise name on the left.
4. **Rest timer.** The owner prefers Fable's: a small pill at the left of the bottom
   bar, shown only while a rest is running, with the countdown in condensed numerals
   and −15 s / +15 s / Skip inside it, beside the Finish button. No permanent
   two-row dock; no "Rest starts after a set." idle line. "Rest over" may show
   briefly at expiry, then the pill leaves. The "9 of 21 saved" count can live in
   the app bar.
5. **Editing a set.** Your ModalBottomSheet wins. The list behind it is a ledger;
   editing always goes through the sheet. Keep it.
6. **Finished exercises.** The owner is conflicted. They like the flat list ("it's
   all just there") AND they like folding a finished exercise to one summary line
   because "it makes it seem more like an accomplishment / completion". Their
   refinement of the fold: the row checkboxes collapse into ONE checked checkbox at
   the RIGHT of the summary line (where the set checkboxes were), not a dot at the
   left. Decide, and justify in Compose terms: if folding is a one-item
   `AnimatedContent` on a keyed `LazyColumn` item (no scroll-linked state, no
   measurement), does it still break your smoothness contract? If you keep the flat
   list, ALSO add one frame showing the owner's fold so they can compare; if you
   adopt the fold, show it in the "later in the workout" frame.
7. **Rotation length.** A rotation can have 2 to 6 days (`WizardAnswers`
   requires `daysPerWeek in 2..6`; `DayAccentColors` defines seven accents A–G and
   cycles). Nothing in the design may assume four: your Plan day tabs must handle
   six (say which M3 tab row), the wizard reveal lists n rows, any "four days" copy
   is derived from the count. Your mock may keep showing this lifter's four.
8. **No bespoke code.** The owner: "i don't want a bunch of bespoke code that i
   have to explain to anyone, myself later included." Every element in the design
   must map to a Material 3 Compose library component or a plain composition of
   them (Row/Column/Text). Where you claim composition, name the pieces.

## Compose guidance, explicit (the owner asked for this)

Add a section `### Compose contract` to the plan. For each screen, list the
Material 3 / Compose APIs it is built from (e.g. `Scaffold`, `TopAppBar`,
`NavigationBar` + `NavigationBarItem`, `PrimaryTabRow` / `PrimaryScrollableTabRow`,
`LazyColumn` with `key` and `contentType`, `ListItem`, `HorizontalDivider`,
`Checkbox`, `Button`/`FilledTonalButton`/`TextButton`, `ModalBottomSheet`,
`OutlinedTextField`, `IconButton`, `DropdownMenu`, `AlertDialog`, `Snackbar`,
`LinearProgressIndicator`, `AnimatedContent`, `Surface`). Then state the practices
the implementation follows, by name, from Google's own guidance (cite the doc
titles you are drawing on; no network, so cite from memory and say so):
unidirectional data flow and state hoisting (ViewModel + `StateFlow`,
`collectAsStateWithLifecycle`), lazy-list keys and content types, the Compose
performance best practices (defer state reads, `derivedStateOf`, no backwards
writes, stable UI models / strong skipping), side effects (`LaunchedEffect`,
`rememberUpdatedState`), Material 3 theming (`MaterialTheme` colorScheme /
typography / shapes, component `*Defaults`), Navigation Compose with type-safe
routes and `saveState`/`restoreState` for the bottom bar, accessibility (48 dp
targets, `contentDescription`, `toggleable`/`Role.Checkbox`, merged semantics on
a set row), edge-to-edge and `WindowInsets` via `Scaffold`, previews
(`@PreviewLightDark`, `PreviewParameter`), tabular numerals via
`FontFeatureSettings`. Be concrete; this section is what the implementer reads.

In the mock, under each frame's existing caption, add a second line inside the
`<figcaption>` wrapped in `<span class="built-from">Built from: …</span>` naming the
M3 components that frame uses. Keep the captions short.

## Keep

Everything in your smoothness contract (fixed app bar, no scrollBehavior, keyed
rows, nothing scroll-linked). The warm-paper theme. The copy table (update rows the
decisions change: ACTUAL label, rest idle line, the Today coach line; add rows for
anything new). Your self-check and cohesion paragraphs (revise if the decisions
change them). Nine or ten frames at 412 × 892, 1 px = 1 dp, dark theme only.

## Report

Put a `## Revision, 12 Oct (after owner review)` section at the TOP of the plan
listing what changed and what you decided on item 6 and why. In your final message,
list the files you changed and anything you did beyond this brief.
