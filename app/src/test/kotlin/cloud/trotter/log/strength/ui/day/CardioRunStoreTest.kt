package cloud.trotter.log.strength.ui.day

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.domain.units.DistanceUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioRunStoreTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var storeScope: CoroutineScope
    private lateinit var prefs: CardioRunPrefs
    private lateinit var store: PrefsCardioRunStore

    @Before fun setUp() {
        storeScope = CoroutineScope(dispatcher + Job())
        prefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-run-store", ".preferences_pb")
        })
        store = PrefsCardioRunStore(prefs, storeScope)
    }

    @After fun tearDown() { storeScope.cancel() }

    @Test fun `load sees a save that is not written yet`() = runTest(dispatcher) {
        val copy = stored()
        store.save(copy)
        assertEquals(copy, store.load(1L, "A"))
    }

    @Test fun `the last write for a day wins`() = runTest(dispatcher) {
        val first = stored()
        val second = first.copy(savedAt = 2L, run = first.run.copy(saveFailed = true))
        store.save(first)
        store.save(second)
        assertEquals(second, store.load(1L, "A"))
        store.save(first)
        store.delete(1L, "A")
        assertNull(store.load(1L, "A"))
    }

    @Test fun `days keep separate copies`() = runTest(dispatcher) {
        val first = stored()
        val second = first.copy(run = first.run.copy(dayId = "B", dayIndex = 1, runId = "second"))
        store.save(first)
        store.save(second)
        assertEquals(first, store.load(1L, "A"))
        assertEquals(second, store.load(1L, "B"))
    }

    @Test fun `a copy keeps every field`() = runTest(dispatcher) {
        val copy = stored().let { stored ->
            val run = stored.run.copy(
                results = listOf(SavedCardioResult("FINISHED", 98, 400)),
                startedAt = 1_000L, wallStart = 2_000L, elapsedStart = 3_000L, bootCount = 4,
                drafts = listOf(CardioDistanceDraft(0, true, "400", true, true, true, true)),
                completedAt = 99_000L, sessionId = 5L, saveFailed = true, showReceipt = false,
            )
            stored.copy(run = run,
                gpsSets = listOf(CardioGpsSet(run.runId, 0, 410, 400, 98, true, true)),
                sprintTimes = listOf(CardioSprintTime(run.runId, 0, 9800, "HAND", "STANDING")),
                readingInputs = listOf(CardioReadingInput(run.runId, 0, "0.4", DistanceUnit.KM)),
                runDistance = CardioRunDistance(run.runId, 1, "1200"))
        }
        store.save(copy)
        assertEquals(copy, store.load(1L, "A"))
    }

    @Test fun `an unreadable copy loads as nothing`() = runTest(dispatcher) {
        prefs.set(1L, "A", "{")
        assertNull(store.load(1L, "A"))
    }

    private fun stored() = StoredCardioRun(1L, CardioRun(
        planId = 1L, planName = "Running", dayId = "A", dayIndex = 0,
        sourceBlockJson = "source", blockJson = "frozen", finishesDay = true,
        nextDayTitle = "Day B, Run", runId = "first",
    ))
}
