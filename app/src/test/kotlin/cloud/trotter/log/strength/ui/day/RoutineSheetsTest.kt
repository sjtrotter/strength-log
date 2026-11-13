package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.prefs.RoutinePrefs
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.domain.routine.Routines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineSheetsTest {
    private val row = SetRowState(0, "Work", false, 80.0, 10, false)
    private val card = ExerciseCardState(1, 0, "Bench", isSuperset = false,
        headerTarget = HeaderTarget.Goal("80"), perHand = false, allDone = false, rows = listOf(row))
    private val state = DayUiState(planId = 1, viewDayId = "A", runId = "run", runOpen = true,
        exercises = listOf(card), warmUpMoves = listOf(Move.JACKS_OR_MARCH, Move.PUSH_UPS),
        coolDownMoves = listOf(Move.DOORWAY_CHEST, Move.OVERHEAD_TRICEPS, Move.WALL_BICEPS))
    private val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800))))
    private val cardio = CardioCardState("Run", "", false, block = block)
    private fun warm(state: DayUiState = this.state, prefs: RoutinePrefs = RoutinePrefs(), progress: RoutineProgress? = null) =
        RoutineSheets.warmUp(state, prefs, progress)
    private fun cool(state: DayUiState = this.state, prefs: RoutinePrefs = RoutinePrefs(), progress: RoutineProgress? = null) =
        RoutineSheets.coolDown(state, prefs, progress)

    @Test fun `unloaded preferences hide lift and cardio routines`() {
        assertNull(RoutineSheets.warmUp(state, null, null))
        val all = state.copy(exercises = listOf(card.copy(rows = listOf(row.copy(done = true)))),
            workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, null))
        assertNull(RoutineSheets.coolDown(all, null, null))
        val only = state.copy(exercises = emptyList(), cardio = cardio, runId = null, runOpen = false)
        val warm = RoutineProgress(RoutineProgress.cardioStartOwner(1, "A"), emptyList(), emptyList(), null)
        assertNull(RoutineSheets.warmUp(only, null, warm))
        val cool = RoutineProgress(RoutineProgress.cardioOwner("cardio-run"), listOf("COBRA"), emptyList(), null)
        assertNull(RoutineSheets.coolDown(only, null, cool))
    }

    @Test fun `lift warmup needs an open non draft zero set run`() {
        assertEquals(300, warm()?.timerSeconds)
        assertNull(warm(state.copy(runOpen = false)))
        assertNull(warm(state.copy(beforeStart = true)))
        assertNull(warm(state.copy(runId = null)))
        assertNull(warm(state.copy(loading = true)))
        assertNull(warm(state.copy(exercises = emptyList())))
        assertNull(warm(prefs = RoutinePrefs(warmUp = false)))
        assertNull(warm(prefs = RoutinePrefs(warmUpClosedRun = "run")))
        assertNull(warm(state.copy(exercises = listOf(card.copy(rows = listOf(row.copy(done = true)))))))
    }

    @Test fun `cardio logged or started first suppresses lift warmup`() {
        assertNull(warm(state.copy(cardio = cardio.copy(loggedSeconds = 20))))
        val run = CardioRun(1, "Plan", "A", 0, CardioBlockJson.encode(block), CardioBlockJson.encode(block), false, null)
        assertNotNull(warm(state.copy(cardio = cardio.copy(run = run))))
        assertNull(warm(state.copy(cardio = cardio.copy(run = run.copy(startedAt = 10)))))
    }

    @Test fun `progress matches owner freezes moves and drops unknown names`() {
        val progress = RoutineProgress("other", listOf("CAT_COW"), listOf("CAT_COW"), 10)
        assertEquals(state.warmUpMoves, warm(progress = progress)?.moves)
        assertTrue(requireNotNull(warm(progress = progress)).checked.isEmpty())
        assertNull(warm(progress = progress)?.timerStartedAt)
        val matching = progress.copy(owner = "run", moves = listOf("CAT_COW", "removed"), checked = listOf("CAT_COW", "removed"))
        assertEquals(listOf(Move.CAT_COW), warm(progress = matching)?.moves)
        assertEquals(setOf(Move.CAT_COW), warm(progress = matching)?.checked)
        assertEquals(10L, warm(progress = matching)?.timerStartedAt)
        assertEquals(state.warmUpMoves, warm(progress = matching.copy(moves = emptyList()))?.moves)
    }

    @Test fun `cardio start owner shows only before a cardio run exists`() {
        val only = state.copy(exercises = emptyList(), cardio = cardio, runId = null, runOpen = false)
        val progress = RoutineProgress(RoutineProgress.cardioStartOwner(1, "A"), emptyList(), emptyList(), null)
        assertTrue(requireNotNull(warm(only, progress = progress)).startsCardio)
        assertNull(warm(only, progress = progress)?.runId)
        assertEquals(300, warm(only, progress = progress)?.timerSeconds)
        assertNull(warm(only))
        assertNull(warm(only.copy(viewDayId = "B"), progress = progress))
        val run = CardioRun(1, "Plan", "A", 0, CardioBlockJson.encode(block), CardioBlockJson.encode(block), true, null)
        assertNull(warm(only.copy(cardio = cardio.copy(run = run)), progress = progress))
    }

    @Test fun `lift cooldown needs all logged and finishable and not closed`() {
        val all = state.copy(exercises = listOf(card.copy(rows = listOf(row.copy(done = true)))),
            workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, null))
        val sheet = requireNotNull(cool(all))
        assertFalse(sheet.startsCardio)
        assertEquals(Routines.seconds(all.coolDownMoves), sheet.timerSeconds)
        assertNull(cool(state))
        assertNull(cool(all.copy(exercises = listOf(card))))
        assertNull(cool(all.copy(runOpen = false)))
        assertNull(cool(all.copy(beforeStart = true)))
        assertNull(cool(all.copy(runId = null)))
        assertNull(cool(all, RoutinePrefs(coolDown = false)))
        assertNull(cool(all, RoutinePrefs(coolDownClosedRun = "run")))
    }

    @Test fun `cardio cooldown uses frozen moves over the receipt`() {
        val progress = RoutineProgress(RoutineProgress.cardioOwner("cardio-run"), listOf("HAMSTRING_STRETCH", "COBRA", "removed"), listOf("COBRA"), 100)
        val sheet = requireNotNull(cool(DayUiState(), progress = progress))
        assertEquals(listOf(Move.HAMSTRING_STRETCH, Move.COBRA), sheet.moves)
        assertEquals(setOf(Move.COBRA), sheet.checked)
        assertEquals(90, sheet.timerSeconds)
        assertNull(sheet.runId)
        assertNull(cool(prefs = RoutinePrefs(coolDown = false), progress = progress))
    }
}
