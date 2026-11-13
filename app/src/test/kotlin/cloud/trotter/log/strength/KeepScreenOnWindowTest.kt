package cloud.trotter.log.strength

import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.data.RunTrack
import cloud.trotter.log.strength.domain.theme.KeepScreenOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepScreenOnWindowTest {
    private fun run(draft: Boolean = false) = OpenRun("run", 1L, "A", "Day A", "Plan", 0L, emptyList(), draft = draft)

    @Test fun duringWorkoutsRequiresAStartedRun() = runTest {
        val setting = MutableStateFlow(KeepScreenOn.DURING_WORKOUTS)
        val openRun = MutableStateFlow<OpenRun?>(null)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch { keepScreenOnWindowFlow(setting, openRun).collect { values += it } }
        runCurrent()
        assertEquals(listOf(false), values)
        openRun.value = run(draft = true)
        runCurrent()
        assertEquals(listOf(false), values)
        openRun.value = run()
        runCurrent()
        assertEquals(listOf(false, true), values)
        openRun.value = run().copy(tracks = listOf(RunTrack(1, 0, 1L, "main", "pushup", false, emptyList(), emptyList())))
        runCurrent()
        assertEquals(listOf(false, true), values)
        openRun.value = null
        runCurrent()
        assertEquals(listOf(false, true, false), values)
    }

    @Test fun timerAndOffNeverHoldTheWindowForAStartedRun() = runTest {
        val setting = MutableStateFlow(KeepScreenOn.WHILE_TIMER)
        val openRun = MutableStateFlow<OpenRun?>(run())
        val values = mutableListOf<Boolean>()
        backgroundScope.launch { keepScreenOnWindowFlow(setting, openRun).collect { values += it } }
        runCurrent()
        assertEquals(listOf(false), values)
        setting.value = KeepScreenOn.OFF
        runCurrent()
        assertEquals(listOf(false), values)
    }
}
