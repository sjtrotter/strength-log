package cloud.trotter.log.strength.ui.components

import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.theme.FocusRing
import cloud.trotter.log.strength.ui.theme.TextSecondary
import kotlin.test.Test
import kotlin.test.assertEquals

class PressableContractTest {
    @Test
    fun `focus ring and disabled treatment survive the ripple migration`() {
        assertEquals(2.dp, PressableFocusRingWidth)
        assertEquals(TextSecondary, FocusRing)
        assertEquals(0.4f, PressableDisabledAlpha)
    }
}
