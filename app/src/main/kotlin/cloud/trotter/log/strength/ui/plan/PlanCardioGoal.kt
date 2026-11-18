package cloud.trotter.log.strength.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.GoalTime
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.NumberField
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import cloud.trotter.log.strength.ui.day.cardioActivityName
import cloud.trotter.log.strength.ui.day.labelRes
import cloud.trotter.log.strength.ui.day.timedLabelRes
import cloud.trotter.log.strength.domain.model.SprintTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val goalDateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

private fun goalDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(goalDateFormat)

@Composable
internal fun PlanCardioGoal(ui: PlanGoalUi, actions: PlanActions) {
    val goal = ui.goal
    ListItem(
        modifier = Modifier.clickable(onClick = actions.onOpenCardioGoal),
        overlineContent = (goal as? PlanGoal.Timed)?.let { { Text(cardioActivityName(it.activity)) } },
        headlineContent = {
            Text(when (goal) {
                is PlanGoal.Sprint -> error("Sprint goals use PlanSprintGoal")
                is PlanGoal.Timed -> goal.underSeconds?.let { seconds ->
                    stringResource(R.string.plan_goal_timed,
                        distanceUnitFor(goal.activity).format(goal.metres), ClockText.duration(seconds))
                } ?: distanceUnitFor(goal.activity).format(goal.metres)
                is PlanGoal.Shuttle -> stringResource(R.string.plan_goal_shuttle, goal.protocol, goal.targetShuttles)
            })
        },
        supportingContent = {
            Column {
                Text(goalProgress(ui))
                goal.byEpochDay?.let { day -> Text(stringResource(R.string.plan_goal_by, goalDate(day))) }
                val recent = ui.predictedFrom
                if (goal is PlanGoal.Timed && ui.predictedSeconds != null && recent != null) {
                    Text(stringResource(R.string.plan_goal_predicted, ClockText.duration(ui.predictedSeconds),
                        distanceUnitFor(goal.activity).format(recent.metres), ClockText.duration(recent.seconds)))
                }
            }
        },
        trailingContent = if (goal is PlanGoal.Shuttle) {
            { TextButton(onClick = actions.onOpenShuttleEntry) { Text(stringResource(R.string.plan_log_attempt)) } }
        } else null,
    )
}

@Composable
private fun goalProgress(ui: PlanGoalUi): String {
    val best = ui.best ?: return stringResource(R.string.plan_goal_no_attempt)
    val gap = when (val goal = ui.goal) {
        is PlanGoal.Sprint -> error("Sprint goals use PlanSprintGoal")
        is PlanGoal.Timed -> {
            val target = goal.underSeconds ?: return stringResource(R.string.plan_goal_best, ClockText.duration(best))
            val seconds = abs(best - target)
            val amount = if (seconds < 60) stringResource(R.string.plan_goal_seconds, seconds)
                else ClockText.duration(seconds)
            when {
                best > target -> stringResource(R.string.plan_goal_over, amount)
                best < target -> stringResource(R.string.plan_goal_under, amount)
                else -> stringResource(R.string.plan_goal_not_under)
            }
        }
        is PlanGoal.Shuttle -> if (ui.met) stringResource(R.string.plan_goal_met)
            else stringResource(R.string.plan_goal_shuttles_to_go, goal.targetShuttles - best)
    }
    val display = if (ui.goal is PlanGoal.Timed) ClockText.duration(best) else best.toString()
    return stringResource(R.string.plan_goal_progress, stringResource(R.string.plan_goal_best, display), gap)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanCardioGoalEditor(draft: PlanGoalDraft, exists: Boolean, actions: PlanActions) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val date = draft.byEpochDay?.takeIf(PlanGoalDate::isValid)
    val focusManager = LocalFocusManager.current
    AppAlertDialog(
        onDismissRequest = actions.onDismissGoal,
        title = { Text(stringResource(R.string.plan_goal)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true, false).forEachIndexed { index, timed ->
                        SegmentedButton(
                            selected = draft.timed == timed,
                            onClick = { focusManager.clearFocus(); actions.onCardioGoalDraft(draft.copy(timed = timed)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(if (timed) R.string.plan_goal_kind_distance else R.string.plan_goal_kind_shuttle)) }
                    }
                }
                if (draft.timed) TimedGoalFields(draft, actions.onCardioGoalDraft)
                else {
                    OutlinedTextField(
                        value = draft.protocol,
                        onValueChange = { actions.onCardioGoalDraft(draft.copy(protocol = it.take(30))) },
                        label = { Text(stringResource(R.string.plan_goal_protocol)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    val onCount: (Double?) -> Unit = { actions.onCardioGoalDraft(draft.copy(targetShuttles = it?.toInt() ?: 0)) }
                    NumberField(
                        value = draft.targetShuttles.takeIf { it > 0 }?.toDouble(),
                        onValueChange = onCount, onDraftChange = onCount,
                        label = stringResource(R.string.plan_goal_target_shuttles), minValue = 1.0, maxValue = 999.0,
                    )
                }
                Row {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(date?.let(::goalDate) ?: stringResource(R.string.plan_goal_add_date))
                    }
                    if (draft.byEpochDay != null) {
                        TextButton(onClick = { actions.onCardioGoalDraft(draft.copy(byEpochDay = null)) }) {
                            Text(stringResource(R.string.plan_goal_remove_date))
                        }
                    }
                }
                if (exists) {
                    TextButton(onClick = actions.onRemoveGoal,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.plan_goal_remove))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveGoal, enabled = draft.goal() != null) { Text(stringResource(R.string.plans_save)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissGoal) { Text(stringResource(R.string.setup_cancel_button)) }
        },
    )
    if (showDatePicker) {
        val displayedDay = date ?: LocalDate.now().toEpochDay().coerceIn(PlanGoalDate.epochDays)
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.let {
                LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
            initialDisplayedMonthMillis = LocalDate.ofEpochDay(displayedDay)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = PlanGoalDate.years,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        actions.onCardioGoalDraft(draft.copy(byEpochDay = Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()))
                    }
                    showDatePicker = false
                }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.plans_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.setup_cancel_button)) }
            },
        ) { DatePicker(picker) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimedGoalFields(draft: PlanGoalDraft, onChange: (PlanGoalDraft) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val activity = CardioActivity.entries.firstOrNull { it.name == draft.activity } ?: CardioActivity.ROAD_RUN
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = cardioActivityName(activity), onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.cardio_edit_activity)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CardioActivity.entries.forEach { option ->
                DropdownMenuItem(text = { Text(cardioActivityName(option)) }, onClick = {
                    focusManager.clearFocus()
                    onChange(draft.copy(activity = option.name).invalidateTimeBelowFloor())
                    expanded = false
                })
            }
        }
    }
    val unit = distanceUnitFor(activity)
    val onDistance: (Double?) -> Unit = { onChange(draft.copy(metres = it?.let(unit::toMetres) ?: 0).invalidateTimeBelowFloor()) }
    NumberField(
        value = draft.metres.takeIf { it > 0 }?.let(unit::fromMetres),
        onValueChange = onDistance, onDraftChange = onDistance,
        label = stringResource(R.string.cardio_distance_label, unit.symbol),
        minValue = unit.fromMetres(1), maxValue = unit.fromMetres(200_000),
        format = { value -> unit.toMetres(value)?.let(unit::inputText) ?: WeightStepper.format(value) },
        decimalInput = true,
    )
    // Just finish stores 0. A time stores INVALID_TIME until the field holds a time, so Save stays off.
    val withTime = draft.underSeconds != 0
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(false, true).forEachIndexed { index, time ->
            SegmentedButton(
                selected = withTime == time,
                onClick = {
                    focusManager.clearFocus()
                    if (withTime != time) onChange(draft.copy(underSeconds = if (time) INVALID_TIME else 0))
                },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
            ) { Text(stringResource(if (time) R.string.setup_flow_event_time else R.string.setup_flow_event_finish)) }
        }
    }
    if (withTime) {
        val onTime: (Double?) -> Unit = { onChange(draft.copy(underSeconds = it?.toInt() ?: INVALID_TIME)) }
        val floor = GoalTime.floorSeconds(draft.metres, activity)
        key(floor) {
            NumberField(
                value = draft.underSeconds.takeIf { it >= floor }?.toDouble(),
                onValueChange = onTime, onDraftChange = onTime,
                label = stringResource(R.string.plan_goal_target_time),
                minValue = floor.toDouble(), maxValue = 86_400.0,
                format = { ClockText.duration(it.toInt()) }, parse = { ClockText.parse(it)?.toDouble() },
                keyboardType = KeyboardType.Ascii,
            )
        }
    }
}

@Composable
internal fun PlanShuttleEntry(entry: String, actions: PlanActions) {
    AppAlertDialog(
        onDismissRequest = actions.onDismissShuttleEntry,
        title = { Text(stringResource(R.string.plan_log_attempt)) },
        text = {
            OutlinedTextField(value = entry, onValueChange = actions.onShuttleEntry,
                label = { Text(stringResource(R.string.plan_goal_shuttles_label)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveShuttleEntry, enabled = entry.toIntOrNull()?.let { it in 0..999 } == true) {
                Text(stringResource(R.string.plans_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissShuttleEntry) { Text(stringResource(R.string.setup_cancel_button)) }
        },
    )
}

@Composable
internal fun PlanSprintGoal(ui: PlanSprintGoalUi, actions: PlanActions) {
    val goal = ui.goal
    val distance = SprintEvent.label(goal.metres, distanceUnitFor(CardioActivity.TRACK_RUN))
    ListItem(
        modifier = Modifier.clickable(onClick = actions.onOpenCardioGoal),
        headlineContent = {
            Text(goal.targetCentis?.let { stringResource(R.string.plan_goal_timed, distance, ClockText.centis(it)) }
                ?: distance)
        },
        supportingContent = {
            Column {
                val best = ui.best
                if (best == null) Text(stringResource(R.string.plan_goal_no_attempt))
                else {
                    Text(stringResource(R.string.plan_sprint_best, stringResource(best.time.timingMethod.timedLabelRes()),
                        ClockText.centis(best.time.centis)))
                    Text(stringResource(R.string.plan_sprint_start, stringResource(best.time.startProtocol.labelRes())))
                }
                goal.byEpochDay?.let { Text(stringResource(R.string.plan_goal_by, goalDate(it))) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanSprintGoalEditor(draft: SprintGoalDraft, actions: PlanActions, metres: Int? = null) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val date = draft.byEpochDay?.takeIf(PlanGoalDate::isValid)
    val target = ClockText.parseCentis(draft.targetText)
    AppAlertDialog(
        onDismissRequest = actions.onDismissGoal,
        title = { Text(stringResource(R.string.plan_goal)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                metres?.let { Text(SprintEvent.label(it, distanceUnitFor(CardioActivity.TRACK_RUN))) }
                NumberField(
                    value = target?.toDouble() ?: 0.0,
                    onValueChange = { actions.onSprintGoalDraft(draft.copy(targetText = ClockText.centis(it.toInt()))) },
                    onDraftEdit = { text, _ -> actions.onSprintGoalDraft(draft.copy(targetText = text)) },
                    label = stringResource(R.string.sprint_time_field),
                    minValue = 1.0, maxValue = SprintTime.MAX_CENTIS.toDouble(),
                    format = { if (it == 0.0) draft.targetText else ClockText.centis(it.toInt()) },
                    parse = { ClockText.parseCentis(it)?.toDouble() }, keyboardType = KeyboardType.Decimal,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(date?.let(::goalDate) ?: stringResource(R.string.plan_goal_add_date))
                    }
                    if (draft.byEpochDay != null) TextButton(onClick = {
                        actions.onSprintGoalDraft(draft.copy(byEpochDay = null))
                    }) { Text(stringResource(R.string.plan_goal_remove_date)) }
                }
                TextButton(onClick = actions.onRemoveGoal,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.plan_goal_remove))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveSprintGoal,
                enabled = (draft.targetText.isBlank() || target != null) && PlanGoalDate.isValid(draft.byEpochDay)) {
                Text(stringResource(R.string.plans_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissGoal) { Text(stringResource(R.string.setup_cancel_button)) }
        },
    )
    if (showDatePicker) {
        val displayedDay = date ?: LocalDate.now().toEpochDay().coerceIn(PlanGoalDate.epochDays)
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.let {
                LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
            initialDisplayedMonthMillis = LocalDate.ofEpochDay(displayedDay)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = PlanGoalDate.years,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        actions.onSprintGoalDraft(draft.copy(byEpochDay = Instant.ofEpochMilli(it)
                            .atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()))
                    }
                    showDatePicker = false
                }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.plans_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.setup_cancel_button)) }
            },
        ) { DatePicker(picker) }
    }
}
