package cloud.trotter.log.strength.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.domain.routine.MoveDose
import cloud.trotter.log.strength.ui.day.formatCardioTime
import cloud.trotter.log.strength.ui.day.formatDurationWords

@StringRes
internal fun Move.nameResource(): Int = when (this) {
    Move.BIKE_OR_ROWER -> R.string.routine_move_bike_or_rower
    Move.JACKS_OR_MARCH -> R.string.routine_move_jacks_or_march
    Move.EASY_JOG_OR_WALK -> R.string.routine_move_easy_jog_or_walk
    Move.EASY_CARDIO -> R.string.routine_move_easy_cardio
    Move.LEG_SWINGS -> R.string.routine_move_leg_swings
    Move.BODYWEIGHT_SQUATS -> R.string.routine_move_bodyweight_squats
    Move.HIP_HINGES -> R.string.routine_move_hip_hinges
    Move.HIGH_KNEES -> R.string.routine_move_high_knees
    Move.BUTT_KICKS -> R.string.routine_move_butt_kicks
    Move.REVERSE_LUNGES -> R.string.routine_move_reverse_lunges
    Move.CALF_RAISES -> R.string.routine_move_calf_raises
    Move.Y_T_RAISES -> R.string.routine_move_y_t_raises
    Move.ARM_CIRCLES -> R.string.routine_move_arm_circles
    Move.PUSH_UPS -> R.string.routine_move_push_ups
    Move.ARM_SWINGS -> R.string.routine_move_arm_swings
    Move.SCAP_PUSH_UPS -> R.string.routine_move_scap_push_ups
    Move.UPPER_BACK_ROTATIONS -> R.string.routine_move_upper_back_rotations
    Move.CAT_COW -> R.string.routine_move_cat_cow
    Move.DEAD_BUGS -> R.string.routine_move_dead_bugs
    Move.QUAD_STRETCH -> R.string.routine_move_quad_stretch
    Move.HIP_FLEXOR_STRETCH -> R.string.routine_move_hip_flexor_stretch
    Move.HAMSTRING_STRETCH -> R.string.routine_move_hamstring_stretch
    Move.FIGURE_FOUR -> R.string.routine_move_figure_four
    Move.CALF_STRETCH -> R.string.routine_move_calf_stretch
    Move.DOORWAY_CHEST -> R.string.routine_move_doorway_chest
    Move.WALL_BICEPS -> R.string.routine_move_wall_biceps
    Move.CROSS_BODY -> R.string.routine_move_cross_body
    Move.HANG -> R.string.routine_move_hang
    Move.CHILDS_POSE -> R.string.routine_move_childs_pose
    Move.OVERHEAD_TRICEPS -> R.string.routine_move_overhead_triceps
    Move.COBRA -> R.string.routine_move_cobra
}

@StringRes
internal fun Move.cueResource(): Int = when (this) {
    Move.BIKE_OR_ROWER -> R.string.routine_move_bike_or_rower_cue
    Move.JACKS_OR_MARCH -> R.string.routine_move_jacks_or_march_cue
    Move.EASY_JOG_OR_WALK -> R.string.routine_move_easy_jog_or_walk_cue
    Move.EASY_CARDIO -> R.string.routine_move_easy_cardio_cue
    Move.LEG_SWINGS -> R.string.routine_move_leg_swings_cue
    Move.BODYWEIGHT_SQUATS -> R.string.routine_move_bodyweight_squats_cue
    Move.HIP_HINGES -> R.string.routine_move_hip_hinges_cue
    Move.HIGH_KNEES -> R.string.routine_move_high_knees_cue
    Move.BUTT_KICKS -> R.string.routine_move_butt_kicks_cue
    Move.REVERSE_LUNGES -> R.string.routine_move_reverse_lunges_cue
    Move.CALF_RAISES -> R.string.routine_move_calf_raises_cue
    Move.Y_T_RAISES -> R.string.routine_move_y_t_raises_cue
    Move.ARM_CIRCLES -> R.string.routine_move_arm_circles_cue
    Move.PUSH_UPS -> R.string.routine_move_push_ups_cue
    Move.ARM_SWINGS -> R.string.routine_move_arm_swings_cue
    Move.SCAP_PUSH_UPS -> R.string.routine_move_scap_push_ups_cue
    Move.UPPER_BACK_ROTATIONS -> R.string.routine_move_upper_back_rotations_cue
    Move.CAT_COW -> R.string.routine_move_cat_cow_cue
    Move.DEAD_BUGS -> R.string.routine_move_dead_bugs_cue
    Move.QUAD_STRETCH -> R.string.routine_move_quad_stretch_cue
    Move.HIP_FLEXOR_STRETCH -> R.string.routine_move_hip_flexor_stretch_cue
    Move.HAMSTRING_STRETCH -> R.string.routine_move_hamstring_stretch_cue
    Move.FIGURE_FOUR -> R.string.routine_move_figure_four_cue
    Move.CALF_STRETCH -> R.string.routine_move_calf_stretch_cue
    Move.DOORWAY_CHEST -> R.string.routine_move_doorway_chest_cue
    Move.WALL_BICEPS -> R.string.routine_move_wall_biceps_cue
    Move.CROSS_BODY -> R.string.routine_move_cross_body_cue
    Move.HANG -> R.string.routine_move_hang_cue
    Move.CHILDS_POSE -> R.string.routine_move_childs_pose_cue
    Move.OVERHEAD_TRICEPS -> R.string.routine_move_overhead_triceps_cue
    Move.COBRA -> R.string.routine_move_cobra_cue
}

@Composable
fun doseText(dose: MoveDose): String = when (dose) {
    is MoveDose.Reps -> pluralStringResource(
        if (dose.perSide) R.plurals.routine_dose_reps_each_side else R.plurals.routine_dose_reps,
        dose.count, dose.count,
    )
    is MoveDose.Time -> if (dose.perSide) {
        stringResource(R.string.routine_dose_time_each_side, formatCardioTime(dose.seconds))
    } else formatCardioTime(dose.seconds)
}

@Composable
fun doseSpoken(dose: MoveDose): String = when (dose) {
    is MoveDose.Reps -> doseText(dose)
    is MoveDose.Time -> {
        val duration = formatDurationWords(LocalContext.current.resources, dose.seconds)
        if (dose.perSide) stringResource(R.string.routine_dose_time_each_side, duration) else duration
    }
}
