package cloud.trotter.log.strength.ui.wizard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.planner.SprintFloor
import cloud.trotter.log.strength.domain.units.DistanceUnit

@Composable
internal fun SprintGoalStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    SprintGoalRows(state, actions)
    Text(stringResource(R.string.setup_flow_sprint_match_note), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SprintGoalRows(state: SetupFlowUiState, actions: SetupFlowActions) {
    Column(Modifier.selectableGroup()) {
        SprintEvent.entries.forEach { event ->
            ChoiceRow(SprintEvent.label(event.metres, DistanceUnit.M), stringResource(when (event) {
                SprintEvent.YD40 -> R.string.setup_flow_sprint_40yd_body
                SprintEvent.M60 -> R.string.setup_flow_sprint_60_body
                SprintEvent.M100 -> R.string.setup_flow_sprint_100_body
                SprintEvent.M200 -> R.string.setup_flow_sprint_200_body
                SprintEvent.M400 -> R.string.setup_flow_sprint_400_body
            }), state.draft.isSprint && state.draft.sprintEvent == event) { actions.onSprintEvent(event) }
        }
    }
}

@Composable
internal fun SprintAgeRow(draft: SetupDraft, actions: SetupFlowActions) {
    SetupLabel(stringResource(R.string.setup_flow_age))
    val selected = when {
        draft.olderAdult -> 2
        draft.fortyOrOlder -> 1
        else -> 0
    }
    SetupSegments(listOf(stringResource(R.string.setup_flow_under_forty),
        stringResource(R.string.setup_flow_forty_to_sixty_four), stringResource(R.string.setup_flow_older)), selected) { index ->
        actions.onOlderAdult(index == 2)
        actions.onFortyOrOlder(index >= 1)
    }
}

@Composable
internal fun SprintAnswerRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(stringResource(label)) },
        leadingContent = { Checkbox(checked, onCheckedChange = null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked,
            role = Role.Checkbox, onValueChange = onChange))
}

@Composable
internal fun SprintFloorLine(floor: SprintFloor) {
    Text(stringResource(when (floor) {
        SprintFloor.SQUAT_OR_HINGE -> R.string.setup_flow_sprint_needs_leg_lift
        SprintFloor.SQUAT -> R.string.setup_flow_sprint_needs_squat
    }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

internal fun sprintHighDayLabel(jumps: Boolean, nordic: Boolean): Int = when {
    jumps && nordic -> R.string.setup_flow_high_day_full
    jumps -> R.string.setup_flow_high_day_no_nordic
    nordic -> R.string.setup_flow_high_day_no_jumps
    else -> R.string.setup_flow_high_day_lifts
}
