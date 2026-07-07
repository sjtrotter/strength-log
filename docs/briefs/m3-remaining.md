# M3 remaining briefs — #9, #11, #12, #13, #14 (all tier:sonnet, sequential)

Run in this order; each branches from main after the previous merges (they all
touch the nav graph / day feature). Every PR runs the full loop: adversarial
review (Opus agent) → fix round → verify pass by the same reviewer → merge.
Standard worktree setup applies (local.properties, Temurin 17, ./gradlew, no
device — see ARCHITECTURE-DECISIONS D10).

---

## #9 Setup wizard (+ optional equipment step, A4)

Read: spec §6.1–§6.3, §8.1; PLAN A4 (equipment step); D1/D3/D4.

- Six steps + the optional equipment step, every step pre-answered so
  next-next-next yields the default program (spec §6.1 defaults are pinned
  behavior). Deadlift anchor exposes trap-bar/conventional/sumo choice.
- Ends by: persist config + wizard answers + `wizardComplete=true`, generate
  via `:domain` ProgramGenerator, `replaceProgram`. All existing repository
  API — the wizard adds no `:data` surface.
- **Deletes the DayViewModel bootstrap hack (D3).** Reuses the existing
  wizard-answer DataStore keys from M2 (SSOT — `resetDayToTemplate` reads the
  same answers).
- Re-run entry (from Setup later) is the same route; when re-run completes it
  replaces the program (destructive confirm lives on the Setup button, #12).
- UI: design system components; stepper for numbers; selection cards for
  choices. No new deps.
- Tests: JVM tests for any wizard state logic (step defaults, anchor-set
  narrowing for 2–3 day splits); generator itself is already pinned in
  `:domain` — do not re-test it, do not touch pinned files.

## #11 Day edit sheet + substitution picker (A4 search/equipment filter)

Read: spec §8.3; PLAN A4; D1 (sheet not route, shares DayViewModel).

- Gear on the day header opens a `ModalBottomSheet`: list of the day's slots
  with swap / remove (min 3 enforced) / add (pattern → exercise) / reset-day-
  to-template (regenerates from stored wizard answers — repository API exists).
- Swap opens the picker: same-pattern candidates ranked by subRank, with a
  search field and equipment filter (A4 — the catalog is 175 entries). Row
  shows name + equipment; confirming calls `swapExercise` (old log kept,
  new exercise seeds fresh via D4 on next observation).
- Picker includes "＋ Create exercise" → navigates to `customExercise` route
  (#13) with the pattern pre-selected; until #13 merges, hide the entry.
- Add flow enforces spec §8.3; remove confirms nothing (cheap, reversible by
  re-adding) but respects min-3.
- Tests: JVM tests for filter/search/rank ordering logic (pure function over
  the catalog — extract it as one).

## #12 Setup screen

Read: spec §8.4; A5 (unit pref); D1.

- Steppers for bodyweight/age, selectors for level/emphasis, cardio prefs,
  lb/kg unit toggle, and the **live GOAL preview**: the four main-lift GOALs
  recomputed on every input change via GoalCalculator (display-only — config
  is persisted on change per data principles; GOALs are always derived, never
  stored).
- "Re-run setup wizard" behind a destructive confirm → `wizard` route.
- Config changes do NOT touch existing logs (GOAL vs ACTUAL principle); the
  day screen's GOAL block updates because it derives from configFlow.
- Tests: JVM test that the preview math delegates to GoalCalculator (no
  reimplementation — SSOT), unit-toggle display conversion via `:domain` units.

## #13 Custom exercise creation (A4)

Read: PLAN A4; D1 (route with optional pattern arg).

- Form: name (required, trimmed, non-blank), movement pattern, equipment
  multi-select, perHand flag, starting weight (unit-aware stepper). Saves via
  the existing `:data` custom-exercise API (`custom_` id prefix, merged
  catalog — all built in M2).
- Reachable from the #11 picker (pattern pre-filled) and Setup (#12) —
  add the Setup entry point in this PR.
- On save from the picker context: return to the picker with the new exercise
  visible (it's in its pattern's list, ranked after catalog entries).
- Tests: JVM validation tests (blank name, id collision impossibility via
  prefix, catalog merge visibility).

## #14 History Log screen (A1)

Read: PLAN A1; D1/D2 (route + LOG tab).

- Reverse-chronological list of `workout_session` records: date, day letter +
  title (badge with that day's accent), set count, bodyweight; expanding a
  session shows its `session_set` rows grouped by exercise (name, `w×r`,
  kind). Read-only. No charts (v2).
- Add the "last time: {w}×{r}" chip to the day screen's exercise cards
  (A1 bonus) — latest matching exerciseId in history; needs a narrow `:data`
  read (`lastPerformedFlow(exerciseId)` or a per-day batch query — prefer one
  query for the day's exercise ids, not N).
- External (Health Connect) sessions will appear later (#17) — leave a
  `source` presentation affordance only if trivially cheap; do not build for
  it (no speculative abstraction).
- Tests: JVM tests for the grouping/formatting logic; `:data` query gets a
  test alongside existing patterns if new SQL is added.
