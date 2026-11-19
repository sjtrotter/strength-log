# ux-testing: exercise.log specifics

Read by the `ux-testing` skill. Generic method lives in the skill; this file names this repo's pieces.

- **Platform:** android-compose
- **Users and moments:** a lifter between sets, phone on a bench about 1 m away, glancing for 1 to 2 seconds with sweaty hands (workout screens); someone on a couch planning or looking back, with time to read (everything else); a wrist turn mid-set on the watch. Key-fact floor on workout screens: about 28 dp (read at 1 m from a bench).
- **Harness:** `docs/playtest.md` is the tester guide (the only repo file testers may read). `scripts/playtest.sh <seat> [script.json]` is the driver (the script defaults to `playtest/<seat>/script.json`); `PLAYTEST_BASE=<sha>` pins the build; results land in `playtest-runs/<seat>/<run>/` with `build.txt`. Pilots are in `playtest/pilot/`.
- **Workflows and artifacts:**
  - `playtest.yml` replays one script on demand, the six core pilots (wizard-to-today, rest-timer-advance, history-day-start, dialog-text-field, ladder-day, sprint-beep-timer) on app PRs, and every pilot except the on-demand `screenshot-stress.json` on harness PRs. Each job uploads `playtest-<run id>-<job index>`; the driver downloads `-0`.
  - `ci.yml` uploads `store-screenshots` and `set-screen-preview` only when the build and the checks before them pass. On failure, each upload fires only if the failing step came before it: `unit-test-results` (Build failed), `room-schemas` (Build or the committed-schema check failed; commit `data/schemas/.../<n>.json` from it with a migration), `listing-images` (any step).
  - `ui-audit.yml` runs on demand and on PRs that change the audit tests or the workflow; it renders the audit set to the `ui-audit` artifact: a PNG plus semantics JSON per shot.
- **Render device:** SDK 35, 360 × 720 dp, xxhdpi; dark theme by default, with light and double-font-scale shots in the audit set.
- **Design rules:** CLAUDE.md principle 5 (Material 3 as the component system, the app's own identity in the theme) and DESIGN.md.
- **Devices:** the owner's Pixel 7 is real hardware. Agents never send input to it. Install and launch, or a passive screencap loop, only when the owner asks. No local emulator (RAM).
- **Kit:** personas, phase A answers, the round brief generator, the Astra seat loop, and past round results are in `~/Projects/strength-log-playtest-kit/`.
- **Builds:** CI only; no local Gradle.

## Harness pilots

The scripts in `playtest/pilot/` cover launch, dialog capture, the wizard to
Today, rest expiry, the three-second Finish gate after all sets are logged,
day crossing, scrolling below the fold and through a lazy list, `ime` on a
sheet field with compound advances, a dialog with a text field, and starting a day
whose lifts have history. The day-crossing pilot watches History's calendar move
its today marker from November 9 to November 11 while keeping the session on November 9,
then checks day B on Today. The wizard has no dialog in this version;
the dialog pilot opens the workout Finish confirmation after completing it.
For example, the host runs `scripts/playtest.sh zero-step-launch playtest/pilot/zero-step-launch.json`.

The round-5 pilots add these checks:

- `optional-step` skips a missing selection and runs the following expectation.
- `sprint-beep-timer` follows the sprint setup, countdown, and a 12.34-second phone-timed rep.
- `sprint-typed-time` saves a typed rep time with its timing method and start type.
- `ladder-day` follows the run setup and finishes all twenty run/walk sets, captures the receipt, then checks Logged 25:00 in History; CI also checks the saved total is exactly 1,500 seconds.
- `run-typed-distance` finishes a live run with GPS distance off and a typed distance.
- `cardio-log-past` saves a past session and finds its distance in History.
- `timed-rep-count` configures reps in time and logs a count after the timer ends.
- `test-date-picker` enters the setup event date through Material's text input mode.
- `balance-day` accepts the 65+ balance offer during setup and checks for Balance in the saved plan.

The second-pass harness regressions have these checks:

| Item | Pilot or workaround |
| --- | --- |
| Swallowed app errors | `history-day-start` covers the history-backed launch path that exposed the clock crash. `AppCrashesTest` injects coroutine and main-looper exceptions and checks step failure details. |
| H5 | `advance-tap` taps Start day A immediately after a two-day advance. |
| H6 | Interaction settling replaces the page-naming `expect` workaround. `page-transition-settle` taps a control on the new exercise page immediately after navigation. |
| H7 | `dropdown-tap` opens Activity in Edit day and selects Trail run. |
| H8 | `screenshot-stress` writes 1,200 checkpoint screenshots. Run it on demand; it is outside the PR matrix. Long persona scripts can use `"capture": "checkpoints"`. |
| H9 | `zero-size-tap` expects `not-visible` for an off-screen equipment row on both tap and long press, then scrolls and taps it. |
| Local clock | `advance-tap` starts at 23:39 in America/Chicago; its summary keeps the local date beside the next-day UTC date. `ScriptAssertionsTest` checks both values and the zone. |
| Launch budget | `zero-step-launch` captures launch within the 20-second budget. |
| Compact capture | `compact-capture` covers text diffs, a named checkpoint, a note, and a full final capture. |

GPS location is not available in the harness. Pilots can switch GPS distance off
in Settings or type distances where the app asks.

## Personas

Invented people, not based on anyone real. Full diaries, "I want to..." lists and phase A capability answers: `~/Projects/strength-log-playtest-kit/r5/personas/p1.md` through `p9.md`.

### P1, beginner
- **Who:** 26, never followed a program, nervous about the barbell; knows squat, bench and deadlift by name.
- **Facts the app will ask for:** 172 lb bodyweight; pounds and miles; no recent lift numbers.
- **Schedule and setting:** commercial gym, three evenings a week, about an hour.
- **Goal:** get stronger and stop feeling lost in the gym.

### P2, intermediate
- **Who:** 34, three years of lifting.
- **Facts the app will ask for:** 192 lb bodyweight; pounds and miles; squat 225 × 5, bench 175 × 5, deadlift 275 × 5, overhead press 115 × 5, pull-up +25 × 5, RDL 185 × 8; 5 lb load steps. Longest run 32 minutes, 3.4 miles.
- **Schedule and setting:** home garage, four days a week; barbell, rack, adjustable dumbbells, pull-up bar; no cables or machines.
- **Goal:** steady progress without program-hopping.

### P3, expert
- **Who:** 41, competitive powerlifter for 10 years; fluent in percentages, RPE and peaking.
- **Facts the app will ask for:** 92.5 kg bodyweight; kilograms and kilometers; January 30, 2027 meet. Recent squat 200 × 3, bench 130 × 3, deadlift 235 × 3 at RPE 8; close-grip bench 90 × 6 at RPE 7. Bar steps 2.5 kg; dumbbells 2 kg.
- **Schedule and setting:** powerlifting gym, five days a week.
- **Goal:** a tool that keeps up and stays out of the way.

### P4, cardio inside strength days
- **Who:** 38, general fitness.
- **Facts the app will ask for:** 205 lb bodyweight; pounds, meters on the rower, miles for walks. Squat 185 × 5, bench 155 × 5, deadlift 225 × 5, overhead press 95 × 5, pulldown 120 × 10. Row 2,000 m best 8:05; longest row 20 minutes; bike 8 × 30 s hard / 90 s easy.
- **Schedule and setting:** commercial gym, three days a week, about 75 minutes, ending most sessions with 10 to 20 minutes of rower or bike.
- **Goal:** see both kinds of work going up.

### P5, separate cardio sessions
- **Who:** 29, a runner, about 25 km a week over three runs, added two strength days for injury prevention.
- **Facts the app will ask for:** 134 lb bodyweight; kilometers running, pounds lifting; under a year lifting, no recent running injury. October 31 5 km time trial 26:41; longest run 12 km in 1:14:30. Goblet squat 35 × 8, DB RDL 30 per hand × 8.
- **Schedule and setting:** runs and lifts are separate sessions, sometimes the same day.
- **Goal:** keep run times and distances, and fit the lifting around the running.

### P6, brand-new runner
- **Who:** Dana, 43, no running since high school and no lifting experience.
- **Facts the app will ask for:** 214 lb bodyweight; pounds and miles; no recent run or race, no running injury; longest walk 35 minutes, 1.7 miles; considering a March 20, 2027 5K.
- **Schedule and setting:** neighborhood sidewalks or rec center treadmill; Monday and Wednesday at 6:15 am, Saturday at 8 am; 30 to 40 minutes; phone in an armband, no watch.
- **Goal:** finish a 5K with less walking, starting with run/walk intervals.

### P7, team-sport athlete with a sprint goal
- **Who:** Mateo, 19, college soccer winger; three years lifting; left hamstring strain in August, back since September.
- **Facts the app will ask for:** 168 lb bodyweight; pounds, yards and meters; recent all-out sprinting; 40 yd best 4.92 hand-timed, aiming under 4.85; squat 225 × 5, trap-bar deadlift 275 × 5, bench 165 × 5, bodyweight pull-ups × 10.
- **Schedule and setting:** college weight room and turf, Tuesday and Friday afternoons, 60 to 75 minutes; Wednesday futsal and Sunday pickup; hand, phone, or gate timing.
- **Goal:** improve the 40-yard dash and strength without losing speed.

### P8, police officer with a fitness test
- **Who:** Ray, 34, patrol officer; some running and a year or more lifting.
- **Facts the app will ask for:** 208 lb bodyweight; pounds and miles; December 12, 2026 test, 1.5-mile target 12:59; recent 14:05, 32 push-ups and 33 sit-ups in a minute; longest run 22 minutes, 2.2 miles; no running injury; bench 205 × 5, DB row 70 × 10, goblet squat 70 × 10.
- **Schedule and setting:** Tuesday and Thursday after shift, about 45 minutes at the station gym; weekend mornings on roads, track, or treadmill; overtime disrupts sessions.
- **Goal:** 40 push-ups, 40 sit-ups, and 1.5 miles under 13 minutes on test day.

### P9, older lifter
- **Who:** Joan, 67, retired librarian; under a year lifting; replaced right knee, osteopenia, large phone text.
- **Facts the app will ask for:** 148 lb bodyweight; pounds and miles; leg press 110 × 10, chest press 40 × 10, seated row and pulldown 50 × 10, seated DB shoulder press 10 per hand × 10, about three reps left; no running; walks 30 minutes, 1.5 miles.
- **Schedule and setting:** YMCA machines, cables and dumbbells, Tuesday and Friday at 9:30 am, 45 minutes; Thursday balance class; short home balance sessions after walks.
- **Goal:** build strength with slow load increases and balance practice while accommodating the knee.
