package cloud.trotter.log.strength.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.ui.components.NumberField

@Composable
internal fun TestStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    val draft = state.draft
    val focus = LocalFocusManager.current
    SetupLabel(stringResource(R.string.setup_flow_tests_label))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TestEvents.all.forEach { event ->
            val selected = draft.tests.any { it.eventId == event.id }
            SetupChip(selected, { focus.clearFocus(); actions.onTestEvent(event.id, !selected) }) { Text(event.name) }
        }
    }
    draft.tests.forEach { test ->
        val event = TestEvents.find(test.eventId) ?: return@forEach
        key(event.id) {
            SetupLabel(event.name)
            if (event.custom) SetupTime(test.windowSeconds, stringResource(R.string.setup_flow_test_window), minSeconds = 10) {
                actions.onTestWindow(event.id, it)
            }
            if (event.timed) {
                SetupTime(test.required, stringResource(R.string.setup_flow_test_required_hold)) { actions.onTestRequired(event.id, it) }
                SetupTime(test.lastResult, stringResource(R.string.setup_flow_test_last_hold)) { actions.onTestLast(event.id, it) }
            } else {
                val required: (Double?) -> Unit = { actions.onTestRequired(event.id, it?.toInt()) }
                NumberField(value = test.required?.toDouble(), onValueChange = required, onDraftChange = required,
                    label = stringResource(R.string.setup_flow_test_required_reps), minValue = 1.0, maxValue = 999.0)
                val last: (Double?) -> Unit = { actions.onTestLast(event.id, it?.toInt()) }
                NumberField(value = test.lastResult?.toDouble(), onValueChange = last, onDraftChange = last,
                    label = stringResource(R.string.setup_flow_test_last_reps), minValue = 1.0, maxValue = 999.0)
            }
            Text(event.form, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (draft.tests.isNotEmpty()) SetupEventDate(draft.testDate, actions.onTestDate)
}
