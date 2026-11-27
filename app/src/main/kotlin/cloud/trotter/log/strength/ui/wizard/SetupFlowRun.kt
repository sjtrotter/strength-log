package cloud.trotter.log.strength.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.GoalTime
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.NumberField
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun runGoalLabel(goal: RunGoal): Int = when (goal) {
    RunGoal.FIRST_5K -> R.string.setup_flow_run_first_5k
    RunGoal.FIVE_TO_TEN_K -> R.string.setup_flow_run_5k_10k
    RunGoal.HALF_MARATHON -> R.string.setup_flow_run_half
    RunGoal.MARATHON -> R.string.setup_flow_run_marathon
    RunGoal.ULTRA -> R.string.setup_flow_run_ultra
    RunGoal.MIDDLE_DISTANCE -> R.string.setup_flow_run_middle
    RunGoal.FITNESS_TEST -> R.string.setup_flow_run_test
    RunGoal.CARDIO_FITNESS -> R.string.setup_flow_run_cardio
}

@Composable
internal fun RunGoalStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    Column(Modifier.selectableGroup()) {
        RunGoal.entries.filter { it != RunGoal.FITNESS_TEST }.forEach { goal ->
            ChoiceRow(stringResource(runGoalLabel(goal)), stringResource(when (goal) {
                RunGoal.FIRST_5K -> R.string.setup_flow_run_first_5k_body
                RunGoal.FIVE_TO_TEN_K -> R.string.setup_flow_run_5k_10k_body
                RunGoal.HALF_MARATHON -> R.string.setup_flow_run_half_body
                RunGoal.MARATHON -> R.string.setup_flow_run_marathon_body
                RunGoal.ULTRA -> R.string.setup_flow_run_ultra_body
                RunGoal.MIDDLE_DISTANCE -> R.string.setup_flow_run_middle_body
                RunGoal.FITNESS_TEST -> R.string.setup_flow_run_test_body
                RunGoal.CARDIO_FITNESS -> R.string.setup_flow_run_cardio_body
            }), !state.draft.isSprint && state.draft.runGoal == goal) { actions.onRunGoal(goal) }
        }
    }
    if (state.draft.branch == PlanBranch.BOTH) {
        SetupLabel(stringResource(R.string.setup_flow_sprint_section))
        SprintGoalRows(state, actions)
    }
}

@Composable
internal fun SetupSegments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(selected = selected == index, onClick = { focus.clearFocus(); onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(label)
            }
        }
    }
}

@Composable
internal fun SetupTime(value: Int?, label: String, minSeconds: Int = 1, maxSeconds: Int = 86_400,
    invalid: Int? = null, onChange: (Int?) -> Unit) {
    val change: (Double?) -> Unit = { onChange(it?.toInt()) }
    key(minSeconds) {
        // Draft edits persist already; disposing a focused field must not write using its old floor.
        NumberField(value = value?.takeIf { it >= minSeconds }?.toDouble(), onValueChange = {},
            onDraftChange = { if (invalid == null) change(it) },
            onDraftEdit = { text, parsed ->
                if (invalid != null) onChange(when {
                    text.isBlank() -> null
                    parsed == null -> invalid
                    else -> parsed.toInt()
                })
            },
            error = value == INVALID_GOAL_TIME, label = label, minValue = minSeconds.toDouble(), maxValue = maxSeconds.toDouble(), format = { ClockText.duration(it.toInt()) },
            parse = { ClockText.parse(it)?.toDouble() }, keyboardType = KeyboardType.Ascii)
    }
}

@Composable
internal fun SetupDistance(value: Int?, activity: CardioActivity, onChange: (Int?) -> Unit) {
    val unit = distanceUnitFor(activity)
    val change: (Double?) -> Unit = { onChange(it?.let(unit::toMetres)) }
    NumberField(value = value?.takeIf { it > 0 }?.let(unit::fromMetres), onValueChange = change, onDraftChange = change,
        label = stringResource(R.string.cardio_distance_label, unit.symbol), minValue = unit.fromMetres(1),
        maxValue = unit.fromMetres(200_000), decimalInput = true,
        format = { unit.toMetres(it)?.let(unit::inputText) ?: WeightStepper.format(it) })
}

@Composable
internal fun EventStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    val focus = LocalFocusManager.current
    val draft = state.draft
    val run = SetupFlowStateBuilder.answers(draft).run
    val timed = draft.goalSeconds != null
    if (draft.runGoal.events.size > 1) {
        SetupLabel(stringResource(R.string.setup_flow_event_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            draft.runGoal.events.forEach { event ->
                SetupChip(run.resolvedEvent == event, { focus.clearFocus(); actions.onRunEvent(event) }) { Text(stringResource(eventLabel(event))) }
            }
        }
    }
    if (draft.runGoal == RunGoal.ULTRA) {
        SetupLabel(stringResource(R.string.setup_flow_surface_label))
        SetupSegments(listOf(stringResource(R.string.setup_flow_surface_road), stringResource(R.string.setup_flow_surface_trail)),
            if (run.activity == CardioActivity.TRAIL_RUN) 1 else 0) {
            actions.onActivity(if (it == 1) CardioActivity.TRAIL_RUN else CardioActivity.ROAD_RUN)
        }
        SetupTime(draft.marathonBestSeconds, stringResource(R.string.setup_flow_marathon_best),
            minSeconds = GoalTime.floorSeconds(RunEvent.MARATHON.metres, run.activity), onChange = actions.onMarathonBest)
    }
    SetupLabel(stringResource(R.string.setup_flow_event_goal_label))
    SetupSegments(listOf(stringResource(R.string.setup_flow_event_finish), stringResource(R.string.setup_flow_event_time)),
        if (timed) 1 else 0) {
        actions.onGoalSeconds(if (it == 1) draft.goalSeconds ?: INVALID_GOAL_TIME else null)
    }
    if (timed) SetupTime(draft.goalSeconds, stringResource(R.string.plan_goal_target_time),
        minSeconds = SetupFlowStateBuilder.goalTimeFloor(draft)) {
        actions.onGoalSeconds(it ?: INVALID_GOAL_TIME)
    }
    if (draft.runGoal.events.isNotEmpty()) {
        SetupEventDate(run.dateEpochDay, actions.onEventDate)
    }
    SetupLabel(stringResource(R.string.setup_flow_recent_label))
    SetupDistance(draft.recentMetres, run.activity) { actions.onRecent(it, draft.recentSeconds) }
    SetupTime(draft.recentSeconds, stringResource(R.string.setup_flow_recent_time)) { actions.onRecent(draft.recentMetres, it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupEventDate(date: Long?, onChange: (Long?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SetupLabel(stringResource(R.string.setup_flow_event_date_label))
    Row {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(date?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)) }
                ?: stringResource(R.string.plan_goal_add_date))
        }
        if (date != null) TextButton(onClick = { onChange(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.plan_goal_remove_date))
        }
    }
    if (open) {
        val displayedDay = date ?: LocalDate.now().toEpochDay().coerceIn(PlanGoalDate.epochDays)
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.let {
            LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }, initialDisplayedMonthMillis = LocalDate.ofEpochDay(displayedDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = PlanGoalDate.years)
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    onChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay())
                }
                open = false
            }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.plans_save)) }
        }, dismissButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.setup_cancel_button)) }
        }) { DatePicker(picker) }
    }
}

@Composable
internal fun WhereStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    val draft = state.draft
    val run = SetupFlowStateBuilder.answers(draft).run
    val activity = run.activity
    if (draft.runGoal == RunGoal.CARDIO_FITNESS) {
        SetupLabel(stringResource(R.string.setup_flow_activity_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(CardioActivity.ROAD_RUN to R.string.setup_flow_activity_run,
                CardioActivity.OUTDOOR_WALK to R.string.setup_flow_activity_walk,
                CardioActivity.STATIONARY_BIKE to R.string.setup_flow_activity_bike,
                CardioActivity.ROWER to R.string.setup_flow_activity_row,
                CardioActivity.ELLIPTICAL to R.string.setup_flow_activity_elliptical).forEach { (option, label) ->
                SetupChip(activity == option, { actions.onActivity(option) }) { Text(stringResource(label)) }
            }
        }
    }
    SetupLabel(stringResource(if (CardioFamily.of(run.activity) != CardioFamily.RUN) R.string.setup_flow_longest_session
        else if (run.shortLadderGate) R.string.setup_flow_longest_run_nonstop else R.string.setup_flow_longest_run))
    SetupSegments(listOf(stringResource(R.string.setup_flow_longest_minutes), stringResource(R.string.setup_flow_longest_distance)),
        if (draft.longestByDistance) 1 else 0) { actions.onLongestByDistance(it == 1) }
    if (draft.longestByDistance) SetupDistance(draft.longestMetres, activity, actions.onLongest)
    else {
        val change: (Double?) -> Unit = { actions.onLongest(it?.toInt()?.times(60)) }
        NumberField(value = draft.longestSeconds?.takeIf { it > 0 }?.let { (it / 60).toDouble() },
            onValueChange = change, onDraftChange = change, label = stringResource(R.string.setup_flow_longest_minutes),
            minValue = 1.0, maxValue = (Int.MAX_VALUE / 60).toDouble())
    }
}

private fun eventLabel(event: RunEvent): Int = when (event) {
    RunEvent.K5 -> R.string.setup_flow_event_5k
    RunEvent.K10 -> R.string.setup_flow_event_10k
    RunEvent.M800 -> R.string.setup_flow_event_800
    RunEvent.M1500 -> R.string.setup_flow_event_1500
    RunEvent.MILE -> R.string.setup_flow_event_mile
    RunEvent.M3000 -> R.string.setup_flow_event_3000
    RunEvent.K50 -> R.string.setup_flow_event_50k
    RunEvent.MI50 -> R.string.setup_flow_event_50mi
    RunEvent.K100 -> R.string.setup_flow_event_100k
    RunEvent.MI100 -> R.string.setup_flow_event_100mi
    RunEvent.HALF -> R.string.setup_flow_run_half
    RunEvent.MARATHON -> R.string.setup_flow_run_marathon
}
