package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.ProgressionUndoDto
import cloud.trotter.log.strength.data.serialization.ProgressionUndoJson
import cloud.trotter.log.strength.data.serialization.UndoRuleDto
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressionUndoSlotTest {
    @Test fun `an undo rule without a slot reads as main and a partner rule names its slot`() {
        val old = """{"rules":[{"programExerciseId":7,"beforeJson":"a","afterJson":"b"}]}"""
        assertEquals(Slot.MAIN, ProgressionUndoJson.decode(old)!!.rules.single().slot)
        assertEquals(old, ProgressionUndoJson.encode(ProgressionUndoDto(rules = listOf(UndoRuleDto(7, "a", "b")))))
        val partner = ProgressionUndoDto(rules = listOf(UndoRuleDto(7, "a", "b", Slot.SS)))
        val encoded = ProgressionUndoJson.encode(partner)
        assertTrue("\"slot\":\"ss\"" in encoded)
        assertEquals(partner, ProgressionUndoJson.decode(encoded))
    }
}
