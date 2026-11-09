package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetDtoRirTest {
    @Test fun `reported rir round trips and absent rir keeps the legacy bytes`() {
        val set = LoggedSet(100.0, 5, SetKind.WORK, rir = 1)
        assertEquals(listOf(set), SetJson.decodeSets(SetJson.encodeSets(listOf(set))))
        val legacy = """[{"weightLb":100.0,"reps":5,"kind":"WORK","done":false}]"""
        assertEquals(legacy, SetJson.encodeSets(listOf(set.copy(rir = null))))
        assertNull(SetJson.decodeSets(legacy).single().rir)
    }
}
