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


internal fun workoutLiftSheetFixture(): DayUiState {
    val state = workoutSetSingleFixture()
    val weights = listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0)
    val kinds = listOf(SetKind.RAMP, SetKind.RAMP, SetKind.RAMP, SetKind.RAMP, SetKind.TOP, SetKind.BACKOFF)
    val labels = listOf("R1", "R2", "R3", "R4", "TOP", "B/O")
    return state.copy(dayTitle = "Squat focus", runOpen = true, exercises = state.exercises.map { card ->
        card.copy(lastTimeDisplay = "235 × 5", lastTimeDate = "Oct 21", rows = card.rows.mapIndexed { index, row ->
            row.copy(weightDisplay = weights[index], kind = kinds[index], kindLabel = labels[index],
                isTop = index == 4, rampIndex = if (index < 4) index + 1 else 0,
                reps = when (index) { 3 -> 3; 5 -> 8; else -> 5 })
        })
    })
}

internal fun workoutDaySheetFixture(): DayUiState {
    val state = workoutSetSupersetFixture()
    val single = workoutLiftSheetFixture().exercises.single()
    val squat = single.copy(programExerciseId = 3L, position = 0,
        rows = single.rows.map { it.copy(done = it.index == 0) })
    val bench = single.copy(programExerciseId = 2L, position = 1,
        title = "Barbell Bench Press", exerciseId = "bb_bench",
        rows = single.rows.map { it.copy(done = false) })
    val plank = single.copy(programExerciseId = 4L, position = 3,
        title = "Plank", exerciseId = "plank", isMain = false, tracking = TrackingType.TIMED,
        rows = List(3) { SetRowState(it, "${it + 1}", false, 0.0, 0, false, seconds = 45) })
    return state.copy(dayTitle = "Squat focus", runOpen = true,
        exercises = listOf(squat, bench, state.exercises.single().copy(position = 2), plank),
        cardio = CardioCardState("Zone 2", "", false))
}
