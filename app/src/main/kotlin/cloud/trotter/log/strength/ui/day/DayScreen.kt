package cloud.trotter.log.strength.ui.day

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import cloud.trotter.log.strength.ui.theme.CardTitle
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
) {
    val accent = dayAccent(state.dayIndex)
    val onAccent = onDayAccent(state.dayIndex)
    // The sheet stays inside the day feature; its shared ViewModel restores
    // the open editor and prescription draft after process death.
    val showEditSheet = dayEditState.editorOpen
    // The page menu reuses the day editor's slot picker.
    var swapCardSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Clearing the entire day retains its confirmation; single-set removal offers Undo.
    var confirmingClearChecks by rememberSaveable { mutableStateOf(false) }
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
        !confirmingPartialFinish && !confirmingClearChecks && cascadeCeremony == null && sessionReceipt == null
    LaunchedEffect(state.timedRep?.requestsSheet, countHostFree) {
        if (state.timedRep?.requestsSheet == true && countHostFree) actions.onOpenTimedRepCount()
    }
    val dayBackProgress = rememberBackGestureProgress(
        enabled = onBack != null &&
            !showEditSheet && !exercisesOpen && openSheet == null &&
            noteCardId == null && !confirmingPartialFinish &&
            swapCardSlotId == null &&
            !confirmingClearChecks &&
            cascadeCeremony == null &&
            sessionReceipt == null,
        onBack = { onBack?.invoke() },
    )
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val removedMessage = stringResource(R.string.day_set_removed)
    val undoLabel = stringResource(R.string.day_undo)
    LaunchedEffect(openOffer, removedSets.size) {
        snackbarHost.currentSnackbarData?.dismiss()
        if (openOffer != null) {
            val result = snackbarHost.showSnackbar(
                message = removedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoRemoveSet()
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
            // Two different silences (#127): one that ends on its own, and one
            // that only ends when the lifter does something about it.
            if (state.loading) ProgramLoadingState() else NoProgramState(actions.onSetUpProgram)
            return@Box
        }

        key(state.viewDayId) {
            val pages = if (standaloneCardio) state.pages.filter { it.exerciseId == null } else state.pages
            val pagerState = rememberPagerState(
                initialPage = if (standaloneCardio) 0 else state.currentPage,
                pageCount = { pages.size },
            )
            val latestPages by rememberUpdatedState(pages)
            val latestSelect by rememberUpdatedState(actions.onSelectPage)
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    latestPages.getOrNull(page)?.key?.let(latestSelect)
                }
            }
            // The derived page changes only on explicit selection or invalid selection,
            // never on a log once selected; this one-way sync cannot fight the finger.
            LaunchedEffect(state.currentPage) {
                val target = if (standaloneCardio) 0 else state.currentPage
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
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.day_menu_clear_checks)) },
                                        onClick = {
                                            menuOpen = false
                                            confirmingClearChecks = true
                                        },
                                    )
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
                                enabled = state.doneSets > 0,
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                                onClick = {
                                    if (state.doneSets < state.totalSets) {
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
                                    onSwapWeight = dayEditActions.onSwap,
                                    onNote = { noteCardId = card.programExerciseId },
                                    timedRep = state.timedRep,
                                    executionBusy = state.executionBusy,
                                )
                            } else {
                                state.cardio?.let {
                                    CardioPage(
                                        it, accent, onAccent, actions.onStartCardio, actions.onStopCardio,
                                        startEnabled = !state.executionBusy,
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

    openSheet?.let { sheet ->
        state.exercises.firstOrNull { it.programExerciseId == sheet.programExerciseId }?.let { card ->
            when (sheet.kind) {
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

    val swapSlot = dayEditState.slots.firstOrNull { it.programExerciseId == swapCardSlotId }
    if (swapSlot != null && swapSlot.pattern != null) {
        SlotSwapSheet(
            slot = swapSlot,
            pattern = swapSlot.pattern,
            state = dayEditState,
            accent = accent,
            onSwap = dayEditActions.onSwap,
            onDismiss = { swapCardSlotId = null },
            onCreateExercise = actions.onCreateExercise,
        )
    }

    if (confirmingClearChecks) {
        ClearChecksConfirmDialog(
            onConfirm = {
                confirmingClearChecks = false
                actions.onClearChecks()
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

    // The finished session's receipt (#126), over a day screen that has already
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
        title = { Text(stringResource(R.string.day_finish_partial_confirm_title, doneSets, totalSets)) },
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

@Composable
private fun CardioPage(
    cardio: CardioCardState,
    accent: Color,
    onAccent: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
    startEnabled: Boolean = true,
) {
    // Saveable so pager eviction and rotation preserve the disclosure.
    var open by rememberSaveable { mutableStateOf(false) }
    val expandedState = stringResource(R.string.day_expanded_state)
    val collapsedState = stringResource(R.string.day_collapsed_state)
    val executing = cardio.phase != CardioPhase.SUGGESTION
    LaunchedEffect(executing) { open = executing }
    val chevronRotation by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "cardioChevron")
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)
                .then(if (!executing) Modifier.clickable { open = !open } else Modifier)
                .semantics { if (!executing) stateDescription = if (open) expandedState else collapsedState },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.day_cardio_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = CardTitle,
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        text = stringResource(
                            if (cardio.hard) R.string.day_cardio_hard_label else R.string.day_cardio_easy_label,
                            cardio.label,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!executing) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).rotate(chevronRotation).clearAndSetSemantics {},
                    )
                }
            }
            Column(Modifier.animateContentSize(tween(320))) {
                if (open) {
                    Spacer(Modifier.size(10.dp))
                    if (executing) {
                        if (cardio.phase == CardioPhase.OVERRUN) {
                            Text(
                                text = stringResource(R.string.day_cardio_overrun),
                                color = accent,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.day_cardio_step_left,
                                    cardio.currentStepLabel.orEmpty(),
                                    formatCardioTime(cardio.stepSecondsLeft),
                                ),
                                color = accent,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(Modifier.size(5.dp))
                        Text(
                            text = stringResource(R.string.day_cardio_elapsed, formatCardioTime(cardio.elapsedSeconds)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(
                            onClick = onStop,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(stringResource(R.string.day_cardio_stop_button))
                        }
                    } else {
                        Text(
                            text = cardio.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = onStart,
                            enabled = startEnabled,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                        ) {
                            Text(stringResource(R.string.day_cardio_start_button))
                        }
                    }
                }
            }
            if (!executing) {
                cardio.loggedSeconds?.let { seconds ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.day_cardio_logged, formatCardioTime(seconds)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private fun formatCardioTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Callbacks the screen forwards to [DayViewModel] — one place, so previews stay trivial. */
data class DayActions(
    val onSelectDay: (String) -> Unit,
    val onWeightChange: (Long, String, Int, Double) -> Unit,
    val onRepsChange: (Long, String, Int, Int) -> Unit,
    val onSecondsChange: (Long, String, Int, Int) -> Unit,
    val onToggleDone: (Long, Int, Boolean, Boolean) -> Unit,
    val onAddSet: (Long, Boolean) -> Unit,
    val onRemoveSet: (Long, Int, Boolean) -> Unit,
    val onSelectPage: (String) -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit,
    val onClearChecks: () -> Unit,
    val onDone: () -> Unit,
    val onCreateExercise: (MovementPattern) -> Unit,
    /** The recovery out of the no-program state (#127): back into the wizard. */
    val onSetUpProgram: () -> Unit,
    val onStartCardio: () -> Unit = {},
    val onStopCardio: () -> Unit = {},
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
