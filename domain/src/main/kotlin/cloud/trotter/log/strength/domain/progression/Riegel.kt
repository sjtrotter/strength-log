package cloud.trotter.log.strength.domain.progression

import kotlin.math.pow

/** Riegel's prediction, T2 = T1 × (D2 ÷ D1)^1.06, trusted up to the half (design §3.2 Goal check). Computed in doubles
 *  and rounded to the nearest second once, at the end. */
object Riegel {
    const val EXPONENT = 1.06
    const val MARATHON_METRES = 42_195.0
    const val MARATHON_MARGIN = 1.05

    fun seconds(fromMetres: Double, fromSeconds: Double, toMetres: Double): Int =
        Math.round(predictedSeconds(fromMetres, fromSeconds, toMetres)).toInt()

    /** The Plan screen's goal check: [seconds], × [MARATHON_MARGIN] when [toMetres] is a marathon or longer. */
    fun goalCheckSeconds(fromMetres: Double, fromSeconds: Double, toMetres: Double): Int =
        Math.round(predictedSeconds(fromMetres, fromSeconds, toMetres) * if (toMetres >= MARATHON_METRES) MARATHON_MARGIN else 1.0).toInt()

    /** Unrounded; callers round once. */
    fun predictedSeconds(fromMetres: Double, fromSeconds: Double, toMetres: Double): Double =
        fromSeconds * (toMetres / fromMetres).pow(EXPONENT)
}
