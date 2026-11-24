package cloud.trotter.log.strength.ui.plan

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val goalDateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

internal fun goalDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(goalDateFormat)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalDatePicker(epochDay: Long?, onDate: (Long) -> Unit, onDismiss: () -> Unit) {
    val date = epochDay?.takeIf(PlanGoalDate::isValid)
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
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    onDate(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay())
                }
                onDismiss()
            }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.plans_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_cancel_button)) }
        },
    ) { DatePicker(picker) }
}
