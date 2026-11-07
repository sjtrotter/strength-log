package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppHaptics
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.EmptyState
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.components.ScreenStateAction
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.day.receipt.FinishAttempt
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt
import cloud.trotter.log.strength.ui.day.receipt.ReceiptNextTimes
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSetCounts
import cloud.trotter.log.strength.ui.theme.LocalAppPalette
import cloud.trotter.log.strength.ui.theme.dayAccent
import kotlinx.coroutines.launch

/** Hosts the receipt or the workout with its sheets and confirmations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    state: DayUiState,
    actions: DayActions,
    dayEditState: DayEditUiState,
    dayEditActions: DayEditActions,
    receipt: WorkoutReceipt? = null,
    finish: FinishAttempt? = null,
    noteSave: NoteSave? = null,
    onRetryFinish: () -> Unit = {},
    onBackToWorkout: () -> Unit = {},
    onNoteSaveHandled: () -> Unit = {},
    onShareSession: () -> Unit = {},
    onFinishSession: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    removedSets: List<RemovedSet> = emptyList(),
    onUndoRemoveSet: () -> Unit = {},
    nowMillis: () -> Long = System::currentTimeMillis,
    receiptSetCounts: ReceiptSetCounts? = null,
    receiptNextTimes: ReceiptNextTimes? = null,
) {
    // A receipt replaces the workout, including its back handler and auto-sheet
    // effects. No workout nodes or sheet requests survive underneath it.
    if (receipt != null) {
        SessionReceiptScreen(
            receipt = receipt,
            setCounts = receiptSetCounts,
            nextTimes = receiptNextTimes,
            onShare = onShareSession,
            onFinishSession = onFinishSession,
            onSetNote = actions.onSetSessionNote,
            noteSave = noteSave,
            onNoteSaveHandled = onNoteSaveHandled,
        )
        return
    }
    if (finish != null) {
        if (finish.failed) FinishFailedScreen(finish, onRetryFinish, onBackToWorkout)
        else FinishingScreen(finish, state)
        return
    }
    if (dayEditState.planEditing) {
        DayPlanEditor(dayEditState, state.viewDayId, state.loading, dayAccent(state.dayIndex), dayEditActions,
            onBack = { onBack?.invoke() }, onCreateExercise = actions.onCreateExercise)
        state.pendingPlanSwap?.let { PlanSwapConfirmDialog(it, actions.onConfirmPlanSwap, actions.onDismissPlanSwap) }
        return
    }
    val accent = dayAccent(state.dayIndex)
    val hasUnloggedSets = state.runSetCounts?.let { it.remaining > 0 }
        ?: state.exercises.any { card -> card.rows.any { !it.complete } }
    // The sheet stays inside the day feature; its shared ViewModel restores
    // the open editor and prescription draft after process death.
    val showEditSheet = dayEditState.editorOpen
    // Clearing the entire day retains its confirmation; single-set removal offers Undo.
    var confirmingUntickChecks by rememberSaveable { mutableStateOf(false) }
    var confirmingClearChecks by rememberSaveable { mutableStateOf(false) }
    var confirmingEndCardio by rememberSaveable(state.viewDayId) { mutableStateOf(false) }
    var endingCardioStartedAt by rememberSaveable(state.viewDayId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.cardio?.run) {
        if (state.cardio?.run?.complete == true ||
            (endingCardioStartedAt != null && state.cardio?.run?.startedAt != endingCardioStartedAt)) {
            confirmingEndCardio = false
        }
    }
    var confirmingPartialFinish by rememberSaveable { mutableStateOf(false) }
    var noteCardId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Only the newest removal can be undone against the current row indices.
    val openOffer = removedSets.lastOrNull()
    val openSheet = state.openSheet
    val countHostFree = !showEditSheet && openSheet == null &&
        noteCardId == null &&
        !confirmingEndCardio && !confirmingPartialFinish && !confirmingClearChecks && !confirmingUntickChecks && state.pendingPlanSwap == null
    LaunchedEffect(state.timedRep?.requestsSheet, countHostFree) {
        if (state.timedRep?.requestsSheet == true && countHostFree) actions.onOpenTimedRepCount()
    }
    val distanceRuns = state.cardio?.distanceRuns.orEmpty()
    val cardioRequestsSheet = distanceRuns.any { run -> run.drafts.any { !it.saved && !it.requested && !it.dismissed } }
    LaunchedEffect(cardioRequestsSheet, countHostFree, distanceRuns) {
        if (cardioRequestsSheet && countHostFree) actions.onOpenCardioDistance()
    }
    val dayBackProgress = rememberBackGestureProgress(
        enabled = onBack != null &&
            !showEditSheet && openSheet == null && !confirmingEndCardio &&
            noteCardId == null && !confirmingPartialFinish &&
            !confirmingClearChecks && !confirmingUntickChecks && state.pendingPlanSwap == null,
        onBack = { onBack?.invoke() },
    )
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val otherWorkoutMessage = stringResource(R.string.day_swap_other_workout_open)
    LaunchedEffect(dayEditState.otherWorkoutOpenMessage) {
        if (dayEditState.otherWorkoutOpenMessage) {
            scope.launch { snackbarHost.showSnackbar(otherWorkoutMessage) }
            dayEditActions.onMessageShown()
        }
    }
    val removedMessage = stringResource(R.string.day_set_removed)
    val undoLabel = stringResource(R.string.day_undo)
    LaunchedEffect(openOffer, removedSets.size) {
        // Canceling showSnackbar removes its own offer. An expired removal
        // must not dismiss a newer swap.
        if (openOffer != null) snackbarHost.currentSnackbarData?.dismiss()
        if (openOffer != null) {
            val result = snackbarHost.showSnackbar(
                message = removedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoRemoveSet()
        }
    }
    val swappedMessage = state.swapUndo?.let { stringResource(if (it.skippedSlot != null) R.string.day_skipped_lift else R.string.day_swapped_to, it.title) }
    LaunchedEffect(state.swapUndo) {
        if (swappedMessage != null) {
            snackbarHost.currentSnackbarData?.dismiss()
            val result = snackbarHost.showSnackbar(swappedMessage, undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) actions.onUndoWorkoutSwap()
            else actions.onDismissSwapUndo()
        }
    }
    // The keep-screen-on PREFERENCE is a window flag owned by MainActivity; this
    // view flag holds only while an execution clock runs; disposal clears it.
    DisposableEffect(state.executionKeepsScreenOn) {
        view.keepScreenOn = state.executionKeepsScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> actions.onCardioScreenLive(true)
                Lifecycle.Event.ON_STOP -> actions.onCardioScreenLive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            actions.onCardioScreenLive(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            actions.onCardioScreenLive(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .backGesturePreview { dayBackProgress.value },
    ) {
        if (state.loading || !state.hasProgram) {
            if (state.loading) LoadingState(stringResource(R.string.screen_loading)) else NoProgramState(actions.onSetUpProgram)
        } else if (state.exercises.isEmpty() && state.cardio == null && !state.workoutAllLogged) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHost) },
                topBar = {
                    TopAppBar(
                        title = { state.viewDayId?.let { Text(stringResource(R.string.today_day_label, it)) } },
                        navigationIcon = {
                            if (onBack != null) IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.day_back))
                            }
                        },
                        actions = {
                            IconButton(onClick = actions.onOpenDaySheet) {
                                Icon(Icons.AutoMirrored.Filled.List, stringResource(
                                    R.string.workout_exercises, state.viewDayId.orEmpty(), state.doneSets, state.totalSets,
                                ))
                            }
                            if (!state.workoutAllLogged) {
                                TextButton(onClick = {
                                    AppHaptics.perform(view, AppHaptics.Cue.FINISH)
                                    actions.onDone()
                                }, enabled = state.canFinish) {
                                    Text(stringResource(R.string.day_finish_partial_confirm_button))
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    EmptyState(
                        title = stringResource(R.string.screen_no_lifts_title, state.viewDayId.orEmpty()),
                        action = ScreenStateAction(stringResource(R.string.screen_edit_day), { dayEditActions.onEditorOpen(true) }),
                    )
                }
            }
        } else WorkoutSetScreen(
            state = state,
            actions = actions,
            catalog = dayEditState.catalog,
            onExercises = actions.onOpenDaySheet,
            onNote = { noteCardId = it },
            onFinish = {
                if (hasUnloggedSets ||
                    state.cardio?.loggedSeconds == null && state.cardio != null) {
                    confirmingPartialFinish = true
                } else {
                    AppHaptics.perform(view, AppHaptics.Cue.FINISH)
                    actions.onDone()
                }
            },
            snackbarHost = snackbarHost,
            onEditDay = { dayEditActions.onEditorOpen(true) },
            onBack = onBack,
            nowMillis = nowMillis,
        )
    }

    if (confirmingEndCardio) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingEndCardio = false },
            title = { Text(stringResource(R.string.cardio_end_title)) },
            text = { Text(stringResource(R.string.cardio_end_message)) },
            dismissButton = { TextButton(onClick = { confirmingEndCardio = false }) { Text(stringResource(R.string.cardio_keep_going)) } },
            confirmButton = { TextButton(onClick = { confirmingEndCardio = false; actions.onEndCardioBlock() }) { Text(stringResource(R.string.cardio_end_log)) } },
        )
    }
    openSheet?.let { sheet ->
        if (sheet.kind == OpenSheet.Kind.DAY) DaySheet(state, actions,
            onEditDay = { dayEditActions.onEditorOpen(true) },
            onClearChecks = { confirmingUntickChecks = true }, onDismiss = actions.onCloseAllSets,
            onDiscardWorkout = { confirmingClearChecks = true })
        if (sheet.kind == OpenSheet.Kind.CARDIO_SETS) state.cardio?.let {
            WorkoutCardioSheet(it, state.viewDayId.orEmpty(), actions,
                onEnd = { endingCardioStartedAt = it.run?.startedAt; confirmingEndCardio = true },
                startEnabled = state.cardioStartEnabled)
        }
        if (sheet.kind == OpenSheet.Kind.CARDIO_DISTANCE) state.cardio?.distanceRuns?.firstOrNull { it.runId == sheet.cardioRunId }?.let { CardioDistanceSheet(it, sheet.setIndex, actions) }
        state.exercises.firstOrNull { it.programExerciseId == sheet.programExerciseId }?.let { card ->
            when (sheet.kind) {
                OpenSheet.Kind.CARDIO_SETS, OpenSheet.Kind.CARDIO_DISTANCE, OpenSheet.Kind.DAY -> Unit
                OpenSheet.Kind.LIFT -> {
                    val exerciseId = if (sheet.slot == Slot.SS) card.partnerExerciseId else card.exerciseId
                    val entry = exerciseId?.let(dayEditState.catalog::find)
                    LiftSheet(card, sheet.slot, state.workout?.focus, actions,
                        canSwap = entry?.pattern != null,
                        canSkipLift = card.isSuperset || state.exercises.size > 1,
                        skippedPartner = state.skippedPartners[card.programExerciseId],
                        weightSwap = if (sheet.slot == Slot.SS) DayScreenBuilder.weightSwapAffordance(entry, dayEditState.catalog) else card.weightSwap,
                        onWeightSwap = { swap ->
                            actions.onCloseAllSets()
                            val planned = dayEditState.slots.firstOrNull { it.programExerciseId == card.programExerciseId }
                            val plannedId = if (sheet.slot == Slot.SS) planned?.partnerExerciseId else planned?.exerciseId
                            if (exerciseId != plannedId) dayEditActions.onWorkoutSwapHalf(card.position, swap.targetExerciseId, sheet.slot)
                            else if (sheet.slot == Slot.SS) dayEditActions.onSetSuperset(card.position, swap.targetExerciseId)
                            else dayEditActions.onSwap(card.position, swap.targetExerciseId)
                        },
                        onNote = { actions.onCloseAllSets(); noteCardId = card.programExerciseId },
                        onDismiss = actions.onCloseAllSets)
                }
                OpenSheet.Kind.SWAP -> {
                    val slot = dayEditState.slots.firstOrNull { it.programExerciseId == card.programExerciseId }
                        ?.let { planned ->
                            val id = if (sheet.slot == Slot.SS) card.partnerExerciseId else card.exerciseId
                            val entry = id?.let(dayEditState.catalog::find)
                            if (entry != null) planned.copy(exerciseId = entry.id, title = entry.name, pattern = entry.pattern)
                            else dayEditState.performedSwapSlot(planned, sheet.slot)
                        }
                    if (slot?.pattern != null) SlotSwapSheet(slot, slot.pattern, dayEditState, accent,
                        onSwap = { position, id ->
                            if (sheet.slot == Slot.SS) dayEditActions.onSetSuperset(position, id)
                            else dayEditActions.onSwap(position, id)
                        },
                        onWorkoutSwap = { position, id -> dayEditActions.onWorkoutSwapHalf(position, id, sheet.slot) },
                        onDismiss = actions.onCloseAllSets, onCreateExercise = actions.onCreateExercise,
                        half = sheet.slot)
                }
                OpenSheet.Kind.TIMED_REP_COUNT -> state.timedRep?.event?.let { event ->
                    TimedRepCountSheet(card, event, state.dayIndex, actions)
                }
                OpenSheet.Kind.EDITOR -> card.rows.getOrNull(sheet.setIndex)?.let { row ->
                    SetEditSheet(
                        card = card,
                        row = row,
                        unit = state.unit,
                        dayIndex = state.dayIndex,
                        actions = actions,
                        onDismiss = actions.onCloseSetEditor,
                    )
                }
            }
        }
    }

    if (showEditSheet) {
        DayEditSheet(
            state = dayEditState,
            actions = dayEditActions,
            accent = accent,
            onDismiss = { dayEditActions.onEditorOpen(false) },
            onCreateExercise = actions.onCreateExercise,
        )
    }

    if (confirmingPartialFinish) {
        FinishDayConfirmDialog(
            doneSets = state.doneSets,
            totalSets = state.totalSets,
            onlyPartnerSetsOpen = hasUnloggedSets &&
                state.exercises.all { card -> card.rows.all { it.done } } &&
                state.exercises.any { card -> card.rows.any { it.partner?.done == false } },
            cardioTitle = if (!hasUnloggedSets) {
                state.cardio?.takeIf { it.loggedSeconds == null }?.block?.activity?.let { cardioActivityName(it) }
            } else null,
            cardioStarted = state.cardio?.run?.startedAt != null,
            onConfirm = {
                confirmingPartialFinish = false
                AppHaptics.perform(view, AppHaptics.Cue.FINISH)
                actions.onDone()
            },
            onDismiss = { confirmingPartialFinish = false },
        )
    }

    state.pendingPlanSwap?.let { PlanSwapConfirmDialog(it, actions.onConfirmPlanSwap, actions.onDismissPlanSwap) }

    if (confirmingUntickChecks && state.runOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingUntickChecks = false },
            title = { Text(stringResource(R.string.day_sheet_clear_title)) },
            text = { Text(stringResource(R.string.day_sheet_clear_body)) },
            confirmButton = { TextButton(onClick = {
                confirmingUntickChecks = false
                actions.onClearTodayChecks()
            }) { Text(stringResource(R.string.day_sheet_clear_button)) } },
            dismissButton = { TextButton(onClick = { confirmingUntickChecks = false }) {
                Text(stringResource(R.string.day_cancel_button))
            } },
        )
    }

    if (confirmingClearChecks && state.runOpen) {
        ClearChecksConfirmDialog(
            cardioSaved = state.cardio?.loggedSeconds != null,
            onConfirm = {
                confirmingClearChecks = false
                actions.onDiscardWorkout()
            },
            onDismiss = { confirmingClearChecks = false },
        )
    }

    noteCardId?.let { id ->
        val card = state.exercises.firstOrNull { it.programExerciseId == id }
        if (card != null) {
            NoteSheet(card.note, { actions.onSetExerciseNote(id, it) }, { noteCardId = null })
        } else noteCardId = null
    }
}

@Composable
private fun FinishDayConfirmDialog(
    doneSets: Int,
    totalSets: Int,
    onlyPartnerSetsOpen: Boolean,
    cardioTitle: String?,
    cardioStarted: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (cardioTitle != null) stringResource(
                    if (cardioStarted) R.string.day_finish_save_cardio_title else R.string.day_finish_without_cardio_title,
                    cardioTitle,
                )
                else if (onlyPartnerSetsOpen) stringResource(R.string.day_finish_partner_open_confirm_title)
                else stringResource(R.string.day_finish_partial_confirm_title, doneSets, totalSets),
            )
        },
        text = { Text(stringResource(R.string.day_finish_partial_confirm_body)) },
        confirmButton = {
            DialogAction(
                label = stringResource(R.string.day_finish_partial_confirm_button),
                color = LocalAppPalette.current.done,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            DialogAction(
                label = stringResource(R.string.day_finish_partial_dismiss_button),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun ClearChecksConfirmDialog(cardioSaved: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.day_clear_checks_confirm_title)) },
        text = { Text(stringResource(if (cardioSaved) R.string.day_clear_checks_confirm_body_cardio_saved else R.string.day_clear_checks_confirm_body)) },
        confirmButton = {
            DialogAction(
                label = stringResource(R.string.day_clear_checks_confirm_button),
                color = MaterialTheme.colorScheme.error,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            DialogAction(
                label = stringResource(R.string.day_cancel_button),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onDismiss,
            )
        },
    )
}

/** Callbacks the screen forwards to [DayViewModel] — one place, so previews stay trivial. */
data class DayActions(
    val onWeightChange: (Long, String, Int, Double) -> Unit,
    val onRepsChange: (Long, String, Int, Int) -> Unit,
    val onSecondsChange: (Long, String, Int, Int) -> Unit,
    val onToggleDone: (Long, Int, Boolean, String) -> Unit,
    val onAddSet: (Long, Boolean) -> Unit,
    val onRemoveSet: (Long, Int, Boolean) -> Unit,
    val onDiscardWorkout: () -> Unit,
    val onDone: () -> Unit,
    val onCreateExercise: (MovementPattern) -> Unit,
    /** The recovery out of the no-program state: back into the wizard. */
    val onSetUpProgram: () -> Unit,
    val onNextTime: (programExerciseId: Long, slot: String, weightLb: Double) -> Unit = { _, _, _ -> },
    val onStartCardio: () -> Unit = {},
    val onStopCardio: (CardioSetAction) -> Unit = {},
    val onReachCardioTarget: (CardioSetAction) -> Unit = {},
    val onSkipCardioSet: (CardioSetAction) -> Unit = {},
    val onEndCardioBlock: () -> Unit = {},
    val onAddCardioSet: () -> Unit = {},
    val onOpenCardioSets: () -> Unit = {},
    val onOpenCardioDistance: () -> Unit = {},
    val onRequestCardioDistance: (CardioSetAction) -> Unit = {},
    val onCardioDistanceChange: (String) -> Unit = {},
    val onCardioRunDistanceChange: (String) -> Unit = {},
    val onSaveCardioDistance: (CardioSetAction) -> Unit = {},
    val onRetryCardioSave: () -> Unit = {},
    val onCardioScreenLive: (Boolean) -> Unit = {},
    val onAdjustRest: (Int) -> Unit = {},
    val onSkipRest: () -> Unit = {},
    val onSetExerciseNote: (Long, String) -> Unit = { _, _ -> },
    val onSetSessionNote: (String) -> Unit = {},
    val onOpenSetEditor: (Long, Int) -> Unit = { _, _ -> },
    val onCloseSetEditor: () -> Unit = {},
    val onCloseAllSets: () -> Unit = {},
    val onStartTimedRep: (Long, Int) -> Unit = { _, _ -> },
    val onStopTimedRep: () -> Unit = {},
    val onRequestTimedRepCount: () -> Unit = {},
    val onOpenTimedRepCount: () -> Unit = {},
    val onTimedRepDraftChange: (String) -> Unit = {},
    val onLogTimedRepCount: () -> Unit = {},
    val onLogFocusedSet: () -> Unit = {},
    val onUndoLastLog: () -> Unit = {},
    val onSelectWorkoutLift: (String) -> Unit = {},
    val onNextWorkoutLift: () -> Unit = {},
    val onOpenLiftSheet: (Long, String) -> Unit = { _, _ -> },
    val onOpenDaySheet: () -> Unit = {},
    val onSkipLift: (Long, String) -> Unit = { _, _ -> },
    val onRestoreSkipped: (Long, String) -> Unit = { _, _ -> },
    val onSkipNowSet: (Long, String) -> Unit = { _, _ -> },
    val onOpenSwapSheet: (Int, String) -> Unit = { _, _ -> },
    val onUndoWorkoutSwap: () -> Unit = {},
    val onDismissSwapUndo: () -> Unit = {},
    val onClearTodayChecks: () -> Unit = {},
    val onConfirmPlanSwap: () -> Unit = {},
    val onDismissPlanSwap: () -> Unit = {},
)

@Composable
private fun PlanSwapConfirmDialog(pending: PendingPlanSwap, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (pending.doneCount == 1) R.string.day_plan_swap_confirm_one else R.string.day_plan_swap_confirm,
            pending.title, pending.doneCount)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.day_swap_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.day_cancel_button)) } },
    )
}
