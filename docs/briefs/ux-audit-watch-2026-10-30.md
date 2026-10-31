# Watch UX audit, 2026-10-30

Run with the `ux-design` skill against `DESIGN.md` (repo root, this worktree) and the
watch sources it points at: `wear-dial-redesign.md`, `wear-dial-v2.md`, `wear-dial-v3.md`,
`wear-curved-bands.md`, `glance-surfaces.md` and `m5-wear.md`. Scope is everything under
`wear/src/main/kotlin/cloud/trotter/log/strength/wear/` plus the phone's
`sync/WatchSnapshotBuilder.kt` where it decides what the watch shows. Paths below are
relative to the wear package unless they start with a module name.

Evidence: the three CI renders in `.shots/wearScreenshots/` (454 px at density 2.0, so
the dial's 384 px reference canvas is scaled by 1.18), pixel samples taken from them,
and WCAG contrast computed from the token hexes in `theme/WearTheme.kt` and
`domain/.../theme/DayAccentColors.kt`. Before and after drawings of the changed faces are
in `ux-audit-watch-2026-10-30-mockups.html`, next to this file.

Overall: the dial's structure is sound. One target, four zones, rings that nest by
timescale, and a crown that does the precise work all fit the wrist. What fails is
smaller and cheaper. Two parts of the ring vocabulary do not render as specified (the
exercise ring's gaps are closed by its own round caps, and the clock ring is painted
under the disc). Logging a set takes two taps where the phone takes one. And the
accessibility floor is missed in the two places `DESIGN.md` lists (font scale, 9 sp
labels) and two it does not (gray text under 4.5:1, ring states told apart by hue alone).
Nothing below touches a hard constraint.

## The user and the moment

The reference user is about forty, training alone for maintenance. That age matters on
a watch: near vision starts to decline in the forties, so small gray type and a
font-size setting that does nothing land on exactly this lifter. About one man in twelve
has red-green color deficiency, which matters for a dial that separates "done" from
"you are here" by green against orange.

In the gym the watch is read with a wrist turn of a second or two, at arm's length,
under bright and uneven light, often with chalk on the hands. Between sets (one to three
minutes) the eyes are free and the lifter loads plates; under the bar the eyes are off
the watch entirely. With the wrist down the screen drops to ambient. Away from the gym
the watch is a glance at the tile or the face. The phone is the source of truth; the
watch logs and shows.

| Surface | Code | Moment | One job |
|---|---|---|---|
| Overview face | `ui/DialState.kt` `overview()` | Walking in, or between lifts | Say which day it is and open the lift I'm about to do |
| Day browse | `DialState.kt` `browse()` | Curious what Thursday holds | Show another day without starting it |
| Ready and rest over | `DialState.kt` `ready()`, `restOver()` | Loading the bar | Show the weight to load and take the tap that starts the set |
| Lifting | `DialState.kt` `lifting()` | Under the bar, eyes off | Take the tap that logs the set. **Finding:** this face exists only because one job (log the set) is split across two taps (plan item 1) |
| Rest | `DialState.kt` `rest()` | Between sets, eyes free | Say when the next set is due and what it is |
| Timed hold, timed reps | `DialState.kt` `timedHold()`, `timedRepExecuting()` | Plank, carry, rep window | Time the effort and log it |
| Day done | `DialState.kt` `dayDone()` | Last set racked | Say the day is finished |
| Cardio sets | `ui/CardioSetDialState.kt` | Treadmill or road | Run the current interval. **Finding:** the distance-set disc carries prescription, target, elapsed and the action at once (item 8) |
| Crown: peek, swap, rep count | `ui/CrownLayer.kt`, `ui/CrownInput.kt` | Between sets | Look back at a set; take a prescribed alternate; count reps |
| Ambient | `ui/AmbientDial.kt`, `ui/AmbientDialState.kt` | Wrist down | Keep time and the rest readable, burn-in safe |
| Loading and phone states | `ui/LoadingDial.kt`, `ui/LoadingDialStateMachine.kt` | First launch, phone away | Say what is missing and offer the one fix |
| Time pill | `ui/Dial.kt` `TimePill` | Any lit face with a free bottom band | Show the time |
| Tile | `glance/DayTileService.kt`, `glance/DayTileLayout.kt` | Tile carousel | Today's day and progress, one tap from the app |
| Complication | `glance/DayComplicationService.kt` | Watch face | Today's progress on the face |
| Ongoing chip | `OngoingWorkoutChip.kt` | Watch face mid-session | Get back into the workout |
| Snapshot builder | app `sync/WatchSnapshotBuilder.kt` | Every phone change | Decide the names, titles, alternates and detail the watch shows |

## Plan, ranked by how much each change raises the user's chance of success

| # | Surface | What the user meets today | Rule | Change | Cost | Risk |
|---|---|---|---|---|---|---|
| 1 | Ready, lifting, rest over | A weighted or reps set takes two taps: START (`DialState.kt:402`, `ui/WearApp.kt:530-562`), then TAP TO LOG on the lifting face (`DialState.kt:469-488`). A lifter who does the set first and taps after lands on the lifting face with the set unlogged and no rest running. Ready draws the weight at 30 px under a 36 px START (`DialState.kt:719-728`); the 60 px numeral appears only under the bar, when nobody is looking (`DialState.kt:482`) | One primary action per surface; the thing that matters stands off its background | Ready's filled disc shows the round as the numeral group (190 ×5) over LOG SET, and one tap logs the set and starts the rest. `tick()` already accepts a set with no start (`WearApp.kt:597-598`). Timed holds and rep windows keep START, because their clock is the work. The superset partner line moves from lifting to ready's bottom band. The undo hold also works on the rest face, replacing the drain on the clock ring while held, because the set most likely to need undoing is the one just logged | M | Medium: current users' habit (a tap at the start of a set would now log it); tests rewritten; peek's TOOK line loses its source (owner question 1) |
| 2 | Exercise ring, every face | Segments are stroked with round caps (`ui/Dial.kt:416`) and 4° gaps (`ui/DialGeometry.kt:95`). At radius 145 the two 7 px caps cover more than the 10 px gap, so neighbors touch: sampling the 02-set render along the ring finds no gap at 60° or 120°, and six sets read as three colored arcs. The current segment is the raw accent (`Dial.kt:423`), 1.6 to 1.8:1 against the gray track on days B, D and E. Today's progress rides today's cycle segment in done green at 1.2 to 2.3:1 against it (`Dial.kt:372-378`), so on day A a red-green color-deficient lifter cannot see it | Segments are countable things (redesign §4); non-text contrast 3:1; no meaning by color alone | Butt caps, as the cycle ring already uses (`Dial.kt:366`). Current segment, clock ring and hold fill in `accentBright`, which `DESIGN.md` already requires of strokes (3.7 to 6.5:1 against the track). A 2 px background line separates the progress arc from the segment beneath it, so its length reads by shape. Done against current stays a hue pair on every day (1.1 to 2.3:1 even when bright), so the set position stays printed in text on every face that draws the ring; item 9 adds it to the rest face | S | Low; renders change |
| 3 | Rest, timed hold, timed reps, cardio, overview during a rest | The clock ring is drawn on the ring canvas (`Dial.kt:320-331`), which is composed before the disc (`Dial.kt:143`, `:147`); the disc then paints its fill over it (`Dial.kt:572`). The 03-resting render shows a one-pixel fringe at the disc rim and nothing else. The draining rest, the filling hold and the cardio step clock are all invisible; only the undo fill, drawn inside the disc (`Dial.kt:597-608`), shows | The vocabulary must render; spend motion on change | Draw the clock ring in the disc's own `drawBehind`, the path the hold fill already uses, so one code path draws "the ring touching the disc". The peek's dimming then comes from the disc's alpha and the special case at `Dial.kt:325` goes | S | Low. Once visible, the ring sits within a couple of pixels of the rest face's NEXT line (160 px wide, 53 px below center, where the ring's inner edge is 158 px across); set that line at BAND_SECONDARY (142 px). On the white overview disc the bright accent is weak; use the raw accent there |
| 4 | Bands, disc, ambient, loading, tile | `TextTertiary` `#6B6B73` (`theme/WearTheme.kt:20`) is 3.68:1 on the background and 3.42:1 on the disc surface. It carries SET 3 OF 6, TAP TO SKIP, WAITING ON PHONE, the alternates count and the peek hint (`DialState.kt:400`, `:430`, `:466`, `:501`, `:529`, `:817`) and the tile's no-program line (`glance/DayTileLayout.kt:121`). Ambient bands and ring use `AmbientDim` `#4E4E55` at 2.54:1 on black; the center uses `AmbientClock` at 3.98:1 (`WearTheme.kt:24`, `:30`). A dimmed disc lowers its whole layer to 62% (`Dial.kt:569`), taking secondary text to 3.16:1 and tertiary to 2.01:1 (peek TOOK line, browsed day's first lift, SWAPPING). The loading action is raw day A at 3.79:1 (`ui/LoadingDial.kt:121`) | Text contrast 4.5:1 | `TextTertiary` becomes `#808086` (4.95:1 on background, 4.60:1 on surface). Ambient uses it for bands and ring, and `TextSecondary` (7.52:1 on black) for the center; `AmbientDim` and `AmbientClock` are deleted; low-bit stays white. In a dimmed disc every tone resolves to `TextPrimary` before the alpha (6.67:1), and size carries the hierarchy. The loading action moves into the disc (item 6) | S | Low. Ambient lights the same pixels a little brighter; the burn-in orbit is unchanged |
| 5 | All dial type; cycle ring | The dial turns reference px into sp through the density (`theme/DialType.kt:84`), which divides out the watch's font size, so the setting has no effect (KDoc `:20-26`). Cycle labels are 9 sp (`CYCLE_LABEL`, 18 px, `DialType.kt:113`). The labels of days other than today are `TextTertiary` on a 30% accent, 2.4 to 3.2:1 (`Dial.kt:506-512`, `:364`) | Layouts survive the largest font scale; the 12 sp floor (v2 §1) | Each text role grows by min(font scale, its zone's budget), with the budget derived in `DialGeometry` from the zone it lives in. The band annulus is 36 px thick (`DialGeometry.kt:172-181`); a caps-only band row with a 1.0 em line box can grow 1.38× at BAND and 1.5× at BAND_SECONDARY, a mixed-case row keeps 1.2 em and 1.15×. Disc lines grow with the setting and auto-size down to fit the disc (Compose `BasicText` autoSize), never below today's size. Cycle labels: only today's segment and a browsed one keep a label, at BAND_SECONDARY (24 px, 12 sp) with a 0.9 em line box, 21.6 px, the same box today's 18 px label already has (18 × 1.2) | M | Medium: needs a pass on the wrist at the largest setting; the curved renderer takes its line height in sp (`DialType.kt:70`) and must accept a box below the font's natural height |
| 6 | Loading and phone states; no program | Ten seconds without a snapshot, the face shows two centered lines and the tap target is the column around them (`LoadingDial.kt:95-104`): about 62 reference px tall, 31 dp on a Pixel Watch. The copy is lowercase, unlike every other face (`res/values/strings.xml:56-63`). The install hand-off shows nothing on success; only a failure changes the face (`WearApp.kt:362-373`). The "open the phone app" state's tap retries instead of opening it (`WearApp.kt:375`). The no-program face says "set up on your phone" and its disc does nothing (`DialState.kt:894-912`) | 48 dp targets; an error says what happened and what to do; an empty state invites the first action | These become dial faces: status in the top band (PHONE AWAY, NO PHONE APP, NO PROGRAM), the one action on a filled disc (RETRY, INSTALL, OPEN ON PHONE), the time in the bottom band. After a remote launch, Wear Material 3's `OpenOnPhoneDialog` confirms that something happened on the phone. The loading sweep turns neutral rather than borrowing day A | S for the faces; M for opening the phone app | Low. Opening the phone app needs a browsable deep link on the phone (exported, opens the app and does nothing else) |
| 7 | Overview | Once the day has started, the disc's second line is the crown-selected lift's set position without its name (`DialState.kt:309-313`): turning the crown changes SET 3 OF 6 to SET 1 OF 3 and nothing says which lift. The time pill is a 22 px capsule (`DialGeometry.kt:67-69`) holding 26 px text in a 31 px line box, so the lower third of the digits is cut off (#330, visible in 01-overview). CONTINUE at DISC_LABEL's 6 px tracking measures 177 px by Barlow's metrics against the disc's 171 px text width (`Dial.kt:611`, `:913`), so it may break inside the word | Show, so the user never has to remember | Disc: START or CONTINUE (at 4 px tracking, 161 px), the lift's name always, and once started the set position as a third line at BAND. The time becomes curved text at BAND_SECONDARY in `TextSecondary` in the bottom band wherever that band is free, drawn by the existing `CurvedBand` (`Dial.kt:822-862`). The pill is deleted | S | Low. Curved text has proportional figures (`DialType.kt:63-66`), so the time re-centers once a minute |
| 8 | Cardio sets | A distance set stacks five lines in the disc: prescription, "Target", the distance, "Elapsed" and the verb (`CardioSetDialState.kt:78-82`). The first line wraps (218 px of text for 171 px of width), so the stack needs about 250 px. Compose's `Column` gives each line only the height left by the lines above it, so the column fills the 204 px disc from its top edge, the wrapped first line is cut by the circle, and the last line, Start, the one that says what a tap does, gets no height and does not show. "End block?" asks a full sentence inside the disc at two lines maximum (`:43`, `Dial.kt:784`). Every string is hard-coded English in mixed case (`CardioSetDialState.kt:27-86`, `Dial.kt:679`) | One reading per face; plan every state | The disc keeps the measure (NUMERAL) and the verb. Activity and role go to the top band; set position and aim to the bottom band. "End block?" becomes Wear Material 3's `AlertDialog`. Copy moves to string resources in the dial's caps | M | Medium; layout only, the cardio state machine is untouched |
| 9 | Rest, peek | The rest face's top band says REST (`DialState.kt:528`), which the countdown already says, and nothing on the face names the lift. The peek's bottom band says "release to return" (`DialState.kt:817`, `strings.xml:43`), but a crown has no release: the peek ends after 1.5 s without a turn (`CrownLayer.kt:126`). The hint is also too long for its band (277 px against the 251 px a 120° sweep allows, `DialGeometry.kt:87`), so it is cut to an ellipsis | Justify every element; labels in the user's words | The rest face's top band carries ready's line for the set coming next (SQUAT · R3; between lifts, the next lift's name). The peek's bottom band names the set being looked at: SET 2 OF 6 · R2 | S | None |
| 10 | Theme, type, dialogs | `:wear` depends on `androidx.wear.compose:compose-material` and uses only its `MaterialTheme`, `Colors` and `Text`. The theme sets `primary` to day A (`WearTheme.kt:58-71`, `:62`) | Material is the component system; color means one thing | Move to `compose-material3` on the same 1.6.2 line. `ColorScheme` by meaning: `primary` `TextPrimary` (disc actions are neutral white, v3 §2), `onPrimary` background, `surfaceContainer` surface, `onSurfaceVariant` `TextSecondary`, `outline` the new `TextTertiary`, `outlineVariant` border, `error` `#C2334D`. Typography in Barlow Condensed for the stock components this plan adds (`AlertDialog`, `OpenOnPhoneDialog`). The dial stays foundation and Canvas, and its KDoc says why: `SegmentedCircularProgressIndicator` draws one indicator color over a track, not current, done, peeked and skipped; `TimeText` sits at 12 o'clock, where the status band lives; `EdgeButton` would be a second tap target | S to M | Low |
| 11 | Ongoing chip | The chip reads "Workout in progress" with a stopwatch (`OngoingWorkoutChip.kt:62-66`, `strings.xml:10`, `:12`) and is posted once per session (`WearApp.kt:1046-1048`). With the wrist down mid-rest, the watch face says nothing about the rest | Show state | Status "Day A · 4/9 sets", and during a rest a countdown part ("Rest 1:24"); re-post on tick and at rest start and end | S to M | Low; the channel is already low importance and silent |
| 12 | Ready, timed hold, rest over (authored plans) | Authored detail (each side, band color) replaces the set position in the bottom band (`DialState.kt:715-717`). Ready shows a prescribed lift's goal label, "3 × 10 · 95 lb", not the round (`DialState.kt:726`; app `ui/day/DayScreenBuilder.kt:43`). The phone sends "Each side" from an English default parameter (app `sync/WatchSnapshotBuilder.kt:198`, `DayScreenBuilder.kt:66`) | Show state; say how sure | Detail becomes a disc line under the round; the bottom band keeps the set position; ready shows the round; the builder passes the localized label | S | Low |
| 13 | Tile | A hand-built box with two arc lines and two text lines (`glance/DayTileLayout.kt:47-127`); the no-program line is `TextTertiary` at 13 sp (`:121`) | Material is the component system | Rebuild on `protolayout-material3`: `primaryLayout` with the day as title and the set count inside its progress indicator, the whole tile still opening the app. The recorded deviation (custom geometry because stock layouts could not show glanceable progress) no longer holds: the tile is one progress ring and two lines | M | Low |
| 14 | Dial semantics | TalkBack gets a description only from `roundDescriptions` (`Dial.kt:131-133`), which only cardio fills (`CardioSetDialState.kt:104-108`); the lift rings and the cycle say nothing. Same as phone audit item 19 | Every control named for a screen reader | Describe the overview ("Day A of 4, squat focus, lift 1 of 2") and each workout ring ("Squat, set 3 of 6, 2 done") | S | None |
| 15 | Ready on an untouched lift | Swap shows itself only after a crown turn; the bottom band says SET 1 OF 6 (`DialState.kt:400`, `:837-853`) | Show options where they apply | While swap is offered the bottom band states the fact: 2 ALTERNATES | S | Low |
| 16 | Queued status | The status dot pulses without end (`Dial.kt:874-887`) for a queue that flushes itself | Spend motion on change | A still dot | S | None |

### Notes on the history the plan changes

`DESIGN.md` records these as inputs. Each plan item that departs from one names it here.

- **Item 1, the start tap** (redesign §5.2 and §5.3, and §9's "the start/complete delta
  is the calorie-calculation input"). That reason no longer holds. Health Connect
  calories use the session window (transfer `health/CaloriesRecordMapper.kt:56`); the
  session start is the stamp the phone keeps from the day's first tick (data
  `TrackerRepository.kt:217-225`, `:1264`); per-set start stamps are stored and backed up
  (transfer `backup/BackupMapping.kt:147`) but no screen or export reads them. Meanwhile
  the 2026-10-12 pager decision made the phone log in one tap ("Log 95 × 5") and gave
  "shares the watch's grammar" as a reason; the watch should now share the phone's.
- **Item 4, ambient gray** (redesign §7, "dim gray type"). Burn-in is handled by the black
  field, the outline-only rule and the ±2 px orbit; the gray level changes how bright the
  lit pixels are, not which pixels are lit. At 2.54:1 the band text cannot be read in a
  lit gym, which costs more than the margin it buys.
- **Item 5, the font-scale pin** ("standing, `DialType.kt`": a 1.3× scale breaks the zone
  guarantee). The guarantee survives if each zone caps the scale it accepts; the pin
  then protects nothing the budget does not.
- **Item 5, the 9 sp waiver** (#152: color carries the identity; 18 px is the largest
  line box that fits the ring). The 18 px limit assumed a 1.2 em line box, which caps-only
  text does not need: Barlow's capitals are about 0.68 em tall (measured in the renders),
  so 24 px caps stand 16 px high in a 22 px ring. The "too large" verdict was about four
  labels; this keeps one. The three it cuts also fail contrast.
- **Item 7, the time pill** (redesign §7 and the note at `DialGeometry.kt:63-66`,
  "geometry beat aesthetics"). The pill solved width and lost height. The bottom band is
  already a zone for exactly this kind of line, and it needs no new shape.
- **Item 13, custom tile geometry** (redesign "Deliberate deviations"). See the item.

The other recorded deviations still hold and stay: local face state instead of
`SwipeDismissableNavHost`, crown instead of pickers, no Horologist, no Health Services.

## Delete outright

- `accentSoft` (`theme/WearTheme.kt:40-45`): nothing calls it.
- The English defaults on `DialCopy` (`ui/DialCopy.kt:40-42`): `rememberDialCopy()` always
  passes the resources.
- The literal "Set options" (`Dial.kt:679`): use a string resource.
- With item 1: the `lifting()` builder (`DialState.kt:469-488`), its branch (`:214`) and
  `LIFTING_TICK_MILLIS` (`WearApp.kt:59`).
- With item 3: the dimmed-alpha special case on the clock ring (`Dial.kt:325`).
- With item 4: `AmbientDim` and `AmbientClock` (`WearTheme.kt:22-30`).
- With item 5: the other-day branch of `cycleLabelColor` (`Dial.kt:511`), `CYCLE_LABEL` and
  its waiver KDoc (`DialType.kt:113`, `DialUiState.kt:28`).
- With item 7: `TimePill` and its helpers (`Dial.kt:170-218`), the `TIME_PILL_*`
  constants and `timePillMaxWidthPx` (`DialGeometry.kt:62-69`, `:148-158`).
- With item 9: `dial_release_to_return` (`strings.xml:43`).
- With item 10: the `compose-material` dependency.
- In `wear-dial-v2.md`, acceptance 7 (ambient draws the clock ring during rests). It was
  never built (`AmbientDial.kt:88-105` draws progress only), and a ring repainted once a
  minute cannot show a 90-second rest honestly. Retire the line rather than build it.

## Decisions only the owner can make

1. **Per-set duration.** One-tap logging (item 1) means weighted and reps sets stop
   carrying a start stamp, and the peek loses its TOOK line. Nothing reads per-set starts
   today. Keep a start tap somewhere, for instance as a setting, or let per-set timing go?
2. **The cycle label on the wrist.** Item 5 replaces the four 9 sp labels you waived in
   #152 with one 12 sp label on today's segment. Please judge it on the wrist. If it still
   reads loud, the other route is no label at all and the day letter in the overview's
   top band ("A · SQUAT FOCUS").
3. **Volume on day done.** The green disc reports tonnage, "12,450 LB" (`DialState.kt:653`).
   For a lifter training for maintenance that is a number the app otherwise avoids.
   Replace it with the set count, as the phone receipt shows? This is the watch side of
   phone audit question 9.
4. **Dynamic color** (open question 8). With Material 3 in place, Wear OS's dynamic scheme
   could tint neutral roles. The recommendation is to leave the dial on its own palette:
   the accents are identity, and a tinted near-black buys nothing on OLED.
5. **Time during a rest.** After item 7 the time shows wherever the bottom band is free:
   overview, day done, phone states. The rest face keeps TAP TO SKIP there. Should the
   rest hand that band to the time after the day's first rest, once the hint has been
   seen?
6. **What the tile's tap does.** Today the whole tile opens the app on its implied face.
   With `protolayout-material3` it could carry an edge button, "Start day A", that opens
   straight into the workout face. One target or two?
7. **The legacy cardio finisher** (phone audit question 14) decides whether item 8 covers
   one cardio dial or two.

## Appendix: inventory (K keep, C cut, Ch change, M move)

**Overview** (`DialState.kt:284-323`, `Dial.kt`): cycle ring K; today's segment K; other
days' segments K; today's label Ch (12 sp, item 5); other days' labels C; day progress arc
K, Ch (separator, item 2); exercise ring K, Ch (butt caps, bright current, item 2); top
band title K; filled disc K; START and CONTINUE K; lift name K, Ch (always shown, item 7);
set position M (into the disc, item 7); time pill C, M (curved bottom band, item 7); crown
select K; swipe to browse K.

**Day browse** (`DialState.kt:335-363`): white marker K; own accent and title K; dimmed
disc K, Ch (text tones, item 4); crown off K.

**Ready and rest over** (`DialState.kt:390-410`, `:542-563`): top band lift and kind K;
START Ch (LOG SET for weighted and reps, item 1); prescription line Ch (numeral size;
the round for prescribed lifts, items 1 and 12); bottom band K, Ch (tone item 4; position
kept under authored detail, item 12; alternates fact, item 15); rested badge K; bloom K;
undo hold K; swipe to next lift K; swap crown K.

**Lifting** (`DialState.kt:469-488`): face C for weighted and reps (item 1); elapsed and
pulsing dot C; TAP TO LOG M (to ready); superset partner line M (ready's bottom band).

**Timed hold and timed reps**: K; clock ring Ch (visible, item 3); rep count crown K.

**Rest** (`DialState.kt:517-540`): REST label Ch (lift and next kind, item 9); countdown
K; NEXT line K; TAP TO SKIP K, Ch (tone, item 4; owner question 5); clock ring Ch (visible,
bright, items 2 and 3); undo hold added (item 1); crown peek K.

**Day done** (`DialState.kt:577-591`): green disc K; DONE K; minutes K; volume (owner
question 3); undo hold K; time Ch (curved band, item 7).

**Crown layer** (`CrownLayer.kt`, `CrownInput.kt`, `DialHaptics.kt`): detent haptics K;
boundary haptic K; peek white marker K; peek's dimmed disc K, Ch (tones, item 4); TOOK
line (owner question 1); release hint C, Ch (set position, item 9); swap preview outlined
K; USE THIS K; alternates count K; SWAPPING K, Ch (tones, item 4); rep count K.

**Queued status** (`DialState.kt:860-869`): K; pulsing dot Ch (still, item 16).

**Cardio sets** (`CardioSetDialState.kt`): rings K; five-line disc Ch (two lines, item 8);
"End block?" sentence M (AlertDialog, item 8); hard-coded mixed-case copy Ch (resources,
caps); "Set options" Ch; round descriptions K. Legacy finisher (owner question 7).

**Ambient** (`AmbientDial.kt`, `AmbientDialState.kt`): true black K; hairline progress K,
Ch (tone, item 4); top band K, Ch (tone); center time or RESTING K, Ch (tone); bottom
time during a rest K; burn-in orbit K; low-bit white K.

**Loading and phone states** (`LoadingDial.kt`): rim sweep K, Ch (neutral, item 6);
wordmark K; centered status text M (top band, item 6); action text M (filled disc, item
6); tap target Ch (the disc, item 6); install hand-off Ch (confirmation, item 6); open the
phone app Ch (opens it, item 6); time pill Ch (item 7). No program and empty day: dashed
disc K for the empty day, Ch for no program (OPEN ON PHONE, item 6).

**Theme and type** (`theme/`): palette K; `primary = dayAccent(0)` C (item 10);
`accentSoft` C; `TextTertiary` Ch (item 4); ambient tokens C (item 4); `DialTextRole` K;
font-scale pin Ch (item 5); `CYCLE_LABEL` C (item 5); Wear Material M (to Material 3, item
10).

**Tile** (`glance/DayTileLayout.kt`): progress ring K; day line K; set line K; no-program
tone Ch (item 4); layout M (`protolayout-material3`, item 13); tap opens app K (owner
question 6).

**Complication** (`glance/DayComplicationService.kt`): ranged value K; short text
fallback K; no colors K; preview data K.

**Ongoing chip** (`OngoingWorkoutChip.kt`): chip K; contextual permission ask K; status
text Ch (day, sets, rest countdown, item 11).

**Snapshot builder** (app `sync/WatchSnapshotBuilder.kt`): short names K (`:249`); cycle
from the program K (`:111`); accent by watch-day position K (`:106`); three alternates K;
goal label for prescribed lifts Ch (ready shows the round, item 12); "Each side" default
Ch (localized, item 12).
