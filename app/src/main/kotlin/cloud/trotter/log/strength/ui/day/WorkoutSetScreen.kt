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
    onBack: (() -> Unit)? = null,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = dayAccent(state.dayIndex), onPrimary = onDayAccent(state.dayIndex),
    )) {
        WorkoutSetContent(state, actions, catalog, onExercises, onFinish, snackbarHost, onBack, nowMillis)
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
                Column(Modifier.widthIn(max = ReadableWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) actionSlot@ {
                    WorkoutRestBand(state, actions, catalog)
                    val focus = workout.focus
                    val card = state.workoutCard
                    val row = card?.rows?.getOrNull(focus?.index ?: -1)
                    val values = row?.values(focus?.slot ?: Slot.MAIN)
                    val tracking = card?.tracking(focus?.slot ?: Slot.MAIN)
                    val label = when {
                        allLogged -> stringResource(R.string.day_finish_button, state.viewDayId.orEmpty())
                        workout.completedView -> stringResource(R.string.day_next_exercise, nextTitle.orEmpty())
                        values != null && tracking != null -> stringResource(R.string.day_log_set, workoutValue(values, tracking))
                        else -> return@actionSlot
                    }
                    WorkoutAction(
                        label = label,
                        tonal = state.rest?.over == false,
                        enabled = if (allLogged) state.canFinish && finishArmed else true,
                        reducedMotion = reducedMotion,
                        onClick = when {
                            allLogged -> onFinish
                            workout.completedView -> actions.onNextWorkoutLift
                            else -> actions.onLogFocusedSet
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val modifier = Modifier.widthIn(max = ReadableWidth).fillMaxSize()
            val scrollModifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp)
            if (reducedMotion) {
                Column(modifier.then(scrollModifier)) { WorkoutLift(state, actions, catalog, reducedMotion) }
            } else {
                AnimatedContent(
                    targetState = state,
                    contentKey = { (it.workout?.shown as? WorkoutSelection.Shown.Group)?.key ?: "all-logged" },
                    transitionSpec = {
                        (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(300)))
                            .togetherWith(slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(300)))
                            .using(null)
                    },
                    modifier = modifier,
                    label = "workoutGroup",
                ) { shown ->
                    Column(Modifier.fillMaxSize().then(scrollModifier)) {
                        WorkoutLift(shown, actions, catalog, reducedMotion)
                    }
                }
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
) {
    val workout = state.workout ?: return
    if (state.workoutAllLogged) {
        Text(stringResource(R.string.workout_all_logged, state.totalSets), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        return
    }
    val card = state.workoutCard ?: return
    val focus = workout.focus
    val index = focus?.index ?: card.rows.lastIndex
    val row = card.rows.getOrNull(index)
    val slot = focus?.slot ?: Slot.MAIN
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
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    val index = focus?.index ?: card.rows.lastIndex
    val position = if (workout.completedView) {
        stringResource(R.string.workout_done_rounds, card.rows.size, card.rows.size)
    } else stringResource(R.string.workout_superset_round, index + 1, card.rows.size)
    val currentHalf by rememberUpdatedState(workout.focus to workout.completedView)
    Column(
        Modifier.fillMaxWidth().testTag("workout-superset-content").semantics {
            liveRegion = LiveRegionMode.Polite
            isTraversalGroup = true
            traversalIndex = 0f
        },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(position, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val row = card.rows.getOrNull(focus.index) ?: return
    val slot = focus.slot
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (slot == Slot.SS) card.partnerTitle.orEmpty() else card.title,
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).testTag("workout-now-lift"))
        TextButton(onClick = { actions.onOpenLiftSheet(card.programExerciseId, slot) }, colors = neutralTextButtonColors()) {
            Text(stringResource(R.string.day_all_sets))
        }
    }
    row.values(slot)?.let { values ->
        val editLabel = stringResource(R.string.workout_edit_set, focus.index + 1)
        TextButton(
            onClick = { actions.onOpenSetEditor(card.programExerciseId, focus.index) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel },
            colors = neutralTextButtonColors(),
            contentPadding = PaddingValues(0.dp),
        ) {
            WorkoutHero(values, card.tracking(slot))
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
    BasicText(
        buildAnnotatedString {
            append(primary)
            append(" ")
            withStyle(MaterialTheme.typography.displayMedium.toSpanStyle().copy(
                fontSize = 0.5.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )) {
                append(secondary)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutAction(label: String, tonal: Boolean, enabled: Boolean, reducedMotion: Boolean, onClick: () -> Unit) {
    var guarded by remember { mutableStateOf(false) }
    LaunchedEffect(guarded) {
        if (guarded) {
            delay(600L)
            guarded = false
        }
    }
    val onTap = {
        if (!guarded) {
            guarded = true
            onClick()
        }
    }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(if (tonal) "workout-action-tonal" else "workout-action-filled")
    val content: @Composable () -> Unit = {
        Crossfade(label, animationSpec = if (reducedMotion) snap() else tween(FAST_EFFECTS_MILLIS), label = "workoutAction") {
            Text(it, style = MaterialTheme.typography.titleLarge)
        }
    }
    if (tonal) FilledTonalButton(onClick = onTap, enabled = enabled, modifier = modifier) { content() }
    else Button(onClick = onTap, enabled = enabled, modifier = modifier) { content() }
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
