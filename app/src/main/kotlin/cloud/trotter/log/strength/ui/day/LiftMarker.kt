package cloud.trotter.log.strength.ui.day

import androidx.annotation.StringRes
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.domain.progression.SlotRule

enum class LiftMarker(@StringRes val label: Int) {
    ESTIMATE(R.string.marker_estimate),
    LIGHT_WEEK(R.string.marker_light_week),
    LIGHTER_TODAY(R.string.marker_lighter_today),
    AFTER_BREAK(R.string.marker_after_break);

    companion object {
        fun of(rule: SlotRule?, opening: OpeningDto?, lightRemaining: Int): LiftMarker? = when {
            rule?.state?.calibrating == true || opening?.layoff == OpeningDto.RECALIBRATE -> ESTIMATE
            opening?.light == true -> if (lightRemaining > 0) LIGHT_WEEK else LIGHTER_TODAY
            opening?.layoff == OpeningDto.SCALED -> AFTER_BREAK
            else -> null
        }
    }
}
