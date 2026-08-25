package cloud.trotter.log.strength.ui.day

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.AppHaptics
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
import cloud.trotter.log.strength.ui.components.SetRow
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.components.pressableSelectable
import cloud.trotter.log.strength.ui.components.pressableToggleable
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.AppIcons
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.CardTitle
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.SummaryLine
import cloud.trotter.log.strength.ui.theme.Surface
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TabLetter
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.accentBorder
import cloud.trotter.log.strength.ui.theme.accentSoft
import cloud.trotter.log.strength.ui.theme.chromeVerticalPadding
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import cloud.trotter.log.strength.ui.theme.readableWidth
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.text.resolve
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The day screen (spec §8.2). Stateless in the Compose sense: it renders a
 * [DayUiState] and forwards every intent to [DayViewModel]; all logic lives there
 * and in [DayScreenBuilder]. Styling comes entirely from the design system
 * (AppCard/SetRow/dayAccent, spec §8.5, design-pass restyle per
 * docs/design-handoff — visual QA is against `day_screen_reference.html`).
 */
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
    val soft = accentSoft(state.dayIndex)
    // The day-edit sheet (#11) is not a nav route (brief D1): it lives entirely
    // as local UI state here, sharing this screen's DayViewModel-derived data.
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    // The slot whose ⇄ chip was tapped (#122) — the same swap picker the sheet
    // hosts, opened straight from the card instead of through two pages.
    var swapCardSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Rare enough to be worth a question (#124) — unlike the × on a set row,
    // which is frequent enough to be worth an undo instead.
    var confirmingClearChecks by rememberSaveable { mutableStateOf(false) }
    var confirmingPartialFinish by rememberSaveable { mutableStateOf(false) }
    var noteCardId by rememberSaveable { mutableStateOf<Long?>(null) }
    var receiptNoteOpen by rememberSaveable { mutableStateOf(false) }
    // Only the newest removal is on offer: an older entry's index describes a
    // list that no longer exists, so drawing its line would point at the wrong
    // gap. Taking this one reveals the next (see DayViewModel.removedSets).
    val openOffer = removedSets.lastOrNull()
    // A card offers ⇄ only when its slot resolves to a pattern to rank
    // substitutes against — the same rule that disables Swap in the sheet.
    val swappableSlotIds = remember(dayEditState.slots) {
        dayEditState.slots.filter { it.pattern != null }.mapTo(mutableSetOf()) { it.programExerciseId }
    }
    val dayBackProgress = rememberBackGestureProgress(
        enabled = onBack != null &&
            !showEditSheet &&
            swapCardSlotId == null &&
            !confirmingClearChecks &&
            cascadeCeremony == null &&
            sessionReceipt == null,
        onBack = { onBack?.invoke() },
    )
    val view = LocalView.current
    val accessibilityManager = LocalAccessibilityManager.current
    val listState = rememberLazyListState()
    val topBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val cardioRunning = state.cardio?.phase == CardioPhase.EXECUTING || state.cardio?.phase == CardioPhase.OVERRUN
    // The keep-screen-on PREFERENCE is a window flag owned by MainActivity; this
    // view flag belongs to a running cardio block alone, so dispose always clears it.
    DisposableEffect(cardioRunning) {
        view.keepScreenOn = cardioRunning
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
            .background(Background)
            .backGesturePreview { dayBackProgress.value },
    ) {
        if (!state.hasProgram) {
            // Two different silences (#127): one that ends on its own, and one
            // that only ends when the lifter does something about it.
            if (state.loading) ProgramLoadingState() else NoProgramState(actions.onSetUpProgram)
            return@Box
        }

        // Fixed chrome top and bottom (day tabs and the day's title above, DONE
        // and keep-screen-on below); only the exercise cards scroll between them,
        // so navigation and the primary action never scroll out of reach.
        //
        // readableWidth, not fillMaxSize: a full-bleed set row on a tablet is
        // 900dp of empty card with a stepper marooned at each end, so the column
        // caps and centres. That is all it does — two-pane is #29.
        Column(readableWidth().nestedScroll(topBarScrollBehavior.nestedScrollConnection)) {
            DayTabStrip(state, accent, actions)
            TopBar(
                state, accent, soft, actions,
                editable = !standaloneCardio,
                scrollBehavior = topBarScrollBehavior,
                onEditDay = { showEditSheet = true },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // contentType, not just key (#156): without it every slot in this
                // list is the same anonymous type, so a card scrolling in can be
                // handed the recycled node tree of a spacer or the footer and has
                // to build itself from nothing. Naming the types lets a card
                // reuse a card — the one recycling that actually pays here, since
                // a card is by far the most expensive thing in the list.
                item(contentType = "spacer") { Spacer(Modifier.size(4.dp)) }
                items(
                    state.exercises,
                    key = { it.programExerciseId },
                    contentType = { "exercise" },
                ) { card ->
                    ExerciseCard(
                        card = card,
                        unit = state.unit,
                        accent = accent,
                        onAccent = onAccent,
                        accentSoftColor = soft,
                        showMainHelper = state.showMainHelper,
                        showSupersetHelper = state.showSupersetHelper,
                        actions = actions,
                        onSwapWeight = dayEditActions.onSwap,
                        canSwapExercise = card.programExerciseId in swappableSlotIds,
                        onSwapExercise = { swapCardSlotId = card.programExerciseId },
                        undoSlotIndex = openOffer?.takeIf { it.programExerciseId == card.programExerciseId }?.index,
                        onUndoRemoveSet = onUndoRemoveSet,
                        autoCollapseDelayMillis = accessibilityManager?.calculateRecommendedTimeoutMillis(
                            originalTimeoutMillis = 420L,
                            containsIcons = true,
                            containsText = true,
                        ) ?: 420L,
                        onAutoCollapsed = {
                            val collapsedCardIndex = state.exercises.indexOfFirst {
                                it.programExerciseId == card.programExerciseId
                            }
                            val nextCardIndex = state.exercises.indices.firstOrNull { index ->
                                index > collapsedCardIndex && state.exercises[index].rows.any { !it.done }
                            } ?: -1
                            if (nextCardIndex >= 0) {
                                scope.launch {
                                    // Let the body finish shrinking (320ms) first, or the
                                    // scroll aims at geometry that is still moving.
                                    delay(320)
                                    val viewportHeight = listState.layoutInfo.run { viewportEndOffset - viewportStartOffset }
                                    listState.animateScrollToItem(nextCardIndex + 1, -(viewportHeight / 3))
                                }
                            }
                        },
                        onEditNote = { noteCardId = card.programExerciseId },
                    )
                }
                state.cardio?.let { cardio ->
                    item(contentType = "cardio") {
                        CardioCard(cardio, accent, onAccent, actions.onStartCardio, actions.onStopCardio)
                    }
                }
                if (!standaloneCardio) item(contentType = "footer") { Footer(onClearChecks = { confirmingClearChecks = true }) }
                item(contentType = "spacer") { Spacer(Modifier.size(8.dp)) }
            }
            if (!standaloneCardio) BottomBar(
                rest = state.rest,
                nextDayId = state.nextDayId,
                doneSets = state.doneSets,
                totalSets = state.totalSets,
                accent = accent,
                onAccent = onAccent,
                onDone = actions.onDone,
                onConfirmPartial = { confirmingPartialFinish = true },
                keepScreenOn = state.keepScreenOn,
                onKeepScreenOnChange = actions.onKeepScreenOnChange,
                onAdjustRest = actions.onAdjustRest,
                onSkipRest = actions.onSkipRest,
            )
        }
    }

    if (showEditSheet) {
        DayEditSheet(
            state = dayEditState,
            actions = dayEditActions,
            accent = accent,
            onDismiss = { showEditSheet = false },
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

// --- pinned tabs + collapsing M3 day app bar ---------------------------------

/**
 * The rotation rail stays bespoke because M3 tabs cannot draw the suggested-day
 * dot against each tab's measured scrolling geometry or express that distinct
 * suggested-versus-selected state.
 */
@Composable
private fun DayTabStrip(state: DayUiState, accent: Color, actions: DayActions) {
    val selectedIndex = state.tabs.indexOfFirst { it.isSelected }.coerceAtLeast(0)
    if (state.tabs.isEmpty()) return
    val tabScrollState = rememberScrollState()
    var tabGeometry by remember(state.tabs.map { it.dayId }) {
        mutableStateOf(List<TabGeometry?>(state.tabs.size) { null })
    }
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        scrollState = tabScrollState,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .drawSuggestedDayDecoration(state.tabs, tabGeometry, tabScrollState, Background)
            .padding(top = 2.dp),
        containerColor = Background,
        contentColor = accent,
        edgePadding = 16.dp,
        minTabWidth = 0.dp,
        divider = {},
        indicator = {},
    ) {
        state.tabs.forEachIndexed { index, tab ->
            Box(Modifier.onGloballyPositioned { coordinates ->
                val measured = TabGeometry(coordinates.positionInParent().x, coordinates.size.width.toFloat())
                if (tabGeometry[index] != measured) {
                    tabGeometry = tabGeometry.toMutableList().also { it[index] = measured }
                }
            }) {
                DayTab(tab, onClick = { actions.onSelectDay(tab.dayId) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    state: DayUiState,
    accent: Color,
    accentSoftColor: Color,
    actions: DayActions,
    editable: Boolean,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onEditDay: () -> Unit,
) {
    Column {
        val collapsed = scrollBehavior.state.collapsedFraction > .5f
        val dayLabel = state.viewDayId?.let { stringResource(R.string.day_header_label, it.uppercase()) }.orEmpty()
        val hasProgress = state.doneSets > 0
        MediumTopAppBar(
            title = {
                if (collapsed) {
                    Text(dayLabel, color = accent, style = MaterialTheme.typography.titleMedium)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (hasProgress) {
                                stringResource(R.string.day_header_with_status, dayLabel, state.doneSets, state.totalSets)
                            } else {
                                dayLabel
                            },
                            color = accent,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    Text(text = state.dayTitle, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    Text(text = state.emphasisLine, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    state.watchStatus?.let { watch ->
                        val label = when (watch.kind) {
                            WatchStatusKind.ACTIVE -> stringResource(R.string.watch_status_active)
                            WatchStatusKind.SYNCING -> pluralStringResource(
                                R.plurals.watch_status_syncing, watch.changeCount, watch.changeCount,
                            )
                            WatchStatusKind.OFFLINE_QUEUED -> stringResource(
                                R.string.watch_status_offline_queued, watch.changeCount,
                            )
                        }
                        Row(
                            modifier = Modifier.pressable(
                                onClickLabel = stringResource(R.string.watch_status_open_rest_timer),
                                role = Role.Button,
                                onClick = actions.onOpenRestTimer,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(AppIcons.Watch, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (state.isOverride && state.suggestedDayId != null) {
                        OverridePill(accent = accent, accentSoftColor = accentSoftColor, suggestedDayId = state.suggestedDayId)
                    }
                }
                }
            },
            actions = { if (editable) EditDayButton(onClick = onEditDay) },
            collapsedHeight = 48.dp,
            expandedHeight = 112.dp,
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Background,
                scrolledContainerColor = Background,
                titleContentColor = TextPrimary,
                actionIconContentColor = TextSecondary,
            ),
            scrollBehavior = scrollBehavior,
        )
        ProgressHairline(
            progress = if (state.totalSets > 0) state.doneSets.toFloat() / state.totalSets else 0f,
            accent = accent,
        )
    }
}

@Composable
private fun EditDayButton(onClick: () -> Unit) {
    val description = stringResource(R.string.day_edit_action)
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(40.dp, 40.dp)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, Border),
        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Surface2, contentColor = TextSecondary),
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp).clearAndSetSemantics {},
        )
    }
}

/** 1dp separator between the fixed chrome and the scrolling list. */
@Composable
private fun Hairline() {
    HorizontalDivider(thickness = 1.dp, color = Border)
}

/**
 * The header's separator doing double duty as the day's progress rule
 * (#126) — M3 has no divider that carries progress, and LinearProgressIndicator
 * cannot be this 1dp chrome rule: the same 1dp line, with the day's accent filling the fraction of
 * it the lifter has ticked. Deliberately no extra height — a session's
 * progress is worth a line, not a band, and growing the rule once the first
 * set lands would shove the whole list down mid-workout.
 *
 * The fill is drawn, not laid out (#156). A child `fillMaxWidth(fraction)`
 * made every tick re-measure a node in the fixed chrome to move a line that
 * only ever changes colour along its length; a `drawBehind` rect is the same
 * pixels for a draw pass. The draw scope is unaware of the reading direction
 * the laid-out child got for free, so the anchor is worked out here: progress
 * grows from the edge the day's title starts at, not from the left.
 */
@Composable
private fun ProgressHairline(progress: Float, accent: Color) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Border)
            .drawBehind {
                if (fraction <= 0f) return@drawBehind
                val filled = size.width * fraction
                val start = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - filled
                drawRect(color = accent, topLeft = Offset(start, 0f), size = Size(filled, size.height))
            },
    )
}

@Composable
private fun OverridePill(accent: Color, accentSoftColor: Color, suggestedDayId: String) {
    val prefix = stringResource(R.string.day_override_suggested_prefix)
    val suggestedDay = stringResource(R.string.day_header_label, suggestedDayId.uppercase())
    val text = buildAnnotatedString {
        append(prefix)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(suggestedDay)
        }
    }
    Box(
        modifier = Modifier
            .background(accentSoftColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = accent, style = MaterialTheme.typography.labelSmall)
    }
}

// internal, not private (A7): the semantics test exercises this composable
// directly rather than standing up a whole DayScreen fixture.
@Composable
internal fun DayTab(tab: DayTab, onClick: () -> Unit) {
    val accent = dayAccent(tab.dayIndex)
    val showSuggestedRing = tab.isSuggested && !tab.isSelected
    // Border-color uses the muted 55% blend; the suggested ring is the pure accent.
    val borderColor = if (showSuggestedRing) accentBorder(tab.dayIndex) else Border
    val description = stringResource(
        if (showSuggestedRing) R.string.day_tab_suggested_description else R.string.day_tab_description,
        tab.dayId,
    )
    Tab(
        selected = tab.isSelected,
        onClick = onClick,
        selectedContentColor = onDayAccent(tab.dayIndex),
        unselectedContentColor = accent,
        modifier = Modifier
            // Reserves the 48dp target around a 40dp tab, which also gives the
            // visual tab room to grow with enlarged system type.
            .minimumInteractiveComponentSize()
            .defaultMinSize(DayTabVisualSize, DayTabVisualSize)
            .background(if (tab.isSelected) accent else Surface, RoundedCornerShape(10.dp))
            .then(if (tab.isSelected) Modifier else Modifier.border(1.dp, borderColor, RoundedCornerShape(10.dp)))
            // Generic M3 Tab fills its available width. Hold the authored
            // surface at 40dp; minimumInteractiveComponentSize remains the
            // outer 48dp layout/touch target.
            .requiredWidth(DayTabVisualSize)
            .semantics { contentDescription = description },
    ) {
        Text(
            tab.dayId,
            style = TabLetter,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private val DayTabVisualSize = 40.dp

private data class TabGeometry(val offsetX: Float, val width: Float)

/**
 * Draws the suggested ring/dot in space owned by the row. Horizontal centres
 * come from each tab wrapper's measured layout rather than assumed tab sizes.
 */
private fun Modifier.drawSuggestedDayDecoration(
    tabs: List<DayTab>,
    tabGeometry: List<TabGeometry?>,
    scrollState: ScrollState,
    background: Color,
): Modifier = drawWithContent {
    val visual = DayTabVisualSize.toPx()
    val ownedTopClearance = 2.dp.toPx()
    val contentHeight = size.height - ownedTopClearance
    val visualTop = ownedTopClearance + (contentHeight - visual) / 2f
    val ringInset = (-2).dp.toPx()

    tabs.forEachIndexed { index, tab ->
        val position = tabGeometry.getOrNull(index)
        if (!tab.isSuggested || tab.isSelected || position == null) return@forEachIndexed
        val logicalCenter = position.offsetX + position.width / 2f - scrollState.value
        val centerX = if (layoutDirection == LayoutDirection.Ltr) logicalCenter else size.width - logicalCenter
        val left = centerX - visual / 2f
        drawRoundRect(
            color = dayAccent(tab.dayIndex),
            topLeft = Offset(left + ringInset, visualTop + ringInset),
            size = Size(visual - 2 * ringInset, visual - 2 * ringInset),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
    drawContent()
    tabs.forEachIndexed { index, tab ->
        val position = tabGeometry.getOrNull(index)
        if (!tab.isSuggested || tab.isSelected || position == null) return@forEachIndexed
        val logicalCenter = position.offsetX + position.width / 2f - scrollState.value
        val centerX = if (layoutDirection == LayoutDirection.Ltr) logicalCenter else size.width - logicalCenter
        val dotCenter = Offset(centerX + visual / 2f, visualTop)
        drawCircle(color = background, radius = 6.dp.toPx(), center = dotCenter)
        drawCircle(color = dayAccent(tab.dayIndex), radius = 4.dp.toPx(), center = dotCenter)
    }
}

// --- exercise card -----------------------------------------------------------

@Composable
private fun ExerciseCard(
    card: ExerciseCardState,
    unit: WeightUnit,
    accent: Color,
    onAccent: Color,
    accentSoftColor: Color,
    showMainHelper: Boolean,
    showSupersetHelper: Boolean,
    actions: DayActions,
    onSwapWeight: (position: Int, targetExerciseId: String) -> Unit,
    canSwapExercise: Boolean,
    onSwapExercise: () -> Unit,
    undoSlotIndex: Int? = null,
    onUndoRemoveSet: () -> Unit = {},
    autoCollapseDelayMillis: Long = 420L,
    onAutoCollapsed: () -> Unit = {},
    onEditNote: () -> Unit = {},
) {
    val expandAction = stringResource(R.string.day_expand_action)
    val collapseAction = stringResource(R.string.day_collapse_action)
    val expandedState = stringResource(R.string.day_expanded_state)
    val collapsedState = stringResource(R.string.day_collapsed_state)
    // Animation-layer only, and deliberately *not* saveable: both are how the
    // fold looks, never what it is. The fold itself is [ExerciseCardState.collapsed],
    // which DayViewModel keeps in SavedStateHandle — so a rotation lands on the
    // right shape via the effect below, it just doesn't replay the tween.
    var previousAllDone by remember { mutableStateOf(card.allDone) }
    var displayCollapsed by remember { mutableStateOf(card.collapsed) }
    // Confirm-before-swap (§4.2): the pill never mutates on tap alone — a swap
    // discards the slot's live rows, the same courtesy other destructive day
    // actions get (ResetToTemplateDialog).
    //
    // Saveable, because an open question is user state and rotating the phone
    // must not answer it by dropping it (#125). Only the *asking* is stored: the
    // affordance itself is re-read from [card] below, so there is one copy of it
    // (SSOT), and a swap that stops being on offer while the dialog is up simply
    // closes it rather than confirming against a stale target.
    var confirmingSwap by rememberSaveable { mutableStateOf(false) }
    val undoOnOffer = undoSlotIndex != null
    LaunchedEffect(card.collapsed, card.allDone, undoOnOffer) {
        val justFinished = card.allDone && !previousAllDone
        previousAllDone = card.allDone
        when {
            // Removing the last unfinished row is itself a way to finish a card,
            // and the collapsed card has no rows to hang the undo offer on. So
            // the auto-fold waits for the window to close (#124) — at which
            // point this effect re-runs and folds without the ceremony delay,
            // which by then it has already had five seconds of.
            card.collapsed && justFinished && undoOnOffer -> displayCollapsed = false
            card.collapsed && justFinished -> {
                // Auto-collapse only (the last tick just landed): let the ✓ chip
                // and green edge register before the card folds — an
                // animation-layer delay, not a change to DayScreenBuilder's
                // (already-tested) collapse decision. A manual header tap
                // collapses/expands instantly (below).
                delay(autoCollapseDelayMillis)
                displayCollapsed = true
                onAutoCollapsed()
            }
            else -> displayCollapsed = card.collapsed
        }
    }

    val doneEdge by animateFloatAsState(
        targetValue = if (card.allDone) 1f else 0f,
        animationSpec = tween(200),
        label = "cardDoneEdge",
    )
    val doneColor = Done
    val cardView = LocalView.current
    val remoteTickEvent = card.rows.maxOfOrNull { it.remoteTickEventId } ?: 0L
    val previousRemoteTickEvent = remember { longArrayOf(remoteTickEvent) }
    LaunchedEffect(remoteTickEvent) {
        if (remoteTickEvent > 0L && remoteTickEvent != previousRemoteTickEvent[0]) {
            AppHaptics.perform(cardView, AppHaptics.Cue.CONFIRM_TICK)
        }
        previousRemoteTickEvent[0] = remoteTickEvent
    }
    AppCard(modifier = Modifier.drawWithContent {
        drawContent()
        // A finished card gets a 3dp green left edge (spec §8.2); non-done cards
        // carry only AppCard's hairline border (reference: no muted strip).
        if (doneEdge > 0f) drawRect(color = doneColor.copy(alpha = doneEdge), size = Size(3.dp.toPx(), size.height))
    }) {
        // The collapse toggle covers the title block only, never the trailing
        // controls: a control's touch target must not sit on top of a different
        // action's, and the ⇄ chip's 48dp target is bigger than the chip.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .pressable(
                        onClickLabel = if (displayCollapsed) expandAction else collapseAction,
                        role = Role.Button,
                        onClick = { actions.onToggleCollapse(card.programExerciseId) },
                    )
                    .semantics { stateDescription = if (displayCollapsed) collapsedState else expandedState },
            ) {
                Text(
                    card.title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.combinedClickable(
                        onClick = { actions.onToggleCollapse(card.programExerciseId) },
                        onLongClick = onEditNote,
                    ),
                )
                if (card.note.isNotBlank()) {
                    Text(
                        card.note,
                        color = TextFaint,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                // Centered, because the swap pill reserves a 48dp target (#123)
                // and so sets this row's height whenever one is present; the
                // badges beside it must stay on the pill's centre line.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 5.dp),
                ) {
                    if (card.isMain) Badge(stringResource(R.string.day_main_badge), accent, onAccent)
                    if (card.hasWarmupHint) Badge(stringResource(R.string.day_warmup_badge), Color.Transparent, TextSecondary, outlined = true)
                    if (card.allDone) {
                        Badge(
                            "✓",
                            Done,
                            Background,
                            description = stringResource(R.string.day_all_sets_done_description),
                        )
                    }
                    card.weightSwap?.let { swap ->
                        WeightSwapPill(swap, accent, onClick = { confirmingSwap = true })
                    }
                }
                // History bonus (PLAN.md A1): the exercise's last completed
                // performance, when one exists — silent otherwise (no "never
                // logged" copy clutter on a brand-new slot).
                card.lastTimeDisplay?.let {
                    Text(
                        stringResource(R.string.day_last_time, it),
                        color = TextFaint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                // Profile bonus (performance-profile.md Phase 1): the exercise's
                // all-time-best completed performance — same quiet register as
                // "Last time", silent when there's no record or it would just
                // repeat the last-time number.
                card.personalRecordDisplay?.let {
                    Text(
                        stringResource(R.string.day_best, it),
                        color = TextFaint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            // Trailing controls, outside the collapse toggle. The chip rides
            // GoalBlock's height so its 48dp target costs the card nothing, and
            // GOAL keeps the top-right corner it is owed (spec §8.2).
            if (!displayCollapsed) {
                if (canSwapExercise) {
                    SwapExerciseChip(
                        onClick = onSwapExercise,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                GoalBlock(card.goalDisplay, card.perHand, accent)
            }
        }

        // Widen this section 10dp into the card gutter so the TOP row can bleed
        // (see [bleedHorizontal]); every other element is inset back to the card
        // content edge. The collapse animates the two body states in and out
        // rather than the card's size: animateContentSize made every card
        // re-measure as it scrolled into the LazyColumn, which read as jank,
        // while AnimatedVisibility measures only during the transition.
        val rowInset = Modifier.padding(horizontal = 10.dp)
        Column(Modifier.bleedHorizontal(10.dp).fillMaxWidth()) {
            AnimatedVisibility(
                visible = displayCollapsed,
                enter = expandVertically(tween(320)) + fadeIn(tween(320)),
                exit = shrinkVertically(tween(320)) + fadeOut(tween(320)),
            ) {
                Column {
                    Spacer(Modifier.size(6.dp))
                    Text(card.collapsedSummary, color = TextSecondary, style = SummaryLine, modifier = rowInset)
                }
            }
            AnimatedVisibility(
                visible = !displayCollapsed,
                enter = expandVertically(tween(320)) + fadeIn(tween(320)),
                exit = shrinkVertically(tween(320)) + fadeOut(tween(320)),
            ) {
                Column {
                if (card.isMain && showMainHelper) {
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.day_main_helper), color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = rowInset)
                }
                if (card.isSuperset && showSupersetHelper) {
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.day_superset_helper), color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = rowInset)
                }

                Spacer(Modifier.size(4.dp))
                var cascadeOrdinal = 0
                var requestedWeightIndex by rememberSaveable { mutableStateOf(-1) }
                var weightEditorRequest by rememberSaveable { mutableStateOf(0) }
                card.rows.forEachIndexed { rowPosition, row ->
                    val nextWeightIndex = card.rows.getOrNull(rowPosition + 1)?.index
                    val ordinal = cascadeOrdinal
                    if (!row.isTop) cascadeOrdinal++
                    // The offer sits in the gap the removed row left, so undo is
                    // where the eye already is.
                    if (row.index == undoSlotIndex) UndoRemovedSetRow(accent, rowInset, onUndoRemoveSet)
                    // The card's narration (#127) sits on the row it is about:
                    // the TOP set is where a main lift's number actually moves,
                    // and the ramp under it is derived from this one.
                    if (row.isTop) {
                        card.topSetComparison?.let {
                            Text(
                                it,
                                color = TextFaint,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = rowInset.padding(top = 4.dp, bottom = 3.dp),
                            )
                        }
                    }
                    SetRow(
                        kindLabel = row.kindLabel,
                        accent = accent,
                        accentSoft = accentSoftColor,
                        weight = row.weightDisplay,
                        onWeightChange = {
                            actions.onWeightChange(card.programExerciseId, Slot.MAIN, row.index, WeightStepper.round(it, unit))
                        },
                        weightStep = { WeightStepper.increment(it, unit) },
                        weightFormat = WeightStepper::format,
                        weightRound = { WeightStepper.round(it, unit) },
                        reps = row.reps,
                        onRepsChange = { actions.onRepsChange(card.programExerciseId, Slot.MAIN, row.index, it) },
                        modifier = if (row.isTop) Modifier else rowInset,
                        isTop = row.isTop,
                        ticked = row.done,
                        onToggleDone = { actions.onToggleDone(card.programExerciseId, row.index, it, card.isSuperset) },
                        onRemove = { actions.onRemoveSet(card.programExerciseId, row.index, card.isSuperset) },
                        cascadeOrdinal = ordinal,
                        tracking = card.tracking,
                        seconds = row.seconds,
                        onSecondsChange = { actions.onSecondsChange(card.programExerciseId, Slot.MAIN, row.index, it) },
                        showTimedWeight = card.timedShowsWeight,
                        isNext = row.isNext,
                        trailingLine = card.plateLine.takeIf { row.isNext },
                        weightUnit = unit.name.lowercase(),
                        weightEditorRequest = weightEditorRequest.takeIf { requestedWeightIndex == row.index } ?: 0,
                        onNext = nextWeightIndex?.let { nextIndex ->
                            {
                                requestedWeightIndex = nextIndex
                                weightEditorRequest++
                            }
                        },
                    )
                    val partner = row.partner
                    if (card.isSuperset && partner != null) {
                        SetRow(
                            kindLabel = "",
                            accent = accent,
                            accentSoft = accentSoftColor,
                            weight = partner.weightDisplay,
                            onWeightChange = {
                                actions.onWeightChange(card.programExerciseId, Slot.SS, row.index, WeightStepper.round(it, unit))
                            },
                            weightStep = { WeightStepper.increment(it, unit) },
                            weightFormat = WeightStepper::format,
                            weightRound = { WeightStepper.round(it, unit) },
                            reps = partner.reps,
                            onRepsChange = { actions.onRepsChange(card.programExerciseId, Slot.SS, row.index, it) },
                            // 10dp base inset + 30dp superset indent; dims with the round's tick.
                            modifier = Modifier.padding(start = 40.dp, end = 10.dp),
                            isSubRow = true,
                            ticked = row.done,
                            tracking = card.partnerTracking ?: TrackingType.WEIGHTED,
                            seconds = partner.seconds,
                            onSecondsChange = { actions.onSecondsChange(card.programExerciseId, Slot.SS, row.index, it) },
                            showTimedWeight = card.partnerTimedShowsWeight,
                            weightUnit = unit.name.lowercase(),
                        )
                    }
                }
                // The removed row was the last one: its gap is below every
                // surviving row, not between two of them.
                if (undoSlotIndex != null && undoSlotIndex >= card.rows.size) {
                    UndoRemovedSetRow(accent, rowInset, onUndoRemoveSet)
                }
                if (card.rows.isNotEmpty()) {
                    Spacer(Modifier.size(2.dp))
                    AddSetButton(modifier = rowInset, isSuperset = card.isSuperset) { actions.onAddSet(card.programExerciseId, card.isSuperset) }
                }
                }
            }
        }
    }

    card.weightSwap?.takeIf { confirmingSwap }?.let { swap ->
        WeightSwapConfirmDialog(
            swap = swap,
            onConfirm = {
                onSwapWeight(card.position, swap.targetExerciseId)
                confirmingSwap = false
            },
            onDismiss = { confirmingSwap = false },
        )
    }
}

// --- ADD WEIGHT / REMOVE WEIGHT pill (§4.2) ----------------------------------

/**
 * The labeled swap pill on an exercise card's header — "+ ADD WEIGHT" toward a
 * loaded variant, "− REMOVE WEIGHT" back off one. A bordered pill rather than
 * a filled badge (spec §8.5: not a generic Material button) so it reads as an
 * affordance, not a status chip like [Badge]. `internal`, not `private` (A7
 * pattern, see `DayTab`): a UI test taps this composable directly.
 *
 * The pill draws ~20dp tall but has to answer to a finger, so
 * [minimumInteractiveComponentSize] reserves the 48dp target *in the layout*
 * (#123). That is the point: the pill lives inside the card's collapse toggle,
 * and an unreserved 48dp target would silently claim 28dp of card header where
 * the user sees nothing but header and expects a collapse. Reserving instead
 * grows the badge row on the cards that show a pill — the one place this change
 * moves a pixel — and every dp the target claims is the pill's own row.
 */
@Composable
internal fun WeightSwapPill(swap: WeightSwapAffordance, accent: Color, onClick: () -> Unit) {
    val label = stringResource(if (swap.isRemove) R.string.day_remove_weight_button else R.string.day_add_weight_button)
    val borderColor = if (swap.isRemove) Border else accent
    val textColor = if (swap.isRemove) TextSecondary else accent
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .pressable(
                onClickLabel = label,
                role = Role.Button,
                shape = RoundedCornerShape(50),
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}

// --- swap-this-exercise chip (#122) ------------------------------------------

/** Visible size of the ⇄ chip. The height is a filled [Badge]'s (labelSmall's
 *  14sp line plus 3dp above and below), so the chip carries the card header's
 *  existing rhythm rather than introducing a new one. */
private val SwapChipWidth = 30.dp
private val SwapChipHeight = 20.dp

/**
 * The ⇄ chip on an expanded card, left of the GOAL block: opens the ranked
 * substitute picker for this slot directly ([SlotSwapSheet], issue #122),
 * instead of the four taps through the day-edit sheet. Bordered like
 * [WeightSwapPill] — a control, not a status [Badge] — and glyph-only, so the
 * accessible name lives on the clickable parent while [clearAndSetSemantics]
 * silences the glyph (A7).
 *
 * [minimumInteractiveComponentSize] grows the layout box, not just the touch
 * target, so the chip claims a real 48dp slot rather than spilling an invisible
 * one over its neighbours. It costs the card no height: [GoalBlock] beside it
 * is taller, and the chip only ever renders next to it.
 */
@Composable
internal fun SwapExerciseChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.day_swap_exercise_action)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .pressable(
                onClickLabel = description,
                role = Role.Button,
                shape = RoundedCornerShape(50),
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = SwapChipWidth, minHeight = SwapChipHeight)
                .border(1.dp, Border, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.SwapHoriz,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).clearAndSetSemantics {},
            )
        }
    }
}

/** One-line confirm before a swap discards the slot's live rows (spec §4.2) —
 *  same courtesy [ResetToTemplateDialog] gives other destructive day actions. */
@Composable
internal fun WeightSwapConfirmDialog(swap: WeightSwapAffordance, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.day_weight_swap_confirm_title, swap.targetName)) },
        text = { Text(stringResource(R.string.day_weight_swap_confirm_body)) },
        confirmButton = { DialogAction(stringResource(R.string.day_weight_swap_confirm_button), Done, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.day_cancel_button), TextSecondary, onDismiss) },
    )
}

// --- small pieces ------------------------------------------------------------

@Composable
private fun GoalBlock(goalDisplay: String, perHand: Boolean, accent: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(stringResource(R.string.day_goal_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(goalDisplay, color = accent, style = MaterialTheme.typography.displayLarge)
        if (perHand) {
            Text(stringResource(R.string.day_per_hand_suffix), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** [description] overrides the accessible name for badges whose visible
 *  [text] is a glyph rather than a word (the "✓" all-done badge, A7) — TalkBack
 *  must never read a raw glyph. */
@Composable
private fun Badge(text: String, fill: Color, textColor: Color, outlined: Boolean = false, description: String? = null) {
    Box(
        modifier = Modifier
            .background(fill, RoundedCornerShape(4.dp))
            .then(if (outlined) Modifier.border(1.dp, Border, RoundedCornerShape(4.dp)) else Modifier)
            // Outline badges pad 7/2 (tighter, to offset the 1px border); filled 8/3.
            .padding(horizontal = if (outlined) 7.dp else 8.dp, vertical = if (outlined) 2.dp else 3.dp)
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier),
    ) {
        Text(text.uppercase(), color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AddSetButton(modifier: Modifier = Modifier, isSuperset: Boolean, onClick: () -> Unit) {
    // M3 exposes no dashed-border hook, and its Surface would put the 48dp
    // envelope inside the dashes instead of around the bespoke visual bounds.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .fillMaxWidth()
            .padding(top = 8.dp)
            .dashedBorder(Border, radius = 8.dp)
            .pressable(onClick = onClick, shape = RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (isSuperset) R.string.day_add_round_button else R.string.day_add_set_button),
            color = TextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The standing undo for a set the × just took (#124). This app has no Snackbar
 * and shouldn't grow one for a five-second offer, so the offer sits in the card
 * where the row was, in the card's own register — a faint label and one accent
 * verb, on a whole-row target so taking it back is as cheap as the × was.
 *
 * Both labels are real text, so the accessible name is the line itself; the
 * click label supplies the verb TalkBack announces.
 */
@Composable
private fun UndoRemovedSetRow(accent: Color, modifier: Modifier = Modifier, onUndo: () -> Unit) {
    val undoDescription = stringResource(R.string.day_undo_remove_set_action)
    // A plain `visible = true` never animates its first frame; the transition
    // state starts hidden so the row actually fades in.
    val appearance = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = appearance, enter = fadeIn(tween(140))) {
        TextButton(
            onClick = onUndo,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { onClick(label = undoDescription, action = null) },
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
            contentPadding = PaddingValues(0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.day_set_removed_label), color = TextFaint, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.day_undo_button), color = accent, style = DoneButtonLabel)
            }
        }
    }
}

// --- cardio, done, footer ----------------------------------------------------

@Composable
private fun CardioCard(
    cardio: CardioCardState,
    accent: Color,
    onAccent: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // Saveable so LazyColumn eviction and rotation don't snap it shut (defaults closed).
    var open by rememberSaveable { mutableStateOf(false) }
    val expandAction = stringResource(R.string.day_expand_action)
    val collapseAction = stringResource(R.string.day_collapse_action)
    val expandedState = stringResource(R.string.day_expanded_state)
    val collapsedState = stringResource(R.string.day_collapsed_state)
    val executing = cardio.phase != CardioPhase.SUGGESTION
    LaunchedEffect(executing) { open = executing }
    val chevronRotation by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "cardioChevron")
    // Not the clickable-card overload: OutlinedCard(onClick) cannot carry the
    // Expand/Collapse click label, and TalkBack loses the verb without it.
    AppCard(
        modifier = Modifier
            .then(if (!executing) Modifier.pressable(
                onClickLabel = if (open) collapseAction else expandAction,
                role = Role.Button,
                shape = MaterialTheme.shapes.large,
            ) { open = !open } else Modifier)
            .semantics { if (!executing) stateDescription = if (open) expandedState else collapsedState },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.day_cardio_title),
                    color = TextPrimary,
                    style = CardTitle,
                )
                Spacer(Modifier.size(5.dp))
                Badge(
                    stringResource(if (cardio.hard) R.string.day_cardio_hard_label else R.string.day_cardio_easy_label, cardio.label),
                    Color.Transparent,
                    TextSecondary,
                    outlined = true,
                )
            }
            if (!executing) Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).rotate(chevronRotation).clearAndSetSemantics {},
            )
        }
        Column(Modifier.animateContentSize(tween(320))) {
            if (open) {
                Spacer(Modifier.size(10.dp))
                if (executing) {
                    if (cardio.phase == CardioPhase.OVERRUN) {
                        Text(stringResource(R.string.day_cardio_overrun), color = accent, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(
                            stringResource(R.string.day_cardio_step_left, cardio.currentStepLabel.orEmpty(), formatCardioTime(cardio.stepSecondsLeft)),
                            color = accent,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.size(5.dp))
                    Text(
                        stringResource(R.string.day_cardio_elapsed, formatCardioTime(cardio.elapsedSeconds)),
                        color = TextSecondary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onStop, colors = ButtonDefaults.textButtonColors(contentColor = TextFaint)) {
                        Text(stringResource(R.string.day_cardio_stop_button))
                    }
                } else {
                    Text(cardio.detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                    ) {
                        Text(stringResource(R.string.day_cardio_start_button))
                    }
                }
            }
        }
        if (!executing) cardio.loggedSeconds?.let { seconds ->
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.day_cardio_logged, formatCardioTime(seconds)),
                color = TextFaint,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun formatCardioTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

// --- fixed bottom bar: the primary action, and keep-screen-on ----------------

/**
 * DONE plus the keep-screen-on switch (#125). The switch sits here and not in
 * the header because it is a *during-workout* control — you reach for it with
 * chalked hands between sets, and the bottom edge is the only part of a 6"
 * phone a thumb reaches without regripping. DONE keeps the width it isn't using.
 */
@Composable
private fun BottomBar(
    rest: RestUiState?,
    nextDayId: String?,
    doneSets: Int,
    totalSets: Int,
    accent: Color,
    onAccent: Color,
    onDone: () -> Unit,
    onConfirmPartial: () -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onAdjustRest: (Int) -> Unit,
    onSkipRest: () -> Unit,
) {
    val verticalPadding = chromeVerticalPadding()
    Column(Modifier.fillMaxWidth().background(Background)) {
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The last rest is kept so the pill fades out with its numbers
            // rather than vanishing the frame the timer clears.
            var shownRest by remember { mutableStateOf(rest) }
            if (rest != null) shownRest = rest
            AnimatedVisibility(
                visible = rest != null,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160)),
            ) {
                shownRest?.let { RestPill(it, accent, onAdjustRest, onSkipRest) }
            }
            DoneButton(
                nextDayId = nextDayId,
                doneSets = doneSets,
                totalSets = totalSets,
                accent = accent,
                onAccent = onAccent,
                onClick = onDone,
                onConfirmPartial = onConfirmPartial,
                modifier = Modifier.weight(1f),
            )
            KeepScreenOnSwitch(
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
                accent = accent,
                onAccent = onAccent,
            )
        }
    }
}

/** M3 has no compact countdown/action cluster with a draining typographic
 * hairline; composing text buttons keeps the one bespoke piece to that layout. */
@Composable
private fun RestPill(state: RestUiState, accent: Color, onAdjust: (Int) -> Unit, onSkip: () -> Unit) {
    Column(Modifier.widthIn(min = 112.dp, max = 150.dp)) {
        Text(
            if (state.over) stringResource(R.string.rest_over) else "%d:%02d".format(state.remainingSeconds / 60, state.remainingSeconds % 60),
            color = accent,
            style = DisplayXl.copy(fontSize = 24.sp, lineHeight = 26.sp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border)) {
            Box(Modifier.fillMaxWidth(state.remainingFraction).height(1.dp).background(accent))
        }
        Row {
            TextButton(onClick = { onAdjust(-15) }, contentPadding = PaddingValues(horizontal = 3.dp)) {
                Text(stringResource(R.string.rest_minus_15), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { onAdjust(15) }, contentPadding = PaddingValues(horizontal = 3.dp)) {
                Text(stringResource(R.string.rest_plus_15), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onSkip, contentPadding = PaddingValues(horizontal = 3.dp)) {
                Text(stringResource(R.string.rest_skip), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DoneButton(
    nextDayId: String?,
    doneSets: Int,
    totalSets: Int,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
    onConfirmPartial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val state = DayScreenBuilder.doneButtonState(doneSets, totalSets)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "doneButtonPress",
    )
    Button(
        onClick = {
            when (state) {
                DoneButtonState.ALL_DONE -> {
                    AppHaptics.perform(view, AppHaptics.Cue.FINISH)
                    onClick()
                }
                DoneButtonState.PARTIAL -> onConfirmPartial()
                DoneButtonState.NOTHING_LOGGED -> Unit
            }
        },
        enabled = state != DoneButtonState.NOTHING_LOGGED,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // heightIn(min), not height (A7 font-scale): the longer "ADVANCE TO
            // DAY B" label wraps to two lines at large fontScale instead of
            // overflowing the fixed-height pill.
            .heightIn(min = 56.dp),
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = onAccent,
            disabledContainerColor = Surface2,
            disabledContentColor = TextFaint,
        ),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Text(
            text = when (state) {
                DoneButtonState.ALL_DONE -> if (nextDayId != null) stringResource(R.string.day_done_advance_button, nextDayId.uppercase()) else stringResource(R.string.day_done_button)
                DoneButtonState.PARTIAL -> stringResource(R.string.day_finish_partial_button, doneSets, totalSets)
                DoneButtonState.NOTHING_LOGGED -> stringResource(R.string.day_nothing_logged_button)
            },
            style = DoneButtonLabel,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
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
        confirmButton = { DialogAction(stringResource(R.string.day_finish_partial_confirm_button), Done, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.day_finish_partial_dismiss_button), TextSecondary, onDismiss) },
    )
}

/** The scroll's tail: the quiet "clear checkmarks" action. */
@Composable
private fun Footer(onClearChecks: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        QuietButton(onClick = onClearChecks)
    }
}

/**
 * Confirm before the footer's quiet action unticks the whole day. A dialog and
 * not an undo (unlike the × on a set row, #124): this is a once-in-a-while
 * action, and undoing it would mean capturing and replaying every tick in the
 * day rather than one row.
 */
@Composable
private fun ClearChecksConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.day_clear_checks_confirm_title)) },
        text = { Text(stringResource(R.string.day_clear_checks_confirm_body)) },
        confirmButton = { DialogAction(stringResource(R.string.day_clear_checks_confirm_button), Error, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.day_cancel_button), TextSecondary, onDismiss) },
    )
}

@Composable
private fun QuietButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        // The one labelLarge element the reference leaves mixed-case (no caps).
        Text(stringResource(R.string.day_clear_checks_button), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Keep-screen-on, in the compact form the bottom bar has room for: the same
 * 40×24 track this app has always drawn, stacked over a two-word label instead
 * of set beside a sentence. Stacking is what buys DONE the width — the control
 * is 48dp wide where the old label-beside-track row was closer to 110dp.
 */
@Composable
private fun KeepScreenOnSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    onAccent: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            // widthIn, not width (A7 font-scale): the label sets the width at
            // large scales instead of clipping, and DONE's weight(1f) yields.
            .widthIn(min = 48.dp)
            // toggleable(role = Switch) (A7): TalkBack gets the switch role and
            // on/off state for free; the label is real Text, so it merges into
            // the accessible name without a separate contentDescription.
            .pressableToggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
                // A stacked block is block-shaped, so it takes the same radius
                // the cards and DONE do; without it the ripple would square off
                // the one control on this screen that isn't a chip.
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        val trackColor by animateColorAsState(if (checked) accent else Surface2, tween(200), label = "switchTrack")
        val thumbOffset by animateFloatAsState(if (checked) 18f else 2f, tween(200), label = "switchThumb")
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(24.dp)
                .background(trackColor, RoundedCornerShape(50))
                .border(1.dp, if (checked) accent else Border, RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset.dp, top = 2.dp)
                    .size(18.dp)
                    .background(if (checked) onAccent else TextSecondary, CircleShape),
            )
        }
        Text(
            stringResource(R.string.day_keep_screen_on_label),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// --- modifiers / effects -----------------------------------------------------

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
    drawRoundRect(color = color, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
}

/**
 * Renders [bleed] wider on each side than the width it reports to its parent,
 * placed shifted left by [bleed] — so the child spills symmetrically into the
 * parent's padding without changing the parent's layout. The TOP set row uses
 * this to bleed into the card gutter while the card keeps its normal width.
 */
private fun Modifier.bleedHorizontal(bleed: Dp): Modifier = layout { measurable, constraints ->
    // No bleed under an unbounded-width parent (nothing to spill into).
    val extra = if (constraints.hasBoundedWidth) bleed.roundToPx() * 2 else 0
    val widened =
        if (extra > 0) constraints.copy(maxWidth = constraints.maxWidth + extra) else constraints
    val placeable = measurable.measure(widened)
    val reported = (placeable.width - extra).coerceAtLeast(0)
    layout(reported, placeable.height) { placeable.place(if (extra > 0) -bleed.roundToPx() else 0, 0) }
}

/** Callbacks the screen forwards to [DayViewModel] — one place, so previews stay trivial. */
data class DayActions(
    val onSelectDay: (String) -> Unit,
    val onWeightChange: (Long, String, Int, Double) -> Unit,
    val onRepsChange: (Long, String, Int, Int) -> Unit,
    val onSecondsChange: (Long, String, Int, Int) -> Unit,
    val onToggleDone: (Long, Int, Boolean, Boolean) -> Unit,
    val onAddSet: (Long, Boolean) -> Unit,
    val onRemoveSet: (Long, Int, Boolean) -> Unit,
    val onToggleCollapse: (Long) -> Unit,
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
)

// --- preview: the reference scenario (day_screen_reference.html) ------------

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun DayScreenPreview() {
    DayScreenPreviewContent()
}

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFFF1EFEA)
@Composable
private fun DayScreenLightPreview() {
    DayScreenPreviewContent(theme = ThemePreference.LIGHT)
}

// Font-scale audit (A7): the DONE button's "ADVANCE TO DAY B" label and the
// GOAL numeral are the two elements most at risk of clipping/overflow at
// large system font scales — see the heightIn(min)/maxLines fix on
// [DoneButton]. Same fixture as [DayScreenPreview], just re-scaled.
@Preview(showBackground = true, heightDp = 1000, backgroundColor = 0xFF0D0D0F, fontScale = 1.3f)
@Composable
private fun DayScreenFontScale130Preview() {
    DayScreenPreviewContent()
}

@Preview(showBackground = true, heightDp = 1200, backgroundColor = 0xFF0D0D0F, fontScale = 2.0f)
@Composable
private fun DayScreenFontScale200Preview() {
    DayScreenPreviewContent()
}

@Composable
private fun DayScreenPreviewContent(theme: ThemePreference = ThemePreference.DARK) {
    fun row(index: Int, kind: String, isTop: Boolean, w: Double, r: Int, done: Boolean = false) =
        SetRowState(index = index, kindLabel = kind, isTop = isTop, weightDisplay = w, reps = r, done = done)

    val previewState = DayUiState(
        hasProgram = true,
        tabs = listOf(
            DayTab("A", 0, isSuggested = false, isSelected = true),
            DayTab("B", 1, isSuggested = true, isSelected = false),
            DayTab("C", 2, isSuggested = false, isSelected = false),
            DayTab("D", 3, isSuggested = false, isSelected = false),
        ),
        viewDayId = "A",
        dayIndex = 0,
        dayTitle = "Lower — squat focus",
        emphasisLine = "hip-hinge hamstrings · gastroc calves",
        unit = WeightUnit.LB,
        suggestedDayId = "B",
        nextDayId = "B",
        exercises = listOf(
            ExerciseCardState(
                programExerciseId = 1,
                position = 0,
                title = "Barbell Back Squat",
                isMain = true,
                isSuperset = false,
                hasWarmupHint = true,
                goalDisplay = "235",
                perHand = false,
                lastTimeDisplay = "230×5",
                personalRecordDisplay = "245×5",
                topSetComparison = "+5 LB FROM LAST",
                allDone = false,
                collapsed = false,
                collapsedSummary = "5 sets · GOAL 235",
                rows = listOf(
                    row(0, "R1", isTop = false, w = 130.0, r = 5),
                    row(1, "R2", isTop = false, w = 165.0, r = 5),
                    row(2, "R3", isTop = false, w = 190.0, r = 5),
                    row(3, "R4", isTop = false, w = 210.0, r = 3),
                    row(4, "TOP", isTop = true, w = 235.0, r = 5),
                    row(5, "B/O", isTop = false, w = 175.0, r = 8),
                ),
            ),
            ExerciseCardState(
                programExerciseId = 2,
                position = 1,
                title = "SS: EZ-Bar Curl + Rope Pushdown",
                isMain = false,
                isSuperset = true,
                hasWarmupHint = false,
                goalDisplay = "60",
                perHand = false,
                allDone = false,
                collapsed = false,
                collapsedSummary = "3 sets · GOAL 60",
                rows = listOf(
                    SetRowState(0, "1", false, 60.0, 12, false, PartnerRowState(50.0, 15)),
                    SetRowState(1, "2", false, 60.0, 11, false, PartnerRowState(50.0, 14)),
                    SetRowState(2, "3", false, 60.0, 10, false, PartnerRowState(50.0, 12)),
                ),
            ),
            ExerciseCardState(
                programExerciseId = 3,
                position = 2,
                title = "Seated Leg Curl",
                isMain = false,
                isSuperset = false,
                hasWarmupHint = false,
                goalDisplay = "90",
                perHand = false,
                allDone = true,
                collapsed = true,
                collapsedSummary = "90×10 · 90×10 · 90×9",
                rows = listOf(
                    row(0, "1", false, 90.0, 10, done = true),
                    row(1, "2", false, 90.0, 10, done = true),
                    row(2, "3", false, 90.0, 9, done = true),
                ),
            ),
            ExerciseCardState(
                programExerciseId = 4,
                position = 3,
                title = "Plank / Side Plank",
                isMain = false,
                isSuperset = false,
                hasWarmupHint = false,
                goalDisplay = "45s",
                perHand = false,
                tracking = TrackingType.TIMED,
                allDone = false,
                collapsed = false,
                collapsedSummary = "3 sets · GOAL 45s",
                rows = listOf(
                    SetRowState(0, "1", false, 0.0, 0, false, seconds = 45),
                    SetRowState(1, "2", false, 0.0, 0, false, seconds = 45),
                    SetRowState(2, "3", false, 0.0, 0, false, seconds = 45),
                ),
                weightSwap = WeightSwapAffordance("weighted_plank", "Weighted Plank", isRemove = false),
            ),
        ),
        cardio = CardioCardState("Zone 2", "20-25 min easy — legs were heavy today, keep it conversational.", hard = false),
        keepScreenOn = false,
    )

    AppTheme(preference = theme) {
        DayScreen(
            state = previewState,
            actions = DayActions(
                onSelectDay = {},
                onWeightChange = { _, _, _, _ -> },
                onRepsChange = { _, _, _, _ -> },
                onSecondsChange = { _, _, _, _ -> },
                onToggleDone = { _, _, _, _ -> },
                onAddSet = { _, _ -> },
                onRemoveSet = { _, _, _ -> },
                onToggleCollapse = {},
                onKeepScreenOnChange = {},
                onClearChecks = {},
                onDone = {},
                onStartCardio = {},
                onStopCardio = {},
                onCardioScreenLive = {},
                onCreateExercise = {},
                onSetUpProgram = {},
            ),
            // Slots for the four cards above: the preview needs them or no card
            // shows its ⇄ chip (#122), which is exactly what the font-scale
            // audit previews below are here to catch.
            dayEditState = DayEditUiState(
                slots = listOf(
                    DayEditSlotState(1, 0, "bb_back_squat", "Barbell Back Squat", MovementPattern.SQUAT_BILATERAL, isSuperset = false),
                    DayEditSlotState(2, 1, "ez_curl", "EZ-Bar Curl", MovementPattern.BICEPS, isSuperset = true, partnerTitle = "Rope Pushdown"),
                    DayEditSlotState(3, 2, "seated_curl", "Seated Leg Curl", MovementPattern.KNEE_FLEXION, isSuperset = false),
                    DayEditSlotState(4, 3, "plank", "Plank / Side Plank", MovementPattern.CORE_ANTI_EXT, isSuperset = false),
                ),
                canRemove = true,
            ),
            dayEditActions = DayEditActions(
                onSwap = { _, _ -> },
                onAdd = {},
                onRemove = {},
                onSetSuperset = { _, _ -> },
                onRemoveSuperset = {},
                onResetToTemplate = {},
            ),
        )
    }
}
