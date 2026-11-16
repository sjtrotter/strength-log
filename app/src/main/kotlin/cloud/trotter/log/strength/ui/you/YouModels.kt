package cloud.trotter.log.strength.ui.you

import cloud.trotter.log.strength.data.LifterProfile
import cloud.trotter.log.strength.domain.units.WeightUnit

data class YouUiState(
    val profile: LifterProfile = LifterProfile(),
    val unit: WeightUnit = WeightUnit.LB,
)

data class YouActions(
    val onBodyweightChange: (Double) -> Unit,
    val onAgeChange: (Int) -> Unit,
    val onBack: () -> Unit,
)
