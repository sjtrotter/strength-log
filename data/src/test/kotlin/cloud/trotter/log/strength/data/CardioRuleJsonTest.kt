package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.domain.progression.CardioRule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioRuleJsonTest {
    @Test fun `ladder edges and null round trip`() {
        for (level in listOf(1, 12, null)) {
            val rule = CardioRule(level)
            val json = CardioRuleJson.encode(rule)
            CardioRuleJson.validate(json)
            assertEquals(rule, CardioRuleJson.decode(json))
        }
        assertEquals(CardioRule(null), CardioRuleJson.decode("""{"unknown":true}"""))
        assertNull(CardioRuleJson.decode(null))
        assertNull(CardioRuleJson.decode("junk"))
    }

    @Test fun `levels outside the ladder reject`() {
        for (level in listOf(0, 13)) {
            val json = """{"ladderLevel":$level}"""
            assertNull(CardioRuleJson.decode(json))
            assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(json) }
        }
    }
}
