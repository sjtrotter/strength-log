package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.Composable
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.PhoneRest

@Composable
internal fun WorkoutSetHost(
    state: DayUiState,
    actions: DayActions = workoutActions(),
    dayEditState: DayEditUiState = DayEditUiState(),
    dayEditActions: DayEditActions = workoutEditActions(),
    removedSets: List<RemovedSet> = emptyList(),
    onUndoRemoveSet: () -> Unit = {},
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    DayScreen(state, actions, dayEditState, dayEditActions,
        removedSets = removedSets, onUndoRemoveSet = onUndoRemoveSet,
        nowMillis = nowMillis,
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

internal fun workoutSetTimedRepFixture(): DayUiState {
    val state = workoutSetSingleFixture()
    val card = state.exercises.single().copy(
        title = "Push-Up", exerciseId = "pushup", tracking = TrackingType.REPS, plateLine = null,
        timedRepDurationSeconds = 40, timedRepAim = 20, headerTarget = HeaderTarget.Prescribed("40 s"),
        rows = List(2) { SetRowState(it, "${it + 1}", false, 0.0, 20, false) },
    )
    return state.copy(exercises = listOf(card), workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 0)))
}

internal fun workoutSetTimedRepRunningFixture(awaiting: Boolean = false, index: Int = 0): DayUiState {
    val state = workoutSetTimedRepFixture()
    return state.copy(executionBusy = true, timedRep = TimedRepState(
        TimedRepEvent("A", 1L, "pushup", index, 40, 1_000L, 1_000L, 1,
            awaitingCount = awaiting, sheetDismissed = awaiting), if (awaiting) 0 else 27,
    ))
}

internal fun workoutSetCardioFixture(): DayUiState {
    val state = workoutSetSingleFixture()
    return state.copy(cardio = cardioFixture(),
        exercises = state.exercises.map { card -> card.copy(allDone = true, rows = card.rows.map { it.copy(done = true) }) },
        workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", false), focus = null))
}

internal fun workoutSetCardioRunningFixture(): DayUiState = workoutSetCardioFixture().copy(
    cardio = runningCardioFixture(), cardioRunning = true, executionBusy = true,
)

internal fun workoutSetCardioDistanceFixture(): DayUiState {
    val state = workoutSetCardioRunningFixture()
    val cardio = requireNotNull(state.cardio)
    val run = requireNotNull(cardio.run)
    val block = cloud.trotter.log.strength.domain.model.CardioBlock(
        cloud.trotter.log.strength.domain.model.CardioActivity.ROAD_RUN,
        listOf(cloud.trotter.log.strength.domain.model.CardioSetSpec(
            cloud.trotter.log.strength.domain.model.CardioRole.WORK,
            cloud.trotter.log.strength.domain.model.Measure.Distance(400),
            cloud.trotter.log.strength.domain.model.Measure.Time(105),
        )),
    )
    val json = cloud.trotter.log.strength.data.serialization.CardioBlockJson.encode(block)
    return state.copy(cardio = cardio.copy(block = block, elapsedSeconds = 27,
        run = run.copy(sourceBlockJson = json, blockJson = json)))
}

internal fun workoutSetCardioRecoveryFixture(): DayUiState {
    val state = workoutSetCardioRunningFixture()
    val cardio = requireNotNull(state.cardio)
    val run = requireNotNull(cardio.run)
    val block = requireNotNull(cardio.block).let { it.copy(sets = it.sets +
        cloud.trotter.log.strength.domain.model.CardioSetSpec(
            cloud.trotter.log.strength.domain.model.CardioRole.RECOVERY,
            cloud.trotter.log.strength.domain.model.Measure.Time(60))) }
    val json = cloud.trotter.log.strength.data.serialization.CardioBlockJson.encode(block)
    return state.copy(cardio = cardio.copy(block = block, stepSecondsLeft = 42, elapsedSeconds = 18,
        run = run.copy(sourceBlockJson = json, blockJson = json,
            results = listOf(SavedCardioResult("FINISHED", 300)), wallStart = 301_000L)))
}

internal fun workoutSetCardioDoneFixture(): DayUiState {
    val state = workoutSetCardioFixture()
    val cardio = requireNotNull(state.cardio)
    val spec = requireNotNull(cardio.block).sets.single()
    return state.copy(cardio = cardio.copy(loggedSeconds = 300, loggedSets = listOf(
        cloud.trotter.log.strength.domain.model.CardioSetResult(spec,
            cloud.trotter.log.strength.domain.model.CardioSetStatus.FINISHED, 300))),
        workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", true), completedView = true))
}

internal fun workoutSetEmptyFixture(): DayUiState = workoutSetSingleFixture().copy(exercises = emptyList(),
    workout = WorkoutState(WorkoutSelection.Shown.Empty, null, false, null, null))

internal fun workoutDayFixture(): DayUiState = DayUiState(
    hasProgram = true, viewDayId = "D", dayIndex = 3, dayTitle = "Full body",
    exercises = List(3) { page ->
        val rows = List(3) { index -> SetRowState(index, "R${index + 1}", false, 85.0 + index * 10, 5,
            done = page == 0 || page == 1 && index == 0, kind = SetKind.RAMP, rampIndex = index + 1) }
        val focus = DayScreenBuilder.focusIndex(rows)
        ExerciseCardState(programExerciseId = page + 1L, position = page, title = "Lift ${page + 1}",
            isMain = true, isSuperset = false, hasWarmupHint = false, headerTarget = HeaderTarget.Goal("120"), perHand = false,
            allDone = page == 0, rows = rows, focusIndex = focus,
            summaryLine = "85×5, 95×5, 105×5")
    },
).withWorkoutSelection()

internal fun workoutActions() = DayActions(
    onWeightChange = { _, _, _, _ -> }, onRepsChange = { _, _, _, _ -> },
    onSecondsChange = { _, _, _, _ -> }, onToggleDone = { _, _, _, _ -> }, onAddSet = { _, _ -> },
    onRemoveSet = { _, _, _ -> }, onDiscardWorkout = {},
    onDone = {}, onCreateExercise = {}, onSetUpProgram = {},
)

internal fun workoutEditActions() = DayEditActions(onSwap = { _, _ -> }, onAdd = {}, onRemove = {},
    onSetSuperset = { _, _ -> }, onRemoveSuperset = {}, onResetToTemplate = {})

internal fun workoutRest() = RestUiState(
    rest = PhoneRest(deadlineEpochMillis = 90_000L, totalSeconds = 90, nextSetLabel = "Ramp set 2"),
    remainingSeconds = 90,
    remainingFraction = 1f,
    over = false,
)

/** Resolve a snapshot just as the ViewModel resolves committed rows for the screen. */
internal fun DayUiState.withWorkoutSelection(key: String? = selectedPageKey): DayUiState {
    val shown = WorkoutSelection.shown(pages.map { WorkoutSelection.Group(it.key, it.allDone) }, key)
    val group = shown as? WorkoutSelection.Shown.Group
    val card = exercises.firstOrNull { "exercise:${it.programExerciseId}" == group?.key }
    val row = card?.rows?.firstOrNull { !it.complete }
    return copy(workout = WorkoutState(shown,
        if (card != null && row != null) WorkoutFocus(card.programExerciseId, row.nextSlot, row.index) else null,
        group?.complete == true, null, null))
}
