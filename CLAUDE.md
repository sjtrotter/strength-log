# strength-log

Native Android + Wear OS strength-training tracker. Local-first, no accounts, no
ads, no network permission. The product spec is `STRENGTH_TRACKER_SPEC.md`; the
delivery plan and spec amendments are `docs/PLAN.md`. When those two disagree,
`docs/PLAN.md` wins (it is the reviewed superset).

## Model usage & delegation policy

- **Claude plans, briefs, adjudicates reviews and merges.** It does not grind.
- **Codex builds.** Bounded implementation goes to `codex exec` with Sol from a
  written spec (the `codex-harness` skill). Claude subagents take what codex
  can't: Opus for tricky logic (goal math, cascade rules, the program
  generator, sync, Room migrations, anything the pinned numbers depend on),
  Sonnet for mechanical work (library data, docs, test scaffolding). Never
  Haiku.
- **Fable designs heavy code changes** (new domain rules, the data model,
  multi-PR features) when its budget allows. Without it, Opus takes that role;
  say so.

## PR loop (applies to every PR and to the initial commit)

```
PR → council round → fixes → next round (at most 3) → merge
```

Reviews run through the `council` skill with the seats in
`~/.claude/council.md` (Opus and Sol; Astra when added; Fable only when its
budget allows). Every seat gets the same read-only brief, and every round
checks:
1. **Design principles** (below) are upheld.
2. **Normal security hygiene** — not an in-depth audit; think: no secrets in the
   repo, no unnecessary permissions, exported components locked down, input from
   files/imports validated, dependencies not obviously sketchy.
3. **The PR solves the issue it was meant to solve** — trace the diff back to
   the task/issue it claims to close.
4. **No regressions elsewhere** — pinned domain tests (spec §11) still pass;
   behaviors the diff touches adjacently still hold.

Rounds follow the sol-loop method:
- **Three rounds at most.** A round that returns only nits is the last one.
- **Nits don't block.** Merge with them open and list them in the PR
  description as follow-ups.
- **Step back after round three.** Blockers or shoulds still arriving mean the
  approach is wrong: stop patching and redesign. Bring the redesign to the
  user if it changes scope.
- **Record each round** in the PR description: the council's score table and
  each finding's outcome.

Once CI is green (the playtest pilots included, though only `build` is a
required check) and no blocker or should is open, Claude merges the PR itself
(`gh pr merge <n> --squash --delete-branch`) and moves straight on to the next
task — the review loop is the quality gate, not a human on the merge button
(policy changed 2026-07-07; the user granted the merge permission). Escalate
to the user only when a finding survives the step-back or a change would alter
pinned spec numbers.

## Design principles

1. **Simple.** Prefer the boring solution. No speculative abstraction, no
   pattern for a pattern's sake. If a module/interface/layer isn't earning its
   keep, delete it.
2. **SSOT.** Every fact lives in exactly one place: the exercise catalog, goal
   constants, program state, unit conversions. Derive, never duplicate.
3. **Clean code.** Small functions, honest names, no dead code. Comments only
   for constraints the code can't express — never narration.
4. **MAD architecture.** Modern Android Development: single-activity Jetpack
   Compose, MVVM/UDF with `StateFlow`, Hilt, Room + DataStore, kotlinx
   coroutines/serialization, version catalog. `:domain` stays pure Kotlin — no
   Android imports, ever.
5. **Material is the component system, not the visual identity.** The app
   conforms to Material 3 and defines its colors, typography, shapes, state
   layers, and component defaults completely in the app theme. Prefer themed M3
   library components whenever their public APIs can faithfully reproduce the
   intended design and behavior; do not hand-roll conventional buttons, cards,
   fields, dialogs, tabs, switches, or navigation controls merely to avoid
   Material defaults. Keep custom UI only where M3 has no faithful equivalent,
   and keep that custom layer as small as possible. The app's character remains
   non-negotiable: near-black surfaces, per-day earth-tone accents, condensed
   display numerals (spec §8.5), restrained copy, and authored workout
   interactions must survive the implementation choice. No baseline-purple
   leakage, generic dashboard styling, gratuitous gradients, emoji decoration,
   or wall-of-cards sameness.

   A bespoke component must state in its KDoc what M3 lacks; if that reason
   stops being true, migrate it.

   The same "don't design like an AI" instinct applies to writing: commit
   messages and docs read like a person wrote them.

## Data principles

- **On-device is the source of truth.** Room + DataStore. No INTERNET permission
  in v1 (Health Connect is on-device IPC and does not break this).
- **Write on every mutation, commit immediately.** The React prototype lost data
  on rotation because state lived in memory; that class of bug must be
  impossible here. UI state that must survive process death goes through
  SavedStateHandle or the DB, never a bare ViewModel field.
- **A Parcelable kept in SavedStateHandle never gains, drops or reorders a
  field.** `@Parcelize` reads every field in order, so a bundle saved by the
  previous release would be misread after an update. New state gets its own
  SavedStateHandle key.
- **The user can always get their data out**: versioned JSON full backup +
  CSV history export via the share sheet / SAF, plus Health Connect writes.

## Verification

Domain math is pinned. The unit tests encoding spec §11's numbers (squat GOAL
235, seeded ramp 130/165/190/210, cascade to 245 → 135/170/195/220/185, etc.)
are the contract — a diff that changes those numbers is wrong unless the spec
constants themselves were deliberately changed and re-verified.
