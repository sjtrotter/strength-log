package cloud.trotter.log.strength.audit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.HeaderTarget
import cloud.trotter.log.strength.ui.day.SavedCardioResult
import cloud.trotter.log.strength.ui.day.UndoOffer
import cloud.trotter.log.strength.ui.day.WorkoutFocus
import cloud.trotter.log.strength.ui.day.WorkoutSelection
import cloud.trotter.log.strength.ui.day.WorkoutState
import cloud.trotter.log.strength.ui.day.withWorkoutSelection
import cloud.trotter.log.strength.ui.day.workoutActions
import cloud.trotter.log.strength.ui.day.workoutDaySheetFixture
import cloud.trotter.log.strength.ui.day.workoutLiftSheetFixture
import cloud.trotter.log.strength.ui.day.workoutRest
import cloud.trotter.log.strength.ui.day.workoutSetCardioBlockedByTimedRepFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioDistanceFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioDoneFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioRecoveryFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioRunningFixture
import cloud.trotter.log.strength.ui.day.workoutSetEmptyFixture
import cloud.trotter.log.strength.ui.day.workoutSetSingleFixture
import cloud.trotter.log.strength.ui.day.workoutSetSupersetFixture
import cloud.trotter.log.strength.ui.day.workoutSetTimedRepFixture
import cloud.trotter.log.strength.ui.day.workoutSetTimedRepRunningFixture
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * WatchStatus is retained in the model but DayScreen does not render a watch chip.
 * TIMED holds have a seconds value to log; execution timers belong to timed reps and cardio.
 * Logging offers Undo in the rest band; the set-removal snackbar is covered under sheets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditWorkoutShotsTest : UiAuditTest() {

    @Test fun shot01WorkoutFirstRamp() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/01-workout-first-ramp") { AuditDay(rampFixture(0)) }
    }

    @Test fun shot02WorkoutRest() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/02-workout-rest") { AuditDay(workoutSetSingleFixture().copy(rest = workoutRest().copy(remainingSeconds = 64, remainingFraction = 64f / 90f))) }
    }

    @Test fun shot03WorkoutRestOver() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/03-workout-rest-over") { AuditDay(workoutSetSingleFixture().copy(rest = workoutRest().copy(remainingSeconds = -23, remainingFraction = 0f, over = true))) }
    }

    @Test fun shot04WorkoutTop() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/04-workout-top") { AuditDay(rampFixture(4)) }
    }

    @Test fun shot05WorkoutBackoff() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/05-workout-backoff") { AuditDay(rampFixture(5)) }
    }

    @Test fun shot06WorkoutSupersetMain() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/06-workout-superset-main") { AuditDay(workoutSetSupersetFixture()) }
    }

    @Test fun shot07WorkoutSupersetPartner() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/07-workout-superset-partner") { AuditDay(workoutSetSupersetFixture().let { it.copy(exercises = it.exercises.map { card -> card.copy(rows = card.rows.map { row -> if (row.index == 1) row.copy(done = true) else row }) }, workout = it.workout?.copy(focus = WorkoutFocus(1, Slot.SS, 1))) }) }
    }

    @Test fun shot08WorkoutHold() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/08-workout-hold") { AuditDay(workoutDaySheetFixture().copy(selectedPageKey = "exercise:4").withWorkoutSelection()) }
    }

    @Test fun shot09WorkoutRepsOnly() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/09-workout-reps-only") { AuditDay(workoutSetTimedRepFixture().let { it.copy(exercises = it.exercises.map { card -> card.copy(timedRepDurationSeconds = null, timedRepAim = null, headerTarget = HeaderTarget.Prescribed("20")) }) }) }
    }

    @Test fun shot10WorkoutCardioSuggestion() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/10-workout-cardio-suggestion") { AuditDay(workoutSetCardioFixture()) }
    }

    @Test fun shot11WorkoutCardioExecuting() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(workoutSetCardioFixture())
        rule.auditShot("workout/11-workout-cardio-executing", beforeCapture = {
            rule.onNodeWithText("Start").performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDay(state.value, actions = workoutActions().copy(onStartCardio = {
                state.value = workoutSetCardioRunningFixture()
            }))
        }
    }

    @Test fun shot12WorkoutUndoAfterLog() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(workoutSetSingleFixture())
        rule.auditShot("workout/12-workout-undo-after-log", beforeCapture = {
            rule.onNodeWithText("Log 190 lb × 5").performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDay(state.value, actions = workoutActions().copy(onLogFocusedSet = {
                val old = state.value
                state.value = old.copy(
                    exercises = old.exercises.map { card -> card.copy(rows = card.rows.map { row -> if (row.index == 2) row.copy(done = true) else row }) },
                    workout = old.workout?.copy(focus = WorkoutFocus(1, Slot.MAIN, 3), undo = UndoOffer(
                        1, Slot.MAIN, 2, "Barbell Back Squat", LoggedSet(190.0, 5, SetKind.RAMP), TrackingType.WEIGHTED, WeightUnit.LB, null,
                    )),
                )
            }))
        }
    }

    @Test fun shot13WorkoutLong() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/13-workout-long") { AuditDay(auditLongWorkoutFixture().withWorkoutSelection()) }
    }

    @Config(fontScale = 2f)
    @Test fun shot14WorkoutRampFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/14-workout-ramp-font2", fontScale = 2f) { AuditDay(rampFixture(0)) }
    }

    @Test fun shot15WorkoutRampLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/15-workout-ramp-light", theme = ThemePreference.LIGHT) { AuditDay(rampFixture(0)) }
    }

    @Test fun shot16WorkoutSupersetLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/16-workout-superset-light", theme = ThemePreference.LIGHT) { AuditDay(workoutSetSupersetFixture()) }
    }

    @Test fun shot17WorkoutTimedRepsReady() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/17-workout-timed-reps-ready") { AuditDay(workoutSetTimedRepFixture()) }
    }

    @Test fun shot18WorkoutTimedRepsRunning() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(workoutSetTimedRepFixture())
        rule.auditShot("workout/18-workout-timed-reps-running", beforeCapture = {
            rule.onNodeWithText("Start").performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDay(state.value, actions = workoutActions().copy(onStartTimedRep = { _, _ ->
                state.value = workoutSetTimedRepRunningFixture()
            }))
        }
    }

    @Test fun shot19WorkoutTimedRepsAwaiting() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/19-workout-timed-reps-awaiting") { AuditDay(workoutSetTimedRepRunningFixture(awaiting = true)) }
    }

    @Test fun shot20WorkoutCardioDistance() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/20-workout-cardio-distance") { AuditDay(workoutSetCardioDistanceFixture()) }
    }

    @Test fun shot21WorkoutCardioRecovery() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/21-workout-cardio-recovery") { AuditDay(workoutSetCardioRecoveryFixture()) }
    }

    @Test fun shot22WorkoutCardioDone() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/22-workout-cardio-done") { AuditDay(workoutSetCardioDoneFixture()) }
    }

    @Test fun shot23WorkoutEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/23-workout-empty") { AuditDay(workoutSetEmptyFixture()) }
    }

    @Test fun shot24WorkoutNoProgram() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/24-workout-no-program") { AuditDay(DayUiState()) }
    }

    @Test fun shot25WorkoutLoading() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/25-workout-loading") { AuditDay(DayUiState(loading = true)) }
    }

    @Test fun shot26WorkoutAllLogged() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/26-workout-all-logged") { AuditDay(workoutSetSingleFixture().let { it.copy(exercises = it.exercises.map { card -> card.copy(allDone = true, rows = card.rows.map { row -> row.copy(done = true) }) }, workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, null)) }) }
    }

    @Test fun shot27WorkoutCardioSaveFailed() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/27-workout-cardio-save-failed") { AuditDay(workoutSetCardioDoneFixture().let { done -> val running = requireNotNull(workoutSetCardioRunningFixture().cardio?.run); done.copy(cardio = done.cardio?.copy(loggedSeconds = null, loggedSets = emptyList(), run = running.copy(wallStart = null, results = listOf(SavedCardioResult("FINISHED", 300)), saveFailed = true)), workout = done.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", false), completedView = false)) }) }
    }

    @Test fun shot28WorkoutClockChip() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/28-workout-clock-chip") { AuditDay(workoutSetCardioRunningFixture().copy(workout = workoutSetSingleFixture().workout)) }
    }

    @Test fun shot29WorkoutCardioDisabled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/29-workout-cardio-disabled") { AuditDay(workoutSetCardioBlockedByTimedRepFixture()) }
    }

    @Test fun shot30WorkoutCompletedLift() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/30-workout-completed-lift") { AuditDay(rampFixture(6).let { it.copy(workout = it.workout?.copy(shown = WorkoutSelection.Shown.Group("exercise:1", true), focus = null, completedView = true), exercises = it.exercises + workoutSetSupersetFixture().exercises.single().copy(programExerciseId = 2, position = 1)) }) }
    }

    @Test fun shot31WorkoutFinishDisabled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("workout/31-workout-finish-disabled") { AuditDay(workoutSetSingleFixture().let { it.copy(exercises = it.exercises.map { card -> card.copy(allDone = true, rows = card.rows.map { row -> row.copy(done = true) }) }, workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, 1_000L)) }) }
    }

    @Config(fontScale = 2f)
    @Test fun shot32WorkoutLiftNoteFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = rampFixture(0).let { it.copy(exercises = it.exercises.map { card ->
            card.copy(note = "Belt on top, straps on back-off sets")
        }) }
        rule.auditShot("workout/32-workout-lift-note-font2", fontScale = 2f) { AuditDay(state) }
    }

    private fun rampFixture(done: Int) = workoutLiftSheetFixture().let { state ->
        state.copy(exercises = state.exercises.map { card -> card.copy(
            allDone = done == card.rows.size,
            rows = card.rows.map { it.copy(done = it.index < done) },
        ) })
    }.withWorkoutSelection()

}
