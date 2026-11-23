package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.ui.theme.SetNumerals
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.SnackbarResult
import cloud.trotter.log.strength.run.GpsNotice
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.cardio.startsOnItsOwn
import cloud.trotter.log.strength.domain.model.CardioRole
import java.util.Locale
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.domain.units.ClockText
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.SpacingReason
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.progression.SprintDose
import androidx.compose.foundation.layout.BoxWithConstraints
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.WorkoutRestShape
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.TextUnit
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.accentEmphasis

/** M3's fast-effects duration; `MaterialTheme.motionScheme` is internal in this BOM. */
private const val FAST_EFFECTS_MILLIS = 150
private const val HERO_MIN_SP = 48
private const val ACTION_MIN_SP = 14

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
    onNote: (Long) -> Unit = {},
    gpsNotice: GpsNotice? = null,
) {
    val context = LocalContext.current
    val noticeText = gpsNotice?.let { stringResource(if (it == GpsNotice.NEEDS_PRECISE) R.string.gps_precise_notice else R.string.gps_off_notice) }
    val settingsLabel = stringResource(R.string.gps_settings_action)
    LaunchedEffect(gpsNotice) {
        if (noticeText != null) {
            val result = snackbarHost.showSnackbar(noticeText, actionLabel = settingsLabel.takeIf { gpsNotice == GpsNotice.NEEDS_PRECISE })
            actions.onGpsNoticeShown()
            if (result == SnackbarResult.ActionPerformed) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        }
    }
    val offer = state.workout?.undo?.takeIf { state.workout.review == null }
    val message = offer?.let { workoutLoggedMessage(state, it, catalog) }
    val undoLabel = stringResource(R.string.day_undo)
    val latestActions by rememberUpdatedState(actions)
    LaunchedEffect(offer?.programExerciseId, offer?.slot, offer?.index, offer?.set?.completedAtMillis) {
        if (offer != null && message != null) {
            snackbarHost.currentSnackbarData?.dismiss()
            val result = snackbarHost.showSnackbar(message, undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) latestActions.onUndoLastLog()
            else offer.set.completedAtMillis?.let(latestActions.onDismissLogUndo)
        }
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = dayAccent(state.dayIndex), onPrimary = onDayAccent(state.dayIndex),
    )) {
        WorkoutSetContent(state, actions, catalog, onExercises, onFinish, snackbarHost, onEditDay, onBack, nowMillis, onNote)
    }
}

@Composable
private fun workoutLoggedMessage(state: DayUiState, undo: UndoOffer, catalog: ExerciseCatalog): String {
    undo.partnerSet?.let { partner ->
        val card = state.exercises.firstOrNull { it.programExerciseId == undo.programExerciseId }
        val main = if (undo.slot == Slot.MAIN) undo.set else partner
        val ss = if (undo.slot == Slot.MAIN) partner else undo.set
        fun summary(set: LoggedSet, tracking: TrackingType) = SetFormatter.summary(
            tracking, set.weightLb, set.reps, set.seconds, undo.unit, spaced = true).trim()
        return stringResource(R.string.workout_logged_round, undo.index + 1,
            summary(main, card?.tracking ?: undo.tracking),
            summary(ss, card?.partnerTracking ?: TrackingType.WEIGHTED))
    }
    val focus = state.workout?.focus
    val label = if (focus != null && undo.programExerciseId == focus.programExerciseId && undo.slot == focus.slot) {
        stringResource(R.string.workout_set_number, undo.index + 1)
    } else {
        val card = state.exercises.firstOrNull { it.programExerciseId == undo.programExerciseId }
        val exerciseId = if (undo.slot == Slot.SS) card?.partnerExerciseId else card?.exerciseId
        val shortName = exerciseId?.let(catalog::find)?.shortName ?: undo.title
        stringResource(R.string.workout_lift_set_number, shortName, undo.index + 1)
    }
    val summary = undo.set.sides?.let { SetFormatter.sides(undo.tracking, it, undo.unit) } ?: SetFormatter.summary(undo.tracking, undo.set.weightLb, undo.set.reps, undo.set.seconds, undo.unit, spaced = true).trim()
    val effort = undo.set.rir?.takeIf { showsEffort(undo.set.kind, undo.tracking, undo.set.seconds) }?.let { rir ->
        if (state.effortScale.forTracking(undo.tracking) == EffortScale.REPS_LEFT) stringResource(R.string.effort_reps_left_value, rir)
        else effortWord(rir)?.let { stringResource(effortResource(it)) }
    }
    return if (effort == null) stringResource(R.string.workout_logged, label, summary)
    else stringResource(R.string.workout_logged_effort, label, summary, effort)
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
    onNote: (Long) -> Unit,
) {
    val workout = state.workout ?: return
    val review = workout.review
    var highlight by rememberSaveable(review?.programExerciseId, review?.index) { mutableStateOf(false) }
    val highlighted = if (highlight) review?.unrated.orEmpty() else emptyList()
    val nextTitle = state.workoutNextPage?.title
    val allLogged = state.workoutAllLogged
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TopAppBar(
                    title = { Text(stringResource(R.string.today_day_label, state.viewDayId.orEmpty())) },
                    navigationIcon = {
                        IconButton(onClick = { onBack?.invoke() },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.day_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onExercises,
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.List, stringResource(
                                R.string.workout_exercises, state.viewDayId.orEmpty(), state.doneSets, state.totalSets,
                            ))
                        }
                        if (state.beforeStart) {
                            TextButton(onClick = actions.onStart, colors = neutralTextButtonColors(),
                                modifier = Modifier.testTag("workout-start")) {
                                Text(stringResource(R.string.workout_start))
                            }
                        } else if (!allLogged) {
                            TextButton(onClick = onFinish, enabled = state.canFinish, colors = neutralTextButtonColors(),
                                modifier = Modifier.testTag("workout-finish")) {
                                Text(stringResource(R.string.day_finish_partial_confirm_button))
                            }
                        }
                    },
                )
                if (state.beforeStart) {
                    Row(Modifier.widthIn(max = ReadableWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.day_today_only), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.planId != null) TextButton(onClick = actions.onEditPlan, colors = neutralTextButtonColors()) {
                            Text(stringResource(R.string.day_edit_plan))
                        }
                    }
                } else if (!allLogged && state.finishBlockedReason != null) {
                    Box(Modifier.widthIn(max = ReadableWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        WorkoutBlockedReasonText(state.finishBlockedReason)
                    }
                }
            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                    .then(if (state.exercises.firstOrNull { it.programExerciseId == review?.programExerciseId }?.isSuperset == true || state.workoutCard?.isSuperset == true) {
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
                    WorkoutRestBand(state, actions)
                    val focus = workout.focus
                    val card = state.workoutCard
                    val event = state.timedRep?.takeIf { it.event.programExerciseId == card?.programExerciseId }
                    val hold = state.hold?.takeIf { it.event.programExerciseId == card?.programExerciseId }
                    val index = event?.event?.setIndex ?: hold?.event?.setIndex ?: focus?.index ?: -1
                    val slot = if (event != null) Slot.MAIN else focus?.slot ?: Slot.MAIN
                    val values = card?.rows?.getOrNull(index)?.values(slot)
                    val cardio = state.cardio
                    val cardioShown = (workout.shown as? WorkoutSelection.Shown.Group)?.key == "cardio"
                    val resting = state.rest?.over == false
                    val action = when {
                        review != null -> WorkoutActionSpec(
                            stringResource(when {
                                allLogged -> R.string.day_finish_button
                                resting -> R.string.workout_end_rest
                                else -> R.string.workout_next_set
                            }, state.viewDayId.orEmpty()),
                            resting && !allLogged, review.unrated.isEmpty() && (!allLogged || state.canFinish),
                            if (allLogged) onFinish else actions.onContinueWorkout,
                            guardKey = Triple(review.programExerciseId, review.index, allLogged to resting),
                            onBlockedTap = { highlight = true },
                            blockedDescription = if (review.unrated.isNotEmpty()) stringResource(R.string.workout_mark_effort) else null,
                        )
                        allLogged -> if (state.workoutCardioOnlyDone) null else WorkoutActionSpec(
                            stringResource(R.string.day_finish_button, state.viewDayId.orEmpty()), resting,
                            state.canFinish, onFinish, guardKey = "finish")
                        workout.shown == WorkoutSelection.Shown.Empty -> null
                        cardioShown && cardio != null -> {
                            val run = cardio.run
                            val guardKey = run?.let { Triple(it.runId, it.position, it.running) }
                            val spec = cardio.block?.sets?.getOrNull(cardio.position)
                            val end = spec?.end
                            val sprintRep = cardio.sprintRep
                            when {
                                cardio.completed || (workout.shown as? WorkoutSelection.Shown.Group)?.complete == true ->
                                    if (state.totalSets == 0 || nextTitle == null) null else WorkoutActionSpec(
                                        stringResource(R.string.day_next_exercise, nextTitle.orEmpty()), resting, true,
                                        actions.onNextWorkoutLift, guardKey = guardKey)
                                run?.running == true && sprintRep?.armed == true -> WorkoutActionSpec(
                                    stringResource(R.string.day_cardio_stop_button), false, true,
                                    { actions.onStopSprintTimer(run.action()) }, guardKey = guardKey)
                                run?.running == true && spec?.role == CardioRole.WORK && sprintRep != null && !sprintRep.armed -> WorkoutActionSpec(
                                    stringResource(R.string.sprint_start_on_beep), false, true,
                                    { actions.onArmSprintTimer(run.action()) }, guardKey = guardKey)
                                run?.running == true && end is Measure.Distance -> WorkoutActionSpec(
                                    stringResource(R.string.cardio_reached, if (sprintRep != null) SprintEvent.label(end.metres, distanceUnitFor(cardio.block?.activity))
                                        else distanceUnitFor(cardio.block?.activity).format(end.metres)), false, true,
                                    { actions.onReachCardioTarget(run.action()) },
                                    WorkoutSecondaryAction.Text(stringResource(R.string.day_cardio_stop_button)) {
                                        actions.onStopCardio(run.action())
                                    }, guardKey)
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
                                null -> if (resting) WorkoutActionSpec(stringResource(R.string.workout_end_rest), true, true,
                                    actions.onContinueWorkout) else WorkoutActionSpec(stringResource(R.string.day_cardio_start_button), false,
                                    !state.executionBusy && state.timedRep == null,
                                    { actions.onStartTimedRep(card.programExerciseId, index) })
                            }
                        }
                        card != null && slot == Slot.MAIN && (hold != null || card.holdTargetSeconds(index) > 0) -> {
                            val guardKey = hold?.event?.let { Triple(it.wallStart, it.running, it.heldSeconds.size) }
                            when {
                                hold?.event?.running == true -> WorkoutActionSpec(stringResource(R.string.day_cardio_stop_button), true, true,
                                    actions.onStopHold, guardKey = guardKey)
                                hold != null -> WorkoutActionSpec(stringResource(R.string.day_cardio_start_button), false, !hold.event.complete,
                                    { actions.onStartHold(card.programExerciseId, index) },
                                    secondary = if (hold.event.complete) null else WorkoutSecondaryAction.Text(
                                        stringResource(R.string.workout_hold_end), actions.onStopHold), guardKey = guardKey)
                                resting -> WorkoutActionSpec(stringResource(R.string.workout_end_rest), true, true, actions.onContinueWorkout)
                                else -> WorkoutActionSpec(stringResource(R.string.day_cardio_start_button), false,
                                    !state.executionBusy && state.timedRep == null, { actions.onStartHold(card.programExerciseId, index) },
                                    secondary = if (card.holdPressureCaution) null else WorkoutSecondaryAction.Text(
                                        stringResource(R.string.workout_hold_past_target)) { actions.onStartHoldPastTarget(card.programExerciseId, index) })
                            }
                        }
                        values != null && card != null -> if (resting) WorkoutActionSpec(
                            stringResource(R.string.workout_end_rest), true, true, actions.onContinueWorkout,
                        ) else WorkoutActionSpec(
                            if (card.isSuperset) stringResource(R.string.workout_log_round, index + 1) else
                                stringResource(R.string.workout_log_measurement, workoutValue(values, card.tracking(slot), state.unit, includeTimedWeight = false)), resting, true,
                            actions.onLogFocusedSet,
                            secondary = if (!card.isSuperset && card.tracking(slot) != TrackingType.TIMED) {
                                WorkoutSecondaryAction.DecrementReps(values.reps > 1) { actions.onDecrementFocusedReps(card.programExerciseId, Slot.MAIN, index) }
                            } else null)
                        else -> null
                    }
                    if (action != null) {
                        WorkoutAction(action.label, action.tonal, action.enabled, reducedMotion,
                            action.onClick, action.secondary, action.guardKey,
                            onBlockedTap = action.onBlockedTap, blockedDescription = action.blockedDescription, tapAllowed = {
                                !allLogged || workout.lastCompletionMillis?.let { nowMillis() - it >= 3_000L } != false
                            })
                        if (!action.enabled && (review == null || allLogged)) WorkoutBlockedReasonText(when {
                            allLogged -> state.finishBlockedReason
                            cardioShown -> state.cardioStartBlockedReason
                            state.timedRepRunning || state.hold != null || state.cardioRunning -> WorkoutBlockedReason.TIMER_RUNNING
                            else -> null
                        })
                    }
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
                    Column(modifier.then(scrollModifier)) { WorkoutLift(state, actions, catalog, reducedMotion, onEditDay, onNote, highlighted) }
                } else {
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it.workout?.review?.let { review -> "exercise:${review.programExerciseId}" } ?: when (val shown = it.workout?.shown) {
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
                            WorkoutLift(shown, actions, catalog, reducedMotion, onEditDay, onNote, highlighted)
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
private fun WorkoutTimedRepHero(card: ExerciseCardState, timed: TimedRepState?, index: Int, actions: DayActions, maxFontSize: TextUnit = SetNumerals.fontSize) {
    val duration = card.timedRepDurationSeconds ?: return
    if (timed?.event?.awaitingCount == true) {
        Text(stringResource(R.string.timed_rep_time_up), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.fillMaxWidth().testTag("workout-hero"))
        return
    }
    val seconds = timed?.remainingSeconds ?: duration
    if (timed == null) {
        val editLabel = stringResource(R.string.workout_edit_set, index + 1)
        TextButton(onClick = { actions.onOpenSetEditor(card.programExerciseId, index) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel },
            colors = neutralTextButtonColors(), contentPadding = PaddingValues(0.dp),
            // The default pill shape clips the corners of numerals this tall.
            shape = RectangleShape) {
            WorkoutHeroText(formatCardioTime(seconds), maxFontSize = maxFontSize)
        }
    } else {
        WorkoutHeroText(formatCardioTime(seconds), maxFontSize = maxFontSize)
        WorkoutClockProgress(seconds.toFloat() / duration)
    }
    Text(stringResource(R.string.workout_clock_remaining), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    card.timedRepAim?.let {
        Text(stringResource(R.string.workout_timed_rep_target, it, formatCardioTime(duration)), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkoutHoldHero(hold: HoldState, breathingCue: Boolean, maxFontSize: TextUnit) {
    val event = hold.event
    WorkoutHeroText(formatCardioTime(hold.heldSeconds), maxFontSize = maxFontSize)
    Text(stringResource(R.string.workout_hold_held), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    WorkoutClockProgress(hold.fraction)
    Text(stringResource(R.string.workout_hold_target, formatCardioTime(event.targetSeconds)),
        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (event.sides > 1) Text(stringResource(if (event.sideNumber == 1) R.string.workout_hold_left else R.string.workout_hold_right),
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (breathingCue && event.running) Text(stringResource(R.string.workout_hold_keep_breathing),
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun WorkoutCardio(state: DayUiState, actions: DayActions) {
    val cardio = state.cardio ?: return
    val block = cardio.block ?: return
    val run = cardio.run
    val done = cardio.completed || state.workoutCardioOnlyDone ||
        (state.workout?.shown as? WorkoutSelection.Shown.Group)?.complete == true
    val activity = if (done && run == null) cardio.loggedActivity ?: block.activity else block.activity
    val entryUnit = DistanceUnit.forActivity(cardio.distanceUnit, block.activity)
    val spec = block.sets.getOrNull(cardio.position)
    val position = spec?.let { stringResource(R.string.workout_cardio_position, roleName(it.role), cardio.position + 1, block.sets.size) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(Modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Polite
            if (position != null) contentDescription = position
        }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(cardioActivityName(activity), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (!done && run?.startedAt == null && !state.executionBusy && state.timedRep == null) {
                TextButton(onClick = actions.onOpenPastCardio, colors = neutralTextButtonColors()) {
                    Text(stringResource(R.string.cardio_log_past))
                }
            }
            TextButton(onClick = actions.onOpenCardioSets, colors = neutralTextButtonColors()) { Text(stringResource(R.string.day_all_sets)) }
        }
        if (cardio.hasSaveFailure) {
            Text(stringResource(R.string.operation_cardio_recorded_time, formatCardioTime(cardio.retainedSeconds)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (done) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Check, null)
                Text(stringResource(R.string.set_edit_done), style = MaterialTheme.typography.titleMedium)
            }
            CardioSummary(run?.resultSets ?: cardio.loggedSets, run?.results?.sumOf { it.seconds } ?: cardio.loggedSeconds ?: 0,
                if (run != null) run.block.activity else cardio.loggedActivity)
        } else if (spec != null) {
            Text(position.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val sprintRep = cardio.sprintRep
            when (val end = spec.end) {
                is Measure.Time -> if (block.openEnded) {
                    WorkoutHeroText(formatCardioTime(if (run?.running == true) cardio.elapsedSeconds else 0))
                    Text(stringResource(R.string.workout_clock_elapsed), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.workout_cardio_aim, measureText(end, block.activity)), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (run?.running == true && (cardio.gps == null || cardio.gps.shown == GpsShown.LOCATION_OFF)) OutlinedTextField(
                        value = cardio.runDistanceText, onValueChange = actions.onCardioRunDistanceChange,
                        label = { Text(stringResource(R.string.cardio_distance_label, entryUnit.symbol)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (entryUnit == DistanceUnit.M) KeyboardType.Number else KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("workout-cardio-distance"),
                    )
                } else {
                    WorkoutHeroText(formatCardioTime(if (run?.running == true) cardio.stepSecondsLeft else end.seconds))
                    Text(stringResource(R.string.workout_clock_remaining), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (run?.running == true) {
                        WorkoutClockProgress(cardio.stepSecondsLeft.toFloat() / end.seconds)
                        val next = block.sets.getOrNull(cardio.position + 1)
                        if (spec.role == CardioRole.RECOVERY && next?.role == CardioRole.WORK && startsOnItsOwn(spec, next)) {
                            Text(stringResource(R.string.workout_cardio_next_starts), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is Measure.Distance -> if (run?.running == true) {
                    if (sprintRep?.armed == true) WorkoutSprintTimer(sprintRep)
                    else if (sprintRep != null && spec.role == CardioRole.WORK) {
                        val label = SprintEvent.label(end.metres, distanceUnitFor(block.activity))
                        WorkoutHeroText(label.substringBeforeLast(' '), label.substringAfterLast(' '))
                    } else {
                        WorkoutHeroText(formatCardioTime(cardio.elapsedSeconds))
                        Text(stringResource(R.string.workout_clock_elapsed), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (cardio.sprint != null) {
                    val label = SprintEvent.label(end.metres, distanceUnitFor(block.activity))
                    WorkoutHeroText(label.substringBeforeLast(' '), label.substringAfterLast(' '))
                } else WorkoutHeroText(distanceUnitFor(block.activity).inputText(end.metres), distanceUnitFor(block.activity).symbol)
            }
            if (run?.running == true && sprintRep != null) {
                if (spec.role == CardioRole.WORK && !sprintRep.armed) {
                    val end = spec.end
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (end is Measure.Distance) {
                            TextButton(onClick = { actions.onReachCardioTarget(run.action()) }, colors = neutralTextButtonColors()) {
                                Text(stringResource(R.string.cardio_reached, SprintEvent.label(end.metres, distanceUnitFor(block.activity))))
                            }
                        }
                        TextButton(onClick = { actions.onOpenSprintTime(run.action()) }, colors = neutralTextButtonColors()) {
                            Text(stringResource(R.string.sprint_enter_time))
                        }
                        TextButton(onClick = { actions.onStopCardio(run.action()) }, colors = neutralTextButtonColors()) {
                            Text(stringResource(R.string.sprint_end_rep))
                        }
                    }
                    SprintProtocolChips(sprintRep.protocol, actions.onSprintProtocol)
                }
                val lastTime = sprintRep.lastTime
                if (spec.role == CardioRole.RECOVERY && lastTime != null) {
                    Text(stringResource(R.string.sprint_rep_time, ClockText.centis(lastTime.centis),
                        stringResource(lastTime.timingMethod.timedLabelRes())))
                    if (lastTime.timingMethod == TimingMethod.PHONE) Text(stringResource(R.string.sprint_phone_note))
                }
            }
            spec.aim?.let {
                Text(stringResource(R.string.workout_cardio_aim, measureText(it, block.activity)), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            cardio.gps?.let { WorkoutGps(it, cardio.distanceUnit, activity, run?.running == true) }
            if (run == null && !done) {
                cardio.sprint?.let { WorkoutSprintStart(it, block.sets.any { set -> set.hard }) }
                when (val nextTime = cardio.nextTime) {
                    is CardioNextTime.Offer ->
                        CardioNextTimeChip(nextTime, actions.onApplyCardioNextTime)
                    CardioNextTime.Repeat ->
                        Text(stringResource(R.string.next_time_repeat))
                    null -> Unit
                }
            }
            val end = spec.end
            val sprintDistanceHero = sprintRep != null && !sprintRep.armed && spec.role == CardioRole.WORK
            if (end is Measure.Distance && run?.running == true && !sprintDistanceHero) {
                Text(stringResource(R.string.workout_cardio_to, if (sprintRep != null) {
                    SprintEvent.label(end.metres, distanceUnitFor(block.activity))
                } else measureText(end, block.activity)), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkoutSprintTimer(sprint: SprintRepUi) {
    val beep = sprint.beepElapsedMillis ?: return
    val elapsedMillis = LocalSprintElapsedMillis.current
    var now by remember(beep, elapsedMillis) { mutableStateOf(elapsedMillis()) }
    LaunchedEffect(beep, elapsedMillis) {
        // A frame-driven loop never lets Compose tests go idle; delay ticks fast enough for hundredths.
        while (true) {
            now = elapsedMillis()
            delay(if (now < beep) minOf(250L, beep - now) else 16L)
        }
    }
    if (now < beep) {
        Text(stringResource(R.string.sprint_beep_in, ((beep - now + 999L) / 1_000L).toInt()),
            style = MaterialTheme.typography.headlineLarge)
    } else WorkoutHeroText(ClockText.centis(((now - beep) / 10L).toInt().coerceAtLeast(0)))
}

@Composable
private fun WorkoutSprintStart(sprint: SprintStartUi, maximal: Boolean) {
    when (sprint.dose) {
        SprintDose.SHORTENED -> sprint.shortenedReps?.let { reps ->
            WorkoutSprintLine(pluralStringResource(R.plurals.sprint_allowance_shortened, reps, reps))
        }
        SprintDose.SUBSTITUTE -> WorkoutSprintLine(stringResource(R.string.sprint_allowance_substitute))
        else -> Unit
    }
    val gap = sprint.daysSince
    if (maximal && gap != null && gap < sprint.spacingDays && sprint.dose != SprintDose.SUBSTITUTE) {
        WorkoutSprintLine(when (gap) {
            0L -> stringResource(R.string.sprint_note_today)
            1L -> stringResource(R.string.sprint_note_yesterday)
            else -> pluralStringResource(R.plurals.sprint_note_days_ago, gap.toInt(), gap.toInt())
        })
        val three = sprint.spacingDays == 3
        val reason = if (three) when (sprint.reason) {
            SpacingReason.FORTY_OR_OLDER -> R.string.sprint_reason_forty
            SpacingReason.HAMSTRING_STRAIN -> R.string.sprint_reason_strain
            SpacingReason.STARTING_OUT -> R.string.sprint_reason_starting
            SpacingReason.FOR_NOW -> R.string.sprint_reason_for_now
        } else R.string.sprint_reason_for_now
        WorkoutSprintLine(stringResource(if (three) R.string.sprint_spacing_three else R.string.sprint_spacing_two, stringResource(reason)))
    }
    if (sprint.nineTenths) WorkoutSprintLine(stringResource(R.string.sprint_nine_tenths))
}

@Composable
private fun WorkoutSprintLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WorkoutGps(gps: CardioGpsState, setting: DistanceUnit, activity: cloud.trotter.log.strength.domain.model.CardioActivity, running: Boolean) {
    val context = LocalContext.current
    val label = stringResource(when (gps.shown) {
        GpsShown.READY -> R.string.gps_ready
        GpsShown.FINDING -> R.string.gps_finding
        GpsShown.NO_SIGNAL -> R.string.gps_no_signal
        GpsShown.PARTIAL -> R.string.gps_lost_signal
        GpsShown.LOCATION_OFF -> R.string.gps_location_off
        GpsShown.LIVE -> R.string.gps_distance_label
    })
    val turnOn: @Composable () -> Unit = {
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
            Text(stringResource(R.string.gps_turn_on))
        }
    }
    if (!running) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("workout-gps-status")) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (gps.shown == GpsShown.LOCATION_OFF) turnOn()
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
            if (gps.shown != GpsShown.LOCATION_OFF) Text(
                gps.metres?.takeUnless { gps.shown == GpsShown.FINDING }
                    ?.let { DistanceUnit.forActivity(setting, activity).format(it) }.orEmpty(),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("workout-gps-distance"))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("workout-gps-status"))
            if (gps.shown == GpsShown.LOCATION_OFF) turnOn()
        }
        if (gps.shown == GpsShown.LIVE || gps.shown == GpsShown.NO_SIGNAL) {
            val unit = if (setting == DistanceUnit.MI) DistanceUnit.MI else DistanceUnit.KM
            val ride = CardioFamily.of(activity) == CardioFamily.RIDE
            val speed = gps.metresPerSecond?.takeIf { it > 0 }
            val value = if (speed == null) "" else if (ride) stringResource(R.string.gps_speed_value,
                String.format(Locale.US, "%.1f", speed * 3600 * unit.fromMetres(1)),
                stringResource(if (unit == DistanceUnit.MI) R.string.gps_speed_mph else R.string.gps_speed_kmh))
                else stringResource(R.string.gps_pace_value, formatCardioTime((1.0 / (speed * unit.fromMetres(1))).roundToInt()), unit.symbol)
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("workout-gps-pace"))
                Text(stringResource(if (ride) R.string.gps_speed_label else R.string.gps_pace_label),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkoutCardioFailedBand(state: DayUiState, actions: DayActions) {
    val cardio = state.cardio ?: return
    if (!cardio.hasSaveFailure) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = WorkoutRestShape, modifier = Modifier.fillMaxWidth().testTag("workout-cardio-failed")) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.operation_cardio_not_saved), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = actions.onRetryCardioSave,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Text(stringResource(R.string.receipt_try_again))
            }
        }
    }
}

private val CardioCardState.hasSaveFailure: Boolean get() =
    run?.saveFailed == true || distanceRuns.any { it.sessionId != null && it.saveFailed }

private val CardioCardState.retainedSeconds: Int get() =
    run?.takeIf { it.saveFailed }?.results?.sumOf { it.seconds }
        ?: distanceRuns.firstOrNull { it.sessionId != null && it.saveFailed }?.results?.sumOf { it.seconds }
        ?: loggedSeconds ?: 0

@Composable
private fun WorkoutBlockedReasonText(reason: WorkoutBlockedReason?) {
    if (reason == null) return
    Text(stringResource(when (reason) {
        WorkoutBlockedReason.NO_LOGGED_SETS -> R.string.operation_finish_log_set
        WorkoutBlockedReason.TIMER_RUNNING -> R.string.operation_stop_timer
        WorkoutBlockedReason.CARDIO_NOT_SAVED -> R.string.operation_cardio_retry_before_start
        WorkoutBlockedReason.CARDIO_SAVING -> R.string.operation_cardio_saving
        WorkoutBlockedReason.CARDIO_IN_PROGRESS -> R.string.operation_cardio_in_progress
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutLift(
    state: DayUiState,
    actions: DayActions,
    catalog: ExerciseCatalog,
    reducedMotion: Boolean,
    onEditDay: () -> Unit,
    onNote: (Long) -> Unit,
    highlighted: List<String>,
) {
    val workout = state.workout ?: return
    workout.review?.let {
        WorkoutReviewContent(state, it, actions, catalog, reducedMotion, onNote, highlighted)
        return
    }
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
    val hold = state.hold?.takeIf { it.event.programExerciseId == card.programExerciseId }
    val index = event?.event?.setIndex ?: hold?.event?.setIndex ?: focus?.index ?: card.rows.lastIndex
    val row = card.rows.getOrNull(index)
    val slot = if (event != null) Slot.MAIN else focus?.slot ?: Slot.MAIN
    val timed = (card.timedRepDurationSeconds ?: 0) > 0 && slot == Slot.MAIN && !workout.completedView
    val clocked = timed || hold != null
    val title = if (slot == Slot.SS) card.partnerTitle.orEmpty() else card.title
    if (card.isSuperset) {
        WorkoutSuperset(state, actions, catalog, reducedMotion, onNote)
        return
    }
    val announcement = when {
        workout.completedView -> stringResource(R.string.workout_done_count, card.rows.size, card.rows.size)
        else -> stringResource(R.string.workout_announce_set, title, index + 1, card.rows.size)
    }
    Column(
        Modifier.fillMaxWidth().then(if (clocked) Modifier else Modifier.semantics {
            liveRegion = LiveRegionMode.Polite; contentDescription = announcement
        }),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WorkoutLiftHeader(card, actions, onNote, if (clocked) Modifier.semantics {
            liveRegion = LiveRegionMode.Polite; contentDescription = announcement
        } else Modifier)
        if (workout.completedView) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Check, null)
                Text(stringResource(R.string.workout_done_count, card.rows.size, card.rows.size), style = MaterialTheme.typography.titleMedium)
            }
            Text(card.summaryLine, style = MaterialTheme.typography.bodyLarge)
        } else if (row != null) {
            WorkoutSetCard(state, card, slot, index, actions, catalog, reducedMotion)
        }
    }
}

@Composable
private fun WorkoutLiftHeader(card: ExerciseCardState, actions: DayActions, onNote: (Long) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(Modifier.fillMaxWidth().then(modifier), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.title, style = MaterialTheme.typography.headlineMedium)
            if (card.note.isNotBlank()) Text(card.note,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.note_edit_action)) {
                    onNote(card.programExerciseId)
                })
        }
        TextButton(onClick = { actions.onOpenLiftSheet(card.programExerciseId, Slot.MAIN) }, colors = neutralTextButtonColors()) {
            Text(stringResource(R.string.day_all_sets))
        }
    }
}

@Composable
private fun WorkoutSetCard(
    state: DayUiState, card: ExerciseCardState, slot: String, index: Int, actions: DayActions,
    catalog: ExerciseCatalog, reducedMotion: Boolean, logged: Boolean = false, compact: Boolean = false,
    effortSlots: List<String> = emptyList(), highlighted: List<String> = emptyList(),
) {
    val row = card.rows.getOrNull(index) ?: return
    val values = row.values(slot) ?: return
    val tracking = card.tracking(slot)
    Card(Modifier.fillMaxWidth().testTag("workout-set-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (compact) {
                val title = if (slot == Slot.SS) card.partnerTitle.orEmpty() else card.title
                if (logged) Text(title, style = MaterialTheme.typography.titleMedium) else {
                    val allSetsLabel = stringResource(R.string.workout_all_sets_for, title)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            (if (slot == Slot.SS) card.partnerMarker else card.marker)?.let {
                                Text(stringResource(it.label), style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = { actions.onOpenLiftSheet(card.programExerciseId, slot) },
                            colors = neutralTextButtonColors(),
                            modifier = Modifier.testTag(if (slot == Slot.SS) "workout-all-sets-ss" else "workout-all-sets-main")
                                .semantics { contentDescription = allSetsLabel }) {
                            Text(stringResource(R.string.day_all_sets))
                        }
                    }
                }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (row.kind in setOf(SetKind.RAMP, SetKind.TOP, SetKind.BACKOFF)) {
                        stringResource(R.string.workout_set_kind, index + 1, card.rows.size, row.kindLabel)
                    } else stringResource(R.string.day_set_of, index + 1, card.rows.size),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (if (slot == Slot.SS) card.partnerMarker else card.marker)?.let {
                    Text(stringResource(it.label), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (logged || (compact && values.done)) {
                val editLabel = stringResource(R.string.workout_edit_set, index + 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { actions.onOpenSetEditor(card.programExerciseId, index) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = editLabel },
                        colors = neutralTextButtonColors(), contentPadding = PaddingValues(0.dp), shape = RectangleShape) {
                        Box(Modifier.fillMaxWidth()) {
                            WorkoutHero(values, tracking, state.unit, if (compact) 56.sp else SetNumerals.fontSize)
                        }
                    }
                    Icon(Icons.Default.Check, null, tint = Done, modifier = Modifier.size(if (compact) 24.dp else 32.dp).testTag("workout-set-done"))
                }
            } else {
                val timed = (card.timedRepDurationSeconds ?: 0) > 0 && slot == Slot.MAIN
                val event = state.timedRep?.takeIf { it.event.programExerciseId == card.programExerciseId }
                val hold = state.hold?.takeIf {
                    it.event.programExerciseId == card.programExerciseId && it.event.setIndex == index && slot == Slot.MAIN
                }
                val hero: @Composable () -> Unit = {
                    if (timed) WorkoutTimedRepHero(card, event, index, actions, if (compact) 56.sp else SetNumerals.fontSize)
                    else if (hold != null) WorkoutHoldHero(hold, card.holdPressureCaution, if (compact) 56.sp else SetNumerals.fontSize)
                    else {
                        WorkoutEditableHero(card, slot, index, actions) {
                            Crossfade(values to tracking,
                                animationSpec = if (reducedMotion) snap() else tween(FAST_EFFECTS_MILLIS), label = "workoutHero") {
                                (set, type) -> WorkoutHero(set, type, state.unit, if (compact) 56.sp else SetNumerals.fontSize)
                            }
                        }
                    }
                }
                if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { hero() }
                    OutlinedIconButton(onClick = { actions.onDecrementFocusedReps(card.programExerciseId, slot, index) },
                        enabled = values.reps > 1 && tracking != TrackingType.TIMED && !timed,
                        modifier = Modifier.size(48.dp).testTag(if (slot == Slot.SS) "workout-minus-ss" else "workout-minus-main")) {
                        Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.workout_one_fewer_rep))
                    }
                } else hero()
            }

            if (!compact || !logged) {
                val detailStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge
                if (tracking == TrackingType.TIMED && values.weightDisplay > 0.0) {
                    Text(stringResource(R.string.workout_at_weight, WeightStepper.format(values.weightDisplay), state.unit.name.lowercase()),
                        style = detailStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                workoutDetail(card, slot, index, state.unit, catalog)?.let {
                    Text(it, style = detailStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (logged && slot in effortSlots) {
                SetEffortRow(values.rir, state.effortScale.forTracking(tracking), { actions.onSetEffort(card.programExerciseId, slot, index, it) },
                    { actions.onSetRepsLeft(card.programExerciseId, slot, index, it) },
                    if (slot == Slot.SS) "workout-effort-ss" else "workout-effort",
                    if (slot in highlighted) accentEmphasis(state.dayIndex) else null)
            }
        }
    }
}

@Composable
private fun WorkoutReviewContent(
    state: DayUiState, review: WorkoutReview, actions: DayActions, catalog: ExerciseCatalog,
    reducedMotion: Boolean, onNote: (Long) -> Unit, highlighted: List<String>,
) {
    val card = state.exercises.firstOrNull { it.programExerciseId == review.programExerciseId } ?: return
    val announcement = stringResource(R.string.workout_announce_logged, card.title, review.index + 1, card.rows.size)
    Column(Modifier.fillMaxWidth().semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = announcement
        if (card.isSuperset) { isTraversalGroup = true; traversalIndex = 0f }
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.isSuperset) {
            Text(stringResource(R.string.workout_superset_round, review.index + 1, card.rows.size),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else WorkoutLiftHeader(card, actions, onNote)
        val slots = if (card.isSuperset) listOf(Slot.MAIN, Slot.SS) else listOf(Slot.MAIN)
        for (slot in slots) WorkoutSetCard(state, card, slot, review.index, actions, catalog, reducedMotion,
            logged = true, compact = card.isSuperset, effortSlots = review.effortSlots, highlighted = highlighted)
        WorkoutNextCard(state, review)
    }
}

@Composable
private fun WorkoutNextCard(state: DayUiState, review: WorkoutReview) {
    val workout = state.workout ?: return
    val focus = workout.focus
    val card = state.exercises.firstOrNull { it.programExerciseId == focus?.programExerciseId }
    val title = when {
        card != null && focus != null -> when {
            card.programExerciseId != review.programExerciseId -> card.title
            card.isSuperset -> stringResource(R.string.workout_round_of, focus.index + 1, card.rows.size)
            else -> stringResource(R.string.day_set_of, focus.index + 1, card.rows.size)
        }
        (workout.shown as? WorkoutSelection.Shown.Group)?.key == "cardio" -> state.cardio?.label
        else -> null
    } ?: return
    Card(Modifier.fillMaxWidth().testTag("workout-next-card"), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.day_next_exercise, title), style = MaterialTheme.typography.titleMedium)
            if (card != null && !card.isSuperset && focus != null) card.rows.getOrNull(focus.index)?.values(focus.slot)?.let {
                Text(workoutValue(it, card.tracking(focus.slot), state.unit), style = MaterialTheme.typography.displaySmall)
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
    onNote: (Long) -> Unit,
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
                WorkoutSupersetLift(shown, actions, catalog, reducedMotion, onNote)
            }
        }
    }
}

@Composable
private fun WorkoutSupersetLift(state: DayUiState, actions: DayActions, catalog: ExerciseCatalog, reducedMotion: Boolean, onNote: (Long) -> Unit) {
    val workout = state.workout ?: return
    val card = state.workoutCard ?: return
    if (workout.completedView) {
        for (slot in listOf(Slot.MAIN, Slot.SS)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (slot == Slot.SS) card.partnerTitle.orEmpty() else card.title,
                    style = MaterialTheme.typography.headlineMedium)
                if (card.note.isNotBlank()) Text(card.note,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.note_edit_action)) {
                        onNote(card.programExerciseId)
                    })
            }
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
    if (card.note.isNotBlank()) Text(card.note,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.note_edit_action)) {
            onNote(card.programExerciseId)
        })
    for (slot in listOf(Slot.MAIN, Slot.SS)) {
        WorkoutSetCard(state, card, slot, index, actions, catalog, reducedMotion, compact = true)
    }
}

@Composable
private fun WorkoutEditableHero(
    card: ExerciseCardState,
    slot: String,
    index: Int,
    actions: DayActions,
    hero: @Composable () -> Unit,
) {
    val editLabel = stringResource(R.string.workout_edit_set, index + 1)
    val offer = (if (slot == Slot.SS) card.partnerNextTime else card.nextTime) as? NextTime.Offer
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { actions.onOpenSetEditor(card.programExerciseId, index) },
            modifier = Modifier.align(Alignment.CenterVertically).semantics { contentDescription = editLabel },
            colors = neutralTextButtonColors(),
            contentPadding = PaddingValues(0.dp),
            // The default pill shape clips the corners of numerals this tall.
            shape = RectangleShape,
        ) { hero() }
        if (offer != null) Box(Modifier.align(Alignment.CenterVertically)) {
            NextTimeOfferChip(offer, slot) {
                actions.onNextTime(card.programExerciseId, slot, it)
            }
        }
    }
}

@Composable
private fun WorkoutHero(values: PartnerRowState, tracking: TrackingType, unit: WeightUnit, maxFontSize: TextUnit = SetNumerals.fontSize) {
    val primary = when (tracking) {
        TrackingType.WEIGHTED -> WeightStepper.format(values.weightDisplay)
        TrackingType.REPS -> values.reps.toString()
        TrackingType.TIMED -> values.seconds.toString()
    }
    val secondary = when (tracking) {
        TrackingType.WEIGHTED -> "${unit.name.lowercase()} ${stringResource(R.string.workout_times_reps, values.reps)}"
        TrackingType.REPS -> stringResource(R.string.unit_reps)
        TrackingType.TIMED -> stringResource(R.string.unit_seconds)
    }
    WorkoutHeroText(primary, secondary, Modifier, maxFontSize)
}

@Composable
private fun WorkoutHeroText(primary: String, secondary: String? = null, modifier: Modifier = Modifier.fillMaxWidth(), maxFontSize: TextUnit = SetNumerals.fontSize) {
    BasicText(
        buildAnnotatedString {
            append(primary)
            if (secondary != null) {
                append(" ")
                withStyle(MaterialTheme.typography.titleLarge.toSpanStyle().copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )) {
                    append(secondary)
                }
            }
        },
        modifier = modifier.testTag("workout-hero"),
        style = SetNumerals.copy(color = MaterialTheme.colorScheme.onSurface),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = HERO_MIN_SP.sp,
            maxFontSize = maxFontSize,
        ),
    )
}

@Composable
private fun workoutDetail(card: ExerciseCardState, slot: String, index: Int, unit: WeightUnit, catalog: ExerciseCatalog): String? {
    if (slot == Slot.MAIN) {
        val target = card.headerTarget as? HeaderTarget.Prescribed
        val eachSide = if (card.headerTarget.eachSide) stringResource(if (card.holdTargetSeconds(index) > 0)
            R.string.day_target_each_side_left_first else R.string.day_target_each_side) else null
        val resistance = target?.resistance?.takeIf { it.isNotBlank() }
        val detail = when {
            eachSide != null && resistance != null -> stringResource(R.string.set_edit_partner_field, eachSide, resistance)
            else -> eachSide ?: resistance
        }
        if (detail != null) return detail
    }
    val values = card.rows.getOrNull(index)?.values(slot) ?: return null
    val equipment = if (slot == Slot.MAIN) card.equipment else {
        card.partnerExerciseId?.let(catalog::find)?.equipment.orEmpty()
    }
    val plates = DayScreenBuilder.plateLine(
        listOf(LoggedSet(unit.toLb(values.weightDisplay), values.reps, SetKind.WORK,
            done = values.done, seconds = values.seconds)),
        equipment, unit, card.tracking(slot), focusedIndex = 0,
    )
    return plates?.let {
        if (it == "0") stringResource(R.string.workout_empty_bar)
        else stringResource(R.string.workout_plates_with_unit, it, unit.name.lowercase())
    }
}

@Composable
private fun WorkoutRestBand(state: DayUiState, actions: DayActions) {
    val rest = state.rest?.takeUnless { it.over } ?: return
    val resources = LocalResources.current
    val announcement = formatRestAnnouncement(resources, rest.remainingSeconds)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = WorkoutRestShape,
        modifier = Modifier.fillMaxWidth().testTag("workout-rest").semantics {
            contentDescription = announcement
        }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val view = LocalView.current
                LaunchedEffect(rest.rest) {
                    // Equal-duration rests need separate events even when their descriptions match.
                    @Suppress("DEPRECATION")
                    view.announceForAccessibility(formatRestAnnouncement(resources, rest.remainingSeconds))
                }
                // A fresh live region leaves each start announcement to the identity-keyed event.
                key(rest.rest) {
                    val announcedSeconds = remember((rest.remainingSeconds - 1) / 60) { rest.remainingSeconds }
                    val minuteAnnouncement = formatRestAnnouncement(resources, announcedSeconds)
                    Text(stringResource(R.string.rest_notification_title), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("workout-rest-announcement").semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = minuteAnnouncement
                        })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(formatCardioTime(rest.remainingSeconds), style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.clearAndSetSemantics { })
                    LinearProgressIndicator(progress = { rest.remainingFraction }, modifier = Modifier.weight(1f).clearAndSetSemantics { },
                        color = MaterialTheme.colorScheme.onSurface, trackColor = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            TextButton(onClick = { actions.onAdjustRest(15) }, modifier = Modifier.heightIn(min = 48.dp), colors = neutralTextButtonColors()) {
                Text(stringResource(R.string.rest_notification_add))
            }
        }
    }
}

private sealed interface WorkoutSecondaryAction {
    data class Text(val label: String, val onClick: () -> Unit) : WorkoutSecondaryAction
    data class DecrementReps(val enabled: Boolean, val onClick: () -> Unit) : WorkoutSecondaryAction
}

private data class WorkoutActionSpec(
    val label: String,
    val tonal: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val secondary: WorkoutSecondaryAction? = null,
    val guardKey: Any? = null,
    val onBlockedTap: (() -> Unit)? = null,
    val blockedDescription: String? = null,
)

/** M3 Button has no callback for taps while disabled; the parent catches them to point at what is missing. */
@Composable
private fun WorkoutAction(
    label: String, tonal: Boolean, enabled: Boolean, reducedMotion: Boolean, onClick: () -> Unit,
    secondary: WorkoutSecondaryAction? = null, guardKey: Any? = null,
    onBlockedTap: (() -> Unit)? = null, blockedDescription: String? = null, tapAllowed: () -> Boolean = { true },
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
        if (!guarded && tapAllowed()) {
            guarded = true
            action()
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val latestOnBlockedTap by rememberUpdatedState(onBlockedTap)
        val modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .testTag(if (tonal) "workout-action-tonal" else "workout-action-filled")
            .then(if (blockedDescription != null && !enabled) Modifier.semantics { stateDescription = blockedDescription } else Modifier)
        val content: @Composable () -> Unit = {
            Crossfade(label, animationSpec = if (reducedMotion) snap() else tween(FAST_EFFECTS_MILLIS), label = "workoutAction") {
                // The action row's height is bounded by the non-scrolling page, so a long label at a
                // large font scale wraps and then shrinks to fit rather than clipping or ellipsizing.
                BasicText(
                    it,
                    style = MaterialTheme.typography.titleLarge.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = ACTION_MIN_SP.sp,
                        maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                    ),
                )
            }
        }
        Box(Modifier.weight(1f).testTag("workout-action-gate").then(
            if (!enabled && blockedDescription != null) Modifier.pointerInput(Unit) {
                // Disabled M3 buttons consume events in Main; observe Initial without consuming.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var tapped = down.position.x in 0f..size.width.toFloat() &&
                        down.position.y in 0f..size.height.toFloat()
                    do {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            ?: return@awaitEachGesture
                        tapped = tapped && change.position.x in 0f..size.width.toFloat() &&
                            change.position.y in 0f..size.height.toFloat() &&
                            (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
                    } while (change.pressed)
                    if (tapped) latestOnBlockedTap?.invoke()
                }
            } else Modifier,
        )) {
            if (tonal) FilledTonalButton(onClick = { guardedTap(onClick) }, enabled = enabled, modifier = modifier) { content() }
            else Button(onClick = { guardedTap(onClick) }, enabled = enabled, modifier = modifier) { content() }
        }
        when (secondary) {
            is WorkoutSecondaryAction.DecrementReps -> {
                OutlinedIconButton(onClick = secondary.onClick, enabled = enabled && secondary.enabled, modifier = Modifier.size(64.dp)) {
                    Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.workout_one_fewer_rep))
                }
            }
            is WorkoutSecondaryAction.Text -> {
                OutlinedButton(onClick = { guardedTap(secondary.onClick) }, enabled = enabled, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(secondary.label, style = MaterialTheme.typography.titleLarge)
                }
            }
            null -> Unit
        }
    }
}

@Composable
private fun neutralTextButtonColors() = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

private fun workoutValue(
    values: PartnerRowState,
    tracking: TrackingType,
    unit: WeightUnit,
    includeTimedWeight: Boolean = true,
): String = SetFormatter.display(tracking,
    if (tracking == TrackingType.TIMED && !includeTimedWeight) 0.0 else unit.toLb(values.weightDisplay),
    values.reps, values.seconds, unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EffortRow(selected: Effort?, onPick: (Effort?) -> Unit, tagPrefix: String,
                       entries: List<Effort> = Effort.entries, label: String = stringResource(R.string.effort), highlight: Color? = null) {
    val colors = SegmentedButtonDefaults.colors()
    val density = LocalDensity.current
    val largeFont = density.fontScale >= 1.5f
    val segmentHeight = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 16.dp
    FlowRow(Modifier.fillMaxWidth().testTag(if (highlight == null) "$tagPrefix-row" else "$tagPrefix-row-highlighted"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, modifier = Modifier.align(Alignment.CenterVertically),
            style = MaterialTheme.typography.labelLarge, color = highlight ?: LocalContentColor.current)
        Column((if (largeFont) Modifier.fillMaxWidth() else Modifier.weight(1f)).align(Alignment.CenterVertically),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in entries.chunked(if (largeFont) 2 else entries.size)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { index, effort ->
                        SegmentedButton(
                            selected = effort == selected,
                            onClick = { onPick(if (effort == selected) null else effort) },
                            shape = SegmentedButtonDefaults.itemShape(index, row.size),
                            border = if (highlight != null) SegmentedButtonDefaults.borderStroke(highlight, 2.dp) else SegmentedButtonDefaults.borderStroke(
                                if (effort == selected) colors.activeBorderColor else colors.inactiveBorderColor),
                            label = { EffortText(effort) },
                            modifier = Modifier.testTag("$tagPrefix-${effort.name}").heightIn(min = segmentHeight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EffortText(effort: Effort) {
    Text(stringResource(effortResource(effort)))
}

private fun effortResource(effort: Effort): Int = when (effort) {
    Effort.EASY -> R.string.effort_easy
    Effort.GOOD -> R.string.effort_good
    Effort.HARD -> R.string.effort_hard
    Effort.MAX -> R.string.effort_max
}

internal fun SetRowState.values(slot: String): PartnerRowState? =
    if (slot == Slot.SS) partner else PartnerRowState(weightDisplay, reps, done, seconds, rir = rir)

internal fun ExerciseCardState.tracking(slot: String): TrackingType =
    if (slot == Slot.SS) partnerTracking ?: TrackingType.WEIGHTED else tracking

internal fun effortWord(rir: Int?): Effort? = if (rir == 3) Effort.GOOD else Effort.of(rir)

@Composable
internal fun SetEffortText(rir: Int, scale: EffortScale) {
    if (scale == EffortScale.REPS_LEFT) Text(stringResource(R.string.effort_reps_left_value, rir))
    else effortWord(rir)?.let { EffortText(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetEffortRow(rir: Int?, scale: EffortScale, onWord: (Effort?) -> Unit,
                          onRepsLeft: (Int?) -> Unit, tagPrefix: String, highlight: Color? = null) {
    if (scale == EffortScale.WORDS) {
        EffortRow(effortWord(rir), onWord, tagPrefix, highlight = highlight)
    } else {
        val colors = SegmentedButtonDefaults.colors()
        Column(Modifier.fillMaxWidth().testTag(if (highlight == null) "$tagPrefix-row" else "$tagPrefix-row-highlighted"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_effort_reps_left), style = MaterialTheme.typography.labelLarge,
                color = highlight ?: LocalContentColor.current)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val perRow = if (maxWidth / 6 / LocalDensity.current.fontScale < 48.dp) 3 else 6
                Column(Modifier.fillMaxWidth()) {
                    (0..5).toList().chunked(perRow).forEach { values ->
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            values.forEachIndexed { index, value ->
                                SegmentedButton(selected = rir == value,
                                    onClick = { onRepsLeft(if (rir == value) null else value) },
                                    shape = SegmentedButtonDefaults.itemShape(index, values.size),
                                    border = if (highlight != null) SegmentedButtonDefaults.borderStroke(highlight, 2.dp) else SegmentedButtonDefaults.borderStroke(
                                        if (rir == value) colors.activeBorderColor else colors.inactiveBorderColor),
                                    label = { Text(value.toString()) }, icon = {},
                                    modifier = Modifier.testTag("$tagPrefix-$value").heightIn(min = 48.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
