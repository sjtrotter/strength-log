package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetEditTest {
    @Test fun `phone tick stamps the transition to done`() {
        val row = LoggedSet(50.0, 8, SetKind.WORK, startedAtMillis = 10L)
        val after = SetEditor.edit(listOf(row), 0, phoneTickEdit(row, true, 70L)).single()
        assertTrue(after.done)
        assertEquals(10L, after.startedAtMillis)
        assertEquals(70L, after.completedAtMillis)
    }

    @Test fun `phone retick keeps missing completion timing missing`() {
        val row = LoggedSet(50.0, 8, SetKind.WORK, done = true)
        val after = SetEditor.edit(listOf(row), 0, phoneTickEdit(row, true, 70L)).single()
        assertEquals(row, after)
        assertNull(after.completedAtMillis)
    }

    @Test fun `phone retick preserves observed timing and counted ticks replace duration`() {
        val row = LoggedSet(0.0, 0, SetKind.WORK, seconds = 30, startedAtMillis = 10L)
        val counted = SetEditor.edit(listOf(row), 0, phoneTickEdit(row, true, 70L, 12)).single()
        assertEquals(12, counted.reps)
        assertEquals(0, counted.seconds)
        assertEquals(counted, SetEditor.edit(listOf(counted), 0, phoneTickEdit(counted, true, 90L)).single())
    }
}
