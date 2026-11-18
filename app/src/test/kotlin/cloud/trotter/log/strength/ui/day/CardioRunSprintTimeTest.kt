package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioRunSprintTimeTest {
    @Test fun `only matching finished work rows get the last timing for their position`() {
        val run = requireNotNull(sprintRepFixture().cardio?.run).copy(results = listOf(
            SavedCardioResult("FINISHED", 10, 60), SavedCardioResult("FINISHED", 120),
            SavedCardioResult("SHORTENED", 5)), wallStart = null)
        fun time(position: Int, centis: Int = 852) = CardioSprintTime(run.runId, position, centis, "PHONE", "THREE_POINT")
        val rows = run.resultSets(emptyList(), listOf(time(0), time(0, 830), time(1), time(2),
            time(0, 700).copy(runId = "other")))
        assertEquals(SprintTime(830, TimingMethod.PHONE, StartProtocol.THREE_POINT), rows.first().timing)
        assertNull(rows[1].timing)
        assertNull(rows[2].timing)
        assertEquals(run.resultSets, run.resultSets(emptyList()))
    }
}
