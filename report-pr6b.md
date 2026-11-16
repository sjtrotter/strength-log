# PR #594 (6b): CI unit-test fixes

## Causes and changes

1. `SetupFlowScreenTest > large font keeps every step usable`: the Equipment
   step used M3's default segmented-row spacing, which overlaps adjacent buttons
   by the border width. The touch-target guard expects no overlaps.
2. `SetupFlowScreenTest > step rows follow the gear`: the same default affected
   both the unit row and every smallest-step row, including the metric options.
3. `SetupFlowScreenTest > choose exactly opens eleven tagged boxes`: scrolling
   past the equipment checkboxes exposed the same overlapping rows.

   Both `SingleChoiceSegmentedButtonRow` declarations in `SetupFlowSteps.kt` now
   use `space = 0.dp`, matching the unit and theme rows in Settings
   (`ui/setup/SetupScreen.kt`). This covers the unit row and each generated Bar,
   Dumbbell and Stack row. The M3 components and shared guard stay intact.
   There are no existing callers declaring expected overlap pairs: Settings
   supplies the applicable zero-overlap configuration. The effort and Plan rows
   still use default spacing; they do not establish an overlap allowance.

4. `SetupFlowViewModelTest > case 36 waits for the lifter to choose`: the empty
   list at line 297 was `repo.plans.programFlow.first().days`, the persisted
   selected program. It was neither the over-budget list nor the drop choices.
   With no selected plan yet, `programFlow` emits `Program(emptyList())`.
   The test used `advanceUntilIdle()` as a completion barrier after requesting
   Start, without awaiting completion of creation, restore reconciliation and
   publication. Scheduler idleness does not guarantee completion of external
   file I/O in that path.

   The fixture is valid. Its answers match `DoseLadderTest` case 36: Strength,
   full gym, a year or more, 65 or older, one 30-minute day, five default mains,
   no recent numbers and no accepted extra balance days. The planner retains
   all five mains at step 6: 2,035 seconds, 235 seconds over, displayed as
   4 minutes. Dropping `bb_row` leaves four mains at 1,768 seconds; 45 minutes
   also fits with all five mains. No planner or fixture change is needed.

   The test now awaits a ready preview for the restored 30-minute answers before
   Start, then awaits completion or an explicit creation error before reading
   the persisted program. It asserts successful completion and one saved plan.
   Added assertions identify the expected excess and drop choices, confirm both
   choices fit, and confirm Start remains available for the over-budget plan.

## Files changed

- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/SetupFlowSteps.kt`
- `app/src/test/kotlin/cloud/trotter/log/strength/ui/wizard/SetupFlowViewModelTest.kt`
- `report-pr6b.md`

## Tests added

None. Strengthened `case 36 waits for the lifter to choose`. The three existing
screen tests retain their strict touch-target checks.

## Verification

Reviewed the diff and whitespace with `git diff --check`. No builds, compilers,
tests, Gradle commands, network calls or subagents were used. CI must verify
compilation under `-Werror` and runtime behavior. Both new row arguments use the
existing Settings API and existing `dp` import; the test uses its existing Flow
`first` import. Domain code, pinned domain tests, persistence schemas and saved
state formats were not changed. Nothing was staged, committed or pushed.

## Beyond the spec

No unrelated behavior changed. `spec-pr6b.md` and an existing `report-pr6b.md`
were absent from this checkout, so their contents could not be read. This report
was created as the requested deliverable. Diagnosis used the supplied failure
details, repository code and tests, and revision 37 of
`docs/briefs/wizard-design-2026-11-08.md`. Runtime confirmation remains for CI,
as requested.
