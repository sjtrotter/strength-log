# Cross-cutting decisions (Fable, 2026-07-06)

Decisions with consequences across multiple issues, made now so implementation
agents never have to invent them. Briefs in this folder reference these by
number. Spec/PLAN stay the source of truth for *behavior*; this file only adds
the execution decisions they leave open.

## D1. Navigation graph

Routes in `AppNavHost`: `wizard`, `day` (start when wizard complete), `setup`,
`log`, `customExercise?pattern={pattern}`. The day-edit surface (#11) is a
`ModalBottomSheet` inside the day feature, NOT a nav route — it edits the same
day the user is looking at and shares `DayViewModel`. The substitution picker
lives inside that sheet. Custom-exercise creation (#13) IS a route because it's
reachable from two places (the picker's "create exercise" and Setup).

Start-destination logic: `MainActivity` keeps the splash on-screen
(`setKeepOnScreenCondition`) until `wizardCompleteFlow` emits, then the graph
starts at `wizard` or `day`. No flicker-navigation after compose.

## D2. Tab strip

`⚙` + one lettered day tab each + `LOG` at the trailing end (A1 calls history
"a Log tab"). Setup and Log are plain navigations, not day-selections; day
tabs keep the suggested/override semantics from spec §8.2.

## D3. The #10 bootstrap hack dies in #9

`DayViewModel` currently generates a default program on first run (temporary,
commented). The wizard PR (#9) must delete that path; after #9,
`replaceProgram` from the wizard is the only program creator. A leftover
bootstrap that can race the wizard is a review-blocking finding.

## D4. Seeding stays in the ViewModel

Per the M2 decision: `:data` never seeds; the day ViewModel seeds a slot's log
via `updateSets` when it observes an empty log for a slot (GoalCalculator +
SetSeeder from `:domain`). Wizard/day-edit/custom-exercise do NOT pre-seed;
they only shape the program. Any second seeding site is a finding.

## D5. Hilt module layout

One `DataModule` in `:app/di` provides the singleton repository (plus DAOs/
DataStore if ever needed directly). `:transfer` gets its own `TransferModule`
in `:app/di` when #16/#17 wire UI. No Hilt inside `:data`/`:transfer`
themselves — they stay plain constructors (injection-friendly, framework-free).

## D6. Sync payload DTOs live in `:domain`

Wear sync (#20) and the watch UI (#19) share wire DTOs (`WatchSnapshot`,
`SetEditDelta`). They are pure-Kotlin `@Serializable` types; `:domain` is the
one module both sides already share, so they live there under `sync/`. kotlinx
serialization is already a `:domain` dependency pattern (pure Kotlin — no
Android). Transport code stays out: phone side in `:app/sync`, watch side in
`:wear`.

## D7. Health Connect trigger point

HC writing hooks the same event that writes history: after `advanceDay`
returns, the day ViewModel fires a non-blocking `SessionPublisher.publish(id)`
(interface in `:transfer`, no-op default). No new observer machinery in
`:data`. HC unavailability = the no-op — the feature degrades invisibly (A3).

## D8. Design-pass integration

The user is bringing back a visual direction from a design session (brief:
`docs/DESIGN_BRIEF.html`). When it lands: one restyle issue per surface, all
Sonnet, touching `ui/theme` + `ui/components` first; screens only where layout
changes (the set row is expected to change shape). Token changes must update
the contrast unit tests in the same PR, deliberately. Behavior contracts
(§8.2 etc.) are out of bounds for the restyle. Do not start #21 (a11y/polish)
or #22 (icon) until the design direction exists — they'd be redone.

## D9. Exports never read Room directly

CSV (#16) and JSON backup (#15) consume repository/DAO read surfaces, and
imports write through one staging-transaction API (`:data`), so validation and
atomicity rules live in exactly one place. `:transfer` core APIs take/return
streams or strings — SAF/share-sheet Uri handling is `:app`-side UI work
(lands with Setup #12 or its own small PR).

## D10. Instrumented tests

Local machines never run connected tasks (the attached device is the user's
personal phone — see memory). Instrumented tests live where they must, run in
CI's emulator step (`.github/workflows/ci.yml` `build` job); any module adding
them extends that script line in the same PR. Prefer JVM/Robolectric when the
code under test allows it.
