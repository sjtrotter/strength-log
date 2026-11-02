package cloud.trotter.log.strength.ui.you

import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.units.WeightUnit

data class YouUiState(
    val config: LifterConfig = LifterConfig(),
    val unit: WeightUnit = WeightUnit.LB,
    val bodyweightDisplay: Double = LifterConfig().bodyweightLb.toDouble(),
)

data class YouActions(
    val onBodyweightChange: (Double) -> Unit,
    val onAgeChange: (Int) -> Unit,
    val onLevelChange: (ExperienceLevel) -> Unit,
    val onBack: () -> Unit,
)
