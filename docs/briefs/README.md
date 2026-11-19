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
  `feel-review-2026-08-24-codex-raw.md`, raw reports beside it.
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

The dated sections in this `README.md` index later briefs.

## 2026-11-08 to 2026-11-17

- `wizard-design-2026-11-08.md` — setup by goal and the generator from the log (PLAN.md A19); revisions 1 to 56 record each wizard rebuild PR.
- `wizard-inventory-2026-11-08.md` — the setup wizard, plan generator, their callers and replacement points before the rebuild.
- `wizard-evidence-2026-11-08/` — research for a short setup wizard and a generator that adapts from the log.
- `wizard-evidence-audit-2026-11-13/` — evidence audit of planner and progression rules, with lead decisions distinguishing evidence from practice.
- `run-ladder-2026-11-17/` — two Easy sessions per level, a re-stepped run/walk table, and at most three credited sessions in seven days.
- `sprint-week-2026-11-17/` — high/low layouts for Sprint and Both with sprint goals, and weekly sprint decisions.

## 2026-11-03

- `performed-day-2026-11-03.md` — shared performed-day read model, preview and history keys, and the three-PR sequence (#408).
- `receipt-v7-build-2026-11-03.md` — code design for the approved receipt (#416); `receipt-v7-2026-11-03/`, mockup beside it.

## 2026-11-02

- `set-structures-2026-11-02.md` — per-slot set structures, authored tops, progression, and the PR sequence (#415).
- `codebase-audit-2026-11-02.md` — the first structural audit (deep modules, seams, duplicated rules); tracking issue #418.
- `personal-bests-2026-11-02.md` — the one personal-best rule (step 1 shipped in #402; receipt #416, cardio records #417).
- `receipt-v4-2026-11-02/` — receipt design rounds v3 and v4, both seats; on hold for set structures (#415).
- `run-writes-2026-11-02.md` — one seeding owner and one edit path, split into three PRs (#407 step 2 and #406).

## 2026-10-31

- `product-council-2026-10-31.md` — the app's purpose, the watch, tracking and plan creation; `product-council-2026-10-31/`, raw reports beside it.
- `competitor-complaints-2026-10-31.md` — complaints about workout and cardio apps, and which to address or leave; `competitor-complaints-2026-10-31/`, raw reports beside it.
- `plans-and-places-2026-10-31.md` — plans side by side, training places and extras; `plans-and-places-2026-10-31/`, raw reports beside it.
- `workout-runs-2026-10-31.md` — workout runs, step 2 of M9 (#341).
- `one-plan-model-2026-10-31.md` — one plan model and per-exercise supersets, step 3 of M9 (#342, #343).
- `step4-phone-flow-2026-10-31.md` — plans side by side, workout-only swaps and the phone flow (#361, #362, #344).

## 2026-10-30

- `phone-redesign-2026-10-30.md` — phone navigation and workout flow, amended by the product council; `phone-redesign-2026-10-30-mockups.html`, mockups beside it.
- `watch-redesign-2026-10-30.md` — the watch running a prepared workout, amended by the product council; `watch-redesign-2026-10-30-mockups.html`, mockups beside it.
- `ux-audit-2026-10-30.md` — second UX audit of the phone, widget, launcher icons and theme.
- `ux-audit-watch-2026-10-30.md` — watch UX audit, including the phone snapshot builder; `ux-audit-watch-2026-10-30-mockups.html`, mockups beside it.

## 2026-10-21

- `cardio-sets.md` — cardio sets with timed and distance targets, automatic recovery and cool-down, and manual distance (#280; amended 2026-11-05).
- `timed-rep-events.md` — a focused set clock followed by count entry; completion requires a saved count (#274).

## 2026-10-20

- `plans-separate-cardio-2026-10-20-sol.md` — independent plan cursors already exist; running plans still need content, execution and plan identity.

## 2026-10-19

- `plans-review-2026-10-19-r2.md` — saved plans, cardio as sets, manual distance before GPS, and reviewing recent exercise exposure; `plans-review-2026-10-19-r2-brief.md`, `plans-review-2026-10-19-r2-claude-sealed.md`, `plans-review-2026-10-19-r2-astra-raw.md`, `plans-review-2026-10-19-r2-sol-raw.md`, `plans-review-2026-10-19-r2-astra-critique.md` and `plans-review-2026-10-19-r2-sol-critique.md`, raw reports and critiques beside it.
- `plans-review-2026-10-19.md` — saved plans, cardio phases and returning after time away, with round 2 replacing its open decisions; `plans-review-2026-10-19-brief.md`, `plans-review-2026-10-19-astra-raw.md` and `plans-review-2026-10-19-sol-raw.md`, raw reports beside it.

## 2026-10-12

- `ui-redesign-2026-10-12.md` — two UI proposals and the decisions they need, following the feel and direction reviews.

## 2026-08-07 to 2026-08-10

- `light-theme.md` — a warm-paper expression of the app's identity for v1; tablets stay deferred.
- `cardio-promotion.md` — executable cardio suggestions, logged in history and written to Health Connect, with lifts first (#154).
- `material-migration.md` — which phone controls Material 3 can carry and how to phase their migration (#157).

## 2026-07-31

- `wear-dial-v3.md` — cycle ring, neutral actions and two faces; amends the earlier dial briefs.

## Undated

- `glance-surfaces.md` — read-only phone widget, watch complication and Wear tile derived from existing state (#100).
- `journal.md` — lift history, weekly volume, a calendar and the cascade ceremony (#102).
- `performance-profile.md` — all-time exercise bests derived from completed sets, without a records table.
- `plate-math.md` — plate loading derived in the domain and shown on the phone day screen; watch deferred (#101).
- `restyle-day-screen.md` — mapping the July design handoff's tokens and day screen to the code, with behavior unchanged.
- `session-share.md` — a session share card prepared only when the user taps Share (#103).
- `wear-curved-bands.md` — curved watch bands and short exercise names after the second wrist pass (#110, #111).
- `wear-dial-redesign.md` — concentric watch dial, crown controls and execution screens for round Wear OS watches.
- `wear-dial-v2.md` — readable wrist type, nested rings and the clock ring, amending the first dial design.
