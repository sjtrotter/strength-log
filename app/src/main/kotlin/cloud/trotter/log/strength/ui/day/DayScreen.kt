package cloud.trotter.log.strength.ui.day

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppHaptics
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.LocalAppPalette
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import kotlinx.coroutines.launch

/** Cards scroll internally when needed. TopAppBar has no scrollBehavior or nested-scroll connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    state: DayUiState,
    actions: DayActions,
    dayEditState: DayEditUiState,
    dayEditActions: DayEditActions,
    cascadeCeremony: CascadeCeremony? = null,
    onDismissCascade: () -> Unit = {},
    sessionReceipt: SessionReceipt? = null,
    onShareSession: () -> Unit = {},
    onFinishSession: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    removedSets: List<RemovedSet> = emptyList(),
    onUndoRemoveSet: () -> Unit = {},
    standaloneCardio: Boolean = false,
    setScreenContent: (@Composable (() -> Unit, () -> Unit, SnackbarHostState) -> Unit)? = null,
) {
    val accent = dayAccent(state.dayIndex)
    val onAccent = onDayAccent(state.dayIndex)
    // The sheet stays inside the day feature; its shared ViewModel restores
    // the open editor and prescription draft after process death.
    val showEditSheet = dayEditState.editorOpen
    val currentDayEditState = rememberUpdatedState(dayEditState)
    // The page menu reuses the day editor's slot picker.
    var swapCardSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Clearing the entire day retains its confirmation; single-set removal offers Undo.
    var confirmingUntickChecks by rememberSaveable { mutableStateOf(false) }
    var confirmingClearChecks by rememberSaveable { mutableStateOf(false) }
    var confirmingEndCardio by rememberSaveable(state.viewDayId) { mutableStateOf(false) }
    var endingCardioStartedAt by rememberSaveable(state.viewDayId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.cardio?.run, sessionReceipt) {
        if (state.cardio?.run?.complete == true || sessionReceipt != null ||
            (endingCardioStartedAt != null && state.cardio?.run?.startedAt != endingCardioStartedAt)) {
            confirmingEndCardio = false
        }
    }
    var confirmingPartialFinish by rememberSaveable { mutableStateOf(false) }
    var noteCardId by rememberSaveable { mutableStateOf<Long?>(null) }
    var receiptNoteOpen by rememberSaveable { mutableStateOf(false) }
    // Only the newest removal can be undone against the current row indices.
    val openOffer = removedSets.lastOrNull()
    // A page offers Swap only when its slot resolves to a pattern to rank
    // substitutes against — the same rule that disables Swap in the sheet.
    val swappableSlotIds = remember(dayEditState.slots) {
        dayEditState.slots.filter { it.pattern != null }.mapTo(mutableSetOf()) { it.programExerciseId }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var exercisesOpen by rememberSaveable(state.viewDayId) { mutableStateOf(false) }
    val openSheet = state.openSheet
    val countHostFree = !showEditSheet && !exercisesOpen && openSheet == null &&
        noteCardId == null && !receiptNoteOpen && swapCardSlotId == null &&
        !confirmingEndCardio && !confirmingPartialFinish && !confirmingClearChecks && !confirmingUntickChecks && state.pendingPlanSwap == null && cascadeCeremony == null && sessionReceipt == null
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
            !showEditSheet && !exercisesOpen && openSheet == null && !confirmingEndCardio &&
            noteCardId == null && !confirmingPartialFinish &&
            swapCardSlotId == null &&
            !confirmingClearChecks && !confirmingUntickChecks && state.pendingPlanSwap == null &&
            cascadeCeremony == null &&
            sessionReceipt == null,
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
        // must not dismiss a newer swap; keep the pager's behavior unchanged.
        if (openOffer != null || state.workout == null) snackbarHost.currentSnackbarData?.dismiss()
        if (openOffer != null) {
            val result = snackbarHost.showSnackbar(
                message = removedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoRemoveSet()
        }
    }
    val swappedMessage = state.swapUndo?.let { stringResource(if (it.dropKeep != null) R.string.day_only_this_workout else R.string.day_swapped_to, it.title) }
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
        if (!state.hasProgram) {
            // Two different silences: one that ends on its own, and one that
            // only ends when the lifter does something about it.
            if (state.loading) ProgramLoadingState() else NoProgramState(actions.onSetUpProgram)
            return@Box
        }

        if (setScreenContent != null) {
            setScreenContent(
                actions.onOpenDaySheet,
                {
                    if (state.exercises.any { card -> card.rows.any { !it.complete } }) {
                        confirmingPartialFinish = true
                    } else {
                        AppHaptics.perform(view, AppHaptics.Cue.FINISH)
                        actions.onDone()
                    }
                },
                snackbarHost,
            )
        } else key(state.viewDayId) {
            val pages = if (standaloneCardio) state.pages.filter { it.exerciseId == null } else state.pages
            val selectedKey = (state.workout?.shown as? WorkoutSelection.Shown.Group)?.key
            val selectedPage = if (state.workout != null) pages.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0) else state.currentPage
            val pagerState = rememberPagerState(
                initialPage = if (standaloneCardio) 0 else selectedPage,
                pageCount = { pages.size },
            )
            val latestPages by rememberUpdatedState(pages)
            val latestSelect by rememberUpdatedState(if (state.workout != null) actions.onSelectWorkoutLift else actions.onSelectPage)
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    latestPages.getOrNull(page)?.key?.let(latestSelect)
                }
            }
            // The derived page changes only on explicit selection or invalid selection,
            // never on a log once selected; this one-way sync cannot fight the finger.
            LaunchedEffect(selectedPage) {
                val target = if (standaloneCardio) 0 else selectedPage
                if (pages.isNotEmpty() && target != pagerState.currentPage && !pagerState.isScrollInProgress) {
                    pagerState.scrollToPage(target)
                }
            }
            val currentPage by remember { derivedStateOf { pagerState.currentPage } }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHost) },
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { onBack?.invoke() }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.day_back))
                            }
                        },
                        title = {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DayIndex(state.dayIndex, state.viewDayId.orEmpty(), DayIndexSize.Small)
                                    Text(
                                        text = state.dayTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = stringResource(R.string.day_count_of, state.doneSets, state.totalSets),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                state.openRunElsewhere?.let { title ->
                                    Text(
                                        text = stringResource(R.string.day_run_open_elsewhere, title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (state.retiredTracks.isNotEmpty()) {
                                    val description = state.retiredTracks.map { line ->
                                        pluralStringResource(R.plurals.day_retired_track, line.doneCount, line.name, line.doneCount)
                                    }.joinToString(", ")
                                    val accessibleDescription = stringResource(R.string.day_retired_tracks, description)
                                    Text(
                                        text = stringResource(R.string.day_retired_tracks, state.retiredTracks.joinToString(", ") { it.name }),
                                        modifier = Modifier.semantics { contentDescription = accessibleDescription },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.day_menu))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!standaloneCardio) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.day_menu_edit_day)) },
                                        onClick = {
                                            menuOpen = false
                                            dayEditActions.onEditorOpen(true)
                                        },
                                    )
                                    if (state.runOpen) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.day_menu_clear_checks)) },
                                            onClick = {
                                                menuOpen = false
                                                confirmingClearChecks = true
                                            },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.day_menu_keep_screen_on)) },
                                    modifier = Modifier.semantics {
                                        toggleableState = if (state.keepScreenOn) ToggleableState.On
                                        else ToggleableState.Off
                                    },
                                    trailingIcon = { Checkbox(state.keepScreenOn, onCheckedChange = null) },
                                    onClick = {
                                        actions.onKeepScreenOnChange(!state.keepScreenOn)
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.day_menu_rest_timer)) },
                                    onClick = {
                                        menuOpen = false
                                        actions.onOpenRestTimer()
                                    },
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!standaloneCardio && pages.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            state.rest?.let { RestPill(it, actions.onAdjustRest, actions.onSkipRest) }
                            Button(
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                enabled = state.canFinish,
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                                onClick = {
                                    if (state.exercises.any { card -> card.rows.any { !it.complete } }) {
                                        confirmingPartialFinish = true
                                    } else {
                                        AppHaptics.perform(view, AppHaptics.Cue.FINISH)
                                        actions.onDone()
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.day_finish_button, state.viewDayId.orEmpty()))
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pages.forEachIndexed { index, page ->
                            val status = when {
                                page.allDone -> stringResource(R.string.day_segment_done)
                                page.done > 0 -> stringResource(R.string.day_sets_done, page.done, page.total)
                                else -> stringResource(R.string.day_segment_not_started)
                            }
                            Box(
                                modifier = Modifier.weight(1f).height(48.dp)
                                    .semantics { contentDescription = "${page.title}, $status" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(4.dp).background(
                                        color = when {
                                            page.allDone -> LocalAppPalette.current.done
                                            index == currentPage -> accent
                                            else -> MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                                )
                            }
                        }
                    }
                    if (pages.isNotEmpty()) Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { exercisesOpen = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.day_lift_of, currentPage + 1, pages.size))
                        }
                        Spacer(Modifier.weight(1f))
                        pages.getOrNull(currentPage + 1)?.let {
                            Text(
                                text = stringResource(R.string.day_next_exercise, it.title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(2f),
                            )
                        }
                    }
                    if (pages.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.day_no_exercises), style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = { dayEditActions.onEditorOpen(true) }) {
                                    Text(stringResource(R.string.day_menu_edit_day))
                                }
                            }
                        }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            key = { pages[it].key },
                            beyondViewportPageCount = 1,
                            contentPadding = PaddingValues(start = 20.dp, end = 40.dp),
                            pageSpacing = 12.dp,
                            flingBehavior = PagerDefaults.flingBehavior(pagerState),
                            verticalAlignment = Alignment.Top,
                        ) { index ->
                            val page = pages[index]
                            val card = state.exercises.firstOrNull { it.programExerciseId == page.exerciseId }
                            if (card != null) {
                                ExercisePage(
                                    card = card,
                                    dayIndex = state.dayIndex,
                                    resting = state.rest != null,
                                    actions = actions,
                                    nextTitle = pages.getOrNull(index + 1)?.title,
                                    onNext = { scope.launch { pagerState.animateScrollToPage(index + 1) } },
                                    onEdit = { actions.onOpenSetEditor(card.programExerciseId, it) },
                                    onAllSets = { actions.onOpenAllSets(card.programExerciseId) },
                                    canSwap = card.programExerciseId in swappableSlotIds,
                                    onSwap = { swapCardSlotId = card.programExerciseId },
                                    onSwapWeight = remember(card, dayEditActions) {
                                        { _, exerciseId -> dayEditActions.swapWeight(card, currentDayEditState.value, exerciseId) }
                                    },
                                    onNote = { noteCardId = card.programExerciseId },
                                    timedRep = state.timedRep,
                                    executionBusy = state.executionBusy,
                                )
                            } else {
                                state.cardio?.let {
                                    CardioPage(
                                        it, state.viewDayId.orEmpty(), accent, actions,
                                        startEnabled = !state.executionBusy || it.run?.let { run -> !run.running && !run.complete } == true,
                                        onEnd = { endingCardioStartedAt = it.run?.startedAt; confirmingEndCardio = true },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (exercisesOpen) {
                ExercisesSheet(
                    pages = pages,
                    onSelect = { index ->
                        exercisesOpen = false
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    onDismiss = { exercisesOpen = false },
                )
            }
        }
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
        if (sheet.kind == OpenSheet.Kind.CARDIO_SETS) state.cardio?.let { CardioSetsSheet(it, actions) }
        if (sheet.kind == OpenSheet.Kind.CARDIO_DISTANCE) state.cardio?.distanceRuns?.firstOrNull { it.runId == sheet.cardioRunId }?.let { CardioDistanceSheet(it, sheet.setIndex, actions) }
        state.exercises.firstOrNull { it.programExerciseId == sheet.programExerciseId }?.let { card ->
            when (sheet.kind) {
                OpenSheet.Kind.CARDIO_SETS, OpenSheet.Kind.CARDIO_DISTANCE, OpenSheet.Kind.DAY -> Unit
                OpenSheet.Kind.LIFT -> {
                    val exerciseId = if (sheet.slot == Slot.SS) card.partnerExerciseId else card.exerciseId
                    val entry = exerciseId?.let(dayEditState.catalog::find)
                    LiftSheet(card, sheet.slot, state.workout?.focus, actions,
                        canSwap = entry?.pattern != null,
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
                        half = sheet.slot, onOnly = dayEditActions.onOnly)
                }
                OpenSheet.Kind.ALL_SETS -> AllSetsSheet(
                    card = card,
                    actions = actions,
                    onEdit = { index -> actions.onOpenSetEditor(card.programExerciseId, index) },
                    onDismiss = actions.onCloseAllSets,
                )
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
            onConfirm = {
                confirmingPartialFinish = false
                AppHaptics.perform(view, AppHaptics.Cue.FINISH)
                actions.onDone()
            },
            onDismiss = { confirmingPartialFinish = false },
        )
    }

    val swapSlot = dayEditState.slots.firstOrNull { it.programExerciseId == swapCardSlotId }?.let { planned ->
        val card = state.exercises.firstOrNull { it.programExerciseId == planned.programExerciseId }
        val entry = card?.exerciseId?.let(dayEditState.catalog::find)
        if (card == null || entry == null) planned else planned.copy(
            exerciseId = card.exerciseId, title = card.title, pattern = entry.pattern,
        )
    }
    if (swapSlot != null && swapSlot.pattern != null) {
        SlotSwapSheet(
            slot = swapSlot,
            pattern = swapSlot.pattern,
            state = dayEditState,
            accent = accent,
            onSwap = dayEditActions.onSwap,
            onWorkoutSwap = dayEditActions.onWorkoutSwap,
            onDismiss = { swapCardSlotId = null },
            onCreateExercise = actions.onCreateExercise,
        )
    }

    state.pendingPlanSwap?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = actions.onDismissPlanSwap,
            title = { Text(stringResource(if (pending.doneCount == 1) R.string.day_plan_swap_confirm_one else R.string.day_plan_swap_confirm,
                pending.title, pending.doneCount)) },
            confirmButton = { TextButton(onClick = actions.onConfirmPlanSwap) { Text(stringResource(R.string.day_swap_apply)) } },
            dismissButton = { TextButton(onClick = actions.onDismissPlanSwap) { Text(stringResource(R.string.day_cancel_button)) } },
        )
    }

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

    if (receiptNoteOpen) {
        NoteSheet(sessionReceipt?.note.orEmpty(), actions.onSetSessionNote) { receiptNoteOpen = false }
    }

    // The finished session's receipt, over a day screen that has already
    // advanced — but only once the cascade has been read. One DONE can raise
    // both, and the order they arrive in is a composition rule, not a drawing
    // one: a receipt merely painted *under* the scrim is still in the semantics
    // tree, so TalkBack could reach BACK TO TODAY through the celebration and
    // pop the route out from under it. Gating on the cascade keeps the
    // experienced order — the news the session made, then the ledger of it,
    // then the way out — and keeps it true after a rotation restores both.
    var renderedReceipt by remember { mutableStateOf<SessionReceipt?>(null) }
    val receiptVisible = cascadeCeremony == null && sessionReceipt != null
    if (receiptVisible) renderedReceipt = sessionReceipt
    val receiptVisibility = remember { MutableTransitionState(false) }
    receiptVisibility.targetState = receiptVisible
    val receiptBackProgress = rememberBackGestureProgress(enabled = receiptVisible, onBack = onFinishSession)
    AnimatedVisibility(visibleState = receiptVisibility, enter = fadeIn(tween(260)), exit = fadeOut(tween(160))) {
        renderedReceipt?.let {
            SessionReceiptScrim(
                receipt = it,
                onShare = onShareSession,
                onFinish = onFinishSession,
                onEditNote = { receiptNoteOpen = true },
                modifier = Modifier.backGesturePreview { receiptBackProgress.value },
            )
        }
    }

    // Above everything, including the edit sheet: the cascade only ever arrives
    // straight off a DONE, when nothing else is open (journal brief §2).
    var renderedCascade by remember { mutableStateOf<CascadeCeremony?>(null) }
    if (cascadeCeremony != null) renderedCascade = cascadeCeremony
    val cascadeVisible = cascadeCeremony != null
    val cascadeVisibility = remember { MutableTransitionState(false) }
    cascadeVisibility.targetState = cascadeVisible
    val cascadeBackProgress = rememberBackGestureProgress(enabled = cascadeVisible, onBack = onDismissCascade)
    AnimatedVisibility(visibleState = cascadeVisibility, enter = fadeIn(tween(260)), exit = fadeOut(tween(160))) {
        renderedCascade?.let {
            CascadeScrim(it, onDismissCascade, Modifier.backGesturePreview { cascadeBackProgress.value })
        }
    }
}

@Composable
internal fun WeightSwapConfirmDialog(swap: WeightSwapAffordance, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.day_weight_swap_confirm_title, swap.targetName)) },
        text = { Text(stringResource(R.string.day_weight_swap_confirm_body)) },
        confirmButton = {
            DialogAction(
                label = stringResource(R.string.day_weight_swap_confirm_button),
                color = LocalAppPalette.current.done,
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

@Composable
private fun FinishDayConfirmDialog(
    doneSets: Int,
    totalSets: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (doneSets == totalSets) stringResource(R.string.day_finish_partner_open_confirm_title)
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
private fun ClearChecksConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.day_clear_checks_confirm_title)) },
        text = { Text(stringResource(R.string.day_clear_checks_confirm_body)) },
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
    val onSelectDay: (DaySelection) -> Unit,
    val onWeightChange: (Long, String, Int, Double) -> Unit,
    val onRepsChange: (Long, String, Int, Int) -> Unit,
    val onSecondsChange: (Long, String, Int, Int) -> Unit,
    val onToggleDone: (Long, Int, Boolean, String) -> Unit,
    val onAddSet: (Long, Boolean) -> Unit,
    val onRemoveSet: (Long, Int, Boolean) -> Unit,
    val onSelectPage: (String) -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit,
    val onDiscardWorkout: () -> Unit,
    val onDone: () -> Unit,
    val onCreateExercise: (MovementPattern) -> Unit,
    /** The recovery out of the no-program state: back into the wizard. */
    val onSetUpProgram: () -> Unit,
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
    val onSaveCardioDistance: (CardioSetAction) -> Unit = {},
    val onRetryCardioSave: () -> Unit = {},
    val onCardioScreenLive: (Boolean) -> Unit = {},
    val onAdjustRest: (Int) -> Unit = {},
    val onSkipRest: () -> Unit = {},
    val onSetExerciseNote: (Long, String) -> Unit = { _, _ -> },
    val onSetSessionNote: (String) -> Unit = {},
    val onOpenRestTimer: () -> Unit = {},
    val onOpenSetEditor: (Long, Int) -> Unit = { _, _ -> },
    val onCloseSetEditor: () -> Unit = {},
    val onOpenAllSets: (Long) -> Unit = {},
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
    val onOpenSwapSheet: (Int, String) -> Unit = { _, _ -> },
    val onUndoWorkoutSwap: () -> Unit = {},
    val onDismissSwapUndo: () -> Unit = {},
    val onClearTodayChecks: () -> Unit = {},
    val onConfirmPlanSwap: () -> Unit = {},
    val onDismissPlanSwap: () -> Unit = {},
)

@Composable
private fun RestPill(state: RestUiState, onAdjust: (Int) -> Unit, onSkip: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val time = if (state.over) stringResource(R.string.rest_over) else formatCardioTime(state.remainingSeconds)
    val description = stringResource(R.string.rest_adjust, time)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
                ) {
                    Text(time, style = DisplayXl.copy(fontSize = 24.sp, fontFeatureSettings = "tnum"))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rest_minus_15)) },
                        onClick = {
                            menuOpen = false
                            onAdjust(-15)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rest_plus_15)) },
                        onClick = {
                            menuOpen = false
                            onAdjust(15)
                        },
                    )
                }
            }
            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.rest_skip))
            }
        }
    }
}
