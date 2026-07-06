package io.github.sjtrotter.strengthlog.domain.generator

import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion

/**
 * Cardio rules from spec §6.4 / §12. Always "lift first"; hard work only on
 * leg-light days; easy Zone 2 after leg-heavy days (and always for low-impact).
 */
object CardioPlanner {

    /** Finisher card for one strength day, or null when none belongs there. */
    fun finisher(prefs: CardioPrefs, legHeavy: Boolean): CardioSuggestion? {
        if (prefs.mode == CardioMode.NONE || prefs.placement == CardioPlacement.NONE) return null
        // SEPARATE_DAYS puts cardio on its own cards, never as a finisher.
        if (prefs.placement == CardioPlacement.SEPARATE_DAYS) return null
        val hard = !legHeavy && prefs.mode != CardioMode.LOW_IMPACT
        return suggestion(prefs.mode, prefs.fiveKGoal, hard)
    }

    /** Zone 2 piece for a standalone "Cardio + Core" day. */
    fun standalone(prefs: CardioPrefs): CardioSuggestion =
        suggestion(prefs.mode, prefs.fiveKGoal, hard = false)

    private fun suggestion(mode: CardioMode, fiveK: Boolean, hard: Boolean): CardioSuggestion {
        val verb = if (mode == CardioMode.LOW_IMPACT) "ride" else "run"
        if (hard) {
            val detail = if (fiveK) {
                "Lift first, then $verb. 5 min easy, then 4–6 × 2 min hard / 2 min easy."
            } else {
                "Lift first, then $verb. 20 min tempo."
            }
            return CardioSuggestion(label = "Hard cardio — intervals", detail = detail, hard = true)
        }
        return CardioSuggestion(
            label = "Easy Zone 2",
            detail = "Lift first, then $verb. 20–30 min easy, conversational pace.",
            hard = false,
        )
    }
}
