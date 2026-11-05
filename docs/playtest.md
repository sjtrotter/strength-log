# Playtest scripts

Edit `playtest/<seat>/script.json`. Ask the host operator to run
`scripts/playtest.sh <seat> [script.json]`. The host needs Git, GitHub CLI, jq,
and network access. Your tester workspace does not. The driver keeps the seat
branch's commits, merges updates from main, pushes normally, and downloads results
to `playtest-runs/<seat>/<run>/`. It stops on dirty worktrees or merge conflicts.

```json
{
  "seat": "beginner",
  "device": "w411dp-h891dp",
  "start": { "date": "2026-11-09", "time": "17:30", "zone": "America/Chicago" },
  "steps": [
    { "tap": { "text": "Next" } },
    { "checkpoint": "days-per-week" }
  ]
}
```

`seat` and `steps` are required. Seat names use letters, numbers, underscores,
or hyphens. Device and start default to the values above. Locale is pinned to
`en-US`; a fresh wizard defaults to pounds. Each run starts with fresh storage and replays every step.
Append steps to the script between turns. The last green run's step count controls
where new captures start. Checkpoints and failures are always captured. If you
change earlier steps, remove `playtest-runs/<seat>/.last-ok` to capture everything.

Read `summary.json` first, then `error.json` if present. `log.jsonl` records each
attempted step. `backup.json` is the real app backup at exit, including on failure.
`steps/000-launch.*` shows launch. Other numbered PNG and JSON pairs show the
screen after that step, using zero-based step numbers. JSON includes text, tags,
roles, disabled state, and bounds. A zero-step script still captures launch.
Set top-level `"capture": "checkpoints"` for PNGs only at launch, checkpoints,
and failures. JSON still captures each new non-note step.

## Actions

Put exactly one action in each step. Unknown keys and malformed payloads fail
before the activity launches.

| Action | Payload and behavior |
| --- | --- |
| `tap` | Selector. Clicks the matching node. |
| `longPress` | Selector. Holds the matching node. |
| `type` | Selector plus `"value": "185"` and optional `"ime": "done"`. Replaces all text after requesting focus. |
| `scrollTo` | Selector. Scrolls to an existing target; for an uncomposed target, searches a lazy scroll container, number 0 or the supplied `index`. |
| `swipe` | Selector plus `"direction": "left"`, `"right"`, `"up"`, or `"down"`. |
| `back` | `true`. Uses the activity's real Back dispatcher. |
| `advance` | Positive whole duration such as `"90s"`, `"15m"`, `"2h"`, or `"2d"`. Moves wall time, civil day, and timer clocks together. |
| `recreate` | `true`. Recreates the activity to exercise saved state. |
| `checkpoint` | A nonblank name. Always captures a named screen. |
| `expect` | Selector, optionally `"absent": true`. Requires one match, or zero when absent. `index` selects a repeated match. |
| `note` | A string copied into the log. No capture. |

Use `type`, never `tap`, for a text field. A second click can start a magnifier
that Robolectric cannot draw. Number fields commit on Done or when focus leaves.
Use `"ime": "done"` to commit immediately. Typing also works in dialogs; there
is no keyboard to dismiss. A screen has 10 seconds to settle. Advances over one
hour require no open workout and no armed rest. Finish before jumping to another day.

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
`not-scrollable`, `crash` (stack included), `timeout`, or `script`. Disabled nodes
still match. An expectation can verify their existence, but an action fails.

## Restores and pilots

Optional `start.restore` names a backup, relative to the script's directory or
absolute. It imports through the real backup service before launch. Keep the
backup's sibling `summary.json` to recover its fake clock, or supply explicit
`start.date` and `start.time`. Current app backups have no export timestamp.
Restore opens Today with no saved navigation, drafts, rest deadline, undo offer,
cardio anchors, or watch stamps. A backup from an unfinished workout cannot
resume those transient states. Named checkpoints are screen captures, not resumable saves.

The five scripts in `playtest/pilot/` cover launch, dialog capture, the wizard to
Today, rest expiry, and day crossing. The wizard has no dialog in this version;
the dialog pilot opens the workout Finish confirmation after completing it.
For example, the host runs `scripts/playtest.sh zero-step-launch playtest/pilot/zero-step-launch.json`.

There is no system UI, notification display, IME, share sheet, watch, Health
Connect, haptics, or real animation timing. SAF pickers return nothing. Inspect
backup and export data through the artifact. Use the harness for plan building
and workout execution against real storage.
