package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.SheetCommitButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardioSwapSheet(
    current: CardioActivity,
    onSwap: (CardioActivity, CardioTemplate?) -> Unit,
    onBack: () -> Unit,
) {
    var activity by rememberSaveable { mutableStateOf(current) }
    var template by rememberSaveable { mutableStateOf<CardioTemplate?>(null) }
    AppModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerHeader(stringResource(R.string.sheet_swap_title, cardioActivityName(current)), onBack)
                Text(stringResource(R.string.day_today_only), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        body = {
            CardioActivityField(activity, { activity = it })
            Text(stringResource(R.string.cardio_edit_start_from), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardioTemplate.entries.forEach { choice ->
                    FilterChip(selected = template == choice,
                        onClick = { template = choice.takeUnless { it == template } },
                        label = { Text(cardioTemplateLabel(choice)) })
                }
            }
        },
        footer = {
            SheetCommitButton(label = stringResource(R.string.day_swap_apply), onClick = { onSwap(activity, template) },
                enabled = activity != current || template != null,
                cancelLabel = stringResource(R.string.day_edit_cancel_button), onCancel = onBack)
        },
    )
}
