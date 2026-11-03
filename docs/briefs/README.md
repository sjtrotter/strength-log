# Execution briefs

Fable-authored planning for everything not yet implemented, written 2026-07-06
ahead of the Fable→Opus handoff. Each brief is a delegation-ready contract:
scope, decisions already made, integration points, constraints, and
verification — so the orchestrating session dispatches and reviews without
re-deriving design.

- `ARCHITECTURE-DECISIONS.md` — cross-cutting calls (nav graph, DI layout,
  sync DTO placement, design-pass integration, device policy). Read first.
- `m3-remaining.md` — #9 wizard, #11 day-edit, #12 setup, #13 custom
  exercise, #14 history (sequential, Sonnet).
- `m4-transfer.md` — #16 CSV, #17 Health Connect, #18 auto-backup.
- `m5-wear.md` — #19 watch UI, #20 Data Layer sync, including the wire
  protocol both build against.
- `feel-review-2026-08-24.md` — the two-model (Claude + Codex) product/feel
  review before the paid launch: diagnosis, 21 ranked items, owner decisions.
  `-codex-raw.md` beside it is Codex's unedited report.
- `m6-polish-ledger.md` — #21–#23 plus the debt ledger reviews have
  accumulated (long-press-repeat, OFL packaging, manual verification gates).

These don't restate the spec (`STRENGTH_TRACKER_SPEC.md`) or the plan
(`docs/PLAN.md`, which wins over the spec) — they add only the execution
decisions those leave open. If a brief contradicts PLAN.md, PLAN.md wins;
update the brief.

The standing loop for every PR: implementation agent (worktree, tier per
issue label) → adversarial review (Opus agent) → fix round (same
implementation agent) → verify pass (same reviewer) → merge on clean verdict
+ green CI. Local machines never run adb/connected/install tasks — CI's
emulator executes instrumented tests.

## 2026-11-03

- `performed-day-2026-11-03.md` — shared performed-day read model, preview and history keys, and the three-PR sequence (#408).

## 2026-11-02

- `set-structures-2026-11-02.md` — per-slot set structures, authored tops, progression, and the PR sequence (#415).
- `codebase-audit-2026-11-02.md` — the first structural audit (deep modules, seams, duplicated rules); tracking issue #418.
- `personal-bests-2026-11-02.md` — the one personal-best rule (step 1 shipped in #402; receipt #416, cardio records #417).
- `receipt-v4-2026-11-02/` — receipt design rounds v3 and v4, both seats; on hold for set structures (#415).
