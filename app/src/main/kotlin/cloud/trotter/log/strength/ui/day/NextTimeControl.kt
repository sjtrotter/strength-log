package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.progression.NextTimeMode

object NextTimeGate {
    fun shows(plan: PlanEntity?, slotMode: NextTimeMode?): Boolean =
        plan?.autoApply == true || slotMode == NextTimeMode.AUTO || plan?.offerStrengthIncreases != false

    fun shows(plan: PlanEntity): Boolean = plan.autoApply || plan.offerCardioIncreases
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NextTimeControl(mode: NextTimeMode, onMode: (NextTimeMode) -> Unit,
                             prescribed: Boolean = false, keptToPlan: Boolean = false, onPlan: () -> Unit = {},
                             planAvailable: Boolean = true) {
    val labels = listOf(R.string.next_time_automatic, R.string.next_time_ask) +
        if (prescribed && planAvailable) listOf(R.string.day_edit_next_time_plan) else emptyList()
    Column {
        Text(stringResource(R.string.day_edit_next_time), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = if (index == 2) keptToPlan else !keptToPlan && mode == NextTimeMode.entries[index],
                    onClick = { if (index == 2) onPlan() else onMode(NextTimeMode.entries[index]) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    label = { Text(stringResource(label)) },
                    icon = {},
                )
            }
        }
        Text(stringResource(when {
            keptToPlan && prescribed -> R.string.next_time_plan_detail
            keptToPlan -> R.string.next_time_kept_to_plan
            mode == NextTimeMode.AUTO -> R.string.next_time_automatic_detail
            else -> R.string.next_time_ask_detail
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
