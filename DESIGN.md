# DESIGN.md

Project direction for the `ux-design` skill and any agent designing strength.log's phone or
watch interface. It points at the documents that hold the detail. `docs/PLAN.md` beats
`STRENGTH_TRACKER_SPEC.md`, and a later brief beats an earlier one it names.

Only "Hard constraints" is fixed. Everything else, including the palette, the dial and the
2026-10-12 redesign, is recorded history that a design pass weighs and may propose to change.

## Product and user

strength.log is a paid, local-first strength tracker for Android phones with a Wear OS
companion: no account, no ads, no network permission (spec §1, PLAN "Locked decisions"). The
reference user is about forty and trains for maintenance and health, not records. Their
schedule is unreliable and sessions get missed. They train in a gym, usually alone. During a
session the phone sits on a bench or in a pocket and the hands are chalked or sweaty; looks
last a few seconds, often at arm's length. The watch is read with a wrist turn, sometimes
mid-set. Outside the gym (planning, history, backups) they have both hands and some patience,
but no interest in the app as a hobby.

Their main goal: train the next day in the rotation with loads they can own, record what
actually happened, and come back after a gap without the plan falling apart. The feeling the
app should leave is composure: "the plan survived my absence, I know the next move, and what I
did counted" (`docs/briefs/direction-review-2026-08-25.md`, untracked, main checkout only).

The 2026-10-31 product council (`docs/briefs/product-council-2026-10-31.md`, owner decided)
widened this user.

- The app is a workout logger with optional help making a plan, for lifters who train for
  maintenance and for those who don't.
- The goal math is one way to set a lift's targets.
- The building blocks are:
  - workout runs;
  - one kind of plan;
  - a history page for every exercise;
  - a watch that can run a whole workout.

## Surfaces

### Phone (`app/src/main/kotlin/cloud/trotter/log/strength/`)

A single activity with a Today / Log / Plan `NavigationBar` (`ui/navigation/`,
`ui/AppNavHost.kt`). The workout, receipt, wizard and settings screens hide the bar.

| Surface | Code | Moment | One job |
|---|---|---|---|
| Today | `ui/today/TodayScreen.kt` | Launch, at home or walking in; seconds | Say which day is next and start it |
| Workout pager | `ui/day/DayScreen.kt`, `ExercisePage.kt` | Between sets, phone on a bench, 2 to 5 second looks | Log the set in front of you |
| Rest pill and notification | `DayScreen.kt` `RestPill`, `rest/PhoneRestRuntime.kt` | After a logged set; phone may be pocketed | Say when the next set is due |
| Set edit sheet | `ui/day/SetEditSheet.kt` | Mid-workout correction | Change one set's numbers |
| All sets and Exercises sheets | `ui/day/AllSetsSheet.kt` | Mid-workout check | Show the lift's sets, or jump to another lift |
| Timed-rep count and cardio distance | `ui/day/TimedRepCountSheet.kt`, `CardioExecution.kt` | The moment a timer ends | Record the count or distance just done |
| Cardio page | `ui/day/CardioPresentation.kt` | Treadmill or road, between intervals | Run the current cardio set |
| Day edit and swap | `ui/day/DayEditSheet.kt` | Station taken, equipment missing | Change what today's day contains |
| Cascade | `ui/day/CascadeScrim.kt` | After Finish, only when a top set moved the goal | Say that a lift progressed |
| Receipt | `ui/day/SessionReceiptScrim.kt` | After Finish | Confirm the session saved and say what comes next |
| Share card | `ui/log/share/` | By explicit tap only | Make an image of one session |
| Log | `ui/log/LogScreen.kt`, `JournalSections.kt` | At home, later | Show what I did |
| Plan and Plans | `ui/plan/PlanScreen.kt`, `PlansScreen.kt` | At home, or when the program changes | See and change the rotation; pick a plan |
| Setup (settings) | `ui/setup/SetupScreen.kt` | Rarely | Two jobs today (training inputs and preferences); phase 2 splits it |
| Backup | `ui/backup/BackupScreen.kt` | Rarely, often anxious | Know the data is safe; move it in or out |
| Custom exercise | `ui/customexercise/` | Catalog lacks a movement | Add one exercise |
| Wizard | `ui/wizard/WizardScreen.kt` | First run, or a new plan | Produce a rotation the lifter accepts |
| Health Connect rationale | `health/HealthRationaleActivity.kt` | System permission flow | Explain each permission |
| Widget | `widget/` | Home screen | Say today's day and progress |
| Day launcher icon | `icon/DayIconManager.kt` | Home screen | Show today's letter |

### Watch (`wear/src/main/kotlin/cloud/trotter/log/strength/wear/`)

One composable, the dial (`ui/Dial.kt`, state in `ui/DialState.kt`), renders every screen.

| Surface | Code | Moment | One job |
|---|---|---|---|
| Overview face | `DialState.kt` `overview()` | Wrist turned before or between lifts | Show today's day and the way in |
| Workout face (ready, lifting, rest, rest over, timed hold, timed reps, cardio sets, day done) | `DialState.kt`, `CardioSetDialState.kt`, `TimedRepState.kt` | Under the bar, eyes-off, one hand | Do the one thing this moment needs |
| Crown layer: peek, swap, rep count | `CrownLayer.kt`, `CrownInput.kt` | Between sets | Look back at a set or take an alternate |
| Ambient | `AmbientDial.kt` | Wrist down | Keep time and rest visible, burn-in safe |
| Loading and sync states | `LoadingDial.kt`, queued band in `DialState.kt` | Phone away, first sync | Say what is missing and what to do |
| Time pill | `Dial.kt` `TimePill` | Any lit face with a free bottom band | Show the time |
| Tile, complication, ongoing chip | `glance/`, `OngoingWorkoutChip.kt` | Watch face, tile carousel | Today's day and set progress in one glance |

The dial from the edge in (radius is timescale, attention is innermost; reference px on a 384
canvas, `ui/DialGeometry.kt`): the cycle ring (inset 9, stroke 22; a segment per program day,
today in full accent with a green progress arc inside), the exercise ring (inset 40, stroke 14;
a segment per lift on the overview, per set in the workout), two curved caps bands (top: what
and where; bottom: detail or gesture hint), the 204 px disc (the only tap target), and the
clock ring on the disc's rim (stroke 7; rest draining, holds and the undo hold filling).

## Hard constraints

These are not design choices. A change to any of them goes to the owner.

- Spec §11's pinned numbers. A design never changes a goal, ramp or cascade value.
- Local-first: no INTERNET permission, no account.
- Data principles (CLAUDE.md): every mutation writes to the database on the tap; state that
  must survive process death goes through `SavedStateHandle` or the database; the lifter can
  always export. The phone is the source of truth; the watch sends edits and never computes
  derived sets (PLAN A8, A12).
- The `ux-design` skill's accessibility floor. The watch departs from it in two places (open
  question 7).
- On the phone, Material 3 is the component system and the theme defines color, type, shape,
  state layers and component defaults completely (CLAUDE.md §5). A bespoke component states
  in its KDoc what M3 lacks.

## Material

### Phone

Follow Material 3's guidance and use its library (`androidx.compose.material3`, Compose BOM
2026.06.01):

- Components: the M3 composable for the job (`NavigationBar`, `TopAppBar`, `ListItem`,
  `ModalBottomSheet`, the `Button` family, `Checkbox`, `FilterChip`, `SegmentedButton`,
  `Snackbar`), styled through the theme rather than per call site.
- Color roles by meaning: `primary` for the primary action, `error` for errors, `outline` for
  hairlines. All 48 roles are defined in both schemes (`ThemeCompletenessTest`); what
  `primary` should hold is open (see "Theme").
- Type and shape: new text picks one of the fifteen roles in `ui/theme/Type.kt`; corners come
  from `ui/theme/Shape.kt`.
- State layers: one ripple configuration for foundation and stock components
  (`AppRippleConfiguration`, `ui/theme/Theme.kt`).
- Motion: M3 easing and duration tokens, collapsing under reduced motion.
- Layout: edge-to-edge, predictive back, `ReadableWidth` (600 dp, `ui/theme/Layout.kt`) on
  wide windows. Window size classes and tablet layouts are parked, not rejected.

Guidance by name: Material Design 3 (m3.material.io) "Color roles", "Typography", "Shape",
"States", "Motion", "Layout" and "Dynamic color"; Android Developers "Material Design 3 in
Compose", "Accessibility in Compose" and "Adaptive layouts".

### Watch

Follow the Wear OS design guidelines (developer.android.com/design/ui/wear) and Material for
Wear OS where they fit a dial. The dial's recorded departures (local face state instead of
`SwipeDismissableNavHost`, crown instead of list pickers, custom tile geometry, no Horologist)
are listed with their reasons in `docs/briefs/wear-dial-redesign.md` "Deliberate deviations";
treat them as inputs.

Fact: `:wear` depends on `androidx.wear.compose:compose-material` and `compose-foundation`
1.6.2 (`gradle/libs.versions.toml`), the older Wear Material library, not Wear Compose
Material 3 (`compose-material3`). It takes only `MaterialTheme`, `Colors` and `Text` from it;
the dial is foundation (`CurvedLayout`, `basicCurvedText`, `BasicSwipeToDismissBox`) plus
Canvas. The tile uses `protolayout` 1.4.2 without its Material layer. See open question 6.

## How the watch differs from the phone

| Difference | What it changes in the design |
|---|---|
| A look is a wrist turn of a second or two, often mid-set | One reading per face. The disc carries the one action, the top band the one status. No secondary navigation, no prose. |
| Round screen; the visible width at any height is a chord | Everything is placed by radius from the measured face, never by padding. Text runs on arcs and ellipsizes at a 120° sweep. Nothing sits outside the four zones. |
| One hand, often chalked, sometimes eyes-off | One tap target, 204 px across. One tap logs; undo is a 700 ms hold offered only between efforts. No swipe-left while lifting or resting, so a brushed sleeve cannot skip a set. |
| The crown is the precise input | Crown scrubs sets (peek), flicks between prescribed alternates (swap) and counts reps, with a haptic per detent. Lists and scrolling are absent from the workout. |
| Ambient mode on OLED | True black, outline-only, no accent or fills, minute repaints, a ±2 px burn-in orbit, black and white on low-bit screens. A rest shows "RESTING", not seconds that would lie between repaints. |
| Battery | No wake lock or `keepScreenOn`; the rest buzz is one exact alarm; dark faces are cheap on OLED. |
| Read-only data role (A8, A12) | Today's day only; no invented alternates or re-planning; edits queue for the phone. Setup and history stay on the phone, and empty states point there. |
| Reference px are physical px | On a Pixel Watch the 384 canvas maps 1:1 to physical pixels at density 2.0, so a reference px is about half a sp and a monitor mock lands at half size. Judge type on the wrist (`wear-dial-v2.md`). Nothing read goes below 12 sp except the 9 sp cycle labels. |
| Few characters fit | Caps bands, colloquial short names (#111), copy measured against the widest real strings. |
| The eyes are elsewhere when a timer ends | One buzz, never repeated. |

## Theme

### Both surfaces

The day accents A to G live in `domain/src/main/kotlin/cloud/trotter/log/strength/domain/theme/DayAccentColors.kt`:
`#C1440E #2D5A3D #B8860B #1F4E5F #3C4E78 #8B4356 #6B6A2C`, cycling past seven. Raw accents
are fills. Text and strokes use `brightHex` (35% toward `#F2F2F0`) on dark and `deepHex` (30%
toward ink `#1B1B1E`) on light, both pinned at 4.5:1 or better by `DayAccentColorsTest`; the
phone picks one with `accentEmphasis()` (`app/.../ui/theme/Color.kt`). On-accent ink is per
day: C (goldenrod) takes dark text, the rest light (`onDayAccent`). Launcher icons and the
widget read copies in `app/src/main/res/values/colors.xml`, since resources cannot read
Kotlin; those copies change with the SSOT.

Done green marks completed work only (dark `#3E8E5A`, light `#37774E`). Error is crimson
`#C2334D` in both, so an error never reads as Day A. Numerals and display text are Barlow
Condensed with tabular figures; body text is the platform sans. Corners are 4, 8, 10 and
12 dp, 28 dp for dialogs and sheets, with pills outside the scale.

### Phone

The phone follows the system by default (`ThemePreference.SYSTEM`; Dark and Light are
explicit choices in Setup). Dark is the character the store screenshots show: near-black
`#0D0D0F`, surface `#16161A`, hairline `#2A2A30`. Light is warm paper (`#F1EFEA`, surface
`#FAF9F6`, ink `#1B1B1E`), a light expression of the same identity rather than an inversion
(`docs/briefs/light-theme.md`).

Light mode is a goal: supported properly, designed and previewed on every screen, with
contrast checked in both expressions. Known gaps: the widget is always dark (fixed resource
colors), the share card is dark by decision, and the splash follows the system setting
(`values` vs `values-night`), not the in-app choice.

Known issue: `app/src/main/kotlin/cloud/trotter/log/strength/ui/theme/Theme.kt` sets
`primary = dayAccent(0)` at line 64 (dark) and line 123 (light); `inversePrimary` and
`primaryFixed` follow (lines 68, 94, 127, 145). Every stock control reading `primary` is Day A
terracotta on every day and on admin screens. As text, `#C1440E` is 3.79:1 on `#0D0D0F`,
3.52:1 on `#16161A` and 4.46:1 on `#F1EFEA`, all under 4.5:1. The 2026-10-30 audit (item 1)
proposes a day-scoped scheme on the workout and a neutral primary elsewhere. Related: Error
`#C2334D` as text on `#0D0D0F` is 3.58:1; only on-error text was measured when it was chosen.

### Watch

The watch is dark only: lit faces on `#0D0D0F`, ambient on true black with `#4E4E55` and
`#6B6B73` grays (`wear/.../theme/WearTheme.kt`). Disc actions are neutral white; the day's
identity stays on the rings and bands, band text in `brightHex`. The recorded reason
(`light-theme.md`, 2026-08-10): a lit dial is the wrist's identity, and OLED battery agrees.
Wear OS is dark by platform convention too. `WearTheme.kt:62`
also sets `primary = dayAccent(0)`, though the dial colors its text explicitly.

## House rules

Current defaults drawn from the history below. A design pass follows them unless it proposes
a change and says why.

Both surfaces:
- Color means one thing. The day accent identifies the day and its one primary action;
  green marks completed work; accent text uses the derived foreground, raw accents are fills.
- No emoji, confetti, streaks, badges or gradients. The cascade is the one ceremony.
- A missing value shows as missing: blank distance means unknown (`cardio-sets.md` §3); plate
  math says nothing rather than something wrong (`plate-math.md`).

Phone:
- One primary action per surface; the rest is tonal, outlined or text.
- Sentence case. Caps only for set notation (R1, TOP, B/O). No tracked-caps eyebrows, no
  dot-joined meta strings. The same verb runs through a flow: Start day A, Finish day A,
  Day A, done.
- The app states at most one derived sentence of fact; it never advises.
- Nothing on the workout page scrolls or moves with scroll in portrait.
- Settings, Backup and Licenses carry no day accent.

Watch:
- Four zones only (rings, two bands, disc). Status lives in the top band.
- Type comes from `DialTextRole` (`wear/.../theme/DialType.kt`); nothing between its steps.

There is no copy guide file. Copy direction is in HANDOFF rule 4, Astra's copy table
(`docs/design-handoff/redesign-2026-10-12/astra-design-plan.md`), `wear-dial-v2.md` §4 and the
user-level `prose-whip` skill.

## Design history (inputs, not constraints)

Each entry records what was decided and the reason given at the time. A design pass may
question any of them and propose a change in its plan, naming the reason that no longer holds.
That needs no separate owner question unless it touches a hard constraint. Briefs live in
`docs/briefs/` unless another path is given.

### Identity and theme

| When, where | Decided | Reason given |
|---|---|---|
| 2026-07-06, spec §8.5, #40 | Near-black, four earth accents A to D, a condensed display face (Oswald), dark only in v1 | Keep the React prototype's feel; small v1 |
| 2026-07-06, #40 | M3 `primary` set to Day A's accent | Dark-only theme whose screens drew their own controls and looked up day color per day; few stock components read `primary`. The August migration removed that premise |
| 2026-07-07, #44, #45, `docs/design-handoff/README.md` | Barlow Condensed replaces Oswald; Surface2/3, TextFaint; Error to `#C2334D`; 11 sp tracked caps for overlines and badges; 12 dp cards | The designer's visual pass; Error moved off M3's red because it read as Day A |
| 2026-07-10, #66 | Seven accents with per-day on-accent ink, in `:domain` | Five-to-seven-day plans should not repeat a color; goldenrod fails white text |
| 2026-07-30, `journal.md` §0 | Accents are never competing chart series; charts are single-series | The accents fail as a chart palette (contrast, hue separation) |
| 2026-07-30, `glance-surfaces.md` (#100) | Widget as RemoteViews, not Glance; glances read-only, opening the app | Glance cannot use font resources, and Barlow is the face |
| 2026-08-07, #150, #155 | One `brightHex` rule in `:domain` for both surfaces | The phone and watch brightened differently; the watch's day D was 3.68:1 |
| 2026-08-07, #157, `material-migration.md`, #169 | Identity in the theme, components from M3; every role defined; CLAUDE.md §5 rewritten. Owner chose a themed M3 ripple over the authored veil, against the audit's advice | Hand-rolled controls cost conformity and maintenance |
| 2026-08-10, #232 | Final KEEP inventory (Stepper, SelectionCard internals, SwitchToggle, compact SheetButton and others) plus a guardrail test | M3's minimum sizes could not reproduce the pinned geometry |
| 2026-08-10, `light-theme.md`, #226 | Light theme ships as warm paper with `deepHex`; System is the default; share card always dark; widget keeps fixed colors; watch out of scope | Owner wanted light in v1; the card is a designed artifact; a lit dial is the wrist's identity and OLED battery agrees |
| 2026-08-24, `feel-review-2026-08-24.md` | Keep System as the default (a reviewer argued for Dark because the listing sells dark); "near-black" in §5 means the dark expression; prefer the M3 icon set; a "do not touch" list | The light expression is part of the identity, not a leak |

### Phone layout and copy

| When, where | Decided | Reason given |
|---|---|---|
| 2026-10-12, `ui-redesign-2026-10-12.md` Round 2 | Day index (letter on an accent block); Today / Log / Plan bar; coach states facts only; M3 `Checkbox`; edits in a `ModalBottomSheet`; rotation 2 to 6 days; no bespoke components | Owner: it did not feel like an app; both reviewers found captions carrying the hierarchy and reference outranking the action; an instructing coach line read as condescending |
| 2026-10-12, HANDOFF rule 4 | Retire tracked caps and dot-joined meta; caps only as set notation | Seven caps captions turned Today into a readout. Reverses the July convention |
| 2026-10-12 evening, pager decision | Workout is Fable's variant B: one lift per page, one set in focus, `Button` "Log 95 × 5", next card peeking, R1/TOP/B/O, no day selector, "Day D, done." | Owner: "looks like a pro workout app"; removes the vertical list behind the scroll jank (#260); shares the watch's grammar |
| 2026-10-12 late, #262 | Segments are indicators, not buttons; the rest pill is a time button with a menu | 48 dp targets do not fit six to eight segments on a 360 dp phone |

### Watch

| When, where | Decided | Reason given |
|---|---|---|
| 2026-07-28, A12, `wear-dial-redesign.md` (#84, #92) | Concentric dial, one tap target, crown navigation; one tap logs, a 700 ms hold undoes. Replaces the spec §9 list screens and Horologist | Columns clipped on the round face; eyes-off logging with chalked hands |
| 2026-07-30, `wear-dial-v2.md` (#99) | Type doubled, 12 sp floor, rings nest and never transform, clock ring on the disc rim | On the wrist the canvas landed at half size; the rest "melt" hid the round count mid-rest |
| 2026-07-31, `wear-curved-bands.md` (#110, #111) | Curved bands; colloquial short names | Straight text wasted the annulus; catalog names were too long |
| 2026-07-31, `wear-dial-v3.md` (#116) | Cycle ring outermost; disc actions neutral white; exactly two faces; swipe-left only between efforts; swap only before anything is logged | Controls borrowing the accent mixed identity with action; a brushed sleeve must not lose a set |
| 2026-08-07, #152 | Cycle labels at 9 sp, an owner waiver | Color carries the identity; 18 px is the largest line box that fits the 22 px ring |
| 2026-08-09, #161, #188, #206 | Ambient burn-in orbit and low-bit mode; no `keepScreenOn`; alarm-backed rest buzz; the time pill | Burn-in and battery; the screen lock defeated ambient; time was missing on some faces |
| standing, `DialType.kt` | Dial type pinned to physical size, ignoring system font scale | A 1.3x scale breaks the zone guarantee on a 204 px disc |

## Sources

| Path | Use |
|---|---|
| `CLAUDE.md` | Design principles (§5), data principles |
| `STRENGTH_TRACKER_SPEC.md` | Product principles (§1), day screen (§8.2), visual base (§8.5), watch (§9), pinned numbers (§11) |
| `docs/PLAN.md` | Amendments A1 to A13; wins over the spec |
| `docs/briefs/ui-redesign-2026-10-12.md` | Phone redesign record, Round 2, pager decision |
| `docs/design-handoff/redesign-2026-10-12/HANDOFF.md` | Phone build contract; `fable-mock.html` and `fable-pager.html` are the mocks of record, `before/` the pre-redesign screenshots |
| `docs/briefs/ux-audit-2026-10-30.md` (untracked, this worktree) | Today's audit: ranked plan, deletions, sixteen owner questions |
| `docs/briefs/material-migration.md` | M3 audit, ripple decision, KEEP inventory |
| `docs/briefs/feel-review-2026-08-24.md`, `direction-review-2026-08-25.md` | Owner decisions, "do not touch" list, coach-versus-notebook diagnosis |
| `docs/briefs/light-theme.md` | Light palette and what must survive it |
| `docs/briefs/wear-dial-redesign.md`, `wear-dial-v2.md`, `wear-dial-v3.md`, `wear-curved-bands.md` | Dial spec and its on-wrist amendments; v3 wins |
| `docs/briefs/glance-surfaces.md`, `journal.md`, `session-share.md` | Widget, tile and complication; chart color rules; share card |
| `docs/briefs/plate-math.md`, `cardio-sets.md`, `timed-rep-events.md`, `plans-review-2026-10-19-r2.md` | Feature behavior and copy |
| `docs/design-handoff/README.md`, `tokens/`, `docs/DESIGN_BRIEF.html` | The July visual pass, partly superseded |
| `app/.../ui/theme/`, `domain/.../theme/DayAccentColors.kt`, `wear/.../theme/` | Tokens, schemes, accents, dial type |
| CI artifact `store-screenshots` | Rendered phone and watch screens with demo data |

## Open questions

1. Phase 2 build: receipt, Log, Plan goals, Settings and the wizard reveal have a target in
   `fable-mock.html` but no handoff, and still carry the pre-redesign caps style.
2. What `primary` holds on the phone: a day-scoped scheme on the workout with a neutral
   primary elsewhere (the audit's proposal), or a neutral primary everywhere with the accent
   applied explicitly.
3. Dynamic color would replace the earth palette with the wallpaper's. Opt-in for neutral
   roles only, fully, or not at all?
4. Light mode gaps: a light widget, a splash that follows the in-app choice, the share card
   still dark? Error text on near-black needs a foreground variant or a rule against it.
5. Health Connect in Settings (#320): Setup has no row to grant or review permissions.
6. Wear Material 3: move `:wear` to `compose-material3`? The direct cost is small (theme and
   `Text`); the question is whether any of its components fit the dial, and whether the tile
   should use the protolayout Material layer.
7. The watch's accessibility departures (dial type ignores font scale; 9 sp cycle labels):
   keep as waivers, or find a layout that honors font scale?
8. A lighter watch: Wear OS convention is dark and dark is cheaper on OLED. Should lit faces
   ever follow anything lighter, such as Wear OS dynamic color?
9. No bespoke components (the redesign rule) against the August KEEP inventory on phase 2
   screens (audit question 4).
10. Rotation counter: deferred until the domain can verify coverage of one plan version.
11. Glance caps: the widget, tile and share card were specified in caps and are built in
    `domain/.../glance/GlanceLines.kt`. Does HANDOFF rule 4 reach them?
12. Distance units: meters only, or km and miles too?
13. Open bugs with design constraints: the watch time pill clips (#330) and must yield to
    workout content; scroll jank (#260).

The audit's other owner questions are in `docs/briefs/ux-audit-2026-10-30.md`.
