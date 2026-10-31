package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.data.serialization.LegacyCardio
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyCardioTest {
    @Test
    fun `stored finishers and standalone suggestions retain every set for each mode and fiveK flag`() {
        val finisher = CardioSuggestion("Hard cardio — intervals", "Lift first, then run. 20 min tempo.", true)
        val standalone = CardioSuggestion("Easy Zone 2", "Lift first, then run. 20–30 min easy, conversational pace.", false)
        for (mode in listOf(CardioMode.OUTDOOR_RUN, CardioMode.TREADMILL, CardioMode.LOW_IMPACT)) {
            for (fiveK in listOf(false, true)) {
                for (suggestion in listOf(finisher, standalone)) {
                    val answers = WizardAnswers(cardio = CardioPrefs(mode = mode, fiveKGoal = fiveK))
                    val expected = CardioBlocks.fromLegacy(suggestion, fiveK, requireNotNull(CardioBlocks.activityOf(mode)))
                    assertEquals(expected, LegacyCardio.toBlock(requireNotNull(CardioDto.encode(suggestion)), answers))
                }
            }
        }
    }

    @Test
    fun `legacy conversion uses owning recipe rather than snapshot execution facts`() {
        val json = """{"label":"Intervals","detail":"hard","hard":true,"mode":"OUTDOOR_RUN","fiveK":true}"""
        val suggestion = CardioSuggestion("Intervals", "hard", true)
        val answers = WizardAnswers(cardio = CardioPrefs(mode = CardioMode.TREADMILL, fiveKGoal = false))
        assertEquals(CardioBlocks.fromLegacy(suggestion, false, requireNotNull(CardioBlocks.activityOf(CardioMode.TREADMILL))),
            LegacyCardio.toBlock(json, answers))
    }

    @Test
    fun `NONE clears a stored suggestion without making a block`() {
        val json = requireNotNull(CardioDto.encode(CardioSuggestion("Intervals", "", true)))
        assertNull(LegacyCardio.toBlock(json, WizardAnswers(cardio = CardioPrefs(mode = CardioMode.NONE))))
    }
}
