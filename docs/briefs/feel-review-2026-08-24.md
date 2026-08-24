# Feel review — why the app reads as unfinished (2026-08-24)

Two independent product/UX reviews of `main @ ed8f85d`, run the same
afternoon with the same brief and no sight of each other: one by Claude
(Fable 5), one by Codex (gpt-5.6-sol). Neither was a bug review — correctness
was closed out in the 2026-08-08 Codex pass, all fifteen findings of which
merged in #182–#237. This is about the owner's actual complaint:

> "it doesn't feel good, polished. It still feels clunky and like it's
> missing something."

Both reviewers read the real Compose code (phone + wear), the spec, PLAN, the
briefs and the design handoff, reconstructed a workout day step by step, and
compared against Hevy, Strong, FitNotes, Apple Workout, Gentler Streak, and
the indie-paid feel benchmarks (Things 3, Overcast). Every load-bearing code
claim below was spot-checked against the tree before this was written.

## The diagnosis (both reviewers, independently)

The app is finished *within* each surface and unfinished *between* them.

Today, the workout editor, the receipt, the journal and the watch dial are
each better than most shipped fitness apps. But the moments that connect
them are raw navigation or database events. The clearest example, named by
both reviews as the single biggest gap: **ticking a set.** On the watch it
begins a tactile, timed recovery phase. On the phone it fades a row to 55%
alpha and leaves the same dense screen sitting there — no haptic, no rest
countdown, no "next", no scroll, no notification. Hevy and Strong feel
finished not because they have more features but because after every logged
set the app *does something back*. strength.log logs the set and waits.

Concretely, on the phone today there is: one haptic in the whole app
(`CascadeScrim.kt`), zero route transitions (`AppNavHost.kt` declares none),
no animated card collapse, no rest state, no notification, DONE always
enabled, and history that can never be corrected. The identity — near-black,
earth accents, condensed numerals, restrained copy — is intact everywhere and
is not the problem. The connective tissue is.

## Where the two reviews agree

Fourteen findings were raised by both, independently. That overlap is the
strongest signal in this document.

| Finding | Claude | Codex |
|---|---|---|
| No phone-side rest timer | A1, #1 | A.1, #1 |
| Set tick has no haptic / no "next" cue | B1, #2 | B.7 + B.9 |
| No route or wizard transitions | B2, #3 | B.8 |
| Card collapse / row insert snaps | B3, #4 | B.12 |
| DONE always enabled; incomplete = complete | B6, #5 | A.4, #3 |
| Past sessions can't be edited or deleted | A4, #8 | A.2, #4 |
| Steppers poor for big corrections; need tap-to-type | B7 | B.10 |
| Viewport doesn't settle on the next card | B10 | B.9 |
| No session / exercise notes | A3 | A.5 |
| Nothing to open the app for on a rest day | A5 | A.6 |
| Wizard is `lb`-only / ends without a reveal | A6 | C.13, C.17 |
| Text glyph icons (`⚙ ✎ ⇄ ▼`) read as cheap | C1 | C.15 |
| Watch↔phone continuity invisible on the phone | A8 | B.11 |
| Everything in the "do not touch" list below | (d) | D |

## The consolidated plan, ranked by feel-per-effort

Effort: S ≈ one small PR, M ≈ one substantial PR, L ≈ several. Impact 1–5.

### Tier 1 — the seams. Do these and the "clunky" complaint goes away.

1. **Phone rest timer.** (L, 5) Reuse the pure `RestTimer` + `RestPolicy`
   the watch already uses. A one-line countdown pill beside DONE in the
   bottom bar — condensed numerals, the day accent draining along a
   hairline, `−15 / +15 / SKIP` — plus an `AlarmManager`-backed buzz (the
   #188 pattern) and an ongoing notification (`POST_NOTIFICATIONS`,
   runtime-requested, degrade silently). No full-screen takeover. Rename the
   Setup section from **WATCH** to "Rest timer" and give the phone its own
   toggle. Anyone who buys this at $4.99 without a watch currently gets no
   rest guidance at all.

2. **A phone haptic vocabulary.** (S, 5) Port the watch's grammar
   (`DialHaptics.kt`), don't reinvent it: tick → `Confirm`, untick → lighter
   toggle, finish → `Confirm`, boundary/invalid → `Reject`, stepper detent →
   `SegmentTick` (API 34+, fallback `TextHandleMove`). Not every button.

3. **Tick → "next".** (S, 5) Mark the first undone row (accent kind label or
   left rule) and move the existing barbell plate line
   (`DayScreenBuilder.plateLine`) onto that row's trailing edge so one glance
   says *load this*. When a card's last set is ticked: let the green edge
   register, collapse, then `animateScrollToItem` the next unfinished card
   into the upper third. Do **not** auto-scroll on every set — it fights
   supersets and intentional order.

4. **Route and wizard transitions.** (S, 4) One `NavHost` config: pushed
   routes slide from the end edge (`it/4`) + fade, 200–300ms emphasized
   decelerate, symmetric pop. Wizard steps get a horizontal shared-axis
   swap that reverses on BACK. Wrap DAY in the `backGesturePreview` the
   wizard already has. Honour system animation scale.

5. **Animate the collapse, not the card.** (S, 4) `animateContentSize` was
   removed for scroll jank — right call. Instead wrap only the card *body*
   in `AnimatedVisibility(expandVertically/shrinkVertically, 320ms)`, which
   measures only during the transition. Row insert/remove: lazy-item
   placement animation on the affected rows only; undo row enters at
   120–160ms.

6. **DONE gets a boundary.** (S, 4) Three outcomes: all sets done →
   `DONE · ADVANCE` as now; some done → `FINISH 7 OF 18 SETS` behind a
   restrained confirm, receipt says `DAY A ENDED · 7 OF 18` not `COMPLETE`;
   zero done → leave without writing a session or advancing (offer "Skip
   day"). Today a stray tap on the 56dp accent button writes a phantom
   session and advances the rotation. A short hold-to-confirm would also
   match the watch's own 700ms hold-to-undo.

7. **Give the receipt and cascade an entrance.** (M, 4) Scrim fades in
   (~260ms), the display numeral scales 0.92→1 on the existing low-bouncy
   spring, ledger rows stagger 60ms. For the cascade: the strike-through
   draws left→right, then the new GOAL counts up (`animateIntAsState`,
   235→245 over ~500ms). Still typographic, still one haptic, still no
   confetti.

### Tier 2 — the "missing something". These make it feel thin rather than clunky.

8. **Edit / delete a past session.** (M, 4) Spec A1's "written once, never
   mutated" is a good sync principle and a hostile UX: a fat-fingered 275
   for 175 poisons trajectory, `Best`, CSV and Health Connect forever.
   Minimum: delete with confirm + short undo window. Better: edit set
   load/reps/done inline in the expanded session card with the same
   `Stepper`, and republish/retract the Health Connect record. Single-user
   app — append-only can stay an internal detail.

9. **The wizard's missing last beat.** (M, 4) Seven questions, then the
   lifter lands on `DAY A · LOWER · START` and never sees B/C/D or the GOALs
   just generated (Setup's `GoalPreviewCard` has them, behind `⚙`). Add an
   eighth step "Your rotation": four day cards in their accents, main lift
   + GOAL, then START. Codex also suggests a brief `BUILDING 4-DAY ROTATION`
   moment at Generate. This is the "it built me a plan" reveal and it is
   currently skipped.

10. **Wizard asks in `lb` only.** (S, 3) `Bodyweight (lb)` with no unit
    choice; kg appears later in Setup. A kg-native buyer configures
    themselves in the wrong system on minute one. LB/KG segmented choice in
    About You, through the same conversion/rounding path as Setup.

11. **Tap-to-type on steppers.** (M, 3) Steppers stay primary (spec §1.5).
    Tapping the numeral opens a compact numeric entry, current value
    selected, unit visible, range and rounding applied on commit, NEXT
    advances weight → reps. Also accelerate long-press repeat (90→45ms after
    ~8 steps) with a detent haptic. 135→225 is currently ~2s of blind holding.

12. **Standalone cardio is a broken promise.** (S to remove / M to deliver,
    3) The wizard offers "Separate days" and "Both"; `WizardViewModel.kt:311`
    knowingly drops `GeneratedProgram.cardioDays` (recorded in the M6 polish
    ledger). Users pick an option and never see the result. **Decision for
    the owner:** ship Today-level cardio cards outside the rotation, or
    remove the two choices before launch. Removal is the higher-integrity
    launch move.

13. **One line of life on Today for rest days.** (S–M, 3) Under Last
    Session, exactly one quiet, tappable, derived line — `THIS WEEK · 2 OF
    4 · LAST: 2 DAYS AGO` or `SQUAT · 4 SESSIONS AT GOAL` — that opens the
    journal. Derived, never stored. No streak flame, no stat tiles. A
    `bodyweight_log` (one line: `BW 182 · −1 since Jul`) is the first honest
    reason to open the app on a Tuesday; optional, Room migration + backup v7.

14. **Notes.** (M, 3) `ProgramExercise.note` exists in domain and nothing
    reads or writes it. Long-press a card title → note; a `TextFaint` italic
    line under the title when present; an optional one-line session note on
    the receipt and in the session card. Absent everywhere when empty. No
    RPE, no mood — one plain-text field.

15. **Watch continuity, visible on the phone.** (S, 2) While relevant only:
    `WATCH ACTIVE · REST 1:12`, `WATCH SYNCING 2 CHANGES`, `WATCH OFFLINE ·
    CHANGES QUEUED` as one status line on the Day screen; when a watch tick
    arrives while DAY is visible, run the same pop + haptic as a local tick.
    No permanent device chrome.

### Tier 3 — visual polish. Cheap, and they are what reads as "unpolished" at a glance.

16. **Real icons for `⚙ ✎ ⇄ ▼`.** (S, 3) These render from the OEM
    fallback font and differ per device; the gear is emoji-adjacent on some.
    Use `Icons.Outlined.Settings / Edit / SwapHoriz / ExpandMore` themed to
    `TextSecondary` (M3-native — CLAUDE.md §5 prefers it). Keep `+ − × ✓ ↳`:
    those are the app's notation.

17. **Helper copy shown once, not forever.** (S, 3) `day_main_helper`
    ("Change the TOP set — ramp & back-off recalculate.") sits on every main
    card every session; same for the superset helper and the rotation
    philosophy paragraph in the workout footer. Show each until the lifter
    has done the thing once (one DataStore bool each); move the paragraph to
    Setup. Restrained copy means less of it.

18. **Pressed states are invisible.** (S, 2) `AppRipplePressedAlpha = 0.05f`
    on `#16161A` is nothing; `StepSegment` has no pressed fill at all though
    the handoff specified `Surface3`. Raise to ~0.10 dark / 0.08 light, or
    per-component `Surface3` via `collectIsPressedAsState` as `StartButton`
    and `ShareButton` already do.

19. **Day-screen header density.** (M, 3) ~140dp of chrome (tab strip +
    overline + title + emphasis + status + pill + ✎) before the first card on
    a 411dp phone. Collapse on scroll (`exitUntilCollapsedScrollBehavior` is
    M3-native) and fold status into the overline: `DAY B · 4 OF 18`.

20. **Secondary screens drift toward wall-of-cards.** (M, 2) Wizard, Setup,
    Backup lean on repeated `AppCard`/`SelectionCard` blocks — coherent, but
    generic Compose forms in custom colours, which CLAUDE.md §5 names.
    Setup: ruled sections + inline rows for simple values, cards only for
    the GOAL preview and destructive/data operations.

21. **Smaller things.** (S, 1–2) `log_title` is `Log` while every other
    title is caps — make it `LOG`. Light-theme users get a near-black frame
    before paper on cold launch (`windowBackground` isn't night-qualified;
    fix via the SplashScreen API). Exercise picker rows could carry one
    `TextFaint` pattern line. The `×` remove control as a swipe-to-reveal
    (`SwipeToDismissBox`, M3-native) would end the row at the tick and
    resolve #136 as a side effect.

## Owner decisions (2026-08-24, same day)

- **Theme:** stay with the user's chosen system theme as the default. The
  light expression is a deliberate part of the identity, not a leak;
  CLAUDE.md §5's "near-black" reads as the dark expression, not a mandate
  over the user's system setting.
- **Cardio "Separate days / Both":** deliver it (item 12, the M path), don't
  remove it.
- **History:** allow edits and deletes of past sessions (item 8).
- **Icons:** where a vector is needed, prefer the M3 icon set; anything
  bespoke is generated with Sol image generation, critiqued by Sol, then by
  Claude, before it's accepted.

## Where the reviews disagreed (kept for the record)

- **Default theme.** Codex: `ThemePreference.SYSTEM` as first-run default
  contradicts CLAUDE.md's "near-black is non-negotiable" — make DARK the
  default, keep System/Light as explicit preferences, or soften the
  principle. Claude didn't flag the default, only the light-launch flash.
  Both positions can't stand; pick one. Recommendation: DARK default — it's
  the identity the listing screenshots sell.
- **Cardio "Separate days / Both".** Deliver or remove (item 12). Both
  reviews say remove is fine for launch; only the owner knows whether it's
  a feature they want in v1.
- **History mutability.** Both reviews want edit/delete; the spec says
  append-only. Recommendation: allow it — the sync-safety argument doesn't
  apply to a single-user, on-device store, and the trust cost of an
  uncorrectable mistake is real.
- **How far to take the rest timer.** Pill + buzz + notification is the
  floor both reviews set. A Live-Activity-style rich notification (Hevy's
  current direction) is not needed.

## Do not touch — both reviews, independently

- Today as an editorial statement and as the launch destination. Don't add
  cards; don't open straight into the editor.
- Rotation, not calendar. The rail, suggested-day marker and override pill
  already carry it.
- GOAL quiet and read-only; no rings, percentages, or red failure states.
- The cascade as the *only* celebration: strike-through, new ramp, one
  haptic. It needs an entrance (item 7), not confetti, badges or streaks.
- The receipt as a ledger, not a trophy.
- In-card undo on set removal (#124) rather than a confirm dialog.
- Auto-collapse limited to fully completed cards.
- Condensed numerals, earth-tone day accents, hairlines, the TOP-row bleed
  and the cascade stagger. The identity is intact everywhere both reviewers
  looked: no baseline purple, no gradients, no emoji.
- The journal's single-series charts, GOAL dash, tonnage bars and calendar.
  Only make them reachable from Today (item 13).
- The Wear dial, crown detents, hold-to-undo, ambient handling and rest
  timing. The watch has the feedback grammar the phone lacks — port it.

## Guardrails for whoever implements this

Every item above must survive CLAUDE.md §5: no particles or confetti (item 7
stays typographic), no emoji anywhere including the watch hint (ImageVector),
the rest pill is a hairline and a numeral rather than a Material progress
band, the Today line is one caps label rather than a stat-tile row, and any
bespoke component states in its KDoc what M3 lacks. Items 1, 8, 13 and 14
touch Room/backup — additive migrations, backup version bump, §11 pinned
numbers untouched.

## Suggested sequencing

Items 2–6 and 16–18 are each an S PR and together change the clunky
perception more per hour than anything else. Item 1 is the feature the paid
listing will be judged on by anyone without a watch — it should land before
production. Items 9 and 10 fix the first minute of a paid app. Everything in
tier 2 beyond that is a v1.x decision.

Sources both reviewers leaned on for competitor behaviour: Hevy rest timer
and Live Activity feature pages, Hevy's July 2026 update, Strong's finish
flow, Apple Workout's phase transitions, Gentler Streak's rest-day thesis.
Full raw reports are in the session transcripts; this document is the
reconciled version.
