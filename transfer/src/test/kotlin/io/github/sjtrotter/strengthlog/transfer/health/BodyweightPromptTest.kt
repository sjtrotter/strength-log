package io.github.sjtrotter.strengthlog.transfer.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The GOAL-vs-ACTUAL bodyweight-prompt decision (#17, A3) — pure, plain JVM.
 * It only decides whether to offer the prompt and what value applying would
 * write; it never applies anything itself (that is an explicit config write).
 */
class BodyweightPromptTest {

    @Test
    fun noReading_noPrompt() {
        assertNull(BodyweightPrompt.evaluate(latestWeightLb = null, configBodyweightLb = 180))
    }

    @Test
    fun withinThreshold_noPrompt() {
        // 180.4 rounds to the same pound the user already has configured.
        assertNull(BodyweightPrompt.evaluate(latestWeightLb = 180.4, configBodyweightLb = 180))
    }

    @Test
    fun meaningfulChange_prompts() {
        val prompt = BodyweightPrompt.evaluate(latestWeightLb = 187.6, configBodyweightLb = 180)
        assertEquals(180, prompt?.currentConfigLb)
        assertEquals(187.6, prompt?.healthConnectLb)
    }

    @Test
    fun appliedValueRoundsToWholePound() {
        val prompt = BodyweightPrompt.evaluate(latestWeightLb = 187.6, configBodyweightLb = 180)!!
        assertEquals(188, BodyweightPrompt.appliedBodyweightLb(prompt))
    }

    @Test
    fun neverAutoApplies_evaluateDoesNotReturnConfig() {
        // The decision surfaces the Health Connect value; it must NOT quietly
        // hand back the config value as if nothing changed.
        val prompt = BodyweightPrompt.evaluate(latestWeightLb = 165.0, configBodyweightLb = 180)!!
        assertEquals(165.0, prompt.healthConnectLb)
    }
}
