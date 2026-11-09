# ux-testing: exercise.log specifics

Read by the `ux-testing` skill. Generic method lives in the skill; this file names this repo's pieces.

- **Platform:** android-compose
- **Users and moments:** a lifter between sets, phone on a bench about 1 m away, glancing for 1 to 2 seconds with sweaty hands (workout screens); someone on a couch planning or looking back, with time to read (everything else); a wrist turn mid-set on the watch. Key-fact floor on workout screens: about 28 dp (read at 1 m from a bench).
- **Harness:** `docs/playtest.md` is the tester guide (the only repo file testers may read). `scripts/playtest.sh <seat> [script.json]` is the driver (the script defaults to `playtest/<seat>/script.json`); `PLAYTEST_BASE=<sha>` pins the build; results land in `playtest-runs/<seat>/<run>/` with `build.txt`. Pilots are in `playtest/pilot/`.
- **Workflows and artifacts:**
  - `playtest.yml` replays one script on demand, the four core pilots on app PRs, and every pilot except the on-demand `screenshot-stress.json` on harness PRs. Each job uploads `playtest-<run id>-<job index>`; the driver downloads `-0`.
  - `ci.yml` uploads `store-screenshots` and `set-screen-preview` only when the build and the checks before them pass. On failure, each upload fires only if the failing step came before it: `unit-test-results` (Build failed), `room-schemas` (Build or the committed-schema check failed; commit `data/schemas/.../<n>.json` from it with a migration), `listing-images` (any step).
  - `ui-audit.yml` runs on demand and on PRs that change the audit tests or the workflow; it renders the audit set to the `ui-audit` artifact: a PNG plus semantics JSON per shot.
- **Render device:** SDK 35, 360 × 720 dp, xxhdpi; dark theme by default, with light and double-font-scale shots in the audit set.
- **Design rules:** CLAUDE.md principle 5 (Material 3 as the component system, the app's own identity in the theme) and DESIGN.md.
- **Devices:** the owner's Pixel 7 is real hardware. Agents never send input to it. Install and launch, or a passive screencap loop, only when the owner asks. No local emulator (RAM).
- **Kit:** personas, phase A answers, the round brief generator, the Astra seat loop, and past round results are in `~/Projects/strength-log-playtest-kit/`.
- **Builds:** CI only; no local Gradle.

## Personas

Invented people, not based on anyone real. Full diaries, "I want to..." lists and phase A capability answers: `~/Projects/strength-log-playtest-kit/personas/` (`personas.md`, `astra-pN.md` / `fable-pN.md`).

### P1, beginner
- **Who:** 26, never followed a program, nervous about the barbell; knows squat, bench and deadlift by name.
- **Facts the app will ask for:** lb; bodyweight (add to the persona file before round 5; testers invented it in round 4).
- **Schedule and setting:** commercial gym, three evenings a week, about an hour.
- **Goal:** get stronger and stop feeling lost in the gym.

### P2, intermediate
- **Who:** 34, three years of lifting.
- **Facts the app will ask for:** squat about 225 × 5, bench 175 × 5, deadlift 275 × 5 (lb); bodyweight to add.
- **Schedule and setting:** home garage, four days a week; barbell, rack, adjustable dumbbells, pull-up bar; no cables or machines.
- **Goal:** steady progress without program-hopping.

### P3, expert
- **Who:** 41, competitive powerlifter for 10 years; fluent in percentages, RPE and peaking.
- **Facts the app will ask for:** kg; a meet in 12 weeks; recent squat, bench and deadlift numbers (to add before round 5; round 4's tester invented them).
- **Schedule and setting:** powerlifting gym, five days a week.
- **Goal:** a tool that keeps up and stays out of the way.

### P4, cardio inside strength days
- **Who:** 38, general fitness.
- **Facts the app will ask for:** lb; rower and bike work, steady and intervals.
- **Schedule and setting:** commercial gym, three days a week, about 75 minutes, ending most sessions with 10 to 20 minutes of rower or bike.
- **Goal:** see both kinds of work going up.

### P5, separate cardio sessions
- **Who:** 29, a runner, about 25 km a week over three runs, added two strength days for injury prevention.
- **Facts the app will ask for:** km; a recent race or time trial and the longest run in the past 30 days (to add before round 5).
- **Schedule and setting:** runs and lifts are separate sessions, sometimes the same day.
- **Goal:** keep run times and distances, and fit the lifting around the running.
