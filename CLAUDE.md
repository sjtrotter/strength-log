# strength-log

Native Android + Wear OS strength-training tracker. Local-first, no accounts, no
ads, no network permission. The product spec is `STRENGTH_TRACKER_SPEC.md`; the
delivery plan and spec amendments are `docs/PLAN.md`. When those two disagree,
`docs/PLAN.md` wins (it is the reviewed superset).

## Model usage & delegation policy

This repo is built with a deliberate division of labor to spend Fable capacity
where it matters:

- **Fable (if available) plans and reviews. It does not grind.** Use Fable for:
  architecture and design decisions, breaking work into tasks, anything with
  cross-cutting consequences, and every PR/initial-commit review (below).
- **Delegate discrete, well-bounded implementation tasks** to subagents at the
  appropriate intelligence tier:
  - **Opus** — tricky logic: goal math, cascade rules, program generator, sync,
    Room migrations, anything the pinned verification numbers depend on.
  - **Sonnet** — well-specified mechanical work: library data entry, UI built to
    an exact behavioral contract, boilerplate, test scaffolding, docs.
  - **Never Haiku.**
- If Fable is not available in a session, fall back to Opus for the planning/
  review role and say so.

## PR loop (applies to every PR and to the initial commit)

```
PR submitted → Fable review → subagent fixes (as applicable)
            → Fable final check (must pass) → merge
```

The Fable review checks, every time:
1. **Design principles** (below) are upheld.
2. **Normal security hygiene** — not an in-depth audit; think: no secrets in the
   repo, no unnecessary permissions, exported components locked down, input from
   files/imports validated, dependencies not obviously sketchy.
3. **The PR solves the issue it was meant to solve** — trace the diff back to
   the task/issue it claims to close.
4. **No regressions elsewhere** — pinned domain tests (spec §11) still pass;
   behaviors the diff touches adjacently still hold.

Loop until the final check passes cleanly, then merge. Don't merge on a review
with unresolved findings.

Merging is pre-authorized: once CI is green and the adversarial review is
accepted, merge without asking and move straight on to the next task. Only
stop for a human when a review finding can't be resolved or a change would
alter pinned spec numbers.

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
5. **Don't design like an AI.** No purple-gradient dashboards, no emoji-strewn
   UI, no default-Material-theme sameness, no wall-of-cards genericism. This app
   has an opinionated look (spec §8.5: near-black, per-day earth-tone accents,
   condensed display numerals) — keep and extend that character. Same applies to
   writing: commit messages and docs read like a person wrote them.

## Data principles

- **On-device is the source of truth.** Room + DataStore. No INTERNET permission
  in v1 (Health Connect is on-device IPC and does not break this).
- **Write on every mutation, commit immediately.** The React prototype lost data
  on rotation because state lived in memory; that class of bug must be
  impossible here. UI state that must survive process death goes through
  SavedStateHandle or the DB, never a bare ViewModel field.
- **The user can always get their data out**: versioned JSON full backup +
  CSV history export via the share sheet / SAF, plus Health Connect writes.

## Verification

Domain math is pinned. The unit tests encoding spec §11's numbers (squat GOAL
235, seeded ramp 130/165/190/210, cascade to 245 → 135/170/195/220/185, etc.)
are the contract — a diff that changes those numbers is wrong unless the spec
constants themselves were deliberately changed and re-verified.
