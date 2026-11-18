package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
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

    @Test fun `run rule shapes round trip`() {
        for (rule in listOf(
            CardioRule(null, RunSession.EASY),
            CardioRule(null, RunSession.THRESHOLD, steps = 4, credits = 1, alternate = RunSession.VO2MAX),
            CardioRule(null, RunSession.SHARPENING, steps = 3),
            CardioRule(null, RunSession.LONG, longMetres = 40_000, longSeconds = 12_600),
            CardioRule(null, RunSession.LONG, longSeconds = 5_400),
        )) {
            val json = CardioRuleJson.encode(rule)
            CardioRuleJson.validate(json)
            assertEquals(rule, CardioRuleJson.decode(json))
        }
    }

    @Test fun `existing rules keep their exact JSON`() {
        assertEquals("""{"ladderLevel":3}""", CardioRuleJson.encode(CardioRule(3)))
        assertEquals("{}", CardioRuleJson.encode(CardioRule(null)))
        assertEquals(CardioRule(3), CardioRuleJson.decode("""{"ladderLevel":3}"""))
        assertEquals(CardioRule(null), CardioRuleJson.decode("{}"))
    }

    @Test fun `invalid run rules reject on decode and validation`() {
        for (json in listOf(
            """{"session":"EASY","steps":1}""",
            """{"session":"NOPE"}""",
            """{"session":"THRESHOLD","credits":2}""",
            """{"session":"EASY","longMetres":100}""",
            """{"ladderLevel":2,"session":"EASY"}""",
        )) {
            assertNull(CardioRuleJson.decode(json))
            assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(json) }
        }
    }

    @Test fun `sprint rule state round trips and stays absent by default`() {
        for ((rule, json) in listOf(
            CardioRule(null, RunSession.ACCELERATION, intro = 3) to """{"session":"ACCELERATION","intro":3}""",
            CardioRule(null, RunSession.ACCELERATION, steps = 5, credits = 1) to
                """{"session":"ACCELERATION","steps":5,"credits":1}""",
            CardioRule(null, RunSession.ACCELERATION) to """{"session":"ACCELERATION"}""",
        )) {
            assertEquals(json, CardioRuleJson.encode(rule))
            assertEquals(rule, CardioRuleJson.decode(json))
            CardioRuleJson.validate(json)
        }
        for (json in listOf(
            """{"session":"EASY","intro":1}""",
            """{"session":"ACCELERATION","steps":1,"intro":2}""",
        )) {
            assertNull(CardioRuleJson.decode(json))
            assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(json) }
        }
    }

    @Test fun `sprint step and trial cycles round trip and remain absent at default`() {
        for ((rule, json) in listOf(
            CardioRule(null, RunSession.ACCELERATION, steps = 5, credits = 1, stepCycle = 4, sprintTrialCycle = 9) to
                """{"session":"ACCELERATION","steps":5,"credits":1,"sprintStepCycle":4,"sprintTrialCycle":9}""",
            CardioRule(null, RunSession.MAX_VELOCITY, sprintTrialCycle = 0) to
                """{"session":"MAX_VELOCITY","sprintTrialCycle":0}""",
            CardioRule(null, RunSession.MAX_VELOCITY, steps = 1, stepCycle = 0) to
                """{"session":"MAX_VELOCITY","steps":1,"sprintStepCycle":0}""",
            CardioRule(null, RunSession.SPRINT_TEMPO, steps = 1, stepCycle = 0) to
                """{"session":"SPRINT_TEMPO","steps":1,"sprintStepCycle":0}""",
            CardioRule(null, RunSession.ACCELERATION) to """{"session":"ACCELERATION"}""",
        )) {
            assertEquals(json, CardioRuleJson.encode(rule))
            assertEquals(rule, CardioRuleJson.decode(json))
            CardioRuleJson.validate(json)
        }
        for (invalid in listOf(
            """{"session":"THRESHOLD","sprintStepCycle":0}""",
            """{"session":"ACCELERATION","sprintStepCycle":-1}""",
            """{"session":"ACCELERATION","sprintTrialCycle":-1}""",
            """{"session":"SPRINT_ENDURANCE","sprintTrialCycle":0}""",
            """{"session":"SPRINT_TEMPO","sprintTrialCycle":0}""",
            """{"session":"THRESHOLD","sprintTrialCycle":0}""",
        )) {
            assertNull(CardioRuleJson.decode(invalid))
            assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(invalid) }
        }
    }

    @Test fun `easy run step cycle keeps the existing JSON key`() {
        val json = """{"session":"EASY","sprintStepCycle":3}"""
        val rule = CardioRule(null, RunSession.EASY, stepCycle = 3)
        assertEquals(rule, CardioRuleJson.decode(json))
        assertEquals(json, CardioRuleJson.encode(rule))
        CardioRuleJson.validate(json)
        val invalid = """{"session":"THRESHOLD","sprintStepCycle":0}"""
        assertNull(CardioRuleJson.decode(invalid))
        assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(invalid) }
    }

    @Test fun `v0_11 rules decode and re-encode byte-identical`() {
        for (json in listOf(
            "{}",
            """{"ladderLevel":3}""",
            """{"session":"EASY"}""",
            """{"session":"THRESHOLD","steps":4,"credits":1,"alternate":"VO2MAX"}""",
            """{"session":"SHARPENING","steps":3}""",
            """{"session":"LONG","longMetres":40000,"longSeconds":12600}""",
            """{"session":"LONG","longSeconds":5400}""",
        )) assertEquals(json, CardioRuleJson.encode(requireNotNull(CardioRuleJson.decode(json))))
    }

    @Test fun `careful intro six round trips and seven rejects`() {
        val json = """{"session":"ACCELERATION","intro":6}"""
        val rule = CardioRule(null, RunSession.ACCELERATION, intro = 6)
        assertEquals(rule, CardioRuleJson.decode(json))
        assertEquals(json, CardioRuleJson.encode(rule))
        CardioRuleJson.validate(json)
        val invalid = """{"session":"ACCELERATION","intro":7}"""
        assertNull(CardioRuleJson.decode(invalid))
        assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(invalid) }
    }

    @Test fun `maximal rotation markers round trip only on maximal sprint days`() {
        val json = """{"session":"ACCELERATION","sprintMaximalCycle":3}"""
        val rule = CardioRule(null, RunSession.ACCELERATION, maximalCycle = 3)
        assertEquals(rule, CardioRuleJson.decode(json))
        assertEquals(json, CardioRuleJson.encode(rule))
        for (invalid in listOf(
            """{"session":"SPRINT_TEMPO","sprintMaximalCycle":0}""",
            """{"session":"EASY","sprintMaximalCycle":0}""",
            """{"session":"ACCELERATION","sprintMaximalCycle":-1}""",
        )) {
            assertNull(CardioRuleJson.decode(invalid))
            assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(invalid) }
        }
    }

    @Test fun `ladder credits and dates round trip`() {
        val json = """{"ladderLevel":3,"credits":1,"ladderCounted":[20000,20001],"ladderRaised":20001}"""
        val rule = CardioRule(3, credits = 1, ladderCounted = listOf(20000, 20001), ladderRaised = 20001)
        assertEquals(rule, CardioRuleJson.decode(json))
        assertEquals(json, CardioRuleJson.encode(rule))
        CardioRuleJson.validate(json)
        val old = """{"ladderLevel":3}"""
        assertEquals(old, CardioRuleJson.encode(CardioRule(3)))
        for (invalid in listOf(
            """{"ladderLevel":3,"ladderCounted":[1,2,3,4]}""",
            """{"ladderLevel":3,"ladderCounted":[2,1]}""",
            """{"ladderCounted":[1]}""",
            """{"ladderRaised":1}""",
            """{"ladderLevel":3,"credits":2}""",
        )) {
            assertNull(CardioRuleJson.decode(invalid))
            assertFailsWith<IllegalArgumentException> { CardioRuleJson.validate(invalid) }
        }
    }
}
