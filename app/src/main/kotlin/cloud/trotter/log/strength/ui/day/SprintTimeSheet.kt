package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.NumberField

internal fun StartProtocol.labelRes(): Int = when (this) {
    StartProtocol.THREE_POINT -> R.string.sprint_protocol_three_point
    StartProtocol.TWO_POINT -> R.string.sprint_protocol_two_point
    StartProtocol.BLOCKS -> R.string.sprint_protocol_blocks
    StartProtocol.STANDING -> R.string.sprint_protocol_standing
    StartProtocol.FLYING -> R.string.sprint_protocol_flying
}

internal fun TimingMethod.timedLabelRes(): Int = when (this) {
    TimingMethod.PHONE -> R.string.sprint_timed_phone
    TimingMethod.HAND -> R.string.sprint_timed_hand
    TimingMethod.VIDEO -> R.string.sprint_timed_video
    TimingMethod.GATES -> R.string.sprint_timed_gates
}

private fun TimingMethod.labelRes(): Int = when (this) {
    TimingMethod.PHONE -> R.string.sprint_method_phone
    TimingMethod.HAND -> R.string.sprint_method_hand
    TimingMethod.VIDEO -> R.string.sprint_method_video
    TimingMethod.GATES -> R.string.sprint_method_gates
}

@Composable
internal fun SprintProtocolChips(protocol: StartProtocol, onProtocol: (String) -> Unit) {
    Text(stringResource(R.string.sprint_start_type))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StartProtocol.entries.forEach { choice ->
            FilterChip(selected = protocol == choice, onClick = { onProtocol(choice.name) },
                label = { Text(stringResource(choice.labelRes())) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SprintTimeSheet(
    draft: SprintTimeDraft,
    onChange: (SprintTimeDraft) -> Unit,
    onSave: (Int, TimingMethod, StartProtocol) -> Unit,
    onDismiss: () -> Unit,
) {
    val centis = ClockText.parseCentis(draft.text)
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = { Text(stringResource(R.string.sprint_time_sheet_title)) },
        body = {
            key(draft.runId, draft.position) {
                NumberField(
                    value = centis?.toDouble() ?: 0.0,
                    onValueChange = { onChange(draft.copy(text = ClockText.centis(it.toInt()))) },
                    onDraftEdit = { text, _ -> onChange(draft.copy(text = text)) },
                    label = stringResource(R.string.sprint_time_field),
                    minValue = 1.0, maxValue = SprintTime.MAX_CENTIS.toDouble(),
                    format = { if (it == 0.0) draft.text else ClockText.centis(it.toInt()) },
                    parse = { ClockText.parseCentis(it)?.toDouble() }, keyboardType = KeyboardType.Decimal,
                )
            }
            Text(stringResource(R.string.sprint_method_label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TimingMethod.HAND, TimingMethod.GATES, TimingMethod.VIDEO, TimingMethod.PHONE).forEach { method ->
                    FilterChip(selected = draft.method == method.name,
                        onClick = { onChange(draft.copy(method = method.name)) },
                        label = { Text(stringResource(method.labelRes())) })
                }
            }
            SprintProtocolChips(StartProtocol.valueOf(draft.protocol)) { onChange(draft.copy(protocol = it)) }
        },
        footer = {
            Button(onClick = {
                if (centis != null) onSave(centis, TimingMethod.valueOf(draft.method), StartProtocol.valueOf(draft.protocol))
            }, enabled = centis != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.plans_save)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_cancel_button)) }
        },
    )
}
