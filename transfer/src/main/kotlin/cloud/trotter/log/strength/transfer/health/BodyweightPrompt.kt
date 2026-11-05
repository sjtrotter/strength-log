package cloud.trotter.log.strength.transfer.health

import cloud.trotter.log.strength.domain.units.Bodyweight
import kotlin.math.abs

/**
 * The GOAL-vs-ACTUAL decision for the bodyweight prompt (A3). Pure and
 * unit-testable: given the latest Health Connect bodyweight and the app's
 * configured bodyweight, it only decides *whether* to offer the "bodyweight
 * changed — update your GOALs?" prompt. It never applies anything — per the
 * GOAL-vs-ACTUAL principle the user always decides, and applying is a separate,
 * explicit config write in the ViewModel.
 */
object BodyweightPrompt {

    /** Below this pound difference the two are "the same" and no prompt shows —
     *  bodyweight noise should not prompt a goal update. */
    private const val DEFAULT_THRESHOLD_LB = 1.0

    fun evaluate(
        latestWeightLb: Double?,
        configBodyweightLb: Double,
        thresholdLb: Double = DEFAULT_THRESHOLD_LB,
    ): BodyweightPromptData? {
        if (latestWeightLb == null) return null
        if (abs(latestWeightLb - configBodyweightLb) < thresholdLb) return null
        return BodyweightPromptData(healthConnectLb = latestWeightLb, currentConfigLb = configBodyweightLb)
    }

    fun appliedBodyweightLb(data: BodyweightPromptData): Double = Bodyweight.round(data.healthConnectLb)
}
