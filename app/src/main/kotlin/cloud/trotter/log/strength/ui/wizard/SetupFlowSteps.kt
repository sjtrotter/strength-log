package cloud.trotter.log.strength.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.text.resolve

@Composable
internal fun ChoiceRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(body) },
        leadingContent = { RadioButton(selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick))
}

@Composable
internal fun GoalStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    Column(Modifier.selectableGroup()) {
        ChoiceRow(stringResource(R.string.setup_flow_goal_lift), stringResource(R.string.setup_flow_goal_lift_body),
            state.draft.branch == PlanBranch.LIFT) { actions.onBranch(PlanBranch.LIFT) }
        ChoiceRow(stringResource(R.string.setup_flow_goal_run), stringResource(R.string.setup_flow_goal_run_body),
            state.draft.branch == PlanBranch.RUN) { actions.onBranch(PlanBranch.RUN) }
        ChoiceRow(stringResource(R.string.setup_flow_goal_sprint), stringResource(R.string.setup_flow_goal_sprint_body),
            state.draft.branch == PlanBranch.SPRINT) { actions.onBranch(PlanBranch.SPRINT) }
        ChoiceRow(stringResource(R.string.setup_flow_goal_both), stringResource(R.string.setup_flow_goal_both_body),
            state.draft.branch == PlanBranch.BOTH) { actions.onBranch(PlanBranch.BOTH) }
    }
    if (state.restore.offered) {
        OutlinedButton(onClick = actions.onRestoreFromBackup, enabled = !state.restore.inFlight && !state.finishInFlight,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_flow_restore)) }
        if (state.restore.inFlight) LoadingState(stringResource(R.string.operation_restore_backup))
        state.restore.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun subGoalLabel(goal: LiftGoal): Int = when (goal) {
    LiftGoal.STRENGTH -> R.string.setup_flow_strength
    LiftGoal.MUSCLE -> R.string.setup_flow_muscle
    LiftGoal.GENERAL -> R.string.setup_flow_general
}

@Composable
internal fun SubGoalStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    Column(Modifier.selectableGroup()) {
        LiftGoal.entries.forEach { goal ->
            ChoiceRow(stringResource(subGoalLabel(goal)), stringResource(when (goal) {
                LiftGoal.STRENGTH -> R.string.setup_flow_strength_body
                LiftGoal.MUSCLE -> R.string.setup_flow_muscle_body
                LiftGoal.GENERAL -> R.string.setup_flow_general_body
            }), state.draft.goal == goal) { actions.onSubGoal(goal) }
        }
    }
}

@Composable
internal fun SetupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun SetupChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = label, modifier = modifier,
        leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null) } } else null)
}

@Composable
internal fun WeekStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    SetupLabel(stringResource(R.string.setup_flow_days_label))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        (1..6).forEach { days -> SetupChip(state.draft.days == days, { actions.onDays(days) }) { Text(days.toString()) } }
    }
    SetupLabel(stringResource(R.string.setup_flow_minutes_label))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SetupFlowStateBuilder.minutesOptions.forEach { minutes ->
            SetupChip(state.draft.minutes == minutes, { actions.onMinutes(minutes) }) { Text(minutes.toString()) }
        }
    }
}

@Composable
internal fun EquipmentStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EquipmentPreset.entries.forEach { preset ->
            SetupChip(state.preset == preset, { actions.onPreset(preset) }) { Text(stringResource(presetLabel(preset))) }
        }
    }
    if (!state.showEquipmentList) {
        TextButton(onClick = actions.onChooseExactly, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_flow_choose_exactly)) }
    } else {
        Equipment.entries.forEach { equipment ->
            val checked = equipment in state.draft.equipment
            ListItem(headlineContent = { Text(stringResource(equipmentLabel(equipment))) },
                leadingContent = { Checkbox(checked, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = if (checked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-equipment-${equipment.name}")
                    .toggleable(checked, role = Role.Checkbox) { actions.onToggleEquipment(equipment) })
        }
    }
    SetupLabel(stringResource(R.string.settings_units))
    val focus = LocalFocusManager.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp) {
        WeightUnit.entries.forEachIndexed { index, unit ->
            SegmentedButton(selected = unit == state.draft.unit, onClick = { focus.clearFocus(); actions.onUnit(unit) },
                shape = SegmentedButtonDefaults.itemShape(index, WeightUnit.entries.size), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(unit.name.lowercase())
            }
        }
    }
    if (state.stepRows.isNotEmpty()) SetupLabel(stringResource(R.string.setup_flow_steps_label))
    state.stepRows.forEach { row ->
        SetupLabel(stringResource(when (row.row) {
            StepRow.BAR -> R.string.setup_flow_step_bar
            StepRow.DUMBBELL -> R.string.setup_flow_step_dumbbell
            StepRow.STACK -> R.string.setup_flow_step_stack
        }))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp) {
            row.optionsLb.forEachIndexed { index, lb ->
                SegmentedButton(selected = row.selected == index, onClick = { actions.onStepOption(row.row, index) },
                    shape = SegmentedButtonDefaults.itemShape(index, row.optionsLb.size), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(SetFormatter.weight(lb, state.draft.unit))
                }
            }
        }
    }
}

@Composable
internal fun AboutStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    SetupLabel(stringResource(R.string.setup_flow_lifting_label))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LiftExperience.entries.forEach { experience ->
            SetupChip(state.draft.experience == experience, { actions.onExperience(experience) }) {
                Text(stringResource(when (experience) {
                    LiftExperience.NEW -> R.string.setup_flow_experience_new
                    LiftExperience.UNDER_A_YEAR -> R.string.setup_flow_experience_under_a_year
                    LiftExperience.A_YEAR_OR_MORE -> R.string.setup_flow_experience_a_year_or_more
                }))
            }
        }
    }
    if (state.draft.branch != PlanBranch.LIFT && !state.draft.isSprint) {
        SetupLabel(stringResource(R.string.setup_flow_running_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RunExperience.entries.forEach { experience ->
                SetupChip(state.draft.runExperience == experience, { actions.onRunExperience(experience) }) {
                    Text(stringResource(when (experience) {
                        RunExperience.NEW -> R.string.setup_flow_running_new
                        RunExperience.SOME -> R.string.setup_flow_running_some
                        RunExperience.RACING -> R.string.setup_flow_running_racing
                    }))
                }
            }
        }
        ListItem(headlineContent = { Text(stringResource(R.string.setup_flow_running_injury)) },
            leadingContent = { Checkbox(state.draft.runningInjury, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.draft.runningInjury,
                role = Role.Checkbox, onValueChange = actions.onRunningInjury))
    }
    if (state.draft.isSprint) SprintAgeRow(state.draft, actions)
    else ListItem(headlineContent = { Text(stringResource(R.string.setup_flow_older)) },
        leadingContent = { Checkbox(state.draft.olderAdult, onCheckedChange = null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.draft.olderAdult,
            role = Role.Checkbox, onValueChange = actions.onOlderAdult))
    if (state.draft.isSprint) {
        SprintAnswerRow(R.string.setup_flow_sprinted_recently, state.draft.sprintedRecently, actions.onSprintedRecently)
        SprintAnswerRow(R.string.setup_flow_hamstring_strain, state.draft.hamstringStrain, actions.onHamstringStrain)
    }
}

private fun presetLabel(preset: EquipmentPreset): Int = when (preset) {
    EquipmentPreset.FULL_GYM -> R.string.setup_flow_preset_full_gym
    EquipmentPreset.HOME_BARBELL -> R.string.setup_flow_preset_home_barbell
    EquipmentPreset.DUMBBELLS -> R.string.setup_flow_preset_dumbbells
    EquipmentPreset.KETTLEBELLS -> R.string.setup_flow_preset_kettlebells
    EquipmentPreset.MACHINES_ONLY -> R.string.setup_flow_preset_machines
    EquipmentPreset.BODYWEIGHT -> R.string.setup_flow_preset_bodyweight
}

private fun equipmentLabel(equipment: Equipment): Int = when (equipment) {
    Equipment.BARBELL -> R.string.setup_flow_equipment_barbell
    Equipment.TRAP_BAR -> R.string.setup_flow_equipment_trap_bar
    Equipment.DUMBBELL -> R.string.setup_flow_equipment_dumbbell
    Equipment.MACHINE -> R.string.setup_flow_equipment_machine
    Equipment.CABLE -> R.string.setup_flow_equipment_cable
    Equipment.BODYWEIGHT -> R.string.setup_flow_equipment_bodyweight
    Equipment.BENCH -> R.string.setup_flow_equipment_bench
    Equipment.RACK -> R.string.setup_flow_equipment_rack
    Equipment.PULLUP_BAR -> R.string.setup_flow_equipment_pullup_bar
    Equipment.EZ_BAR -> R.string.setup_flow_equipment_ez_bar
    Equipment.KETTLEBELL -> R.string.setup_flow_equipment_kettlebell
}
