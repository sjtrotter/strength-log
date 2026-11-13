package cloud.trotter.log.strength.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.MovementPattern

@Composable
fun patternLabel(pattern: MovementPattern): String = stringResource(pattern.labelResource())

@StringRes
internal fun MovementPattern.labelResource(): Int = when (this) {
    MovementPattern.SQUAT_BILATERAL -> R.string.movement_pattern_squat_bilateral
    MovementPattern.SINGLE_LEG -> R.string.movement_pattern_single_leg
    MovementPattern.HINGE -> R.string.movement_pattern_hinge
    MovementPattern.KNEE_FLEXION -> R.string.movement_pattern_knee_flexion
    MovementPattern.KNEE_EXTENSION -> R.string.movement_pattern_knee_extension
    MovementPattern.H_PUSH -> R.string.movement_pattern_horizontal_push
    MovementPattern.V_PUSH -> R.string.movement_pattern_vertical_push
    MovementPattern.H_PULL -> R.string.movement_pattern_horizontal_pull
    MovementPattern.V_PULL -> R.string.movement_pattern_vertical_pull
    MovementPattern.SIDE_DELT -> R.string.movement_pattern_side_deltoid
    MovementPattern.REAR_DELT -> R.string.movement_pattern_rear_deltoid
    MovementPattern.BICEPS -> R.string.movement_pattern_biceps
    MovementPattern.TRICEPS -> R.string.movement_pattern_triceps
    MovementPattern.CALF_GASTROC -> R.string.movement_pattern_calf_gastrocnemius
    MovementPattern.CALF_SOLEUS -> R.string.movement_pattern_calf_soleus
    MovementPattern.CORE_ANTI_EXT -> R.string.movement_pattern_core_anti_extension
    MovementPattern.CORE_ANTI_ROT -> R.string.movement_pattern_core_anti_rotation
    MovementPattern.CORE_FLEX -> R.string.movement_pattern_core_flexion
    MovementPattern.CARDIO -> R.string.movement_pattern_cardio
    MovementPattern.PLYOMETRIC -> R.string.movement_pattern_plyometric
    MovementPattern.BALANCE -> R.string.movement_pattern_balance
}
