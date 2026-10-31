# Fable, round 2: converge or hold

The round 1 agreements stand; I disagree with none of them.

## The splits

1. **Watch scope.** Moved, toward Astra and Sol. The line is the run, not the plan: on the watch, anything about *this workout* (log, correct the reps or load of the set in front of you, skip, undo, jump to another lift, take a phone-offered alternate, Finish); on the phone, anything about *the plan* (add or remove exercises, prescriptions, swaps outside the offered alternates, history). Sol moved me: a "log five" button is wrong when four reps happened, and that is the common correction, not an edit.

2. **Tap or hold.** Hold, with Astra's and Sol's test as the gate: build tap-plus-Undo as the default, keep the arming time one constant so hold is a one-line change, and run the wrist test (five wrist-extension movements, count unmeant logs) before the dial is replaced. A failed test flips the constant.

3. **GPS.** Moved partway. Astra is right that linking cannot give live interval guidance, which is what the cardio blocks exist for; the prototype belongs on the roadmap, not behind "only if linking falls short". It still comes last (step 7), and only if they run intervals at all.

4. **Templates.** Hold. No bundled catalog: the generator's split table already is a 2-to-6-day template set, and "Make me one", "Blank" and "Duplicate" cover the doors. There is no plan file format to import; restore exists.

5. **A run as a first-class record.** The same change from three sides, and Astra and Sol named the missing piece: a run identity. There is none; `runId` appears nowhere in `:domain` or `:data`, and `SetEditDelta.kt:20-51` carries `planId` but no run, though PLAN A13 claims deltas carry "the plan and the run". Order: one migration (plan model, run identity, slot policy) before the Log and Plan rebuilds; the set screen can proceed in parallel on existing ViewModel state.

6. **Carry-forward.** Moved to Astra and Sol's explicit per-slot policy: "remember what I did" by default for goal-derived and blank-built slots, "repeat the prescription" for slots someone else wrote. Opus's own objection moved me: one bad PT day must not lower the next prescription. "Reset to plan" stays on remembered slots.

7. **Metrics.** Estimated 1RM: concede to Astra and Sol, labeled and off by default inside exercise detail, never on a card. Volume: inside exercise detail only (Sol). Adherence: completed versus intended against the captured prescription once runs exist (Astra, Sol); until then Opus's "sessions in the last 28 days". "Rotation complete" on the receipt stays as the one ceremony.

8. **Tabs.** Today, Log, Plans. Log is the app's name; Plans is plural because there are several; Progress promises charts nobody asked for.

9. **Start empty.** In, after the run model: a run with no plan, slots added as you go, landing in Sessions and Exercises like any other. It costs almost nothing once a run exists on its own.

## Fact check

- **Opus**, verified: authored live logs are deleted after archiving (`TrackerRepository.kt:1312-1314`); an authored standard lift seeds at 0 lb × 10 (`:686`); the wizard opens on emphasis (`WizardModels.kt:21-30`). Wrong lines: `Types.kt:110-123`, `CardioPlanner.kt:53-67`, `Cardio.kt:166` and `:189-195` are past the ends of those files (104, 43 and 72 lines); the facts hold at `Types.kt:91-104`, `CardioPlanner.kt:27-42`, `Cardio.kt:43` and `:66-72`.
- **Astra**, verified: a tick after phone DONE can re-tick the next run of a one-day plan (`SetEditApplier.kt:46-47`, open as #296); the accessory fallback returns an unavailable exercise (`ProgramGenerator.kt:394-398`); the TOP cascade ignores `done` (`SetEditor.kt:21-40`); `buildTickDelta` has no `seconds` parameter, so a stopped hold sends no measured duration (`TickDelta.kt:17-37`). Nothing contradicted.
- **Sol**, verified: no run id on strength deltas (`SetEditDelta.kt:20-51`); checks reset by calendar date (`CheckmarkReset.kt:33-42`); swaps and prescription edits delete live logs (`TrackerRepository.kt:670`, `:547`); authored slots refuse a partner (`:742`); the external reader keeps only strength sessions (`HealthConnectReader.kt:87`). Nothing contradicted; PLAN A13 is the document that is wrong.
- **Mine**, re-verified: the watch declares no sensor permission (`wear/src/main/AndroidManifest.xml:10-20`); Health Connect writes no distance (`HealthConnectPermissions.kt:36`).

## The strongest point I missed

Astra's: finish run identity before any more recording. I listed the late-tick bug and the deleted logs as separate half-built parts; Astra saw one defect. Every destructive path (swap `:670`, prescription edit `:547`, authored reseed `:1312`) exists because performed work lives in the plan's rows, and every sync hazard because a tick names a slot and a date instead of a run. One identity fixes both.

## The owner's five decisions, final order

1. **One migration first: plan model, run identity, per-slot policy.** (A) Yes, before the Log and Plan rebuilds. (B) Ship the bifurcation and rebuild the UI now. *A.*
2. **Carry-forward is a per-slot policy** ("remember what I did" or "repeat the prescription"), with "Reset to plan" on remembered slots. Yes or no. *Yes.*
3. **Today is the overview with other days choosable; Day is the start screen; tabs Today, Log, Plans; Log has Sessions and Exercises for every exercise.** (A) Yes. (B) The redesign's Today, Log, Plan with a Lifts tab. *A.*
4. **The watch owns the run** (log, correct reps and load, skip, undo, jump, Finish), tap-plus-Undo as the default, gated by a wrist test. (A) Yes. (B) Companion with Finish on the phone. *A.*
5. **Do they run intervals?** Yes: a bounded Health Services prototype on the watch after step 6. No: close #284 and keep manual distance plus linking. *A fact about them.*

## Build order

1. **Theme and accessibility fixes** (redesign phase 1): contrast, error color, focus ring, large font. They see every screen hold contrast in both themes; nothing moves.
2. **The migration**: one plan model (`PlanOrigin` becomes provenance), a run table with identity on the wire, a slot policy, cardio folded into plan days with the prose suggestions and `CardioDay` deleted, session notes into Room, backup version bumped. They see one thing change: switching plans, swapping an exercise or editing a prescription no longer erases today's sets.
3. **The set screen**: the redesign's one-set workout, rest band with Undo, Lift and Day sheets, app-bar Finish, phone clock for holds, cardio in the same layout. They see the pager go and one set at a time with "Log 190 × 5".
4. **Today and Day**: Today as the overview with the suggested day's Start shortcut and the other days as rows; Day as the start screen; "Start empty" as a row. They see the choice before the start.
5. **Log**: Sessions and Exercises tabs, exercise detail for everything performed with last, best, trend, goal line where one exists, labeled optional e1RM and volume inside detail; the receipt with "rotation complete". They see progress for the plank and the rows, not only the squat.
6. **Plans and the watch**: New plan as Make me one, Blank, Duplicate; the three-screen wizard; the Wear M3 rebuild with tap-plus-Undo, rep and load correction and Finish on the wrist, wrist-tested before it replaces the dial. They see a plan made in three screens and a watch that finishes the day.
7. **Distance, if decision 5 is yes**: Health Connect run linking, then the watch Health Services prototype for outdoor intervals, with the Play declarations. They see a run attach to its cardio day, then a buzz at 400 m.
