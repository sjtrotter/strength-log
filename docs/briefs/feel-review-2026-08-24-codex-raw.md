# strength.log product / UX / feel review

## Executive assessment

strength.log does not feel unfinished because it lacks code, screens, or visual identity. In fact, its workout screen and Wear OS dial are more considered than many shipped fitness apps.

It feels unfinished because the product is polished within individual surfaces but weak at the transitions between them:

- Setup does not culminate in a satisfying reveal.
- Starting a workout does not clearly create a session.
- Ticking a phone set gives little physical feedback and does nothing with the ensuing rest.
- Completing an incomplete workout is indistinguishable from completing a full one.
- Phone and watch behave as two capable interfaces, not one continuous workout.
- History is attractive but immutable, so mistakes become permanent.
- Several configuration promises—most seriously standalone cardio days—lead nowhere.

Hevy and Strong are less distinctive visually, but they make the workout lifecycle unmistakable. Apple Workout is simpler, but every state change is physically legible. Things 3 and Overcast feel finished because actions settle, navigation carries spatial meaning, and unusual states receive more care than their frequency would suggest. strength.log has the authored surfaces; it still needs that connective tissue.

This is a static product review of the real implementation, not a device-rendering QA pass.

---

## Reconstructed workout-day experience

### Open the app

After the initial program read, the lifter lands on Today rather than directly in the editor. Today gives:

- next day in rotation;
- lift and set counts;
- cardio intent;
- rotation position;
- a quiet last-session line;
- one large START/CONTINUE/FINISH action.

This is a good decision. It gives orientation before manipulation and is cleaner than the dashboard-heavy home screens in many trackers.

Relevant code:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/AppNavHost.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayViewModel.kt`

### Start and log

START pushes the day screen. Opening the screen also seeds any missing log rows, so “starting” is visually a navigation event rather than a durable session boundary. The lifter sees every exercise card and edits load/reps through compound steppers. Ticking a set:

- persists immediately;
- pops the check control;
- fades the row;
- updates the one-pixel progress rule;
- eventually collapses the card if every set is done.

TOP-set changes get the best feedback in the phone app: derived rows flash in sequence and updated numerals briefly take the accent.

Relevant code:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayViewModel.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Stepper.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/CheckmarkToggle.kt`

### Rest

On Wear OS, rest is a first-class state: a deadline-anchored countdown, ambient handling, completion buzz, skip action, and continued operation across face changes.

On phone, ticking a set produces no rest state at all. The interface remains a large sheet of workout data. That is the single greatest disparity in the product.

Relevant code:

- `wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/WearApp.kt`
- `wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/RestTimerController.kt`
- `wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/DialState.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`

### Finish

DONE is permanently enabled, regardless of completion. It immediately archives the session and advances rotation. A cascade may appear, followed by a restrained session receipt with completed-set count, strongest set, next day, and sharing.

The receipt is good. The missing piece is an authored distinction between “completed the workout” and “stopped after four sets.”

Relevant code:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayViewModel.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/CascadeScrim.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceiptScrim.kt`

### Look back

Log has real value:

- main-lift trajectories;
- weekly volume;
- month calendar;
- expandable completed sessions;
- sharing;
- Health Connect sessions and bodyweight prompts.

But completed strength sessions cannot be corrected, deleted, annotated, or otherwise reconciled with reality.

Relevant code:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/JournalSections.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogViewModel.kt`

---

# A. Missing product capabilities that make it feel thin

## 1. Phone users have no between-set experience

**Impact:** Very high  
**Effort:** Medium  
**Feel-per-effort:** Excellent

The domain already resolves rest duration and the watch receives it, but the phone workout screen has no rest countdown, completion signal, notification, or “next set” state. For someone without a watch, the app goes inert during the majority of gym-floor time.

Hevy and Strong automatically begin a rest timer after set completion, keep it accessible without obscuring the workout, and notify at expiry. Apple Workout makes active, recovery, and completion phases visibly different.

**Proposed change**

After a phone tick, show a compact persistent rest strip above the bottom action:

- `REST 1:27` in condensed numerals;
- next exercise/set name;
- `−15`, `+15`, and SKIP;
- one confirm haptic at tick and one distinct completion haptic;
- optional notification while backgrounded;
- no full-screen takeover.

Use the existing `RestPolicy` resolution and settings. “Watch-primary” can remain the product preference without making phone-only use second-class.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayViewModel.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/standards/RestPolicy.kt`
- `data/src/main/kotlin/cloud/trotter/log/strength/data/prefs/SettingsStore.kt`

## 2. Completed history cannot be corrected

**Impact:** Very high  
**Effort:** Medium–high  
**Feel-per-effort:** High

The plan describes archived sessions as immutable, and Log exposes only expand/share. That is clean internally but hostile to an “honest logging” product. Accidental early DONE, a wrong plate, or a missed tick becomes permanent and pollutes trajectory, volume, CSV, and Health Connect.

Strong, Hevy, and FitNotes let users edit or delete past workouts. Things 3’s feel benchmark is relevant: users trust polished tools partly because mistakes are recoverable.

**Proposed change**

Add a session detail route with:

- edit set load/reps/done state;
- adjust completion time if necessary;
- delete session with confirmation;
- undo delete for a short window;
- republish/retract the corresponding Health Connect record.

Preserve append-only audit semantics internally if desired, but do not expose immutability as a user constraint.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogViewModel.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/AppNavHost.kt`
- `data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt`

## 3. Standalone cardio choices are a broken product promise

**Impact:** High for affected users  
**Effort:** Medium  
**Feel-per-effort:** Excellent

The wizard offers “Separate days” and “Both,” and the generator creates `cardioDays`, but `WizardViewModel` intentionally takes only `.program`. The generated cardio days are discarded. Users select an option, finish setup, and never see the promised result.

This is recorded in the repository’s own polish ledger and directly causes “missing something” feelings.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardViewModel.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/generator/ProgramGenerator.kt`
- `docs/briefs/m6-polish-ledger.md`

**Proposed change**

Either:

1. ship standalone cardio as Today-level optional cards outside strength rotation, or
2. remove “Separate days” and “Both” before launch.

Removing unavailable choices is the lower-effort, higher-integrity launch decision.

## 4. Incomplete workouts have no product state

**Impact:** High  
**Effort:** Low–medium  
**Feel-per-effort:** Excellent

DONE is always available and `completeDay()` always archives and advances. Zero sets, four sets, and every set all receive the same completion treatment.

Best-in-class apps either distinguish finish/discard or explicitly summarize incomplete work. Apple Workout asks for intention around ending; Strong makes the workout session boundary clear.

**Proposed change**

Use three outcomes:

- all prescribed sets complete: `DONE — ADVANCE`;
- some work complete: `FINISH 7 OF 18 SETS`, then a restrained confirmation;
- no work complete: `LEAVE WORKOUT` or back, without archiving/advancing.

The incomplete receipt should say `DAY A ENDED · 7 OF 18 SETS`, not `DAY A COMPLETE`.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayViewModel.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceipt.kt`
- `app/src/main/res/values/strings.xml`

## 5. There is no training-note/journal voice

**Impact:** Medium  
**Effort:** Medium  
**Feel-per-effort:** Good

`ProgramExercise.note` exists, but there is no session note, exercise note, pain/equipment annotation, or quick “how it felt” record. History therefore remembers numbers but not context.

FitNotes and Strong support notes because “machine was different,” “left shoulder sore,” and “hotel gym” often explain the data better than a graph. A restrained maintenance app does not need RPE or mood gamification; one optional note is enough.

**Proposed change**

Add a collapsed `SESSION NOTE` field on the completion receipt and session detail. Keep it optional, plain text, and absent everywhere when empty.

Files:

- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/model/Types.kt`
- workout/session Room entities
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceiptScrim.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt`

## 6. Non-workout value exists, but it is buried

**Impact:** Medium  
**Effort:** Low  
**Feel-per-effort:** High

The journal answers “why open this on a rest day?” but Today only offers a small LOG button and a last-session sentence. The trajectories and calendar—the app’s best long-term retention surfaces—are one level away and visually undiscoverable.

Gentler Streak succeeds on non-workout days because home still says something meaningful without demanding action.

**Proposed change**

Add exactly one quiet, tappable insight beneath Last Session:

- `SQUAT · 4 SESSIONS AT GOAL`, or
- `3 SESSIONS THIS MONTH`, or
- a 40dp-wide sparkline with direct label.

No dashboard, streak flame, or metric grid. One changing line is enough to make Today feel alive between workouts.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayViewModel.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/JournalBuilder.kt`

---

# B. Interaction, motion, and feedback gaps

## 7. Normal phone logging has almost no haptic grammar

**Impact:** High  
**Effort:** Low  
**Feel-per-effort:** Outstanding

Wear OS has deliberate haptics for start, tick, undo, crown detents, boundaries, and rest completion. Phone only haptically marks the rare cascade ceremony.

The most frequent action—checking a set—gets visual feedback but no tactile confirmation. On a gym floor, with attention on the bar rather than the display, this matters.

**Proposed change**

Create a restrained phone haptic vocabulary:

- tick: confirm;
- untick/undo: reject or lighter toggle;
- long-press stepper repeat start: one texture cue, not every increment;
- finish: confirm;
- invalid/boundary action: reject.

Do not haptic every button.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/CheckmarkToggle.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Stepper.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/DialHaptics.kt` as the conceptual precedent

## 8. Wizard and route changes have no authored spatial motion

**Impact:** Medium–high on first impression  
**Effort:** Low–medium  
**Feel-per-effort:** High

Wizard steps swap directly inside one `when`. The nav graph declares no app-specific transitions. The result is functionally fast but perceptually abrupt: NEXT changes the entire question without indicating forward movement; BACK does not reverse it.

Things 3 and well-made onboarding flows use subtle directionality to make navigation feel physical and predictable.

**Proposed change**

- Wizard: horizontal shared-axis transition, 180–220ms; forward and backward directions must reverse.
- Today → Day: slight forward slide/fade.
- Day → Today: predictive-back motion already exists for overlays; extend the same spatial logic to route transitions.
- Setup/Log: shorter fade-through.

Keep distances small and honor system animation scale.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/AppNavHost.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/PredictiveBack.kt`

## 9. Completing a card does not help the lifter find the next action

**Impact:** Medium–high  
**Effort:** Medium  
**Feel-per-effort:** High

A finished card waits 420ms and collapses, which is good. But the list does not bring the next unfinished card or row into a useful position. On long sessions, the lifter still has to visually reacquire the next target after the layout changes.

Strong and Hevy bias the viewport toward the active exercise. Apple Workout always centers the current state.

**Proposed change**

When the last set in a card is ticked:

- let the green edge and tick register;
- collapse;
- animate the next unfinished card header to the upper third of the viewport;
- briefly accent its next unchecked row.

Do not auto-scroll on every set, because that would fight intentional exercise order and supersets.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreenModels.kt`

## 10. Steppers are excellent for small changes but poor for correction

**Impact:** Medium  
**Effort:** Medium  
**Feel-per-effort:** Good

Long-press repeat fixes one problem, but changing 90 to 225 or correcting an imported/swapped value remains tedious. “Steppers, not keyboards” is a good default, not a reason to prohibit efficient exceptional input.

FitNotes and Strong allow direct entry while retaining fast increment controls.

**Proposed change**

Tapping the center numeral should open a numeric keypad sheet:

- current value selected;
- unit visible;
- valid range and rounding applied on commit;
- NEXT advances weight → reps or to the next row;
- stepper remains the primary affordance.

This is progressive disclosure and does not compromise the cheap-tap principle.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Stepper.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt`

## 11. Phone–watch handoff is technically synchronized but experientially silent

**Impact:** Medium–high for watch owners  
**Effort:** Medium  
**Feel-per-effort:** Good

The watch is exceptionally capable, but the phone does not visibly acknowledge:

- that the watch is active;
- that rest is running on it;
- that edits are queued;
- which device last logged the set.

Apple’s strength is not merely synchronization; each surface makes the continuity legible.

**Proposed change**

On the phone day screen, add one quiet status line only while relevant:

- `WATCH ACTIVE · REST 1:12`
- `WATCH SYNCING 2 CHANGES`
- `WATCH OFFLINE · CHANGES QUEUED`

Tapping it could explain device authority and open rest settings. Do not add permanent device chrome.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/sync/WearSyncStore.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayViewModel.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `wear/src/main/kotlin/cloud/trotter/log/strength/wear/data/PendingEdits.kt`

## 12. Set addition/removal changes snap the list’s geometry

**Impact:** Medium  
**Effort:** Low–medium  
**Feel-per-effort:** Good

Removal has excellent in-place undo, but row insertion/removal and card collapse mostly swap content directly. The comment explains why `animateContentSize` was removed—scroll jank—which is a valid engineering choice. The remaining snap is still perceptible.

**Proposed change**

Use lazy-item placement/appearance animation only on the affected row and following siblings, not whole-card size animation. Keep the current non-animated card body if performance is uncertain. Animate the undo row’s entrance at 120–160ms.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt`

---

# C. Visual and first-run polish gaps

## 13. The first run asks questions before establishing the product

**Impact:** Medium–high for launch conversion  
**Effort:** Low  
**Feel-per-effort:** High

A paid app’s first screen is immediately “What are you training for?” The copy is clear, but there is no compact statement of what the user is about to receive: rotation, local ownership, and goal-vs-actual behavior.

Things 3 and premium indie apps establish confidence before asking for configuration.

**Proposed change**

Do not add a separate marketing carousel. Add a short authored opening above the first question:

> YOUR TRAINING, IN ROTATION  
> A practical program, honest logs, no account. Missing a day never skips the next workout.

Then keep the existing three choices. At Generate, briefly show `BUILDING 4-DAY ROTATION` before Today reveals the resulting plan.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/AuthoredStates.kt`
- `app/src/main/res/values/strings.xml`

## 14. Secondary screens revert toward wall-of-cards sameness

**Impact:** Medium  
**Effort:** Medium  
**Feel-per-effort:** Moderate

The day screen and Today have strong editorial hierarchy. Wizard, Setup, Backup, and portions of Log rely heavily on repeated `AppCard`/`SelectionCard` blocks. They are coherent, but the rhythm can feel like generic Compose forms wearing custom colors.

This approaches the `CLAUDE.md` warning against “wall-of-cards sameness.”

**Proposed change**

- Setup: use ruled sections and inline rows for simple values; reserve cards for GOAL preview and destructive/data operations.
- Wizard: let selected choices join the page background through accent rule/typography instead of making every answer an equal card.
- Log: session cards are appropriate; Health Connect and prompts should retain their distinct treatment.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/setup/SetupScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SelectionCard.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Card.kt`

## 15. Text glyph icons undermine otherwise controlled rendering

**Impact:** Low–medium  
**Effort:** Low  
**Feel-per-effort:** High

Settings uses the literal `⚙`; other controls use `✎`, `⇄`, `×`, `▼`, and similar glyphs. Some are defensible typographic marks, but the gear is especially vulnerable to platform-dependent emoji presentation.

This conflicts with the identity rule in `CLAUDE.md`: no emoji decoration. It can also make the header feel cheaper than the rest of the app.

**Proposed change**

Replace semantic tool icons—settings, edit, swap, disclosure—with a tiny consistent vector set using rounded 1.5–2dp strokes. Retain mathematical/logging marks such as `+`, `−`, `×`, `✓`, and `↳`, because those belong to the app’s notation.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt`

## 16. The default theme can erase the declared identity

**Impact:** Medium  
**Effort:** Trivial product decision  
**Feel-per-effort:** High

`CLAUDE.md` calls near-black surfaces non-negotiable. Yet the default preference is `SYSTEM`, so users with a light phone can encounter warm paper on first launch. The light palette is thoughtful, but it is a real contradiction—not merely a variation.

Files:

- `CLAUDE.md`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/theme/Theme.kt`
- `data/src/main/kotlin/cloud/trotter/log/strength/data/prefs/SettingsStore.kt`
- `docs/briefs/light-theme.md`

**Proposed change**

Make DARK the first-run default and retain System/Light as explicit preferences. If System must remain the default, soften the principle in `CLAUDE.md`; both positions cannot simultaneously be non-negotiable.

## 17. The wizard is lb-only even though the product supports kg

**Impact:** Medium outside the US  
**Effort:** Low  
**Feel-per-effort:** High

The wizard says `Bodyweight (lb)` and does not ask for units. Unit selection appears later in Setup. A kg-native buyer’s first experience is therefore configuring themselves in the wrong system.

**Proposed change**

Put an LB/KG segmented choice in About You and run the bodyweight stepper through the same conversion/rounding path as Setup. Persist it with program generation.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardViewModel.kt`
- `app/src/main/res/values/strings.xml`

---

# D. What is already good and should not be “fixed”

## 1. Keep Today as the launch destination

Do not revert to opening directly into the workout editor. Today’s orientation-first model is calmer and more premium than an immediate spreadsheet.

## 2. Keep rotation, not calendar

This is the product’s clearest differentiation from schedule-heavy coaching apps. The Today rail, suggested-day marker, and override pill communicate it well.

## 3. Keep GOAL quiet and read-only

Do not turn GOAL into a progress demand, percentage ring, or red failure state. `Last time`, `Best`, and TOP comparison already provide sufficient context.

## 4. Keep the cascade ceremony restrained

The struck old number, new ramp, and one haptic are exactly the right degree of celebration for this identity. Do not add confetti, achievements, streaks, or PR badges.

Files:

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/CascadeScrim.kt`
- `docs/briefs/journal.md`

## 5. Keep the session receipt

It repairs what would otherwise be an emotionally silent finish. Improve its incomplete-session honesty and optionally add duration/note, but do not replace it with a generic success dialog.

## 6. Keep the in-card set-removal undo

The undo appears where the row disappeared, preserves visual context, and is better than a generic snackbar.

## 7. Keep auto-collapse limited to completed cards

Do not aggressively collapse untouched or partially complete exercises. The current behavior preserves the lifter’s mental map.

## 8. Keep condensed numerals and earth-tone day accents

These are the visual identity. The TOP-row treatment, tabular numeral styles, and day-specific color are among the strongest parts of the app.

## 9. Keep the journal’s single-series charts

Do not turn Log into an analytics dashboard. The direct labels, quiet goal line, and calendar are appropriately restrained.

## 10. Keep the Wear dial concept

It is distinctive, physically appropriate, and far more product-specific than a generic scrolling watch list. The crown detents, deliberate hold-to-undo, ambient state, and rest timing are unusually well thought out.

---

# Ranked top 10: do these and it will feel finished

| Rank | Change | Category | Effort | Why it matters |
|---:|---|---|---|---|
| 1 | Add the compact phone rest/next-set strip using the existing rest policy | Missing experience | Medium | Fills the largest dead zone in the workout |
| 2 | Add phone haptics for tick, untick, finish, and boundaries | Feedback | Low | Makes every frequent action physically trustworthy |
| 3 | Distinguish complete, partial, and zero-work finishes | Interaction | Low–medium | Makes session completion honest and intentional |
| 4 | Add past-session edit and delete | Missing capability | Medium–high | Restores trust when real-world logging is imperfect |
| 5 | Remove or deliver Separate Days/Both cardio | Broken promise | Low or medium | Eliminates a configuration path that currently goes nowhere |
| 6 | Add directional wizard and route transitions | Motion | Low–medium | Gives the whole app spatial continuity |
| 7 | On card completion, settle the viewport on the next unfinished card | Workflow | Medium | Removes gym-floor reacquisition and scrolling friction |
| 8 | Add direct numeric entry behind a numeral tap | Interaction | Medium | Makes exceptional corrections fast without abandoning steppers |
| 9 | Add one tappable progress insight to Today | Retention | Low | Gives the app a reason to open on non-workout days |
| 10 | Polish first run: value statement, kg selection, dark-first identity, consistent vector icons | First-run/visual | Low–medium | Makes the paid-app promise legible in the first minute |

## Single biggest diagnosis

The app feels unfinished because it has been built as a sequence of well-executed deliverables rather than as one continuous physical experience. Today, the workout editor, the journal, the completion receipt, and the watch dial are each defensible; the moments between them still expose state changes as raw navigation or database events. The largest example is the set tick: on the watch it begins a tactile, timed recovery phase, while on the phone it merely changes a checkbox and leaves the same dense screen in place. Finish the seams—rest, haptics, session boundaries, correction, directional motion, and visible device continuity—and the existing visual identity and feature set are already strong enough to carry a polished $4.99 product.
