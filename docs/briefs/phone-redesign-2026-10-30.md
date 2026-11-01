# Phone redesign, 2026-10-30

Current phone UI (4.5b): `WorkoutSetScreen` replaces the workout pager; lift navigation uses the Day sheet.
> **Changed by the 2026-10-31 product council** (`product-council-2026-10-31.md`, owner
> decided). Workout runs and one plan model come before any of this is built (#341, #342).
> In this brief:
> - Today becomes the overview where the lifter picks a workout. This brief's Today becomes
>   the workout preview.
> - Lifts becomes Exercises, covering everything performed. The tabs are Today, Log and Plans.
> - The wizard is one of four New plan doors.
> - The chevron becomes a labeled "All sets".
> - Superset rounds are logged one exercise at a time (#343).
> - The day letter on the launcher icon is dropped. The icon never changes.
> The workout screen, rest band, app-bar Finish, receipt and Settings split stand.
> Tracked in #344.

A ground-up proposal for the Android phone app, written with the `ux-design` skill against
`DESIGN.md` (repo root, this worktree). It follows the watch redesign of the same day
(`watch-redesign-2026-10-30.md`), which the owner liked, and the owner's request: "do this
also for the main app. I want to see what it comes up with." The same-day audit
(`ux-audit-2026-10-30.md`, 31 items) fixed the phone in place; this brief starts again from
the lifter's moments, takes what the audit found as evidence, and keeps or overturns each
October decision on its reasons. Revised after one review round; the last section lists what
changed.

Drawings of every screen, today-versus-redesign pairs for the six main moments, light and
dark expressions and two screens at the largest font scale are in
`phone-redesign-2026-10-30-mockups.html` next to this file. Kotlin paths are relative to
`app/src/main/kotlin/cloud/trotter/log/strength/` unless they start with a module name;
resource paths are relative to `app/src/main/res/`.

## The concept in one paragraph

The workout shows one set at a time on a screen that never scrolls or swipes: the lift's
name, the load in the biggest numerals the phone can hold with the plates under it, one
line saying where you are in the lift, and one button that does what the moment needs
("Log 190 × 5", and "Finish day A" once the last set is in). Logging moves straight on to
the next set, across lifts, the way the redesigned watch does, by one selection rule: the
lift the lifter chose stays chosen until it is done, then the next lift with work left
after it in plan order. A quiet "Finish" in the app bar ends the day early from any set once one is logged. A rest
appears as one band above the button: the countdown, Skip rest, +15 s, and Undo for the
set just logged; when the rest ends, the band goes and the button fills again. The rest of a lift (its sets, goal, last time, swap, note) sits behind the
lift's name in one sheet, and the rest of the day (its lifts, jumping to another, editing
the day) behind one icon. The day's color leaves text and segments and lands
on the one filled button, in tones that hold contrast in both themes. Outside the gym the
three tabs stay, each with one job: Today says which day is next and starts it, Log lists
sessions first with each lift's trajectory one tab away, and Plan holds the rotation, the
goals and the inputs that drive them, so Settings keeps only preferences, Health Connect
and backup. The pager and its peek, the day and set segments, the second filled button,
the separate cascade screen, the volume chart and five of the eight wizard steps go.

## Why the current app is busy or hard

Counted from the CI renders in `.shots/phoneScreenshots/` (rendered Dark at 360 × 720 dp)
and, for Log, Plan and Setup, which have no render, from the composables. A "thing" is a
separately readable item: a text run, a control, an indicator, a segment.

| Moment | Today: what one look has to parse | Redesign |
|---|---|---|
| Today, in progress (`01-today.png`) | Gear, the A block, "Day A", "Squat focus", "2 of 18 sets logged.", 4 rows of number, name and tracked "6 sets" (12 things), 5 hairlines, "Continue day A", the 3-item navigation bar. 21 things; the day is named three times (A, Day A, Continue day A) | Gear, the A block, "Squat focus", "2 of 18 sets logged.", 4 lift names (the current one says "2 of 6"), Continue day A, the bar. 12 things |
| A set (`02-workout.png`) | Back, A block, "Squat focus", "2 of 18", overflow; 4 day segments; "Lift 1 of 4", "Next: Barbell Bench Press"; in the card the title, "Goal 235", overflow, "Ramp set 3", 190 × 5, 6 set segments, "Set 3 of 6", "210 × 3 next", Log 190 × 5, All sets; the peeking card; Finish day A. 28 things, two filled buttons, four hues (terracotta, light terracotta numerals, green, gray). Position is said four ways (2 of 18, the day segments, Lift 1 of 4, Set 3 of 6 with its segments) | Back, "Day A", the lifts icon, "Finish", "Barbell Back Squat ›", "Set 3 of 6 (R3)", 190 × 5, "45 + 25 + 2.5 a side", Log 190 × 5. 9 things, one filled button, one hue |
| Rest (`03-resting.png`) | The same 28, plus the pill's 1:30 and Skip. Log turns tonal, so "Finish day A" becomes the loudest thing on the screen, during the moment the lifter is most likely to tap something by mistake | The set's 9, with Log tonal, plus one band: "Rest", 1:04, a draining bar, "Undo 165 × 5", "+15 s", "Skip rest". 15 things; nothing louder than the next set |
| Lift done (`05-lift-done.png`) | A finished card: a checkbox, "Top set" over the last set (wrong for a 3 × 10 accessory, audit item 5), the six-set summary, a filled "Barbell Bench Press" button beside a filled "Finish day A" | A log from the button moves to the next lift's first set, with no stop. A lift completed any other way (a Lift-sheet tick, the watch, a jump to a done lift) shows a quiet completed view: "Done, 6 of 6", the sets in one line, and "Next: Barbell Bench Press". 8 things |
| Finish (`07-receipt.png`) | "DAY A COMPLETE", "Squat focus", SETS 18, STRONGEST 235 × 5 and BARBELL BACK SQUAT, NEXT DAY B · UPPER, ADD A NOTE, SHARE, BACK TO TODAY: 13 text runs in seven caps or tracked styles, 4 hairlines, and the workout showing through the 97% scrim (`ui/day/SessionReceiptScrim.kt:104`). A new high adds a separate full-screen cascade first, with its own tap to dismiss (`ui/day/DayScreen.kt:544-582`) | "Day A, done.", the date, "18 sets in 52 min", "Next", "Day B, Upper", Add a note, Share, Done. 8 things. A new top set is one more row on the same screen, with the strike and the number roll kept |
| Log (`ui/log/LogScreen.kt:108-173`) | With four main lifts and a few months of history: about 90 things before the first session row. Four trajectory cards (name, three gridline labels, the goal line and its label, the latest value, a caps caption), the volume chart (12 bars, 2 labels, a caption), the month calendar (title, 2 chevrons, 7 weekday initials, 30 or 31 cells), Health Connect status, then the sessions | Title, the calendar icon, two tabs, then the sessions. The first session row is the first thing under the tabs |
| Plan (`ui/plan/PlanScreen.kt:46-151`) | Plan name, Plans, then per day the block, an overline "Day A" that repeats the block, the title and "4 lifts, 18 sets"; "Next" on one. 19 things. Tapping a row starts that day's workout with no warning (`ui/plan/PlanScreen.kt:110`, `ui/AppNavHost.kt:274`) | Plan name, Plans, gear; per day the block, the title, "4 lifts, 18 sets"; Next; then Goals: four lifts and their goals and one Training row. 27 things, because the goals and their inputs move here from three screens down in Setup. A row opens the day; starting it is an explicit "Start day C" |
| Setup (`ui/setup/SetupScreen.kt:105-136`) | One scroll of about five screens: 5 caps section heads, a policy footer, the goal card, bodyweight and age steppers, 3 level rows, 3 emphasis cards, 4 cardio mode cards, 4 placement cards, a 5k switch, the units switch, 3 theme cards, 2 rest switches, 5 rest steppers, Reset defaults, and three caps buttons. About 45 controls, two jobs | Settings: 8 rows (units, theme, keep screen on, rest timer, Health Connect, backup, custom exercises, licenses). The training inputs move to Plan |
| First run (`ui/wizard/WizardScreen.kt:143-152`) | Eight steps, most of them full screens of Day A selection cards, ending in "START", which starts nothing (`:584`) | Three screens: days per week; about you; the rotation, with "Use this plan" and "Change answers" |

Four things make the workout harder than its count:

- **Two ways to move and both are easy to do by accident.** The pager moves on a
  horizontal drag anywhere on the card (`ui/day/DayScreen.kt:395-402`), and the card has a
  filled button in the middle of it. With chalk or sweat on the hands, a tap that slides
  becomes a swipe, and the next Log logs a different lift. The watch redesign cut its
  swipe-left between lifts for the same reason ("unlabeled, and a brushed sleeve could take
  it").
- **A doubled tap logs two sets.** After a log, the focus moves to the next set and the
  button relabels in place ("Log 190 × 5" becomes "Log 210 × 3") under the same finger
  (`ui/day/ExercisePage.kt:327-339`). There is no undo for a log except finding the set in
  All sets and unticking it.
- **The day's color means four things on one screen.** The fill of Log and Finish, the
  numerals, the current day segment and the current set segment, the "Lift 1 of 4" and "All
  sets" text, and, through `primary = dayAccent(0)` (`ui/theme/Theme.kt:64`), the same
  terracotta on every day. As text it is under 4.5:1 on four of seven days (audit item 2).
- **The finish is three surfaces.** The cascade scrim, then a translucent receipt over a
  workout that has already advanced, then Today. Each wants its own tap.

The day's identity and progress are worth knowing, but at the gym the lifter knows them
already. They get one word in the workout's app bar ("Day A"), the color of the one button,
and a sheet one tap away.

## The lifter and the moments

The reference user is about forty, trains alone for maintenance, has an unreliable
schedule and comes back after gaps. At the gym the phone lies on a bench or the floor, two
or three feet away, screen up; hands are chalked or sweaty; a look lasts two to five
seconds between sets, often while loading plates. Near vision starts to decline in the
forties, so the number that matters has to be large and stand alone. At home they have both
hands and patience but no interest in the app as a hobby: plan, check history, back up.

| Moment | Where, hands, eyes | What they need | Screen and its one job |
|---|---|---|---|
| Opening the app at home or walking in | Phone in hand, a few seconds | Which day is next, what's in it, the way in | **Today**: say which day is next and start it |
| Loading the bar | Phone on the bench, hands on plates, a glance | The load, the plates per side, the reps | **Set**: show the next set and log it when it's done |
| Racking the bar | Reaching down, one tap | Record it, start the rest | Same screen, one tap |
| Between sets | Glances from a few feet | Time left, the next set's load | **Rest band**: say when the next set is due and what it is |
| A wrong log | Right after the tap | Take it back | **Undo** in the band, naming the set |
| Station taken, wrong weight, a different lift | Phone picked up, a few seconds | Jump to another lift, swap, change one set | **Lift sheet**, **Day sheet**, **Set edit sheet** |
| Plank, carry, timed window, treadmill | Phone on the floor or the console | Time the effort, then record it | **Timed and cardio sets**, same layout |
| Last set racked | Tired, packing up | That it counted, and what's next | **Receipt**: confirm the day saved and say what comes next |
| Phone in a pocket during rest | Lock screen | Time left and the next set | **Rest notification** |
| On the couch, later | Both hands, patience | What I did, how a lift is trending | **Log**: show what I did |
| Changing the program | Both hands, some patience | See the rotation, change a day, adjust goals | **Plan**: see and change the rotation and its goals |
| First run, or a new plan | Both hands, mild patience | A plan they accept without learning the generator | **Wizard**: produce a rotation the lifter accepts |
| Rarely: preferences and data | Both hands, sometimes anxious | Change a preference; know the data is safe | **Settings**, **Backup**: change how the app behaves; know the data is safe and move it |
| The home screen | A glance | Today's day and progress | **Widget** and the **launcher icon** |

Every screen below has one job sentence. The two that failed the test today, Setup (training
inputs and preferences) and Log (history plus Health Connect plus the bodyweight prompt), are
split.

## Capability inventory and where each one lands

Inventoried from the code before designing. Nothing is dropped unless the "Cut" column says
so and gives the cost.

| Capability | Today (code) | Redesign |
|---|---|---|
| Today and starting a day | `ui/today/TodayScreen.kt` | Today |
| Switching between saved plans from Today | plan picker sheet, `TodayScreen.kt:220-251` | Kept: the plan name as a drop-down control, same sheet |
| Weighted sets | `ui/day/ExercisePage.kt:211-215` | Set screen |
| Reps-only sets | `ExercisePage.kt:216-219` | Set screen, hero "12 reps" |
| Timed holds (logged as prescribed, no clock) | `ExercisePage.kt:220-226` | Set screen, "Log 45 s"; a clock is owner question 5 |
| Timed reps (a window, then a count) | `ExercisePage.kt:305-326`, `ui/day/TimedRepCountSheet.kt` | Set screen with a running clock, then the count sheet |
| Supersets | `ExercisePage.kt:240-252` | Set screen, "then Seated Cable Row 90 × 12" line; one log per round |
| Swap an exercise | card menu, `ExercisePage.kt:154-162`; `SlotSwapSheet` | Lift sheet |
| Add or remove weight (the tracking swap) | card menu and dialog, `ExercisePage.kt:163-178,349-358` | Lift sheet, same dialog |
| Exercise note | card menu, `ExercisePage.kt:179-185` | Lift sheet |
| Edit a set (weight, reps, seconds) and the top-set cascade note | `ui/day/SetEditSheet.kt` | Set edit sheet, opened from the numeral or a row in the Lift sheet |
| Add a set; remove a set with undo | All sets sheet; `SetEditSheet.kt:86-99`; Snackbar `DayScreen.kt:163-176` | Lift sheet; set edit sheet; Snackbar kept |
| Plates per side | edit sheet only, `SetEditSheet.kt:135-150` | Under the numeral, and in the edit sheet |
| All sets of a lift, tick out of order | `ui/day/AllSetsSheet.kt:36` | Lift sheet |
| Jump to another lift | pager swipe; Exercises sheet `AllSetsSheet.kt:113` | Day sheet |
| Day edit: add, remove, reorder, superset, prescription, cardio block, reset | `ui/day/DayEditSheet.kt` | Edit day, a full-screen route |
| Clear today's checks | overflow, `DayScreen.kt:277-283` | Day sheet |
| Keep screen on | workout overflow, `DayScreen.kt:285-296`; stored off by default (`data/.../FullSnapshot.kt:42`) | Settings; default is owner question 4 |
| Rest timer link from the workout | overflow, `DayScreen.kt:297-303` | Cut from the workout. Cost: one more tap to reach the rest settings mid-workout (Settings, Rest timer). +15 s and Skip rest cover the moment |
| Rest timer, adjust ±15 s, skip | `DayScreen.kt:706-745` | Rest band: +15 s and Skip rest. −15 s cut; Skip rest does what a shorter rest would. Cost: a lifter who wants exactly 15 s less taps Skip rest at the right time |
| Rest notification | `rest/PhoneRestRuntime.kt` | Kept, sentence case, Skip rest and +15 s actions |
| Cardio sets: start, stop, reached, skip, end block, add set, distance entry, retry | `ui/day/CardioPresentation.kt`, `CardioExecution.kt` | Set screen layout for cardio; Cardio sheet behind the activity name; distance sheet |
| Standalone cardio day from Today | `TodayScreen.kt:198-209` | A list row on Today |
| Finish, partial-finish confirm | `DayScreen.kt:317-331,497-508` | "Finish" in the workout's app bar, always present and disabled until a set is logged, same confirm; the action slot once every set is logged |
| Cascade ceremony | `ui/day/CascadeScrim.kt`, `CascadeCeremony.kt` | A row on the receipt, strike and roll kept |
| Receipt, session note, share | `ui/day/SessionReceiptScrim.kt` | Receipt screen |
| Log: sessions, expand, edit sets, delete with undo, note, share card | `ui/log/LogScreen.kt` | Sessions tab and a Session screen |
| Trajectories per main lift | `ui/log/JournalSections.kt:73-102` | Lifts tab |
| Volume chart | `JournalSections.kt:185-274` | Cut. Cost: weekly tonnage is gone; a maintenance lifter does not train for volume, and the chart borrowed Day A's color as a series (audit item 11) |
| Training calendar | `JournalSections.kt` `CalendarCardView` | Calendar view behind an icon on Log |
| Health Connect: connect, status, publish past workouts, external sessions, bodyweight prompt | `LogScreen.kt:133-180,492-590` | Settings row and screen; external sessions in the session list; the prompt on Plan |
| Plans: list, switch, rename, duplicate, archive, restore, new (wizard or empty) | `ui/plan/PlansScreen.kt` | Plans screen, unchanged in content |
| Authored plans: add, rename, remove a day | `PlanScreen.kt:68-102,125-140` | Plan, same menus |
| Goals preview and its inputs (bodyweight, age, level) | `SetupScreen.kt:113-116` | Plan (goals) and the Training screen |
| Emphasis and cardio (generated plans only; writes are dropped for authored plans, `data/.../TrackerRepository.kt:241,254`) | `SetupScreen.kt:117-118` | Training screen, shown for generated plans only |
| Units, theme | `SetupScreen.kt:120-121` | Settings |
| Rest timer on phone and watch, per-category lengths, reset | `SetupScreen.kt:123-131` | Settings, Rest timer screen |
| Custom exercise | `ui/customexercise/` | Same screen, rebuilt; reached from Settings and the exercise picker |
| Backup: automatic, export, restore, CSV export and import with preview | `ui/backup/BackupScreen.kt` | Backup screen |
| Licenses | `ui/licenses/LicensesScreen.kt` | Settings row |
| Wizard, restore from backup on first run | `ui/wizard/WizardScreen.kt` | Short wizard; restore on its first screen |
| Health Connect rationale | `health/HealthRationaleActivity.kt` | Kept |
| Widget | `widget/` | Light and dark, sentence case |
| Day launcher icon | `icon/DayIconManager.kt` | The day's letter |

## Navigation and flow

Three top-level destinations in a `NavigationBar` (a `NavigationRail` on medium and wider
windows through `NavigationSuiteScaffold`): **Today**, **Log**, **Plan**. Settings is a gear
in the top app bar of Today (as now) and of Plan (new; Plan has none today,
`PlanScreen.kt:47-54`). Every other screen is a full-screen route
without the bar.

```
Today ──Start/Continue──▶ Workout ──last set──▶ Finish day A ──▶ Receipt ──Done──▶ Today
  │                        │  ├─ lift name ─▶ Lift sheet ─▶ Set edit sheet
  │                        │  ├─ ≡ ─────────▶ Day sheet ─▶ Edit day ─▶ Exercise picker ─▶ Custom exercise
  │                        │  └─ Finish ────▶ (confirm unless all logged) ─▶ Receipt
  │                        │  └─ numeral ───▶ Set edit sheet
  ├─ plan name ▾ ─▶ plan picker sheet
  └─ gear ─▶ Settings ─▶ Rest timer | Health Connect | Backup ─▶ CSV import preview | Custom exercise | Licenses

Log ─▶ (Sessions tab) session ─▶ Session ─▶ Share card | set edit sheet | note | Delete (confirm, then Snackbar Undo)
    ─▶ (Lifts tab) trajectories
    ─▶ calendar icon ─▶ Calendar ─▶ Session

Plan ─▶ day ─▶ Day (preview) ─▶ Start day C ─▶ Workout
                             └▶ Edit day
     ─▶ Goals ─▶ Training
     ─▶ Plans ─▶ New plan ─▶ Wizard | empty plan
     ─▶ gear ─▶ Settings

First run (no program) ─▶ Wizard: Days ─▶ About you ─▶ Your rotation ─▶ Use this plan ─▶ Today
                                                       └▶ Change answers ─▶ emphasis, split, anchors, cardio, equipment
```

| From | Event | To |
|---|---|---|
| Launch, no program | | Wizard, first screen |
| Launch, program loading | | Today's loading state (a neutral indeterminate bar, no text for the first second) |
| Launch | | Today; a workout already open stays on its route through process death, as now. Reopening the workout returns to the persisted selection and Undo (see the workout record) |
| Today | Start day A / Continue day A | Workout, on the first lift with work left |
| Workout: Set | Tap Log | The next set in plan order, across lifts; the rest band if the set carries a rest |
| Workout: Set, the last set of the day | Tap Log | The "all logged" state: no rest starts; the slot reads Finish day A and arms after 3 s |
| Workout: Rest | Timer ends | One buzz; the band collapses; Log turns filled again |
| Workout: Rest | Skip rest | The band leaves; nothing is logged |
| Workout: Rest | Tap Log during the rest | Logs the set and restarts the rest for it, as now (`DayViewModel.kt:899-915`) |
| Workout | Undo 190 × 5 in the band | `undoLastLog()`: unticks that set, cancels its rest, returns to it (see Undo) |
| Workout | Tap the lift name | Lift sheet |
| Workout | Tap the numeral | Set edit sheet for the set shown |
| Workout | ≡ | Day sheet; a row jumps to that lift |
| Workout | Finish (app bar) or Finish day A (slot) | Today's partial confirm when sets remain; cancels any rest, alarm and notification (the live bug is #338, fixed outside this redesign); Receipt (with the new-top-set row when the cascade fires) |
| Workout | Back | Today; the session and a running rest continue |
| Receipt | Done or back | Today, now showing the next day |
| Plan | A day row | Day: the day's lifts and prescriptions, Start day C, Edit day |

What survives process death, and where it lives:

| State | Owner | New? |
|---|---|---|
| Logs, prescriptions, session start | Room, as now | |
| The running rest (deadline, length, next label) | DataStore (`data/.../prefs/SettingsStore.kt:197-199`), as now | |
| A running timed-rep event | the day `SavedStateHandle` (`DayViewModel.kt:141`), as now | |
| The open sheet and its set | the day `SavedStateHandle` (`KEY_OPEN_SHEET`, `DayViewModel.kt:130`), as now | |
| The workout record: the selected lift, and the undo target (slot, set index, logged values, pre-log values, logged-at time); Undo's validity and expiry are derived from the sets' completion stamps and the live rest deadline | DataStore, beside the running rest (`SettingsStore.kt:197-199`), keyed by plan, day and date | New. The day route's `SavedStateHandle` dies when the route pops to Today (`AppNavHost.kt:187` pushes it; back pops it), so neither the selection nor Undo could survive leaving the workout there |
| The finished session's receipt, including its new-top-set rows | the receipt route's `SavedStateHandle`, written once at finish | New; today the cascade is a bare field by design (`CascadeCeremony.kt` KDoc) |
| The Finish arming deadline | derived: the undo target's logged-at time plus 3 s | No new field |
| Removed-set undo offers (`_removedSets`) | a `ViewModel` field, as now | |

## Screens

Copy follows `DESIGN.md`'s house rules: sentence case, caps only for set notation (R1, TOP,
B/O), a label rather than a sentence beside a number, facts rather than advice, no
dot-joined meta strings. Dates are American ("Oct 28", "Wednesday, Oct 28"); today's "4 Aug"
pattern (`ui/today/TodayScreenBuilder.kt:74`, `ui/plan/PlansScreen.kt:164`) changes to
"Aug 4". User-authored day titles are never recased.

Sizes are drawn for a 360 × 800 dp phone with a 24 dp status bar and a 24 dp gesture bar.
Side gutters are 16 dp.

### Today

One job: say which day is next and start it.

```
                                            [⚙]        TopAppBar, no title
 ┌──────┐
 │  A   │  Squat focus                                  DayIndex Large (96 × 108), headlineLarge
 │      │  Last trained Oct 28, 2 days ago.              bodyLarge, onSurfaceVariant
 └──────┘  [Four-day strength ▾]                        TextButton, only with 2+ plans

 Barbell Back Squat                                      ListItem, one line, no dividers
 Barbell Bench Press
 Barbell Row
 Plank / Side Plank
 Zone 2                     20 to 25 min easy            a cardio finisher, when the day has one

 [            Start day A            ]                   Button, filled, day primary, 56 dp
 ─────────────────────────────────────
  Today        Log         Plan                          NavigationBar
```

- Kept: the day index block, the coach fact, the lift names, one action, the plan picker.
  The block is the app's mark (the launcher icon and widget carry it too), and on Today it
  is the answer to "which day".
- Cut: the "Day A" label beside the block (`TodayScreen.kt:139-143`), which repeats the
  letter and makes TalkBack say it twice; the lift numbers and the tracked "6 sets"
  (`ui/components/LiftListItem.kt`); the hairlines between rows (space groups them); the
  life line (`TodayScreen.kt:166-177`), a second history fact; the Start button's press
  scale (`TodayScreen.kt:261-276`).
- In progress: the fact line becomes "2 of 18 sets logged."; the lift with work in progress
  shows "2 of 6" as trailing text, finished lifts a check icon and "Done" (icon and word,
  never green alone). The button is "Continue day A"; when every set is logged it is
  "Finish day A" and opens the workout's all-logged state.
- A separate cardio day (`state.standaloneCardio`) is a last row "Cardio + core" with
  "Any day" as supporting text and a chevron; tapping opens its cardio set screen. The
  hard-coded line at `TodayScreenBuilder.kt:63` moves to a resource.
- States:
  - **Loading**: the app bar and a neutral `LinearProgressIndicator` under it; nothing else
    for the first second, then the block's outline and two gray text bars would be decoration,
    so it stays bare.
  - **No program** (first run, or the last plan archived): a centered column, "No plan yet"
    (headlineMedium), "Set up a plan" (filled, neutral primary) and "Restore a backup" (text
    button). No explanatory sentence. Today the restore lives
    on the wizard's first step only.
  - **Empty day**: the block and title, "No lifts in day A yet.", "Edit day" as the filled
    action. Start is not offered.
  - **First day ever** (no history): no fact line. Never "Welcome".
  - **A day other than the next one is in progress**: Today derives this from the ticks,
    since how the day was started is not recorded (`TodayViewModel.kt:62,80` reads only the
    suggested day). If a day other than the suggested one has sets logged today and is not
    finished, Today shows it, "Continue day C", with the fact line "Next in rotation: day A."
    (spec §8.2's override note in the house style). If two days both have sets logged today,
    Today shows the one ticked most recently and the fact line names the other: "Day A also
    has 4 sets logged today." Today the screen would show A and offer no way back to C.
  - **Plan switched with a session open**: unchanged behavior; the picker sheet lists each
    plan with "Next: Day C, Deadlift focus" as supporting text, or "No days yet".

### Workout: the set

One job: show the next set and log it when it's done.

```
 [←]  Day A                          [≡]  Finish      TopAppBar: back to Today, title, Day sheet, Finish (TextButton)
 
 Barbell Back Squat                            ›        clickable row, headlineMedium, 56 dp: Lift sheet
 Set 3 of 6 (R3)                                         titleMedium, onSurfaceVariant
 
 190 × 5                                                 displayLarge 104 sp, onSurface, auto-sized;
                                                         "× 5" in displayMedium, onSurfaceVariant
 45 + 25 + 2.5 a side                                    titleLarge, onSurfaceVariant
 
 
 ┌─────────────────────────────────────┐                 the band, only after a log (see Rest)
 └─────────────────────────────────────┘
 [            Log 190 × 5            ]                   Button, filled, day primary, 64 dp, titleLarge label
```

- The lift name, the position, the load, the plates, the action, and a quiet way to end
  the day. Nothing else is on the screen while a set is in front of the lifter.
- "Finish" is a `TextButton` always present in the app bar, disabled until a set is logged
  (as today's button, `DayScreen.kt:319`), so the bar never changes shape mid-workout. It
  hides only in the all-logged state. It is the top of the screen, away from the
  thumb on Log, and it confirms as today: "Finish with 9 of 18 sets?", or "Finish without
  Zone 2?" when only the cardio block is left (today's confirm fires only when done is under
  total, `DayScreen.kt:322`). A lifter who skipped a set finds the way out without
  hunting. When every set is logged, the slot carries Finish and the app-bar one hides.
- The numerals are `onSurface` (17.3:1 dark, 15.0:1 light) rather than the day's accent
  (`ExercisePage.kt:230`). At arm's length contrast matters more than identity, and the
  accent now means the action.
- The numeral is a button for the set edit sheet ("Edit set 3", as today's
  `ExercisePage.kt:231`). The lift name row opens the Lift sheet. The ≡ icon opens the Day
  sheet ("Day A lifts, 2 of 18 sets" to TalkBack).
- Position is text, "Set 3 of 6 (R3)", the watch's form. The suffix appears only for ramp,
  top and back-off sets (R3, TOP, B/O); a plain work set reads "Set 2 of 3", and a superset
  "Round 2 of 3" (a new string, the watch's word). The set segments, the day segments,
  "Lift 1 of 4", "Next: Barbell Bench Press", "210 × 3 next", "Goal 235", "Last" and the
  day's "2 of 18" leave this screen; the Lift and Day sheets carry them.
- Plates: `DayScreenBuilder.plateLine` (`ui/day/DayScreenBuilder.kt:335-340`) for barbell
  lifts, with " a side" appended. When it returns null (not a barbell, or a load the plate
  set cannot make), the line is absent, never a guess (`docs/briefs/plate-math.md`). When it
  returns "0" (an empty bar, `DayScreenBuilder.kt:339`) the line reads "Empty bar".
  The lb plates are 45, 25, 10, 5 and 2.5 (`domain/.../units/PlateMath.kt:14`).
- Authored detail ("Each side, red band", `DayScreenBuilder.headerTargetDetail`) is the
  line under the numeral when the slot has it, in place of plates.
- Variants, same layout:
  - **Reps only**: hero "12 reps" ("reps" in the smaller style). Log 12 reps.
  - **Superset round**: hero is the first lift's load; the line under it is "then Seated Cable Row
    90 × 12" (`day_then_partner`); the lift row reads "Incline DB Press + Seated Cable Row".
    One log per round, as today, and the button says so: "Log round 2". Both loads are on
    the screen above it, so the label names the round rather than repeating them.
  - **Timed hold**: hero "45 s"; for a loaded hold the line under it is "at 25 lb" and the
    button "Log 45 s at 25 lb", keeping the load today's numeral carries
    (`ExercisePage.kt:224`). Today's behavior otherwise (no clock); owner question 5.
  - **Timed reps**: hero "0:40" with "Aim 20" under it when the slot has an aim. Start is
    the filled action. Running: the hero counts down, a `LinearProgressIndicator` drains
    under it, the action is "Cancel" (tonal; today's "Stop" discards the attempt and logs
    nothing, `DayViewModel.kt:777-780`, so it is renamed for what it does), and the screen stays on while the clock runs
    (`executionKeepsScreenOn`, as now). At zero: one buzz and the count sheet.
  - **Cardio**: the lift row is the activity ("Treadmill run ›", opening the Cardio sheet);
    position "Work, set 2 of 6"; hero the interval's end, "4:00" or "400 m", with "aim
    1:45" under it when set; Start. Running time-ended: the hero counts down, bar drains,
    "Stop" tonal. Running distance-ended: hero is the elapsed time, "to 400 m" under it, the
    filled action "Reached 400 m" and "Stop" as an outlined button beside it. Recovery and
    cool-down start themselves, as today. Start turns into Stop in the same place, so Start,
    Stop and Reached each ignore taps for 600 ms after the slot changes: a doubled tap on
    Start cannot record a 0 s shortened interval. A deliberate early Stop stays possible at
    once. A slip that gets through is what Redo is for.
  - **Redo** (the one correction for cardio): "Redo interval 2" appears in the band's slot (where
    Undo sits for strength) after an interval ends by Stop or Reached, and stays while that
    interval is the latest result and the lifter has not left the screen it ended on: the
    next interval's ready face, the recovery or cool-down that started itself after it, or
    the block's done state. It is offered once per interval. Redo discards that interval's
    result and any recovery or cool-down interval that started itself after it, running or
    finished, and makes the redone interval the ready one again; the lifter presses Start.
    For the final interval the run has already been saved (`maybeLogCardio`,
    `DayViewModel.kt:640-675`, clears it): Redo reopens it on the same saved session id, and
    when the redone interval ends its result replaces the saved one in place, through an
    amend like today's distance amend (`amendCardioSetDistance`), so there is never a second
    session and the day never advances twice. If the lifter leaves the redone interval
    unfinished, the saved result stands. Once the lifter leaves the done state (Done, back, or
    Finish), Redo is gone and the history is read-only, as today
    (`CardioPresentation.kt:251` only lists results).
- The double-tap guard: after a log, the action slot ignores taps for 600 ms and crossfades
  its label (`MaterialTheme.motionScheme` fast effects), so a doubled tap writes once and
  the new label is seen to change. The watch council's first finding was a doubled tap
  logging a set nobody lifted; the phone's is the same bug with a bigger button. A hold is
  not needed on the phone: the button is not under a bent wrist.
- Where the screen goes is the selection model below.
- A clock running on another lift (a timed-rep window or a cardio interval the lifter jumped
  away from) shows as an `AssistChip` under the app bar, "Push-Up 0:27", which jumps back
  to it. Nothing else on the set screen reports other lifts.
- All logged: when every round and the cardio block are done, the lift row reads "All 18
  sets logged", the hero area is empty, the band keeps "Undo 45 s" for 10 s, and the action
  is "Finish day A" (the app-bar Finish hides). No rest starts after the day's last set. Finish writes the session,
  advances the rotation and clears the checks (`TrackerRepository.advanceDay`), none of
  which can be undone, and it appears in the slot the thumb just used for Log, so it arms
  3 s after the last log: drawn disabled with its label visible, then filled. Today's
  all-done Finish has no confirm (`DayScreen.kt:322-327`); the arming delay keeps it
  one-tap without letting a hurried second tap end the day. If the rounds are done but the
  cardio block is not, the selection moves to the cardio block, and finishing without it is
  the app-bar Finish.
- States:
  - **Loading** and **no program**: as today's `ProgramLoadingState` and `NoProgramState`
    (`ui/components/AuthoredStates.kt`), restyled: a neutral indeterminate bar; "No plan yet"
    with "Set up a plan".
  - **Empty day**: "No lifts in day A yet." and "Edit day".
  - **Cardio save failed**: an `errorContainer` band in the band's slot: "Couldn't save the
    run. The sets are still here." with "Retry" (text button). Replaces the plain text and
    second filled button at `ui/day/CardioPresentation.kt:195-198`.
  - **Rest timer off**: no countdown; after each log the band shows only "Undo 190 × 5" for
    10 s, as the watch does after a set with no rest.

### Workout: the selection

One rule decides which lift the screen shows. The set screen, the reopen, the rest band's
"next" line and the rest notification all use it, so they cannot disagree.

1. The screen shows the selected lift, at its first set not done (today's `focusIndex`).
2. A lift the lifter picks in the Day sheet stays selected until it has nothing left. Nothing
   moves it: not a tick in the Lift sheet, not a tick from the watch, not a rest ending.
3. When a log from the action slot or the count sheet completes the selected lift, the
   selection moves to the first lift with work left after it in plan order, wrapping; the
   cardio block counts after the last strength slot.
4. With nothing selected (a fresh day), the screen opens on the first lift with work left
   from the top, which is today's reopen fallback (`DayScreenBuilder.kt:118-123`). The rest
   label and the notification today use the same first-unfinished rule
   (`DayViewModel.kt:905-911`); both now use rule 3 from the selected lift: the first lift
   with work left after it in plan order, wrapping.
5. The selection is part of the workout record in DataStore (see the state table), so
   leaving to Today and coming back, or process death, returns to what the lifter chose.

What that does for a lifter who leaves plan order:

- **Shared equipment.** Squat at 2 of 6, the rack is taken: the Day sheet, Barbell Row. The
  row's sets log; when it is done the screen goes to the plank (next after the row), then
  wraps to the squat at set 3, then the bench press.
- **A skipped set.** Back-off squats skipped: the Day sheet, Barbell Bench Press. The squat
  comes back once, at the wrap, still showing its back-off set; "Finish" in the app bar ends
  the day without it.
- **A lift completed off the button** (every set ticked in the Lift sheet or on the watch,
  or a jump to a lift already done): the **completed view**. The lift row stays; the
  position line reads "Done, 6 of 6" with a check icon; the hero area holds the sets in one
  `bodyLarge` line ("130×5, 165×5, 190×5, 210×3, 235×5, 175×8", today's summary line);
  the action is "Next: Barbell Bench Press", which writes nothing and moves the selection by
  rule 3. With no lift left it is the all-logged state. 8 things, no numerals to misread
  as the next set, and "Top set" no longer mislabels an accessory (audit item 5).

When the selection changes, the lift block slides left (a 300 ms shared-axis transition,
none under reduced motion) and TalkBack announces the new lift and set ("Barbell Bench
Press, set 1 of 6") through a polite live region. Today the selection never changes on a log
(`DayScreen.kt:228-235`); this is a `DayViewModel` rule, not domain math.

### Undo

The band's Undo is a new `DayViewModel.undoLastLog()`, not the Lift sheet's untick
(`onToggleDone(false)`), which leaves the rest and its alarm running and keeps the set's
timestamps (`DayViewModel.kt:899-915`; the watch's untick clears them,
`sync/SetEditApplier.kt:259-271`).

- The target is (slot, set index, the values logged, the row's values from just before the
  log, and the logged-at time), saved in the workout record in DataStore when the action slot
  or the count sheet logs. Every phone tick now stamps `completedAtMillis`, as watch ticks
  already do (`sync/SetEditApplier.kt:267-274`; phone ticks stamp nothing today).
- Whether Undo is offered is read from the data each time the band draws, never tracked by
  events: the target's row is still done, with the logged values and a completion stamp
  equal to the logged-at time, and no set of the day has a later completion stamp. A tick
  from the Lift sheet or the watch, applied in the ViewModel or outside it
  (`SetEditApplier`), a new log, Clear checks or Finish (both clear the stamps,
  `data/.../checkmark/CheckmarkReset.kt:36-37`) and an edit that moves or removes the row
  therefore all retire it with no bookkeeping. While it is valid, the band shows Undo
  until the live rest deadline in DataStore, so +15 s extends it and Skip rest ends it, or,
  with no rest, until 10 s after the logged-at time. Undo survives leaving the workout and
  process death because both inputs are stored.
- Undo re-checks that validity, then unticks the set
  (both tracks of a superset round, as `applyRoundTick` does), clears its start and
  completion stamps, cancels the rest, its alarm and its notification, restores a timed-rep
  set's reps and seconds to their values from before the log (a seeded rep window is 0 and 0,
  `domain/.../seeding/SetSeeder.kt:23`, so "the prescription" would lose an edit made before it), and
  selects that lift again.
- A sheet tick still starts its rest (`DayViewModel.kt:899`); by the rule above that rest's
  band shows no Undo, since the sheet's checkbox is its own undo.
- Labels: "Undo 165 × 5"; a superset round "Undo round 1"; a timed-rep count "Undo 18
  reps"; a hold "Undo 45 s". When the undone set belongs to another lift than the one now
  shown (after auto-advance), the label carries its short name (`ExerciseEntry.shortName`,
  else the full name): "Undo Squat 175 × 8".
- Cardio has no Undo: a cardio result is saved through its own path (`CardioExecution.kt`)
  and corrected in the Cardio sheet, as today.

### Workout: rest

One job: say when the next set is due and what it is.

```
 Barbell Back Squat                            ›
 Set 3 of 6 (R3)
 190 × 5                                                 the next set, already in focus
 45 + 25 + 2.5 a side

 ┌─────────────────────────────────────┐                 Surface, surfaceContainer, 16 dp corners
 │ Rest                                │                 labelLarge, onSurfaceVariant
 │ 1:04  ━━━━━━━━━━━━━━━━━──────────── │                 displaySmall; LinearProgressIndicator, onSurface on outlineVariant
 │ [Undo 165 × 5]      [+15 s] [Skip rest] │             three TextButtons, 48 dp, onSurface
 └─────────────────────────────────────┘
 [            Log 190 × 5            ]                   FilledTonalButton during the rest
```

- The next set stays the hero, because the rest is when the lifter loads it; the countdown
  is second, at 40 sp, with a bar so a glance from six feet reads "about half left" without
  the digits.
- "Undo 165 × 5" names the set it takes back and stays for the whole rest. One tap runs
  `undoLastLog()` (see Undo): the set returns to the screen, which is the confirmation, and
  the rest ends. The watch has the same chip.
- +15 s and Skip rest are today's actions (`DayScreen.kt:316`, `PhoneRestRuntime`). −15 s
  goes (see the inventory).
- Log stays tappable during a rest, tonal, as today (`ExercisePage.kt:333-334`). Logging
  restarts the rest for the new set, as now. Nothing on the screen is filled while the rest
  runs.
- **Rest over**: one buzz (`AppHaptics.restComplete`), the band collapses and the Log button
  fills again: the plain set screen is back, which is the signal. Undo after that is the
  set's checkbox in the Lift sheet. A text "Rest over" would be a third way of saying it.
- **Rest notification** (phone locked or in a pocket): title "Rest" with a chronometer
  counting down (the notification is posted once, `PhoneRestRuntime.kt:65-84`, so the title
  holds no number), text "Next: Barbell Back Squat 190 × 5" from the selection rule with
  the load, actions "+15 s" and "Skip rest" through non-exported receivers. "Rest over"
  posts on a new default-importance channel so it sounds and buzzes through a locked phone;
  the existing "rest" channel is low importance (`PhoneRestRuntime.kt:96`) and a channel's
  importance cannot be raised in place, so the running rest keeps it and "Rest over" gets a
  new channel ID. No Log action on the lock screen: a pocket must never write a set.
- Finish, Clear checks and a day switch cancel the rest, its alarm and its notification.
  Today `completeDay` leaves the phone rest running (`DayViewModel.kt:1109-1157`), so "Rest
  over" can buzz over the receipt; that is issue #338, fixed now outside this redesign.

### Lift sheet

One job: show this lift's sets and the few things that change it.

`ModalBottomSheet`, opened from the lift name, `skipPartiallyExpanded`.

```
 Barbell Back Squat                                       titleLarge
 Goal 235. Last time 235 × 5, Oct 21.                     bodyMedium, onSurfaceVariant
 ─────────────────────────────────────
 [✓]  R1    130 × 5                                       ListItem: Checkbox, notation, value
 [✓]  R2    165 × 5
 [ ]  R3    190 × 5                          Now
 [ ]  R4    210 × 3
 [ ]  TOP   235 × 5
 [ ]  B/O   175 × 8
 Add a set                                                TextButton
 ─────────────────────────────────────
 ⇄  Swap exercise                                          ListItem with leading icon
 ⚖  Add weight                                             only when the catalog offers the swap
 ✎  Note                         "Knee wraps on R4+"       note preview as supporting text
```

- A checkbox ticks or unticks a set out of order (today's All sets). A row's text opens the
  set edit sheet for that set. "Now" marks the set in focus in words.
- Swap, Add or Remove weight and Note are today's card menu (`ExercisePage.kt:153-186`).
  Swap shows only when the slot has a pattern to rank alternates against
  (`DayScreen.kt:134-136`).
- Replacing a started slot's exercise deletes its logs: every such path ends in
  `swapInPlan` → `deleteLogsForExercise` (`data/.../TrackerRepository.kt:665-670`). There are
  three: Swap here, Add or Remove weight (its confirm calls the same swap,
  `DayScreen.kt:418`), and Swap in Edit day. On a slot with sets logged today, each first
  confirms with one copy, "Swap Barbell Row? Its 2 logged sets today are removed." (Add
  weight's today says only "Your sets reseed from its goal.", `strings.xml:407`), and
  afterward a Snackbar offers Undo through the slot restore described under Edit day. With
  nothing logged, they act as today with no confirm.
- For cardio, the same sheet is the Cardio sheet: the block's sets with their status in
  words ("Finished", "Shortened, 350 m", "Skipped", "Now"), then "Skip set", "Add set",
  "End cardio" (with today's confirm, `DayScreen.kt:449-457`), and "Enter distance" rows for
  any unsaved distance.

### Set edit sheet

One job: change one set's numbers.

Today's sheet (`ui/day/SetEditSheet.kt`), kept with three changes: the title is "Barbell Back
Squat, R3" with the plate line under it; "Remove set" moves out of the overflow menu to a text
button in `error` at the bottom (the Snackbar undo stays); Done uses the neutral `primary`,
since this sheet is a correction, not the day's action. The note "Warm-ups and back-off
follow this weight." under a TOP set stays (`strings.xml:567`), because it is the cascade
rule the lifter is about to trigger.

### Day sheet

One job: show the rest of the day and the few things that change it.

```
 Day A                                                     titleLarge
 Squat focus, 2 of 18 sets                                 bodyMedium
 ─────────────────────────────────────
 Barbell Back Squat             2 of 6, now               ListItem; tap jumps there
 Barbell Bench Press            6 sets
 Barbell Row                    3 sets
 Plank / Side Plank             3 sets
 Zone 2                         Cardio, 4 sets            the cardio block, when the day has one
 ─────────────────────────────────────
 ✎  Edit day
 ↺  Clear today's checks                                   today's confirm dialog
```

It replaces the Exercises sheet (`AllSetsSheet.kt:113-130`), the workout's overflow menu
(`DayScreen.kt:264-305`). Finished lifts show a check icon and "Done". The cardio block is a
row like a lift. Finish is not here: it is in the app bar, one tap from every set.

### Edit day

One job: change what this day contains.

A full-screen route instead of today's nine-page sheet (`DayEditSheet.kt:139-146` lists the
pages its back handler has to unwind). `TopAppBar` "Edit day A" with a close icon; a list of
the day's slots as `ListItem`s (name; pattern or "With cable row" as supporting text) with
an overflow menu per row: Swap exercise, Add superset or Swap superset partner, Remove
superset, Prescription, Move up, Move down (the last three for authored plans, as today,
`DayEditSheet.kt:386-393`), Remove. Below the list: "Add exercise", "Add cardio" or "Edit
cardio", and "Reset day to template" (generated plans only, with today's confirm).

- Remove takes effect at once and shows a Snackbar "Barbell Bench Press removed" with Undo (audit
  item 15). `removeExercise` deletes the slot's logs (`data/.../TrackerRepository.kt:696-711`),
  so the undo needs a repository restore: the same `programExerciseId`, its position and
  prescription, both tracks with today's ticks, its note and its superset partner. Remove is
  unavailable at the day's minimum exercise count (`ui/day/DayEditLogic.kt:42-43`), as today;
  "Add superset" shows only for generated slots without a prescription
  (`DayEditSheet.kt:394`).
- Add and Swap open the exercise picker: a search field first over the whole catalog, with
  pattern `FilterChip`s under it (Swap preselects its pattern) and equipment chips behind a
  "Filters" chip. Full pattern names ("Horizontal push"), never "H push"
  (`strings.xml:454`). "Create exercise" at the end of the results opens the custom
  exercise screen with the pattern prefilled.
- The prescription and cardio block pages keep their content as full-screen sub-routes.

### Receipt

One job: confirm the session saved and say what comes next.

An opaque full-screen route, replacing the cascade scrim and the 97% receipt scrim.

```
                                              [↗ Share]    TopAppBar, actions: Share
 Day A, done.                                              displaySmall, onSurface
 Friday, Oct 30                                            bodyLarge, onSurfaceVariant
 18 sets in 52 min                                         bodyLarge

 New top set                                               labelLarge, onSurfaceVariant (only when the cascade fires)
 Barbell Back Squat    ~~235~~  245                        the strike, then the number rolls in
 
 Next                                                      labelLarge
 Day B, Upper                                              titleLarge

 Add a note                                                TextButton
 [               Done               ]                      Button, filled, day primary
```

- Partial: "Day A, done." then "9 of 18 sets in 31 min". No "ENDED".
- Minutes come from the session's `startedAt` and `completedAt`
  (`data/.../db/entity/SessionEntities.kt:17-18`); when `startedAt` is null the line is just
  "18 sets".
- The cascade's lifts are the rows under "New top set", in the order performed, one row per
  lift (`CascadeCeremonyBuilder.from`). The old number is struck over 300 ms and the new one
  rolls in, as today's scrim does (`CascadeScrim.kt:105-111`); under reduced motion both
  numbers appear, the old one struck. Reading order and TalkBack order are the same: the
  headline, the new top sets, next, the note, Done. That keeps the order the scrim's comment
  protects ("the news the session made, then the ledger of it, then the way out",
  `DayScreen.kt:544-551`) without a second surface.
- The moment is no longer lost to process death: the cascade rows are computed once at
  finish and stored with the receipt in `SavedStateHandle`, so a rotation or a process
  death while the receipt is open still shows them. They are never recomputed from history.
- Cut: "STRONGEST" (`SessionReceipt.kt:97-100`). It is a load scoreboard in a maintenance
  app, and when nothing is weighted it vanishes; the new-top-set row says the one thing about
  load that matters. Cut: the caps and tracked labels, the hairlines, the scrim.
- "Next" carries the next day's letter and its title as authored (`GlanceLines.dayLine`
  uppercases it today, `domain/.../glance/GlanceLines.kt:16-17`).
- A cardio day's receipt already says "Day A, done." (`strings.xml:672`) and keeps its
  result lines.
- Share opens today's share card (`ui/log/share/`), which stays a dark image.

### Log

One job: show what I did.

```
 Log                                          [📅]        TopAppBar, Calendar action
 [ Sessions ]  [ Lifts ]                                   PrimaryTabRow
 October                                                   sticky header, titleSmall
 [A] Squat focus                          18 sets          ListItem: DayIndex Medium, title, trailing count
     Friday, Oct 30                                        supporting
 [D] Incline focus                        17 sets
     Wednesday, Oct 28
 [C] Deadlift focus                        9 of 18
     Monday, Oct 26
 (run) Treadmill run                       25 min          a cardio session: activity icon, duration
     Sunday, Oct 25
 (♡) Strength training                                     a Health Connect session from another app
     Friday, Oct 23, from Fitbod
 September
 ...
```

- Sessions are the first thing under the tabs, newest first, grouped by month. A partial
  session shows "9 of 18" instead of a total.
- A row opens the **Session** screen (below). Today's expand-in-place with an edit mode
  inside the list (`LogScreen.kt:269-460`) goes; the jump index that had to count the
  sections above the list (`LogScreen.kt:242-252`) goes with it.
- Sessions from other apps through Health Connect sit in the same list by date, with a
  heart icon instead of a day block and "Friday, Oct 23, 6:10 to 7:02 PM, from Fitbod" in
  the supporting line (`ExternalWorkout` carries the start and end,
  `transfer/.../health/HealthReadModels.kt:12-19`). They are display-only: no ripple, no
  chevron, nothing to open, since the app holds only a title, two times and a source; TalkBack
  reads "from Fitbod". Today they are a separate section after every session
  (`LogScreen.kt:175-180`).
- A cardio session opens a read-only Cardio session screen: the activity, the date, the
  duration and the interval results (today's `CardioSummary`), with no edit, delete or share,
  as the Log's cardio card has none today (`LogScreen.kt:209-234`). Only strength sessions
  open the Session screen below.
- **Lifts tab**: one card per main lift, in rotation order: the name, the latest top set as
  a number ("245"), the single-series trajectory in that lift's day color (`accentEmphasis`,
  `docs/briefs/journal.md` §0), the goal as a dashed line labeled "Goal 235", and "12
  sessions since Aug 4". A lift with one session shows its number and goal without a chart,
  as today (`JournalSections.kt:89-97`).
- **Calendar**: a full-screen route with the month grid, the day letter on each trained
  day, `IconButton` chevrons for the months; a trained day opens its session.
- The bodyweight prompt moves to Plan; Health Connect status and "Publish 3 past workouts"
  move to Settings. Volume is cut.
- States:
  - **Loading**: a neutral `LinearProgressIndicator` under the tabs, distinct from empty
    (today the initial `LogUiState()` flashes the empty state, audit item 11).
  - **Empty**: "No sessions yet." and, when a plan exists, "Start day A" as a text button.
    Today's "YOUR FIRST SESSION WILL LAND HERE" (`strings.xml:386`) goes.
  - **Lifts tab, empty**: "Each main lift's top sets show here after two sessions."
  - **Deleted**: back on the list, a Snackbar "Session deleted" with Undo replaces the
    in-list undo row (`LogScreen.kt:433-443`).

### Session

One job: show one session and let me correct it.

```
 [←]  Squat focus                       [↗] [⋮]           TopAppBar: Share; overflow: Delete session
 Friday, Oct 30                                            bodyLarge
 18 sets in 52 min                                         bodyLarge, onSurfaceVariant
 Barbell Back Squat                                                titleMedium
   R1 130 × 5    R2 165 × 5    R3 190 × 5                  FlowRow of TextButtons, each opens the set edit sheet
   R4 210 × 3    TOP 235 × 5   B/O 175 × 8
 Barbell Bench Press
   ...
 Note                                                      titleMedium
 Knee wraps from R4.                       [Edit]          or "Add a note"
```

Edits use the set edit sheet's history variant: the same numeric fields, a "Done" checkbox
(today's `updateSessionSetDone`, `ui/log/LogViewModel.kt:255`), no Remove and no cascade
note, because history edits change only weight, reps, seconds and done
(`LogViewModel.kt:227-262`).
Delete confirms in `error` ("Delete this session?", "Its sets leave the log, the
trajectories and Health Connect.", today's copy at `strings.xml:340-341`), then returns to
the list with the Undo Snackbar. A set not done shows struck through with "skipped" for
TalkBack, so a partial session reads truthfully.

### Plan

One job: see and change the rotation and its goals.

```
 Four-day strength                      [Plans] [⚙]       TopAppBar
 ┌ Health Connect has 228 lb. Goals use 235 lb.  ┐        Card, only when the bodyweight differs
 └ [Update]  [Not now]                           ┘
 Rotation                                                  titleSmall
 [A] Squat focus                          Next             ListItem: block, title, supporting, trailing "Next"
     4 lifts, 18 sets
 [B] Upper
     5 lifts, 20 sets
 [C] Deadlift focus
 [D] Incline focus
 + Add day                                                 TextButton, authored plans, under 7 days
 Goals                                                     titleSmall
 Barbell Back Squat                        235             ListItem, trailing titleLarge numeral, onSurface
 Barbell Bench Press                       195
 Trap-Bar Deadlift                         255
 Incline DB Press                  75 a hand
 Training                                                  ListItem with chevron
 235 lb, age 40, intermediate
```

- A day row opens **Day**: Today's layout for any day (the block, title, lift names, now
  with each lift's prescription as supporting text, "6 sets, top 255 × 5" or "3 sets at 330"), with
  "Start day C" as the action and "Edit day" in the app bar. Today a row starts that workout
  silently (`PlanScreen.kt:110`). When C is not the next day, one line under the title says
  what starting it does: "Next in rotation is day A. Finishing C makes D next." (the
  rotation follows the finished day, `DayViewModel.kt:1125-1127`).
- The overline "Day A" beside the block goes (`PlanScreen.kt:114-116`).
- **Goals** are today's live preview from Setup (`SetupScreen.kt:209-230`), in `onSurface`
  rather than Day A's accent. The numbers are the pinned ones (spec §11 for the reference
  lifter: 235, 195, 255, 75 a hand); this design only moves where they show. The goals are
  the generator's anchor lifts (`ui/setup/SetupStateBuilder.kt:37-45`); on an authored plan
  only the anchors the plan contains in a slot without its own prescription are listed (a
  prescription, not the goal, drives that slot), and with none the Goals group is absent.
- **Training** is a sub-screen: the goals at the top updating live, then "You": bodyweight
  with its unit, age, and experience (`SegmentedButton`: Novice, Intermediate, Advanced);
  then, for generated plans only, "This plan": emphasis (three `RadioButton` rows with
  today's descriptions) and cardio (mode and placement as exposed drop-down menus, "Keep me
  5k-ready" as a switch). For an authored plan the "This plan" group is absent, rather than
  shown and silently ignored (audit item 13).
- The bodyweight prompt is a `Card` at the top of Plan, with "Update" (tonal) and "Not now"
  (text). Plan is where bodyweight now lives; owner question 10 of the audit.
- **Plans**: today's screen (`PlansScreen.kt`), with sentence-case "Archived", the FAB in
  neutral primary, and "Next: none" in place of the dash at `strings.xml:77`.
- States: loading (neutral bar), no program (the Today empty state), an authored plan with
  no days ("No days yet." and "Add day").

### Settings

One job: change how the app behaves.

```
 [←]  Settings                                             TopAppBar
 Units                       [ lb | kg ]                   ListItem + SingleChoiceSegmentedButtonRow
 Theme             [ System | Dark | Light ]
 Keep screen on during workouts            (on)            ListItem + Switch; scope changes, see below
 Rest timer                                        ›       "Phone and watch"
 Health Connect                                    ›       "Connected" | "Not connected"
 Backup                                            ›       "Automatic, last backup yesterday" | error text
 Custom exercises                                  ›
 Licenses                                          ›
```

- Theme as a three-way segmented button replaces three selection cards with descriptions
  ("Keep the lights low.", "Warm paper and ink.", `strings.xml:133,135`).
- **Rest timer**: switches "On this phone" and "On the watch", then five rows with the
  current length (Ramp 1:30, Top set 3:00, Back-off 2:00, Accessory 1:30, Bodyweight and
  timed 1:00, the defaults in `domain/.../standards/RestPolicy.kt:31-37`), each opening a
  dialog with the shared numeric field; "Reset to defaults" with today's confirm. The deep
  link by list index (`SetupScreen.kt:93`) goes with the old screen.
- **Keep screen on** is an app-wide window flag today (`MainActivity.kt:80-89`). The
  proposal scopes it to the workout route (the flag moves from `MainActivity` to the day
  screen's `DisposableEffect`, beside the execution clock's flag at `DayScreen.kt:179-182`)
  and renames it to say so. Its default is owner question 4.
- **Health Connect**: a status line ("Connected" with a check icon, then "Writing workouts
  and calories." and "Reading sessions from other apps and bodyweight." for the granted
  permissions, or "Not connected"), "Manage permissions" (opens the system screen and the rationale
  activity), and "Publish 3 past workouts" when there are any (`LogScreen.kt:543-560`
  today). This answers #320.
- The policy footer ("Rotation, not calendar...", `strings.xml:418`) is cut, as the audit
  proposed.
- A backup failure is mirrored on the Backup row in `error` text: "Backup folder
  unavailable".

### Backup

One job: know the data is safe, and move it in or out.

```
 [←]  Backup
 ✓ Automatic backup is on                                  status, titleMedium; icon + words
   Last backup yesterday, 9:14 PM. Folder: Backups/strength.log
 Automatic backup                          (on)            ListItem + Switch
 Backup folder             Backups/strength.log    ›
 ─────────────────────────────────────
 Export backup                                             ListItem, supporting "Everything, as one JSON file"
 Restore from backup                                       supporting "Replaces all data on this phone"
 Export history as CSV                                     supporting "For spreadsheets, Hevy, FitNotes"
 Import CSV history
```

- The first thing on the screen is whether the data is protected. Today it is a card's
  faint small text (`BackupScreen.kt:145-167`, audit item 14).
- Failure: the status is an `errorContainer` card, "Backup folder unavailable" and "Last
  backup Oct 26." with "Choose folder" as its action; a transient failure reads "Last attempt
  failed. It will retry." The copy moves from code (`ui/backup/BackupModels.kt:97-104`) to
  resources.
- Busy: a `LinearProgressIndicator` at the top and the rows disabled with the default
  disabled colors. Results: a Snackbar ("Backup exported.", "Imported 12 sessions, 214
  sets."). Errors that need reading stay as a dialog with today's copy
  (`strings.xml:297-315`).
- Restore confirms in `error` ("Replace all data"). CSV import preview is a full-screen
  dialog: "12 sessions, 214 sets will be added.", then each unmatched exercise name with an
  exposed drop-down for its movement pattern, and "Import" in the app bar, disabled until
  every name has a pattern.

### Custom exercise

One job: add one exercise.

A full-screen dialog ("New exercise", close icon, "Save" in the app bar): Name field first;
Movement pattern as an exposed drop-down (prefilled when reached from a picker); Equipment as
`FilterChip`s; "How it's tracked" as a segmented button (Weighted, Reps, Timed) with today's
description of the chosen one under it; then the fields that kind needs (starting weight,
target reps or target hold, "Per hand" switch, optional added load). Save failure shows in
`error` under the app bar (today it has no error state, audit owner question 7).

### Wizard

One job: produce a rotation the lifter accepts.

Three screens on first run, under a `TopAppBar` with a `LinearProgressIndicator` (1 of 3):

1. **Days per week**: "How many days a week?" with a `SingleChoiceSegmentedButtonRow` of
   2, 3, 4, 5, 6 (default 4). Under it, "Restore a backup" as a text button.
2. **About you**: bodyweight with its unit (lb or kg from the device locale,
   `ui/wizard/DeviceWeightUnitProvider.kt`), age, experience as a segmented button. One
   line: "These set the starting goals."
3. **Your rotation**: one `ListItem` per day (block, title as generated, the main lift and
   its goal as supporting text), "Missing a day never skips the next workout."
   (`strings.xml:233`), then "Use this plan" (filled) and "Change answers" (text). "Use this
   plan" saves and lands on Today with day A ready; it does not say Start, because it does
   not start anything (`WizardScreen.kt:584`).

"Change answers" opens the other five steps (emphasis, split, anchors, cardio, equipment)
as one scrolling screen of grouped controls with today's defaults selected, and returns to
the rotation. The anchor labels lose "5/3/1-style" in favor of the lifts they name. A new
plan from Plans runs the same three screens with a close icon. The generator is untouched.
The short path takes every default, including all equipment (`domain/.../generator/WizardAnswers.kt:41`);
the rotation screen's lift names show what that picked, and a lifter without a trap bar
fixes it in Change answers (equipment) or later with Swap. Owner question 12.

### Widget and launcher icon

- **Widget**: the day's letter block on the left; "Squat focus" and "2 of 18 sets" on the
  right; a thin progress bar in the day's derived accent. Light and dark from
  `values`/`values-night` and `RemoteViews.setColorInt` on API 31 and up (audit item 23).
  Sentence case: `GlanceLines` returns "Day A, Squat focus" and "4 lifts, 18 sets", "2 of 18
  sets", "Done, 18 sets", for the phone and the watch alike (the watch redesign is in
  sentence case too).
  The picker preview shows a sample day, not "SET UP YOUR PROGRAM". Tap opens the app.
- **Launcher icon**: each day alias shows its letter in Barlow Condensed Bold on the day's
  raw accent, and a monochrome layer with the letter, so themed icons keep the day (today
  the monochrome layer is one barbell for every day, `res/drawable/ic_barbell_monochrome.xml`).
  The splash uses a neutral mark. Store icon: owner question 9.

## What was cut, merged or moved

| Today | Fate | Why |
|---|---|---|
| The `HorizontalPager` of lifts, its 40 dp peek and 12 dp spacing (`DayScreen.kt:395-402`) | Cut. Logging advances in plan order; the Day sheet jumps | A swipe on a page with a button in it is an accident waiting for wet hands, and the watch cut the same gesture. The pager's reasons were "a pro workout app", no vertical list (#260) and the watch's grammar. The first survives in the one-set screen; the second needs no pager, since nothing here is a list; the third reversed when the watch dropped its rings and swipes today. The peek also takes 52 dp from the numeral's width |
| Day segments and "Lift 1 of 4", "Next: …" (`DayScreen.kt:337-384`) | Moved to the Day sheet | Position said three ways; segments differ by color only (audit item 7) |
| Set segments (`ExercisePage.kt:254-277`) | Replaced by "Set 3 of 6 (R3)" | One line of text says it; six 6 dp bars need counting and color |
| The exercise card and its overflow (`ExercisePage.kt:94-188`) | Card cut; menu moved to the Lift sheet | The card drew a box inside the screen around the only content |
| "Goal 235" and "Last …" in the card header (`ExercisePage.kt:105-147`) | Moved to the Lift sheet | Reference outranked the action (the October diagnosis, point 1) |
| "210 × 3 next" (`ExercisePage.kt:285-291`) | Cut | The rest band's screen already shows the next set as the hero |
| Finished-lift state and the next-lift button (`ExercisePage.kt:294-304`) | Changed: a log from the button moves on with no stop; the completed view (one summary line and "Next: …") appears only when a lift is completed another way | The finished card stopped the lifter after every lift with two filled buttons |
| "All sets" text button (`ExercisePage.kt:341-346`) | Merged into the Lift sheet behind the name | One way in |
| The permanent filled "Finish day A" button (`DayScreen.kt:317-331`) | A text "Finish" in the app bar; the action slot once every set is logged | One filled button per screen, and Finish stays one look away; the partial-finish confirm exists because finishing early by mistake is a real slip |
| The app bar's "2 of 18" and the small block (`DayScreen.kt:251-261`) | Cut; "Day A" as the title | The Day sheet carries progress; the button's color carries the day |
| Workout overflow menu (`DayScreen.kt:264-305`) | Edit day and Clear checks to the Day sheet; Keep screen on and Rest timer to Settings | Preferences are not workout actions |
| Rest pill with a time menu (`DayScreen.kt:706-745`) | The rest band | The time was a button that opened a menu; the band shows the actions and the undo |
| −15 s | Cut | Skip rest covers it |
| Cascade scrim (`CascadeScrim.kt`) | Merged into the receipt as a row | Three surfaces and three taps after Finish; the strike and roll survive |
| Receipt scrim at 97% over the workout (`SessionReceiptScrim.kt:104`) | An opaque route | The workout showed through (07-receipt) |
| "STRONGEST" | Cut | A scoreboard; the new-top-set row says what matters |
| Today's "Day A" label and life line (`TodayScreen.kt:139-143,166-177`) | Cut | The block says A; one fact is enough |
| Lift numbers and set counts on Today | Cut | The names say what is in the day |
| Log's analytics-first order (`LogScreen.kt:108-146`) | Sessions first; Lifts tab; calendar behind an icon | Order by frequency of use |
| Volume chart | Cut | Answers a question this lifter does not ask; colored as Day A |
| Expand-in-place session rows with an edit mode (`LogScreen.kt:269-460`) | A Session screen | A list that changes height and mode under the finger; one edit sheet for the app |
| Health Connect card and status in Log | Settings | Administration |
| Bodyweight prompt in Log | Plan | Where bodyweight lives |
| Setup (`SetupScreen.kt`) | Split: Training (Plan) and Settings | Two jobs |
| Setup's rotation-policy footer | Cut | Explanation nobody asked for |
| Eight wizard steps | Three, plus "Change answers" | The defaults already produce the spec's program |
| Day edit as a nine-page sheet | A full-screen route | Pages in a sheet; back has to unwind each |
| Pattern-first Add (19 cards before an exercise, `DayEditSheet.kt:681-704`) | Search first | Start from the exercise the lifter wants |
| `SelectionCard`, `SwitchToggle`, `Stepper`, `CheckmarkToggle`, `MonthChevron`, `EquipmentFilterRow`, compact `SheetButton` (the August KEEP list, #232) | Stock M3 (`ListItem` with `RadioButton`, `Checkbox` or `Switch`; `SegmentedButton`; `FilterChip`; `IconButton`); one numeric field for every number | The geometry they protected is gone (audit item 25) |
| Hand-rolled headers on Log, Setup, Backup, Licenses, wizard, custom exercise | `TopAppBar` everywhere | One app bar |
| The rest notification's caps title (`strings.xml:143`) | "Rest" with a chronometer | House rules; a posted title cannot count down |
| Glance caps (`GlanceLines.kt:16-31`) | Sentence case on the phone | A phone home screen is not a dial |

Kept from the October redesign: one set in focus at display size; the button that says
what it logs ("Log 190 × 5"); R1, TOP and B/O as the only caps; edits in a bottom sheet; no
day selector inside the workout; "Day A, done."; the Today / Log / Plan bar; the day index
block as the mark; the coach line as a fact; "no bespoke components".

## Material 3 components

| Component | Where |
|---|---|
| `NavigationSuiteScaffold` (bar on compact, rail on medium and wider) | Today, Log, Plan |
| `TopAppBar` (small) with a plain navigation `IconButton` | Every screen |
| `Button` (filled), `FilledTonalButton`, `OutlinedButton`, `TextButton` | One filled action per screen; tonal during a rest; outlined for cardio Stop; text for Finish in the workout's app bar and other secondary actions |
| `Surface` (surfaceContainer, `shapes.large`) with `Text`, `LinearProgressIndicator`, `TextButton` | The rest band |
| `LinearProgressIndicator` (determinate and indeterminate) | Rest, running clocks, loading, backup busy, wizard progress |
| `ListItem` with `Checkbox`, `RadioButton`, `Switch`, leading icons, trailing text | Lift sheet, Day sheet, Today, Log, Plan, Settings, Backup |
| `ModalBottomSheet` | Lift, Day, Set edit, Count, Distance, Note, plan picker |
| `SingleChoiceSegmentedButtonRow` | Units, theme, experience, days per week, tracking type |
| `ExposedDropdownMenuBox` | Movement pattern, cardio mode and placement |
| `FilterChip` | Picker patterns and equipment, custom exercise equipment |
| `AssistChip` | A clock running on another lift, under the workout's app bar |
| `OutlinedTextField` with `IconButton` minus and plus | The one numeric field (set edit, rest lengths, bodyweight, age, distance, reps count) |
| `PrimaryTabRow` | Log: Sessions, Lifts |
| `Card` (filled) | Lifts tab charts; the bodyweight prompt; backup failure. Nowhere else |
| `AlertDialog` | Confirms: finish early, clear checks, delete session, restore, reset, remove day |
| `Snackbar` | Remove set, remove exercise, delete session, backup results |
| `ExtendedFloatingActionButton` | New plan, on Plans |
| `DropdownMenu` | Row overflow in Edit day, Plans, authored Plan days |
| `FlowRow` (foundation) | Set chips on the Session screen |

Custom pieces, each with what M3 lacks, for its KDoc:

- **Trajectory chart** (`Canvas`). M3 has no chart. Single series, goal line, new-high
  markers; today's code with sentence-case captions.
- **Calendar month grid**. M3's `DatePicker` can show a month but cannot mark days with a
  letter on an accent, and it is a picker, not a record. A `LazyVerticalGrid` of 48 dp cells,
  with 12 dp side gutters on this screen so seven columns fit 360 dp
  with `Surface` marks.
- **The strike and roll** on the receipt's new top set. M3 has no text-strike animation; a
  `drawWithContent` line over the old number and `AnimatedContent` for the new one, both
  collapsing under reduced motion.
- **The double-tap guard** on the action slot. Not a component: a 600 ms ignore window in
  the click handler, with a unit test.

Not custom: the day index block (a `Surface` with a `Text`), the hero line (a `Text` with an
`AnnotatedString` and `TextAutoSize`), the rest band (a composition of stock parts).

## Theme

### Color roles

The scheme is complete in both expressions (`ThemeCompletenessTest` keeps holding that).
Two changes of principle:

1. **`background` equals `surface`** (audit item 20): one ground, and cards, sheets, dialogs
   and the navigation bar step up through the container roles.
2. **`primary` is scoped.** Off day surfaces (Log, Plan, Settings, Backup, wizard, sheets
   that correct rather than act) `primary` is neutral ink. On the day surfaces (Today, the
   workout, the receipt, a Plan day) a `MaterialTheme` wrapper sets `primary` to that day's
   derived accent: `brightHex` on dark with near-black ink, `deepHex` on light with paper ink.
   The wrapper encloses only the one filled action (the workout's action slot, Today's
   Start, the receipt's Done, the Day preview's Start), so every other control on a day
   surface reads the neutral `primary` with no per-call color overrides.
   Raw accents stay identity fills only: the day index block, the launcher icon, the widget
   block. In one sentence: raw accents mark the day; derived accents act for it.

| Role | Dark | Light | Notes |
|---|---|---|---|
| `surface`, `background` | `#0D0D0F` | `#F1EFEA` | One ground |
| `surfaceContainerLowest` | `#0D0D0F` | `#FAF9F6` | |
| `surfaceContainerLow` | `#131316` | `#F6F4EF` | New dark and light steps, between the existing ones |
| `surfaceContainer` | `#16161A` | `#EFECE6` | Navigation bar, rest band, sheets |
| `surfaceContainerHigh` | `#1D1D22` | `#E9E6E0` | Dialogs, menus |
| `surfaceContainerHighest` | `#26262C` | `#E1DDD5` | Filled cards, tonal buttons |
| `onSurface` | `#F2F2F0` (17.3:1) | `#1B1B1E` (15.0:1) | The hero numerals |
| `onSurfaceVariant` | `#9A9AA2` (6.9:1; 5.4:1 on Highest) | `#5C5C64` (5.8:1; 4.9:1 on Highest) | All secondary text. TextFaint (3.7:1 and 2.9:1) is never text again |
| `outline` | `#808086` (5.0:1; 3.8:1 on Highest) | `#7A756C` (4.0:1; 3.7:1 on High, 3.4:1 on Highest) | Boundaries that must read, M3's meaning; the audit's `#8A857C` fell to 2.9:1 on the dialog surface |
| `outlineVariant` | `#2A2A30` | `#D8D4CC` | Dividers, tracks |
| `primary` (off day) | `#F2F2F0` | `#1B1B1E` | Neutral ink |
| `onPrimary` (off day) | `#0D0D0F` | `#FAF9F6` | |
| `primary` (day) | `brightHex(i)` | `deepHex(i)` | Table below |
| `onPrimary` (day) | `#0D0D0F` | `#FAF9F6` | 5.1:1 or better on every day, both expressions |
| `primaryContainer` (day) | raw accent | raw accent | The block; `onPrimaryContainer` is `onDayAccent(i)` |
| `secondaryContainer` | `#26262C` | `#E1DDD5` | Tonal button fill; `onSecondaryContainer` = `onSurface` |
| `tertiary` | neutral, as `secondary` | | Day D as `tertiary` (`Theme.kt:73`) goes |
| `error` | `#E5677D` (6.1:1 on ground, 5.3:1 on High) | `#B02A43` (5.6:1 on ground, 5.2:1 on High, 4.8:1 on Highest) | Dark error as text failed at 3.6:1; light `#C2334D` is 4.35:1 on the dialog surface, so its destructive confirms failed. `#C2334D` stays as the identity in the error containers |
| `onError` | `#0D0D0F` | `#FAF9F6` | |
| `errorContainer` / `onErrorContainer` | `#3A1A22` / `#F2F2F0` | `#F6DADF` / `#5E0F1F` | Failure cards |
| `inverseSurface` / `inverseOnSurface` / `inversePrimary` | `#F2F2F0` / `#0D0D0F` / `deepHex(i)` or `#1B1B1E` | `#1B1B1E` / `#F1EFEA` / `brightHex(i)` or `#F2F2F0` | Snackbar Undo legible (audit item 4) |
| Done | `#3E8E5A` | `#37774E` | Check icons beside the word "Done"; never alone |

Contrast of the day primary, computed from `DayAccentColors` (`brightHex` is a 35% lift
toward `#F2F2F0`, `deepHex` a 30% move toward `#1B1B1E`, `DayAccentColors.kt:81,89`):

| Day | Raw (identity) | Dark `primary` | Ink on it | On `#16161A` | Light `primary` | Paper ink on it | On paper |
|---|---|---|---|---|---|---|---|
| A | `#C1440E` | `#D2815D` | 6.5:1 | 6.0:1 | `#8F3813` | 7.3:1 | 6.7:1 |
| B | `#2D5A3D` | `#728F7C` | 5.5:1 | 5.1:1 | `#284734` | 9.8:1 | 9.0:1 |
| C | `#B8860B` | `#CCAC5B` | 8.9:1 | 8.3:1 | `#896611` | 5.0:1 | 4.6:1 |
| D | `#1F4E5F` | `#698792` | 5.1:1 | 4.7:1 | `#1E3F4C` | 10.7:1 | 9.8:1 |
| E | `#3C4E78` | `#7C87A2` | 5.4:1 | 5.0:1 | `#323F5D` | 10.0:1 | 9.1:1 |
| F | `#8B4356` | `#AF808C` | 5.8:1 | 5.4:1 | `#693745` | 8.9:1 | 8.2:1 |
| G | `#6B6A2C` | `#9A9A71` | 6.7:1 | 6.2:1 | `#535228` | 7.7:1 | 7.0:1 |

One limit, in both expressions: day-primary as text sits only on the ground, never on a
container. In light, C's deep tone is 4.48:1 on `surfaceContainer` and 4.2:1 on
`surfaceContainerHigh`; in dark, the bright tones on `surfaceContainerHighest` are 4.25:1
for B, 3.93:1 for D and 4.19:1 for E. With the wrapper scoped to the filled action, the only
day-primary text left is chart strokes on the Lifts tab, drawn on a card, which are marks
(3:1) rather than text; buttons and checkboxes are unaffected.

This changes the look of the store screenshots: in dark, Start and Log become a lighter
terracotta with near-black text instead of the raw terracotta with white text. The raw fill
fails the other way in dark (D is 2.1:1 against the ground, so the button's edge disappears),
and the watch redesign made the same choice for the same reason. Owner question 2.

### Dynamic color

None. The accent is information (which day it is), shared with the watch, the widget and the
launcher icon; a wallpaper palette would make `primary` mean nothing in particular, and every
pair in the tables above would have to hold for colors nobody has seen. Neutral-only dynamic
color would add a setting for a tint the user cannot name. The audit reached the same
answer (its owner question 1).

### Type

Barlow Condensed (tabular figures) for display, headline, title and label; the platform sans
for body, which follows the system font. The scale is redefined to hold the sizes the app
sets, so no screen copies `DisplayXl` with a size (audit item 18).

| Role | Face, size/line | Use |
|---|---|---|
| `displayLarge` | Condensed Bold 104/100, `tnum` | The set's load or clock, the cardio hero; `TextAutoSize` from 104 down to 56 to fit one line |
| `displayMedium` | Condensed Bold 56/56 | The "× 5" and "reps" beside the hero |
| `displaySmall` | Condensed Bold 40/44 | The rest countdown; "Day A, done."; goal numbers on Training |
| `headlineLarge` | Condensed Bold 32/36 | Today's day title |
| `headlineMedium` | Condensed Bold 28/32 | The lift name on the set screen |
| `headlineSmall` | Condensed SemiBold 24/28 | Trajectory latest value |
| `titleLarge` | Condensed SemiBold 22/28 | App bar titles; the hero button's label; sheet titles; the plate line |
| `titleMedium` | Condensed SemiBold 17/22 | "Set 3 of 6 (R3)", list headlines on Plan |
| `titleSmall` | Condensed SemiBold 15/20 | Section heads ("Rotation", "Goals"), month headers |
| `labelLarge` | Condensed Medium 15/20, 0.1 tracking | Buttons, tabs, "Rest" |
| `labelMedium` | Condensed Medium 13/16, 0.5 tracking | Navigation labels |
| `labelSmall` | Condensed Medium 12/16, 0.5 tracking | Chart axis labels |
| `bodyLarge` | Sans 16/24 | Fact lines, descriptions |
| `bodyMedium` | Sans 14/20 | Supporting text |
| `bodySmall` | Sans 12/16 | Tertiary lines only, never a status |

The caps tracking of 1.0 and 1.2 sp on the label roles (`ui/theme/Type.kt:248,255`) goes,
since caps are retired. The day index letter keeps its dp size (`ui/components/DayIndex.kt`):
it is a mark, not text.

### Shape

M3's baseline scale: 4, 8, 12, 16, 28 dp (today's 10 dp `medium`, `ui/theme/Shape.kt:27`,
goes). Cards and the rest band 16 (`large`); the day index block 8 at Large and 6 at
Medium and Small, as now; sheets and dialogs 28; buttons fully round, with no per-call
`shape =` overrides (audit item 19).

### Motion

`MaterialTheme.motionScheme` everywhere. Motion is spent on four changes only: the lift block
sliding when the workout advances to another lift, the action label crossfading after a log,
the rest bar draining, and the receipt's strike and roll. Reduced motion (the system animator
scale) collapses all four. The Start press scale, the receipt's entrance spring and stagger,
the loading sweep through day colors and the checkmark bounce go (audit item 27).

## Large font scale

At 200% (Android's largest), drawn for Today and the set screen in the mockups:

- Today: the title wraps to two lines beside the block; the fact line wraps to three; the
  lift list scrolls above a pinned Start button. The block stays its dp size.
- The set: the lift name wraps (the row grows past 56 dp); "Set 3 of 6 (R3)" stays one line
  at 34 sp; the hero is fitted to the width by `TextAutoSize` and stays one line (at 360 dp
  "190 × 5" holds about 96 sp; "102.5 × 5" in kg about 64 sp); the plate line wraps to two
  lines. The band's three buttons wrap to two rows. The action button grows to 88 dp with a
  two-line label limit.
- If the fixed column cannot fit (landscape, split screen, 200% on a short phone), the hero
  block scrolls and the band and action stay pinned at the bottom; the action is never
  scrolled off (audit item 7). In portrait at 100% to 150% nothing scrolls. This is a
  deliberate exception to the house rule that nothing on the workout page scrolls in
  portrait: at 200% on a short phone the alternative is clipped text.

## Implementation cost and phases

Sizes: S is under a day of agent work plus review, M a few days, L a week or more. The whole
is L to XL: about fifteen PRs over five to seven weeks. The screens are mostly rebuilt on the
existing ViewModels' state. The domain math, the timed-rep state machine, the backup engine
and the sync layer are untouched. What does change outside the UI:

- `DayViewModel` and `:data` `SettingsStore`: the selection rule and the workout record
  (selection and undo target in DataStore), `undoLastLog()`, no rest after the day's last
  set, cancelling the rest on Clear checks and a day switch (Finish is #338).
- The cardio run and `:data`: Redo (a rewind transition that also drops a self-started
  recovery or cool-down) and an amend that replaces a saved interval's result in place.
- Phone ticks stamp `completedAtMillis`, which the Undo rule reads.
- `rest/PhoneRestRuntime.kt`: the notification's title, actions (non-exported receivers) and
  a new "Rest over" channel; the next-set label from the selection rule.
- `:domain` `GlanceLines`: sentence case, shared with the watch.
- `:data` `TrackerRepository`: the slot restore (same `programExerciseId`, position,
  prescription, both tracks with today's ticks, note, partner), built in phase 2 for the
  swap Undo and reused by Edit day's remove in phase 9.
- `MainActivity`: the keep-screen-on flag moves to the workout route.

Each phase merges with its tests and the CI screenshots, and leaves the app shippable. Where
a phase would otherwise depend on a later one, the table says how it waits.

| Phase | Content | Size | Risk |
|---|---|---|---|
| 1. Theme | Scoped `primary`; `background` = `surface`; error, outline, faint-text and inverse fixes; the type scale; the shape scale; motion tokens; light renders of every store shot as a review artifact (audit item 9). Accessibility fixes that need no redesign: a visible keyboard focus (the ripple's focused alpha is 0, `ui/theme/Theme.kt:173`; M3's 10%), the Health Connect rationale scrolls at large font (its column does not, `health/HealthRationaleActivity.kt:60`), and the light outline that reaches 3:1 on dialog surfaces | M | Medium: every screenshot changes; `ThemeCompletenessTest`, `DayAccentTest` and the guardrail test change |
| 2. The set screen | Replace the pager with the one-set screen; the selection rule and the completed view; the workout record in DataStore (selection and undo target); the plate line; the double-tap guard; the rest band with Undo; the action slot (Log, Finish day A) and the app-bar Finish; the swap confirm on all three swap paths and its Undo, with the slot restore in `TrackerRepository` built here, where it is first used; phone ticks stamp `completedAtMillis`; the Lift sheet (merging All sets and the card menu); the Day sheet (merging the Exercises sheet and the overflow); keep screen on moves from the overflow into Setup (Settings comes in phase 7). Built behind a `BuildConfig` flag that is off in release, because the pager still hosts the cardio page and the timed-rep clock. The `DayViewModel` rules (the selection rule, Undo, no rest after the last set) sit behind the same flag: the selection rule writes the selected page key, which today's pager follows (`DayScreen.kt:230-235`), so unflagged it would jump the old pager on a log | L | Medium: the most-used screen. Every `DayScreen` test moves; the selection rule gets a JVM table test of the cases in "Workout: the selection" |
| 3. Timed and cardio | Timed reps, holds and cardio in the same layout; the Cardio sheet; the count and distance sheets in the numeric field; save-failure band; the running-clock chip; the 600 ms Start, Stop and Reached guard; Redo with the in-place amend for a saved final interval. Its last PR turns the flag on and deletes the pager, so phases 2 and 3 reach users as one release | M | Medium: the cardio run gains a rewind and the data layer an amend; test that a redone final interval leaves one session and one advance |
| 4. Finish | The receipt route with the new-top-set rows, `SavedStateHandle` for the moment, minutes from `startedAt`; delete the cascade scrim and receipt scrim | M | Low to medium: the cascade's order and TalkBack order need tests |
| 5. Today and glances | Today; the Day route from Plan; the rest notification copy, actions and channel; widget light/dark and sentence case; `GlanceLines` in sentence case; the letter launcher icons | M | Low; the store icon waits for owner question 9 |
| 6. Log | Tabs, sessions-first list grouped by month, external sessions in the list, the Session route with the history edit sheet, the Calendar route, delete with Snackbar, loading state; delete the volume chart. The Health Connect card, backfill and bodyweight prompt stay on Log until phase 7 gives them homes | L | Medium: the Log ViewModel reshapes its output |
| 7. Plan and Settings | Goals on Plan, the Training screen, the bodyweight prompt; Settings, Rest timer, Health Connect screens; move the Health Connect pieces and keep screen on out of Log and Setup; Setup deleted | L | Medium: settings writes keep their repository calls; authored-plan hiding of emphasis and cardio |
| 8. Backup and custom exercise | Status first, failure with Choose folder, Snackbars, CSV preview dialog; custom exercise as a full-screen dialog with a save error | M | Low |
| 9. Edit day and the picker | Full-screen Edit day, per-row overflow, Snackbar undo on remove reusing phase 2's slot restore, search-first picker | M to L | Medium: the restore must bring back the slot's id, position, prescription, both tracks with today's ticks, note and partner |
| 10. Wizard | Three screens and Change answers | M | Low; the generator is untouched |
| 11. Navigation suite and cleanup | `NavigationSuiteScaffold`, icons (Log: history; Plan: a stacked-days icon in place of the calendar); retire the KEEP components and their guardrail entries; delete dead strings and tokens (audit "Delete outright") | M | Low |


## Risks and what to test on a real phone at the gym

These need the owner's phone in a real session; none can be judged from the renders.

- **Auto-advance.** After the last squat set, the screen moves to the bench press. Also jump
  to row first, finish it, and check the screen comes back to the squat; tick a set in the
  Lift sheet and check nothing moves under the sheet. Does the slide read
  as "moved on", or does the lifter think the app jumped? Run a whole day without opening
  the Day sheet and count the times you looked twice.
- **The double tap.** Tap Log twice fast with a wet thumb, ten times. Expect ten logs, not
  twenty. Then tap Log, wait about a second, tap again: that second tap should log the next
  set (it is a real second log).
- **Undo reach.** Log the wrong set on purpose; find and tap "Undo 165 × 5" without reading
  anything else. Check it names the right set across a lift change.
- **Arm's length.** Phone on the bench, standing at the bar: can you read the load and the
  plate line? The countdown? Try in both themes under the gym's lights; light mode under
  fluorescent glare is a real case for it.
- **Screen timeout.** With keep-screen-on off, how many looks hit a locked screen? This
  decides owner question 4.
- **The rest notification.** Phone in a pocket: does "Rest over" buzz and sound on a locked
  phone with the default-importance channel? Do +15 s and Skip rest work from the lock
  screen?
- **Light mode at the gym.** Deep-accent buttons (A is `#8F3813`) on paper: does the day
  still read as the day?
- **Large font.** System font at 200%: run a set and a rest; the action must stay on screen.
- **TalkBack.** The set screen reads "Barbell Back Squat, set 3 of 6, R3, 190 pounds for 5, 45 plus
  25 plus 2.5 a side, Log 190 pounds for 5, button"; after auto-advance it announces the new
  lift once; the band announces the remaining rest once a
  minute, not every second.
- **The Finish slot.** Log the last set and tap again at once, then within two seconds:
  neither tap may finish the day; a tap after the button fills does. Check the 3 s arming
  reads as "not yet" rather than "broken".
- **Finish.** Hit a new top set; check the row on the receipt, the strike and roll, and that
  rotating the phone mid-receipt keeps the row.
- **Identity.** Whether the app still feels like strength.log with no colored numerals and
  no segments. The owner judges this one.

## Questions for the owner

1. **Retire the pager.** The workout stops being a row of lift cards you swipe; logging moves
   on in plan order and the Day sheet jumps to any lift. This reverses the 12 October
   evening decision ("variant B"). The one-set focus, the Log button and the R1/TOP/B/O
   notation from that decision stay. Swipe or no swipe?
2. **The button's color in dark.** The proposal fills the one action with the day's bright
   accent and near-black text (5.1:1 or better on every day), as the watch does. Today it is
   the raw accent with white text, which is what the store screenshots show. Bright or raw?
3. **The cascade inside the receipt.** One screen after Finish, with the new top set as the
   first row and the strike and roll kept, instead of a separate full-screen moment and a tap.
   Merge, or keep it separate?
4. **Keep the screen on during a workout by default?** On a bench, a 30 s timeout means
   unlocking with wet hands between sets. Recommend on, while the workout screen is open,
   with the switch in Settings. The cost is battery for about an hour, mostly on a dark
   screen.
5. **Timed holds on the phone.** Today "Log 45 s" records the prescription with no clock.
   The watch times holds and logs them at the goal. Should the phone run the same clock
   (Start, a countdown, one buzz, logged at the goal; Stop early logs the seconds held)?
   It reuses the timed-rep event plumbing; phase 3 would grow by about a day.
6. **"Strongest" on the receipt.** Cut, as proposed, or keep "Heaviest set" as a line?
7. **The volume chart.** The August feel review put the tonnage bars on its "do not touch"
   list. The proposal cuts them. Agree?
8. **Calendar behind an icon.** The month grid moves from the top of Log to its own screen.
   Agree, or keep it at the top of the Sessions tab?
9. **Launcher and store icon.** The day aliases take the day's letter. Does the store icon
   become a letter too (which one?), or keep the barbell?
10. **Light mode's buttons.** Deep accents (A is a brown terracotta, `#8F3813`) keep text
    contrast on every day; the raw accents fail as text on C and are borderline on A. Accept
    the deeper look?
11. **Wizard finish.** "Use this plan" lands on Today with day A ready. Or "Start day A",
    which opens the workout at once?
12. **Equipment on the short path.** The three-screen wizard assumes a full gym. Add
    equipment as a fourth screen (a row of `FilterChip`s, all on), or leave it in Change
    answers?

## Review round 1

One independent reviewer (read-only, checking claims against the code) returned CHANGES at
6/10: the concept and inventory held, about 60 citations were checked and nearly all were
right. Every item was verified in the code and accepted. What changed, by ledger id:

- **A** (blocker; Undo built on the Lift sheet's untick left the rest and its alarm running,
  kept the set's timestamps, lost its target on process death, could hit a different set
  after an edit, and left timed-rep counts in place): a new "Undo" section specifies
  `undoLastLog()` with a saved, value-checked target that is dropped on any set-list
  change, clears stamps, cancels the rest, restores timed-rep values and reselects the lift;
  labels for rounds, counts and holds; cardio has no Undo.
- **B** (blocker; Finish took over the slot the thumb had just used for Log, and an
  all-done Finish has no confirm and cannot be undone): Finish arms 3 s after the last log,
  drawn disabled with its label until then; a gym test covers it.
- **C** (a rest started after the day's last set, and Finish never cancelled the phone
  rest): no rest after the last set; Finish, Clear checks and a day switch cancel the rest,
  alarm and notification.
- **D** ("Log during a rest ends it" was wrong): it restarts the rest, as now.
- **E** (auto-advance edge cases): it fires only on the action slot's and count sheet's
  logs; the cardio block is the target after the last strength slot and a Day sheet row;
  all-logged requires it; a clock on another lift shows as a chip.
- **F** (notification): title "Rest" with a chronometer, the next set from the same rule
  with its load, a new channel ID for "Rest over", non-exported receivers.
- **G** ("data, domain, rest runtime untouched" was false): the cost section lists what
  changes outside the UI; phase 9 grows to L for the repository restore.
- **H** (the process-death paragraph misplaced state): replaced by a state and owner table.
- **I** (phases depending on later phases): the new set screen sits behind a release-off
  flag until phase 3 flips it; keep screen on goes to Setup first; Health Connect pieces stay
  on Log until phase 7.
- **J** (Session reused the workout sheet): a history variant without Remove or the cascade
  note, with a Done checkbox.
- **K** (the plate line could say "0"): "Empty bar".
- **L** (contrast): light `error` becomes `#B02A43`; the C limit includes
  `surfaceContainer`; A's figure corrected.
- **M** (the calendar did not fit): 12 dp gutters on that screen.
- **N** (starting day C from Plan had unstated consequences): the Day preview says what it
  does to the rotation, and Today gets a "Continue day C" state with the override note.
- **O** (goals for authored plans): only the anchors the plan contains.
- **P** (three meanings of Stop): the timed-rep one is "Cancel".
- **Q** (keep screen on changed scope silently): stated, with where the flag moves.
- **R** (a mockup broke the pinned cascade): the Session mockup uses 135/170/195/220, TOP
  245, B/O 185.
- **S** to **X** (nits): brief and mockups aligned (counts, labels, folder, activity
  name, notification copy); Plan's gear is new, not "as now"; advice copy on the no-plan
  state and the backup failure replaced by facts and a button; the 200% scroll named as an
  exception to the house rule; the "(R3)" suffix rule and "Round 2 of 3" stated; TalkBack
  announces a lift change and Undo names another lift's set; missing conditions (Add
  superset, minimum exercises, Finish early at zero sets, the wizard's equipment default,
  owner question 12) added.

## Review round 2

The reviewer closed all 24 round 1 items at 8/10 and found two gaps the revisions opened,
both fixed: phase 2's `DayViewModel` rules now sit behind the same release-off flag as its
UI, because auto-advance would otherwise jump the old pager; and a tick from the Lift sheet
or the watch drops the undo target, its rest shows no Undo. Three nits were also applied:
Undo restores a timed-rep set's pre-log values rather than the prescription, the
finish-early confirm has copy for "only cardio left", and authored-plan goals leave out
anchors whose slot carries its own prescription. The reviewer conditioned approval on the
two gaps; the fixes themselves were not re-reviewed.

## Council round 1

Two independent seats returned CHANGES at 7/10 each; both held the direction, the 8-item set
screen and the contrast tables. The coordinator verified their claims. A, B and D are
answered as one selection model rather than three patches. What changed, by ledger id:

- **A** (auto-advance "next after, wrapping" and the reopen's "first undone" disagreed, so a
  lifter out of plan order saw the wrong lift): new "Workout: the selection" section. One
  rule serves the screen, the reopen, the rest label and the notification: a chosen lift
  stays chosen until it is done, then the first lift with work left after it, wrapping;
  from the top when nothing is selected. Worked through for shared equipment and a skipped
  set; the selection persists in the workout record.
- **B** (a lift completed off the button had no designed state): the completed view, "Done,
  6 of 6", the sets in one line, and "Next: Barbell Bench Press"; drawn.
- **C** (blocker; Undo lived in the day route's `SavedStateHandle`, which dies when the route
  pops, and the no-rest window lived in composition): the undo target and the selection
  move to a workout record in DataStore beside the rest, with an absolute expiry (the rest's
  deadline, or 10 s), so Undo survives leaving the workout and process death. The Finish
  arming deadline is derived from the target's logged-at time, so it survives with no new
  field. The pre-log values in the target restore an overwritten timed-rep count.
- **D** (a skipped set meant all-logged never came, and Finish hid in the Day sheet): a text
  "Finish" in the workout's app bar whenever a set is logged and sets remain, with today's
  confirm; "Finish day A early" leaves the Day sheet. The set screen is 9 things, the rest
  15 (both were one fewer).
- **E** (keyboard focus invisible, the Health Connect rationale clipping at large font, the
  light outline at 2.9:1 on dialogs): all three in phase 1; light `outline` is `#7A756C`.
- **F** (a doubled tap on cardio Start recorded a 0 s shortened interval with no way back):
  Stop and Reached arm 3 s into an interval, all three ignore taps for 600 ms after a
  change, and the Cardio sheet gains "Redo set" for the last result.
- **G** (loaded holds lost their load): "at 25 lb" under the hero and "Log 45 s at 25 lb".
- **H** (a mid-session swap deleted the slot's logs silently): a confirm that names the
  logged sets, then a Snackbar Undo through the Edit-day restore; the swap drops the band's
  Undo target.
- **I** (Log rows assumed local strength sessions): Health Connect rows are display-only with
  their time range and source; cardio rows open a read-only cardio session; only strength
  rows open the Session screen.
- **J** (plate line size disagreed): `titleLarge` in the type table and the drawings.
- **K** (dark day-primary text failed on `surfaceContainerHighest` for B, D and E): the
  ground-only rule now covers both expressions.
- **L** (per-call color overrides on the band's text buttons): the day-primary wrapper
  encloses only the one filled action, so nothing else needs an override.
- **M** ("Started from Plan" is not recorded): Today derives the in-progress day from today's
  ticks, and with two such days shows the most recent and names the other.
- **N** ("the watch uppercases at its own call sites" contradicted the watch redesign):
  dropped; `GlanceLines` is sentence case for both.
- **O** (the rest is not cancelled on Finish): referenced as issue #338, fixed outside this
  redesign.

## Council round 2

Both seats returned CHANGES at 8/10 and closed rows A to N except F (the final interval) and
H (partly); both judged the 9-item set screen and the 15-item rest screen justified, so those
counts stand. What changed, by ledger id:

- **P** (the swap confirm covered only the Lift sheet): one confirm and one Undo on every path
  that ends in `swapInPlan`: Swap in the Lift sheet, Add or Remove weight, and Swap in Edit
  day; with nothing logged they act as today.
- **Q** (Undo validity was event-driven, so watch ticks applied outside the ViewModel left a
  stale target, and a copied expiry ignored +15 s): validity is read from the data each time
  (the row still done with its logged values and a completion stamp equal to the logged-at
  time, and no later completion stamp in the day), and the expiry is the live rest deadline,
  or 10 s with no rest. Phone ticks now stamp `completedAtMillis`. The event list and the
  stored expiry are gone.
- **R** (Redo was unbounded against self-started recovery and cool-down, and a mistaken final
  Reached saved the run with no way back): Redo is once per interval, only while that
  interval is the latest result and the lifter is still on the screen it ended on; it
  rewinds and discards any interval that started itself after it; for a saved final
  interval it reopens the run on the same session and amends the result in place, so there
  is one session and one advance; after the lifter leaves the done state it is not offered.
  The cost section no longer calls the cardio state machine untouched.
- **S** (phase 2 used a restore built in phase 9): the slot restore is built in phase 2, where
  the swap Undo first needs it; phase 9 reuses it and shrinks to M to L.
- **T** (cut): the 3 s lock on cardio Stop and Reached is removed; the 600 ms guard stays and
  Redo covers what gets through. The 3 s arming on Finish after the last strength set stays.
- **U** ("next after the selected lift"): rule 4 now cites rule 3 by its words.
- **V** (Finish "whenever a set is logged" and "disabled at none" disagreed): Finish is always in
  the app bar, disabled until a set is logged, and hides only in the all-logged state.

## Council round 3

Round 3 was the review cap. The Claude seat approved at 9/10; Sol scored 8/10 with two
open points. Both seats confirmed:
- phone ticks can stamp `completedAtMillis` with no Room migration and no change to pinned
  data or the CSV format. Backups gain the phone completion times;
- the reopened cardio run cannot duplicate the session or advance the rotation twice.

These follow-ups are carried into the build phases rather than reviewed again:

- **Undo validity across phone and watch (both seats).** Completion stamps are wall times
  from two clocks, and a watch tick may carry none, so they cannot order mutations
  reliably. Validate the Undo target against an order both write paths share: a durable
  revision the repository bumps on every set write, or the arrival time of an applied
  watch delta. Keep the stamps as observations. Phase 2.
- **The cardio Redo amend (both seats).** A redone interval changes that set's seconds and
  status as well as the session's `seconds`, `stepsCompleted` and `completedAt`. Amend the
  set and the session totals in one transaction, then replace the Health Connect record
  under its original ID with a newer version. Do not rerun `maybeLogCardio`, which would
  show a second receipt (`ui/day/DayViewModel.kt:724-729`). Phase 3.
- **Cardio-only days (Claude).** When the run finishes the day, saving goes straight to the
  receipt (`ui/day/DayViewModel.kt:724-749`), so there is no done state for Redo. Offer
  "Redo interval N" on that receipt, under the same bounds. Phase 3.
- **Phone untick (Claude).** Now that phone ticks stamp, a phone untick clears both stamps,
  as the watch path does (`SetEditApplier.kt:271`), and the validity check counts only done
  rows. Update the comments at `SessionEntities.kt:58-61` and `Types.kt:33-35`, which say
  phone ticks carry no stamp. Phase 2.
