package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.ui.theme.SetNumerals
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.WorkoutRestShape
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import kotlinx.coroutines.delay

/** M3's fast-effects duration; `MaterialTheme.motionScheme` is internal in this BOM. */
private const val FAST_EFFECTS_MILLIS = 150
private const val HERO_MIN_SP = 48

@Composable
fun WorkoutSetScreen(
    state: DayUiState,
    actions: DayActions,
    catalog: ExerciseCatalog,
    onExercises: () -> Unit,
    onFinish: () -> Unit,
    snackbarHost: SnackbarHostState,
    onEditDay: () -> Unit,
    onBack: (() -> Unit)? = null,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = dayAccent(state.dayIndex), onPrimary = onDayAccent(state.dayIndex),
    )) {
        WorkoutSetContent(state, actions, catalog, onExercises, onFinish, snackbarHost, onEditDay, onBack, nowMillis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutSetContent(
    state: DayUiState,
    actions: DayActions,
    catalog: ExerciseCatalog,
    onExercises: () -> Unit,
    onFinish: () -> Unit,
    snackbarHost: SnackbarHostState,
    onEditDay: () -> Unit,
    onBack: (() -> Unit)?,
    nowMillis: () -> Long,
) {
    val workout = state.workout ?: return
    val nextTitle = state.workoutNextPage?.title
    val allLogged = state.workoutAllLogged
    var finishArmed by remember(workout.lastCompletionMillis) { mutableStateOf(false) }
    LaunchedEffect(workout.lastCompletionMillis) {
        val remaining = workout.lastCompletionMillis?.let { it + 3_000L - nowMillis() } ?: 0L
        delay(remaining.coerceAtLeast(0L))
        finishArmed = true
    }
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.today_day_label, state.viewDayId.orEmpty())) },
                navigationIcon = {
                    IconButton(onClick = { onBack?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.day_back))
                    }
                },
                actions = {
                    IconButton(onClick = onExercises) {
                        Icon(Icons.AutoMirrored.Filled.List, stringResource(
                            R.string.workout_exercises, state.viewDayId.orEmpty(), state.doneSets, state.totalSets,
                        ))
                    }
                    if (!allLogged) {
                        TextButton(onClick = onFinish, enabled = state.canFinish, colors = neutralTextButtonColors()) {
                            Text(stringResource(R.string.day_finish_partial_confirm_button))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                    .then(if (state.workoutCard?.isSuperset == true) {
                        Modifier.testTag("workout-superset-actions").semantics {
                            isTraversalGroup = true
                            traversalIndex = 1f
                        }
                    } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.widthIn(max = ReadableWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkoutCardioFailedBand(state, actions)
                    WorkoutRestBand(state, actions, catalog)
                    val focus = workout.focus
                    val card = state.workoutCard
                    val event = state.timedRep?.takeIf { it.event.programExerciseId == card?.programExerciseId }
                    val index = event?.event?.setIndex ?: focus?.index ?: -1
                    val slot = if (event != null) Slot.MAIN else focus?.slot ?: Slot.MAIN
                    val values = card?.rows?.getOrNull(index)?.values(slot)
                    val cardio = state.cardio
                    val cardioShown = (workout.shown as? WorkoutSelection.Shown.Group)?.key == "cardio"
                    val resting = state.rest?.over == false
                    val action = when {
                        allLogged -> if (state.workoutCardioOnlyDone) null else WorkoutActionSpec(
                            stringResource(R.string.day_finish_button, state.viewDayId.orEmpty()), resting,
                            state.canFinish && finishArmed, onFinish)
                        workout.shown == WorkoutSelection.Shown.Empty -> null
                        cardioShown && cardio != null -> {
                            val run = cardio.run
                            val guardKey = run?.let { Triple(it.runId, it.position, it.running) }
                            val end = cardio.block?.sets?.getOrNull(cardio.position)?.end
                            when {
                                cardio.completed || (workout.shown as? WorkoutSelection.Shown.Group)?.complete == true ->
                                    if (state.totalSets == 0 || nextTitle == null) null else WorkoutActionSpec(
                                        stringResource(R.string.day_next_exercise, nextTitle.orEmpty()), resting, true,
                                        actions.onNextWorkoutLift, guardKey = guardKey)
                                run?.running == true && end is Measure.Distance -> WorkoutActionSpec(
                                    stringResource(R.string.cardio_reached, end.metres), false, true,
                                    { actions.onReachCardioTarget(run.action()) },
                                    stringResource(R.string.day_cardio_stop_button) to { actions.onStopCardio(run.action()) }, guardKey)
                                run?.running == true -> WorkoutActionSpec(
                                    stringResource(R.string.day_cardio_stop_button), true, true,
                                    { actions.onStopCardio(run.action()) }, guardKey = guardKey)
                                else -> WorkoutActionSpec(stringResource(R.string.day_cardio_start_button), resting,
                                    state.cardioStartEnabled, actions.onStartCardio, guardKey = guardKey)
                            }
                        }
                        workout.completedView -> WorkoutActionSpec(
                            stringResource(R.string.day_next_exercise, nextTitle.orEmpty()), resting, true, actions.onNextWorkoutLift)
                        card != null && (card.timedRepDurationSeconds ?: 0) > 0 && slot == Slot.MAIN -> {
                            val guardKey = event?.event?.let { it.wallStart to it.awaitingCount }
                            when (event?.event?.awaitingCount) {
                                false -> WorkoutActionSpec(stringResource(R.string.day_cancel_button), true, true,
                                    actions.onStopTimedRep, guardKey = guardKey)
                                true -> WorkoutActionSpec(stringResource(R.string.timed_rep_enter_reps), resting, true,
                                    actions.onRequestTimedRepCount, guardKey = guardKey)
                                null -> WorkoutActionSpec(stringResource(R.string.day_cardio_start_button), resting,
                                    !state.executionBusy && state.timedRep == null,
                                    { actions.onStartTimedRep(card.programExerciseId, index) })
                            }
                        }
                        values != null && card != null -> WorkoutActionSpec(
                            stringResource(R.string.day_log_set, workoutValue(values, card.tracking(slot))), resting, true,
                            actions.onLogFocusedSet)
                        else -> null
                    }
                    if (action != null) WorkoutAction(action.label, action.tonal, action.enabled, reducedMotion,
                        action.onClick, action.secondary, action.guardKey)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            WorkoutClockChip(state, actions)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val modifier = Modifier.widthIn(max = ReadableWidth).fillMaxSize()
                val scrollModifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp)
                if (reducedMotion) {
                    Column(modifier.then(scrollModifier)) { WorkoutLift(state, actions, catalog, reducedMotion, onEditDay) }
                } else {
                    AnimatedContent(
                        targetState = state,
                        contentKey = { when (val shown = it.workout?.shown) {
                            is WorkoutSelection.Shown.Group -> shown.key
                            WorkoutSelection.Shown.AllLogged -> "all-logged"
                            else -> "empty"
                        } },
                        transitionSpec = {
                            (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(300)))
                                .togetherWith(slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(300)))
                                .using(null)
                        },
                        modifier = modifier,
                        label = "workoutGroup",
                    ) { shown ->
                        Column(Modifier.fillMaxSize().then(scrollModifier)) {
                            WorkoutLift(shown, actions, catalog, reducedMotion, onEditDay)
                        }
                    }
                }
            }
        }
    }
}

private val DayUiState.workoutCardioOnlyDone: Boolean get() = workout != null && totalSets == 0 &&
    cardio != null && (cardio.completed || workoutAllLogged)

@Composable
private fun WorkoutClockChip(state: DayUiState, actions: DayActions) {
    state.workoutClockChip?.let { chip ->
        AssistChip(onClick = { actions.onSelectWorkoutLift(chip.key) }, label = { Text(chip.label) },
            modifier = Modifier.padding(horizontal = 20.dp).testTag("workout-clock-chip"))
    }
}

@Composable
private fun WorkoutClockProgress(fraction: Float) {
    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        color = MaterialTheme.colorScheme.onSurface, trackColor = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun WorkoutTimedRepHero(card: ExerciseCardState, timed: TimedRepState?, index: Int, actions: DayActions) {
    val duration = card.timedRepDurationSeconds ?: return
    val seconds = if (timed?.event?.awaitingCount == true) 0 else timed?.remainingSeconds ?: duration
    if (timed == null) {
        val editLabel = stringResource(R.string.workout_edit_set, index + 1)
        TextButton(onClick = { actions.onOpenSetEditor(card.programExerciseId, index) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel },
            colors = neutralTextButtonColors(), contentPadding = PaddingValues(0.dp)) {
            WorkoutHeroText(formatCardioTime(seconds))
        }
    } else {
        WorkoutHeroText(formatCardioTime(seconds))
        if (!timed.event.awaitingCount) WorkoutClockProgress(seconds.toFloat() / duration)
    }
    card.timedRepAim?.let {
        Text(stringResource(R.string.workout_aim_reps, it), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkoutCardio(state: DayUiState, actions: DayActions) {
    val cardio = state.cardio ?: return
    val block = cardio.block ?: return
    val run = cardio.run
    val done = cardio.completed || (state.workout?.shown as? WorkoutSelection.Shown.Group)?.complete == true
    val spec = block.sets.getOrNull(cardio.position)
    val position = spec?.let { stringResource(R.string.workout_cardio_position, roleName(it.role), cardio.position + 1, block.sets.size) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(Modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Polite
            if (position != null) contentDescription = position
        }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(cardioActivityName(block.activity), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onOpenCardioSets, colors = neutralTextButtonColors()) { Text(stringResource(R.string.day_all_sets)) }
        }
        if (done) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Check, null)
                Text(stringResource(R.string.set_edit_done), style = MaterialTheme.typography.titleMedium)
            }
            CardioSummary(run?.resultSets ?: cardio.loggedSets, run?.results?.sumOf { it.seconds } ?: cardio.loggedSeconds ?: 0)
        } else if (spec != null) {
            Text(position.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val end = spec.end) {
                is Measure.Time -> {
                    WorkoutHeroText(formatCardioTime(if (run?.running == true) cardio.stepSecondsLeft else end.seconds))
                    if (run?.running == true) WorkoutClockProgress(cardio.stepSecondsLeft.toFloat() / end.seconds)
                }
                is Measure.Distance -> if (run?.running == true) WorkoutHeroText(formatCardioTime(cardio.elapsedSeconds))
                    else WorkoutHeroText(end.metres.toString(), stringResource(R.string.workout_metres_unit))
            }
            spec.aim?.let {
                Text(stringResource(R.string.workout_cardio_aim, measureText(it)), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (spec.end is Measure.Distance && run?.running == true) {
                Text(stringResource(R.string.workout_cardio_to, measureText(spec.end)), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkoutCardioFailedBand(state: DayUiState, actions: DayActions) {
    val cardio = state.cardio ?: return
    if (cardio.run?.saveFailed != true && cardio.distanceRuns.none { it.sessionId != null && it.saveFailed }) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = WorkoutRestShape, modifier = Modifier.fillMaxWidth().testTag("workout-cardio-failed")) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.workout_cardio_save_failed))
            TextButton(onClick = actions.onRetryCardioSave,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Text(stringResource(R.string.cardio_retry))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutLift(
    state: DayUiState,
    actions: DayActions,
    catalog: ExerciseCatalog,
    reducedMotion: Boolean,
    onEditDay: () -> Unit,
) {
    val workout = state.workout ?: return
    if (state.workoutAllLogged && !state.workoutCardioOnlyDone &&
        (workout.shown as? WorkoutSelection.Shown.Group)?.key != "cardio") {
        Text(stringResource(R.string.workout_all_logged, state.totalSets), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        return
    }
    if (workout.shown == WorkoutSelection.Shown.Empty) {
        Text(stringResource(R.string.workout_empty_day, state.viewDayId.orEmpty()), style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onEditDay, colors = neutralTextButtonColors()) { Text(stringResource(R.string.day_menu_edit_day)) }
        return
    }
    if ((workout.shown as? WorkoutSelection.Shown.Group)?.key == "cardio" || state.workoutCardioOnlyDone) {
        WorkoutCardio(state, actions)
        return
    }
    val card = state.workoutCard ?: return
    val focus = workout.focus
    val event = state.timedRep?.takeIf { it.event.programExerciseId == card.programExerciseId }
    val index = event?.event?.setIndex ?: focus?.index ?: card.rows.lastIndex
    val row = card.rows.getOrNull(index)
    val slot = if (event != null) Slot.MAIN else focus?.slot ?: Slot.MAIN
    val timed = (card.timedRepDurationSeconds ?: 0) > 0 && slot == Slot.MAIN && !workout.completedView
    val title = if (slot == Slot.SS) card.partnerTitle.orEmpty() else card.title
    if (card.isSuperset) {
        WorkoutSuperset(state, actions, catalog, reducedMotion)
        return
    }
    val announcement = when {
        workout.completedView -> stringResource(R.string.workout_done_count, card.rows.size, card.rows.size)
        else -> stringResource(R.string.workout_announce_set, title, index + 1, card.rows.size)
    }
    Column(
        Modifier.fillMaxWidth().then(if (timed) Modifier else Modifier.semantics {
            liveRegion = LiveRegionMode.Polite; contentDescription = announcement
        }),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth().then(if (timed) Modifier.semantics {
            liveRegion = LiveRegionMode.Polite; contentDescription = announcement
        } else Modifier), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(card.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onOpenLiftSheet(card.programExerciseId, Slot.MAIN) }, colors = neutralTextButtonColors()) {
                Text(stringResource(R.string.day_all_sets))
            }
        }
        if (workout.completedView) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Check, null)
                Text(stringResource(R.string.workout_done_count, card.rows.size, card.rows.size), style = MaterialTheme.typography.titleMedium)
            }
            Text(card.summaryLine, style = MaterialTheme.typography.bodyLarge)
        } else if (row != null) {
            Text(
                if (row.kind in setOf(SetKind.RAMP, SetKind.TOP, SetKind.BACKOFF)) {
                    stringResource(R.string.workout_set_kind, index + 1, card.rows.size, row.kindLabel)
                } else stringResource(R.string.day_set_of, index + 1, card.rows.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val values = row.values(slot)
            if (values != null) {
                if (timed) WorkoutTimedRepHero(card, event, index, actions) else {
                    val editLabel = stringResource(R.string.workout_edit_set, index + 1)
                    TextButton(
                        onClick = { actions.onOpenSetEditor(card.programExerciseId, index) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel },
                        colors = neutralTextButtonColors(),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Crossfade(
                            targetState = values to card.tracking(slot),
                            animationSpec = if (reducedMotion) snap() else tween(FAST_EFFECTS_MILLIS),
                            label = "workoutHero",
                        ) { (set, tracking) -> WorkoutHero(set, tracking) }
                    }
                }
                if (card.tracking(slot) == TrackingType.TIMED && values.weightDisplay > 0.0) {
                    Text(stringResource(R.string.workout_at_weight, WeightStepper.format(values.weightDisplay), state.unit.name.lowercase()),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                workoutDetail(card, slot, state.unit, catalog)?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WorkoutSuperset(
    state: DayUiState,
    actions: DayActions,
    catalog: ExerciseCatalog,
    reducedMotion: Boolean,
) {
    val workout = state.workout ?: return
    val card = state.workoutCard ?: return
    val focus = workout.focus
    val event = state.timedRep?.takeIf { it.event.programExerciseId == card.programExerciseId }
    val index = event?.event?.setIndex ?: focus?.index ?: card.rows.lastIndex
    val timed = (card.timedRepDurationSeconds ?: 0) > 0 && (event != null || focus?.slot == Slot.MAIN) && !workout.completedView
    val position = if (workout.completedView) {
        stringResource(R.string.workout_done_rounds, card.rows.size, card.rows.size)
    } else stringResource(R.string.workout_superset_round, index + 1, card.rows.size)
    val currentHalf by rememberUpdatedState(workout.focus to workout.completedView)
    Column(
        Modifier.fillMaxWidth().testTag("workout-superset-content").semantics {
            if (!timed) liveRegion = LiveRegionMode.Polite
            isTraversalGroup = true
            traversalIndex = 0f
        },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(position, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (timed) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        AnimatedContent(
            targetState = state,
            contentKey = { it.workout?.focus to it.workout?.completedView },
            transitionSpec = {
                fadeIn(if (reducedMotion) snap() else tween(FAST_EFFECTS_MILLIS))
                    .togetherWith(fadeOut(if (reducedMotion) snap() else tween(FAST_EFFECTS_MILLIS)))
                    .using(null)
            },
            label = "workoutSuperset",
        ) { shown ->
            val outgoing = (shown.workout?.focus to shown.workout?.completedView) != currentHalf
            Column(
                if (outgoing) Modifier.clearAndSetSemantics { } else Modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WorkoutSupersetLift(shown, actions, catalog)
            }
        }
    }
}

@Composable
private fun WorkoutSupersetLift(state: DayUiState, actions: DayActions, catalog: ExerciseCatalog) {
    val workout = state.workout ?: return
    val card = state.workoutCard ?: return
    if (workout.completedView) {
        for (slot in listOf(Slot.MAIN, Slot.SS)) {
            Text(if (slot == Slot.SS) card.partnerTitle.orEmpty() else card.title,
                style = MaterialTheme.typography.headlineMedium)
            val summary = card.rows.mapNotNull { it.values(slot) }.joinToString(", ") {
                SetFormatter.summary(card.tracking(slot), state.unit.toLb(it.weightDisplay), it.reps, it.seconds, state.unit)
            }
            Text(summary, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val focus = workout.focus ?: return
    val event = state.timedRep?.takeIf { it.event.programExerciseId == card.programExerciseId }
    val index = event?.event?.setIndex ?: focus.index
    val row = card.rows.getOrNull(index) ?: return
    val slot = if (event != null) Slot.MAIN else focus.slot
    val timed = (card.timedRepDurationSeconds ?: 0) > 0 && slot == Slot.MAIN
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (slot == Slot.SS) card.partnerTitle.orEmpty() else card.title,
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).testTag("workout-now-lift"))
        TextButton(onClick = { actions.onOpenLiftSheet(card.programExerciseId, slot) }, colors = neutralTextButtonColors()) {
            Text(stringResource(R.string.day_all_sets))
        }
    }
    row.values(slot)?.let { values ->
        if (timed) WorkoutTimedRepHero(card, event, index, actions) else {
            val editLabel = stringResource(R.string.workout_edit_set, index + 1)
            TextButton(
                onClick = { actions.onOpenSetEditor(card.programExerciseId, index) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel },
                colors = neutralTextButtonColors(),
                contentPadding = PaddingValues(0.dp),
            ) {
                WorkoutHero(values, card.tracking(slot))
            }
        }
        if (card.tracking(slot) == TrackingType.TIMED && values.weightDisplay > 0.0) {
            Text(stringResource(R.string.workout_at_weight, WeightStepper.format(values.weightDisplay), state.unit.name.lowercase()),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        workoutDetail(card, slot, state.unit, catalog)?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    WorkoutPartnerCard(card, row, slot, state.unit, actions)
    if (state.workoutThenRest) {
        Text(stringResource(R.string.workout_then_rest), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkoutPartnerCard(card: ExerciseCardState, row: SetRowState, slot: String, unit: WeightUnit, actions: DayActions) {
    val partnerSlot = if (slot == Slot.MAIN) Slot.SS else Slot.MAIN
    val values = row.values(partnerSlot)
    val done = values?.done == true
    val status = stringResource(if (done) R.string.set_edit_done else R.string.plan_next)
    val spokenStatus = stringResource(if (done) R.string.workout_partner_done else R.string.workout_partner_next)
    val name = (if (partnerSlot == Slot.SS) card.partnerTitle else card.title)
        ?.takeIf { it.isNotBlank() && values != null } ?: stringResource(R.string.workout_not_available)
    val value = values?.let { workoutValue(it, card.tracking(partnerSlot), unit) }.orEmpty()
    val description = if (value.isEmpty()) {
        stringResource(R.string.workout_open_sets_without_value, name, spokenStatus)
    } else stringResource(R.string.workout_open_sets, name, spokenStatus, value)
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    OutlinedCard(Modifier.fillMaxWidth().testTag("workout-partner-card")) {
        ListItem(
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (done) Icon(Icons.Default.Check, null)
                    Text(status)
                }
            },
            headlineContent = { Text(name) },
            supportingContent = if (largeFont && value.isNotEmpty()) { { Text(value,
                style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant) } } else null,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!largeFont && value.isNotEmpty()) Text(value,
                        style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("workout-half-$partnerSlot")
                .clickable { actions.onOpenLiftSheet(card.programExerciseId, partnerSlot) }
                .clearAndSetSemantics {
                    contentDescription = description
                    onClick {
                        actions.onOpenLiftSheet(card.programExerciseId, partnerSlot)
                        true
                    }
                },
        )
    }
}

@Composable
private fun WorkoutHero(values: PartnerRowState, tracking: TrackingType) {
    val primary = when (tracking) {
        TrackingType.WEIGHTED -> WeightStepper.format(values.weightDisplay)
        TrackingType.REPS -> values.reps.toString()
        TrackingType.TIMED -> values.seconds.toString()
    }
    val secondary = when (tracking) {
        TrackingType.WEIGHTED -> stringResource(R.string.workout_times_reps, values.reps)
        TrackingType.REPS -> stringResource(R.string.unit_reps)
        TrackingType.TIMED -> stringResource(R.string.unit_seconds)
    }
    WorkoutHeroText(primary, secondary)
}

@Composable
private fun WorkoutHeroText(primary: String, secondary: String? = null) {
    BasicText(
        buildAnnotatedString {
            append(primary)
            if (secondary != null) {
                append(" ")
                withStyle(MaterialTheme.typography.displayMedium.toSpanStyle().copy(
                    fontSize = 0.5.em,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )) {
                    append(secondary)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("workout-hero"),
        style = SetNumerals.copy(color = MaterialTheme.colorScheme.onSurface),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = HERO_MIN_SP.sp,
            maxFontSize = SetNumerals.fontSize,
        ),
    )
}

@Composable
private fun workoutDetail(card: ExerciseCardState, slot: String, unit: WeightUnit, catalog: ExerciseCatalog): String? {
    if (slot == Slot.MAIN) {
        val target = card.headerTarget as? HeaderTarget.Prescribed
        val eachSide = if (target?.eachSide == true) stringResource(R.string.day_target_each_side) else null
        val resistance = target?.resistance?.takeIf { it.isNotBlank() }
        val detail = when {
            eachSide != null && resistance != null -> stringResource(R.string.set_edit_partner_field, eachSide, resistance)
            else -> eachSide ?: resistance
        }
        if (detail != null) return detail
    }
    val plates = if (slot == Slot.MAIN) card.plateLine else {
        val equipment = card.partnerExerciseId?.let(catalog::find)?.equipment.orEmpty()
        DayScreenBuilder.plateLine(card.rows.mapNotNull { it.partner?.let { partner ->
            LoggedSet(unit.toLb(partner.weightDisplay), partner.reps, SetKind.WORK, done = partner.done, seconds = partner.seconds)
        } }, equipment, unit)
    }
    return plates?.let {
        if (it == "0") stringResource(R.string.workout_empty_bar) else stringResource(R.string.workout_plates, it)
    }
}

@Composable
private fun WorkoutRestBand(state: DayUiState, actions: DayActions, catalog: ExerciseCatalog) {
    val rest = state.rest?.takeUnless { it.over }
    val undo = state.workout?.undo
    if (rest == null && undo == null) return
    val minutes = rest?.let { (it.remainingSeconds + 59) / 60 }
    val announcement = minutes?.let { pluralStringResource(R.plurals.workout_rest_remaining, it, it) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = WorkoutRestShape,
        modifier = Modifier.fillMaxWidth().testTag("workout-rest").semantics {
            if (announcement != null) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            }
        }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rest != null) {
                Text(stringResource(R.string.rest_notification_title), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(formatCardioTime(rest.remainingSeconds), style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.clearAndSetSemantics { })
                    LinearProgressIndicator(progress = { rest.remainingFraction }, modifier = Modifier.weight(1f).clearAndSetSemantics { },
                        color = MaterialTheme.colorScheme.onSurface, trackColor = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (undo != null) {
                    val summary = SetFormatter.summary(undo.tracking, undo.set.weightLb, undo.set.reps, undo.set.seconds, undo.unit, spaced = true)
                    val focus = state.workout.focus
                    val label = if (focus != null && undo.programExerciseId == focus.programExerciseId && undo.slot == focus.slot) {
                        stringResource(R.string.workout_undo, summary)
                    } else {
                        val card = state.exercises.firstOrNull { it.programExerciseId == undo.programExerciseId }
                        val exerciseId = if (undo.slot == Slot.SS) card?.partnerExerciseId else card?.exerciseId
                        val shortName = exerciseId?.let(catalog::find)?.shortName ?: undo.title
                        stringResource(R.string.workout_undo_lift, shortName, summary)
                    }
                    TextButton(onClick = actions.onUndoLastLog, modifier = Modifier.heightIn(min = 48.dp), colors = neutralTextButtonColors()) { Text(label) }
                }
                if (rest != null) {
                    TextButton(onClick = { actions.onAdjustRest(15) }, modifier = Modifier.heightIn(min = 48.dp), colors = neutralTextButtonColors()) {
                        Text(stringResource(R.string.rest_notification_add))
                    }
                    TextButton(onClick = actions.onSkipRest, modifier = Modifier.heightIn(min = 48.dp), colors = neutralTextButtonColors()) {
                        Text(stringResource(R.string.rest_notification_skip))
                    }
                }
            }
        }
    }
}

private data class WorkoutActionSpec(
    val label: String,
    val tonal: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val secondary: Pair<String, () -> Unit>? = null,
    val guardKey: Any? = null,
)

@Composable
private fun WorkoutAction(
    label: String, tonal: Boolean, enabled: Boolean, reducedMotion: Boolean, onClick: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null, guardKey: Any? = null,
) {
    var guarded by remember { mutableStateOf(false) }
    var previousKey by remember { mutableStateOf(guardKey) }
    // Compare during composition so a tap cannot slip through before the effect runs.
    if (previousKey != guardKey) {
        previousKey = guardKey
        guarded = true
    }
    LaunchedEffect(guarded, guardKey) {
        if (guarded) {
            delay(600L)
            guarded = false
        }
    }
    fun guardedTap(action: () -> Unit) {
        if (!guarded) {
            guarded = true
            action()
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag(if (tonal) "workout-action-tonal" else "workout-action-filled")
        val content: @Composable () -> Unit = {
            Crossfade(label, animationSpec = if (reducedMotion) snap() else tween(FAST_EFFECTS_MILLIS), label = "workoutAction") {
                Text(it, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (tonal) FilledTonalButton(onClick = { guardedTap(onClick) }, enabled = enabled, modifier = modifier) { content() }
        else Button(onClick = { guardedTap(onClick) }, enabled = enabled, modifier = modifier) { content() }
        secondary?.let { (text, action) ->
            OutlinedButton(onClick = { guardedTap(action) }, enabled = enabled, modifier = Modifier.heightIn(min = 64.dp)) {
                Text(text, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun neutralTextButtonColors() = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
private fun workoutValue(values: PartnerRowState, tracking: TrackingType, unit: WeightUnit? = null): String = when (tracking) {
    TrackingType.WEIGHTED -> stringResource(R.string.workout_weight_reps, WeightStepper.format(values.weightDisplay), values.reps)
    TrackingType.REPS -> pluralStringResource(R.plurals.timed_rep_count, values.reps, values.reps)
    TrackingType.TIMED -> if (unit != null && values.weightDisplay > 0.0) {
        stringResource(R.string.workout_hold_load, values.seconds, WeightStepper.format(values.weightDisplay), unit.name.lowercase())
    } else stringResource(R.string.workout_seconds, values.seconds)
}

internal fun SetRowState.values(slot: String): PartnerRowState? =
    if (slot == Slot.SS) partner else PartnerRowState(weightDisplay, reps, done, seconds)

internal fun ExerciseCardState.tracking(slot: String): TrackingType =
    if (slot == Slot.SS) partnerTracking ?: TrackingType.WEIGHTED else tracking
