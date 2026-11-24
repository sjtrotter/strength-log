package cloud.trotter.log.strength.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.ui.components.AppAlertDialog

@Composable
internal fun PlanTestGoal(ui: PlanTestGoalUi, actions: PlanActions) {
    Column {
        ui.rows.forEachIndexed { index, row ->
            fun value(number: Int): String = if (row.timed) ClockText.duration(number) else number.toString()
            ListItem(
                modifier = Modifier.clickable(onClick = actions.onOpenTestGoal),
                overlineContent = if (index == 0) { { Text(stringResource(R.string.plan_test_goal)) } } else null,
                headlineContent = {
                    val window = row.event.ref.windowSeconds
                    Text(if (window != null && TestEvents.find(row.event.ref.eventId)?.custom == true)
                        stringResource(R.string.plan_goal_progress, row.name, ClockText.duration(window)) else row.name)
                },
                supportingContent = {
                    Column {
                        val best = row.best?.let { stringResource(R.string.plan_goal_best, value(it)) }
                            ?: stringResource(R.string.plan_goal_no_attempt)
                        val required = if (row.met) stringResource(R.string.plan_goal_met)
                            else stringResource(R.string.plan_test_required, value(row.event.required))
                        Text(stringResource(R.string.plan_goal_progress, best, required))
                        if (index == ui.rows.lastIndex) ui.goal.byEpochDay?.let {
                            Text(stringResource(R.string.plan_goal_by, goalDate(it)))
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun PlanTestGoalEditor(ui: PlanTestGoalUi, draft: TestGoalDraft, actions: PlanActions) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    val date = draft.byEpochDay?.takeIf(PlanGoalDate::isValid)
    if (confirmRemoval) {
        AppAlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text(stringResource(R.string.plan_test_goal_remove_confirm)) },
            text = {},
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    actions.onRemoveTestGoal()
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.day_edit_remove_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) { Text(stringResource(R.string.setup_cancel_button)) }
            },
        )
        return
    }
    AppAlertDialog(
        onDismissRequest = actions.onDismissTestGoal,
        title = { Text(stringResource(R.string.plan_test_goal_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ui.rows.forEach { row ->
                    val id = row.event.ref.eventId
                    val invalid = draft.requiredValue(id, row.timed) == null
                    OutlinedTextField(
                        value = draft.required[id].orEmpty(),
                        onValueChange = { actions.onTestGoalDraft(draft.copy(required = draft.required + (id to it))) },
                        label = { Text(row.name) },
                        isError = invalid,
                        supportingText = if (invalid) { {
                            Text(if (row.timed) stringResource(R.string.form_time_range, "0:01", "60:00")
                                else stringResource(R.string.form_number_range, "1", "3600"))
                        } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = if (row.timed) KeyboardType.Ascii else KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }
                Row {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(date?.let(::goalDate) ?: stringResource(R.string.plan_goal_add_date))
                    }
                    if (draft.byEpochDay != null) TextButton(onClick = { actions.onTestGoalDraft(draft.copy(byEpochDay = null)) }) {
                        Text(stringResource(R.string.plan_goal_remove_date))
                    }
                }
                if (!PlanGoalDate.isValid(draft.byEpochDay)) Text(
                    stringResource(R.string.stepper_valid_range, goalDate(PlanGoalDate.epochDays.first),
                        goalDate(PlanGoalDate.epochDays.last)), color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { confirmRemoval = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.plan_goal_remove))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveTestGoal, enabled = draft.goal(ui.goal, ui.rows) != null) {
                Text(stringResource(R.string.plans_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissTestGoal) { Text(stringResource(R.string.setup_cancel_button)) }
        },
    )
    if (showDatePicker) GoalDatePicker(
        epochDay = draft.byEpochDay,
        onDate = { actions.onTestGoalDraft(draft.copy(byEpochDay = it)) },
        onDismiss = { showDatePicker = false },
    )
}
