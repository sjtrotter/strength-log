package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.SheetCommitButton
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardioEntrySheet(
    draft: CardioEntryDraft,
    title: String,
    nowMillis: () -> Long,
    onChange: (CardioEntryDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    saveFailed: Boolean = false,
    saving: Boolean = false,
    collision: Boolean = false,
    startEditable: Boolean = true,
    completedAt: Long = draft.completedAt,
) {
    val currentSaving by rememberUpdatedState(saving)
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    var durationText by rememberSaveable { mutableStateOf(formatCardioTime(draft.seconds)) }
    val unit = distanceUnitFor(draft.activity)
    // The Parcelable keeps meters; editable display text has separate saved state.
    var distanceText by rememberSaveable(unit) {
        mutableStateOf(draft.metres?.let(unit::inputText) ?: draft.metresText)
    }
    val onDistanceChange: (String) -> Unit = { text ->
        distanceText = text
        val meters = when {
            text.isBlank() -> ""
            unit == DistanceUnit.M -> text
            else -> text.toDoubleOrNull()?.let(unit::toMetres)?.toString() ?: "0"
        }
        onChange(draft.copy(metresText = meters))
    }
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(draft.startedAt).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()
    val dateInteraction = remember { MutableInteractionSource() }
    val timeInteraction = remember { MutableInteractionSource() }
    val datePressed by dateInteraction.collectIsPressedAsState()
    val timePressed by timeInteraction.collectIsPressedAsState()
    LaunchedEffect(datePressed, saving, startEditable) { if (datePressed && !saving && startEditable) dateOpen = true }
    LaunchedEffect(timePressed, saving, startEditable) { if (timePressed && !saving && startEditable) timeOpen = true }
    AppModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !currentSaving }),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        footer = {
            SheetCommitButton(stringResource(R.string.plans_save), onSave, enabled = !saving && draft.valid(nowMillis(), completedAt),
                cancelLabel = if (saving) null else stringResource(R.string.day_cancel_button), onCancel = onDismiss)
        },
        body = {
            CardioActivityField(draft.activity, { onChange(draft.copy(activity = it)) }, enabled = !saving)
            if (startEditable) {
                OutlinedTextField(
                    value = start.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)),
                    onValueChange = {}, readOnly = true,
                    enabled = !saving,
                    label = { Text(stringResource(R.string.cardio_entry_date)) },
                    interactionSource = dateInteraction,
                    modifier = Modifier.fillMaxWidth().then(if (saving) Modifier else Modifier.semantics { onClick { dateOpen = true; true } }),
                )
                OutlinedTextField(
                    value = start.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)),
                    onValueChange = {}, readOnly = true,
                    enabled = !saving,
                    label = { Text(stringResource(R.string.cardio_entry_start)) },
                    interactionSource = timeInteraction,
                    modifier = Modifier.fillMaxWidth().then(if (saving) Modifier else Modifier.semantics { onClick { timeOpen = true; true } }),
                )
            } else {
                ListItem(
                    overlineContent = { Text(stringResource(R.string.cardio_entry_date)) },
                    headlineContent = { Text(start.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))) },
                )
                ListItem(
                    overlineContent = { Text(stringResource(R.string.cardio_entry_start)) },
                    headlineContent = { Text(start.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))) },
                )
            }
            if (draft.measuresEditable) {
                NumericSetField(
                    identity = "cardio-entry:duration", value = draft.seconds.toDouble(),
                    label = stringResource(R.string.sheet_duration), field = stringResource(R.string.sheet_duration),
                    partnerName = null, modifier = Modifier.fillMaxWidth(), minimum = 1.0,
                    maximum = ManualCardio.MAX_SECONDS.toDouble(), step = { 30.0 }, duration = true,
                    enabled = !saving,
                    onChange = { durationText = formatCardioTime(it.toInt()); onChange(draft.copy(seconds = it.toInt())) },
                    textOverride = durationText,
                    onTextChange = {
                        durationText = it
                        onChange(draft.copy(seconds = parseSheetDuration(it)?.toInt() ?: 0))
                    },
                )
                NumericSetField(
                    identity = "cardio-entry:distance:$unit", value = distanceText.toDoubleOrNull() ?: 0.0,
                    label = stringResource(R.string.cardio_distance_label, unit.symbol), field = stringResource(R.string.sheet_distance_field),
                    partnerName = null, modifier = Modifier.fillMaxWidth(), minimum = 0.0,
                    maximum = unit.fromMetres(ManualCardio.MAX_METRES), decimal = unit != DistanceUnit.M,
                    step = { if (unit == DistanceUnit.M) 1.0 else 0.05 },
                    round = { WeightStepper.format(it).toDouble() },
                    enabled = !saving,
                    onChange = { onDistanceChange(it.takeIf { value -> value > 0 }?.let(WeightStepper::format).orEmpty()) },
                    textOverride = distanceText, onTextChange = onDistanceChange,
                )
            }
            OutlinedTextField(
                value = draft.note, onValueChange = { onChange(draft.copy(note = it.take(NOTE_MAX_LENGTH))) },
                enabled = !saving,
                label = { Text(stringResource(R.string.sheet_note_label)) },
                supportingText = { Text(stringResource(R.string.sheet_note_counter, draft.note.length, NOTE_MAX_LENGTH)) },
                minLines = 1, maxLines = 3, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (completedAt > nowMillis()) {
                Text(stringResource(R.string.cardio_entry_ends_later), color = MaterialTheme.colorScheme.error)
            }
            if (saveFailed) Text(stringResource(if (collision) R.string.cardio_entry_collision else R.string.cardio_entry_save_failed),
                color = MaterialTheme.colorScheme.error)
        },
    )
    if (dateOpen && !saving && startEditable) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= today
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(onClick = {
                dateState.selectedDateMillis?.let {
                    val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    onChange(draft.copy(startedAt = date.atTime(start.toLocalTime()).atZone(zone).toInstant().toEpochMilli()))
                }
                dateOpen = false
            }, enabled = dateState.selectedDateMillis != null) { Text(stringResource(R.string.plans_save)) } },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.day_cancel_button)) } },
        ) { DatePicker(dateState) }
    }
    if (timeOpen && !saving && startEditable) {
        val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute)
        AppAlertDialog(
            onDismissRequest = { timeOpen = false },
            title = { Text(stringResource(R.string.cardio_entry_start)) },
            text = { TimePicker(timeState) },
            confirmButton = { TextButton(onClick = {
                onChange(draft.copy(startedAt = start.withHour(timeState.hour).withMinute(timeState.minute)
                    .withSecond(0).withNano(0).toInstant().toEpochMilli()))
                timeOpen = false
            }) { Text(stringResource(R.string.plans_save)) } },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text(stringResource(R.string.day_cancel_button)) } },
        )
    }
}
