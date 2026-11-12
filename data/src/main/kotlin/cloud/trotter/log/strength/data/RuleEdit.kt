package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.standards.RestPolicy

sealed interface RuleEdit {
    data class NextTime(val mode: NextTimeMode?) : RuleEdit
    data class Rest(val seconds: Int?) : RuleEdit {
        init { require(seconds == null || seconds in 0..RestPolicy.MAX_REST_SECONDS) }
    }
    data class RepsLeft(val value: Int) : RuleEdit {
        init { require(value in 1..3) }
    }
    data class Increase(val fraction: Double) : RuleEdit {
        init { require(fraction in listOf(0.0125, 0.025, 0.05)) }
    }
}
