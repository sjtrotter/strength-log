# Playtest scripts

Edit `playtest/<seat>/script.json`. Ask the host operator to run
`scripts/playtest.sh <seat> [script.json]`. The host needs Git, GitHub CLI, jq,
and network access. Your tester workspace does not. The driver keeps the seat
branch's commits, merges updates from main, pushes normally, and downloads results
to `playtest-runs/<seat>/<run>/`. It stops on dirty worktrees or merge conflicts.

```json
{
  "seat": "beginner",
  "device": "w360dp-h720dp-night-xxhdpi",
  "start": { "date": "2026-11-09", "time": "17:30", "zone": "America/Chicago" },
  "steps": [
    { "tap": { "text": "Next" } },
    { "checkpoint": "days-per-week" }
  ]
}
```

`seat` and `steps` are required. Seat names use letters, numbers, underscores,
or hyphens. Device and start default to the values above. Locale is pinned to
`en-US`; the app runs in the script's `start.zone`. A fresh wizard defaults to
pounds. Each run starts with fresh storage and replays every step.
Append steps to the script between turns. The last green run's step count controls
where new captures start. Checkpoints and failures are always captured. If you
change earlier steps, remove `playtest-runs/<seat>/.last-ok` to capture everything.

Read `summary.json` first, then `error.json` if present. `log.jsonl` records each
attempted step. A `type` step with `ime` also records the field's declared action
and whether it ran. `clock_at_exit` is UTC; `clock_at_exit_local` is the ISO local
date-time in `clock_zone`, the script's zone. For example, a November 9 session
at 23:39 in America/Chicago exits at `2026-11-10T05:39:00Z` in UTC but
`2026-11-09T23:39` locally. `backup.json` is the real app backup at exit, including on
failure when export succeeds. The summary is written before export and rewritten afterward;
an export failure is recorded in `backup_error` without replacing the step failure.
Export and restore each have a 60-second real-time limit. Storage runs in the
background while the calling thread idles the main looper, including storage reads
within a step's remaining budget.
`steps/000-launch.*` shows launch. Other numbered PNG and JSON pairs show the
screen after that step, using zero-based step numbers. JSON includes text, tags,
roles, disabled state, and bounds in dp. The default device matches the audit:
SDK 35, 360 by 720 dp, xxhdpi, night mode, with 1080 px wide PNGs.
A zero-step script still captures launch.
Set top-level `"capture": "checkpoints"` for PNGs only at launch, checkpoints,
and failures. JSON still captures each new non-note step.
Use this mode for long scripts to reduce screenshot work. PNG encoding streams
each bitmap to disk and releases it after the step; playtest test tasks have a
2 GiB maximum heap.

## Actions

Put exactly one action in each step, plus optional `timeoutMs` or `expectError`. Unknown keys and
malformed payloads fail before the activity launches. For example:

```json
{ "expect": { "text": "Start day A" }, "timeoutMs": 20000 }
```

Each step has one real-time deadline, defaulting to 10,000 ms. `timeoutMs` must be
a positive integer. Lookup, action, screen settling, and capture share that budget.
Launch has a separate 20,000 ms budget.
Every selector retries until it resolves to one node (or the requested `index`).
For `tap`, `longPress`, `type`, and `swipe`, a disabled match also retries until
it becomes enabled, sharing the same step deadline as missing or ambiguous matches.
An absent expectation waits for the node to disappear. Between attempts the
harness advances a Compose frame, idles the main looper, and briefly sleeps in
real time so Room and DataStore can finish. A still screen alone does not make
a missing selector fail. Only the deadline produces `not-found`, `ambiguous`, or `disabled`,
using candidates or matches from the final attempt. Espresso and Compose idling
timeouts are reported as `timeout`, with the failing step index.
If a step remains stuck 30 seconds past its deadline, the harness writes `error.json` and `summary.json` and halts the JVM without attempting a screen capture or backup.
An uncaught app coroutine or main-looper exception fails the current step as
`app-crash`, including while a selector is waiting. `error.json` adds
`exception_class`, `exception_message`, and `exception_stack` (up to 12 frames);
the existing `stack` field still includes the exception chain.

| Action | Payload and behavior |
| --- | --- |
| `tap` | Selector. Clicks the matching visible node. Empty or entirely off-screen bounds fail with `not-visible`; use `scrollTo` first. |
| `longPress` | Selector. Holds the matching visible node, with the same bounds check as `tap`. |
| `type` | Selector plus `"value": "185"` and optional `"ime": "done"`. Replaces all text after requesting focus. With `"ime": "done"`, performs the field's declared IME action. A field that declares none (the set and prescription sheets) commits as you type, so the step skips it and logs `"ime": {"declared": "Default", "performed": false}`. |
| `scrollTo` | Selector. Scrolls the nearest scrollable ancestor until the target is in view; for one not yet drawn, pages each lazy list on screen until it appears. Each scroll runs the Compose animation clock one second. |
| `swipe` | Selector plus `"direction": "left"`, `"right"`, `"up"`, or `"down"`. |
| `back` | `true`. Like the system Back key: an open sheet or dialog closes first, otherwise the screen goes back. |
| `advance` | Positive duration in days, hours, minutes and seconds, in that order, such as `"90s"`, `"15m"`, `"2h"`, `"2d"` or `"23h8m"`. Moves wall time, civil day, and timer clocks together. Runs an extra looper and recomposition settle pass before the next step, without advancing the app's fake clock. |
| `recreate` | `true`. Recreates the activity to exercise saved state. |
| `checkpoint` | A nonblank name. Always captures a named screen. |
| `expect` | Selector, optionally `"absent": true`. Requires one match, or zero when absent. `index` selects a repeated match. |
| `note` | A string copied into the log. No capture. |

Use `type` for an editable text field. A second click can start a magnifier
that Robolectric cannot draw. A read-only Material 3 exposed dropdown anchor,
such as Activity in Edit day → Edit cardio, allows `tap` to open its menu.
Plain editable fields still refuse `tap`. Wizard and Settings number fields commit on Done or
when focus leaves; use `"ime": "done"` to commit at once. Set and prescription sheet
fields commit as you type. Typing also works in dialogs; there is no keyboard to
dismiss. Advances over one hour require no open workout and no armed rest. Finish
before jumping to another day.

A tap that switches exercise pages must be followed by an `expect` naming the
new page before using controls shared with the previous page (H6). For example:

```json
{ "tap": { "text": "Next: DB Bench Press" } }
{ "expect": { "text": "DB Bench Press" } }
{ "tap": { "text": "All sets" } }
```

For visibility regression pilots, `tap` and `longPress` accept
`"expectError": "not-visible"` beside the action. The step succeeds only if the
bounds check rejects it, records `expected_error` in `log.jsonl`, and continues
so the script can `scrollTo` and retry. Success without the expected error fails
as `script`; any other error still fails the run. Other expected errors and
actions are refused when parsing.

## Selectors

All supplied keys must match. Clicks, gestures, scrolling, and expectations use
the merged semantics tree. Typing uses editable nodes in the unmerged tree and
also checks the field's label so a filled field stays selectable.

| Key | Meaning |
| --- | --- |
| `text` | Exact text, editable value, or field label, with outer whitespace trimmed. |
| `textContains` | Case-sensitive substring. |
| `desc` | Exact accessibility content description. |
| `tag` | Exact test tag from the JSON dump. |
| `role` | `Button`, `Checkbox`, `Switch`, `Tab`, `RadioButton`, `DropdownList`, or `Image`. |
| `editable` | `true` restricts to editable nodes. Implied for `type`. |
| `inDialog` | `true` includes only dialog descendants; `false` excludes them. |
| `index` | Zero-based match number when repeated rows are intentional. |

Errors are `not-found` (up to 15 nearby text candidates), `ambiguous` (all matches
with bounds, role, and ancestor text), `disabled`, `not-clickable`, `not-editable`,
`not-scrollable`, `not-visible`, `app-crash`, `crash` (stack included), `timeout`, or `script`. Disabled nodes
still match. An expectation can verify their existence. `tap`, `longPress`, `type`,
and `swipe` report `disabled` only if the final match remains disabled at the
deadline. Scrolling to a disabled target or searching a disabled scroll container
reports `disabled` immediately.

## Restores and pilots

Optional `start.restore` names a backup, relative to the script's directory or
absolute. For a CI run, use a file name next to the script: `scripts/playtest.sh`
commits that file to the seat branch with the script. It imports through the real backup service before launch. Keep the
backup's sibling `summary.json` to recover its fake clock, or supply explicit
`start.date` and `start.time`. Current app backups have no export timestamp.
Restore opens Today with no saved navigation, drafts, rest deadline, undo offer,
cardio anchors, or watch stamps. A backup from an unfinished workout cannot
resume those transient states. Named checkpoints are screen captures, not resumable saves.

The scripts in `playtest/pilot/` cover launch, dialog capture, the wizard to
Today, rest expiry, the three-second Finish gate after all sets are logged,
day crossing, scrolling below the fold and through a lazy list, `ime` on a
sheet field with compound advances, a dialog with a text field, and starting a day
whose lifts have history. The day-crossing pilot watches History's calendar move
its today marker from November 9 to November 11 while keeping the session on November 9,
then checks day B on Today. The wizard has no dialog in this version;
the dialog pilot opens the workout Finish confirmation after completing it.
For example, the host runs `scripts/playtest.sh zero-step-launch playtest/pilot/zero-step-launch.json`.

The second-pass harness regressions have these checks:

| Item | Pilot or workaround |
| --- | --- |
| Swallowed app errors | `history-day-start` covers the history-backed launch path that exposed the clock crash. `AppCrashesTest` injects coroutine and main-looper exceptions and checks step failure details. |
| H5 | `advance-tap` taps Start day A immediately after a two-day advance. |
| H6 | Add the page-naming `expect` shown above after exercise navigation. |
| H7 | `dropdown-tap` opens Activity in Edit day and selects Trail run. |
| H8 | `screenshot-stress` writes 1,200 checkpoint screenshots. Run it on demand; it is outside the PR matrix. Long persona scripts can use `"capture": "checkpoints"`. |
| H9 | `zero-size-tap` expects `not-visible` for an off-screen equipment row on both tap and long press, then scrolls and taps it. |
| Local clock | `advance-tap` starts at 23:39 in America/Chicago; its summary keeps the local date beside the next-day UTC date. `ScriptAssertionsTest` checks both values and the zone. |
| Launch budget | `zero-step-launch` captures launch within the 20-second budget. |

There is no system UI, notification display, IME, share sheet, watch, Health
Connect, haptics, or real animation timing. Dialogs and menus are sized to their
content and centered, as on a phone. SAF pickers return nothing. Inspect
backup and export data through the artifact. Use the harness for plan building
and workout execution against real storage.
