package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.Composable
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.SetKind

@Composable
internal fun WorkoutSetHost(
    state: DayUiState,
    actions: DayActions = pagerActions(),
    dayEditState: DayEditUiState = DayEditUiState(),
    dayEditActions: DayEditActions = pagerEditActions(),
    removedSets: List<RemovedSet> = emptyList(),
    onUndoRemoveSet: () -> Unit = {},
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    DayScreen(state, actions, dayEditState, dayEditActions,
        removedSets = removedSets, onUndoRemoveSet = onUndoRemoveSet,
        setScreenContent = if (state.usesWorkoutSetScreen) {
            { onExercises, onFinish, snackbarHost ->
                WorkoutSetScreen(state, actions, dayEditState.catalog, onExercises, onFinish, snackbarHost, nowMillis = nowMillis)
            }
        } else null,
    )
}

internal fun workoutSetSingleFixture(): DayUiState {
    val rows = List(6) { index -> SetRowState(index, "R${index + 1}", false, 190.0, 5, index < 2, kind = SetKind.RAMP, rampIndex = index + 1) }
    val card = ExerciseCardState(
        programExerciseId = 1L, position = 0, title = "Barbell Back Squat", exerciseId = "bb_back_squat",
        isMain = true, isSuperset = false, hasWarmupHint = false, headerTarget = HeaderTarget.Goal("235"), perHand = false,
        equipment = listOf(Equipment.BARBELL), allDone = false, rows = rows, plateLine = "45 + 25 + 2.5",
    )
    return DayUiState(hasProgram = true, viewDayId = "A", exercises = listOf(card), workout = WorkoutState(
        WorkoutSelection.Shown.Group("exercise:1", false), WorkoutFocus(1L, Slot.MAIN, 2), false, null, null,
    ))
}

internal fun workoutSetSupersetFixture(): DayUiState {
    val state = workoutSetSingleFixture()
    val card = state.exercises.single().copy(
        title = "DB Bench Press", exerciseId = "db_bench", isSuperset = true, partnerExerciseId = "cable_row", partnerTitle = "Seated Cable Row", partnerTracking = TrackingType.WEIGHTED,
        plateLine = null, rows = List(3) { index -> SetRowState(index, "${index + 1}", false, 80.0, 10, index == 0,
            partner = PartnerRowState(120.0, 10, done = index == 0)) },
    )
    return state.copy(exercises = listOf(card), workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 1)))
}

