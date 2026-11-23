package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SetJsonSidesTest {
    private val sides = Sides(SideResult(0.0, 0, 40), SideResult(0.0, 0, 52))
    private val row = LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 40, sides = sides)

    @Test fun `a set without sides encodes byte identical to before`() {
        assertEquals("""[{"weightLb":185.0,"reps":5,"kind":"WORK","done":true}]""",
            SetJson.encodeSets(listOf(LoggedSet(185.0, 5, SetKind.WORK, done = true))))
    }

    @Test fun `sides round trip through the live JSON`() {
        assertEquals(listOf(row), SetJson.decodeSets(SetJson.encodeSets(listOf(row))))
    }

    @Test fun `a row whose sides disagree is written without them`() {
        val changed = row.copy(seconds = 52)
        val encoded = SetJson.encodeSets(listOf(changed))
        assertFalse(encoded.contains("sides"))
        val decoded = SetJson.decodeSets(encoded).single()
        assertNull(decoded.sides)
        assertEquals(changed.copy(sides = null), decoded)
    }
}
