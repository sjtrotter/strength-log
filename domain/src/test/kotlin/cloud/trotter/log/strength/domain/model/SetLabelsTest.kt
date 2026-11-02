package cloud.trotter.log.strength.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SetLabelsTest {
    @Test
    fun `ramps count independently of top and backoff sets`() {
        assertEquals(
            listOf(
                SetLabel.Ramp(1), SetLabel.Ramp(2), SetLabel.Ramp(3), SetLabel.Ramp(4),
                SetLabel.Top, SetLabel.Backoff, SetLabel.Backoff,
            ),
            SetLabels.of(List(4) { SetKind.RAMP } + listOf(SetKind.TOP, SetKind.BACKOFF, SetKind.BACKOFF)),
        )
    }

    @Test
    fun `work and extra sets use their one based position`() {
        assertEquals(
            listOf(SetLabel.Ordinal(1), SetLabel.Ordinal(2), SetLabel.Ordinal(3)),
            SetLabels.of(listOf(SetKind.WORK, SetKind.WORK, SetKind.EXTRA)),
        )
    }

    @Test
    fun `work after a ramp keeps its position`() {
        assertEquals(
            listOf(SetLabel.Ramp(1), SetLabel.Ordinal(2)),
            SetLabels.of(listOf(SetKind.RAMP, SetKind.WORK)),
        )
    }

    @Test
    fun `empty kinds have no labels`() {
        assertEquals(emptyList(), SetLabels.of(emptyList()))
    }
}
