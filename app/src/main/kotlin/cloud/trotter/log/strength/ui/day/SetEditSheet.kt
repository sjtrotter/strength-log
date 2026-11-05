package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.SheetCommitButton
import cloud.trotter.log.strength.ui.components.resolveStepperCommit
import cloud.trotter.log.strength.ui.text.resolve
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

/** Field text is the only draft: every valid edit immediately uses the existing mutation callback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetEditSheet(
    card: ExerciseCardState,
    row: SetRowState,
    unit: WeightUnit,
    dayIndex: Int,
    actions: DayActions,
    onDismiss: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        footer = {
            SheetCommitButton(stringResource(R.string.sheet_save_set), onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = dayAccent(dayIndex), contentColor = onDayAccent(dayIndex)))
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(setLabel(row).resolve(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.set_edit_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.set_edit_remove)) },
                            onClick = {
                                actions.onRemoveSet(card.programExerciseId, row.index, card.isSuperset)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        body = {
            SetFields(
                card = card,
                row = row,
                slot = Slot.MAIN,
                weight = row.weightDisplay,
                reps = row.reps,
                seconds = row.seconds,
                tracking = card.tracking,
                timedWeight = card.timedShowsWeight,
                unit = unit,
                actions = actions,
            )
            row.partner?.let { partner ->
                Text(card.partnerTitle.orEmpty(), style = MaterialTheme.typography.titleMedium)
                SetFields(
                    card = card,
                    row = row,
                    slot = Slot.SS,
                    weight = partner.weightDisplay,
                    reps = partner.reps,
                    seconds = partner.seconds,
                    tracking = card.partnerTracking ?: TrackingType.WEIGHTED,
                    timedWeight = card.partnerTimedShowsWeight,
                    unit = unit,
                    actions = actions,
                )
            }
            if (card.timedRepAim != null) {
                Text(
                    stringResource(R.string.timed_rep_aim, row.reps, card.timedRepAim),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val plate = DayScreenBuilder.plateLine(
                main = listOf(
                    LoggedSet(unit.toLb(row.weightDisplay), row.reps, SetKind.WORK, done = false, seconds = row.seconds),
                ),
                equipment = card.equipment,
                unit = unit,
                tracking = card.tracking,
                focusedIndex = 0,
            )
            val plateText = plate?.let {
                if (it == "0") stringResource(R.string.set_edit_empty_bar)
                else stringResource(R.string.set_edit_plates_with_unit, it, unit.name.lowercase())
            }
            val effectText = stringResource(
                when {
                    !row.isTop -> R.string.set_edit_only_this
                    card.rows.any { it.kind == SetKind.BACKOFF } -> R.string.set_edit_top_cascades
                    else -> R.string.set_edit_top_cascades_without_backoff
                },
            )
            Text(
                text = listOfNotNull(plateText, effectText).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun SetFields(
    card: ExerciseCardState,
    row: SetRowState,
    slot: String,
    weight: Double,
    reps: Int,
    seconds: Int,
    tracking: TrackingType,
    timedWeight: Boolean,
    unit: WeightUnit,
    actions: DayActions,
) {
    val partnerName = card.partnerTitle.takeIf { slot == Slot.SS }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tracking == TrackingType.WEIGHTED || timedWeight) {
            NumericSetField(
                identity = "${card.programExerciseId}:$slot:${row.index}:weight",
                value = weight,
                label = stringResource(R.string.sheet_weight, unit.name.lowercase()),
                field = stringResource(R.string.set_edit_weight_field),
                partnerName = partnerName,
                modifier = Modifier.fillMaxWidth(),
                minimum = 0.0,
                decimal = true,
                step = { WeightStepper.increment(it, unit) },
                round = { WeightStepper.round(it, unit) },
                onChange = { actions.onWeightChange(card.programExerciseId, slot, row.index, it) },
            )
        }
        if (tracking == TrackingType.TIMED) {
            NumericSetField(
                identity = "${card.programExerciseId}:$slot:${row.index}:seconds",
                value = seconds.toDouble(),
                label = stringResource(R.string.sheet_duration),
                field = stringResource(R.string.sheet_duration),
                partnerName = partnerName,
                modifier = Modifier.fillMaxWidth(),
                minimum = 0.0,
                step = { 30.0 },
                duration = true,
                round = { it },
                onChange = { actions.onSecondsChange(card.programExerciseId, slot, row.index, it.toInt()) },
            )
        } else {
            NumericSetField(
                identity = "${card.programExerciseId}:$slot:${row.index}:reps",
                value = reps.toDouble(),
                label = stringResource(R.string.set_edit_reps),
                field = stringResource(R.string.set_edit_reps),
                partnerName = partnerName,
                modifier = Modifier.fillMaxWidth(),
                minimum = if (slot == Slot.MAIN && card.timedRepDurationSeconds != null) 0.0 else 1.0,
                step = { 1.0 },
                onChange = { actions.onRepsChange(card.programExerciseId, slot, row.index, it.toInt()) },
            )
        }
    }
}

/** M3 has no numeric field with bounded stepping and buttons that reflow below large type. */
@Composable
internal fun NumericSetField(
    identity: String,
    value: Double,
    label: String,
    field: String,
    partnerName: String?,
    modifier: Modifier,
    minimum: Double,
    decimal: Boolean = false,
    step: (Double) -> Double,
    round: (Double) -> Double = { Math.round(it).toDouble() },
    onChange: (Double) -> Unit,
    maximum: Double = Double.POSITIVE_INFINITY,
    duration: Boolean = false,
    isError: Boolean = false,
    textOverride: String? = null,
    onTextChange: ((String) -> Unit)? = null,
) {
    val format: (Double) -> String = if (duration) { { ClockText.minutesSeconds(it.toInt()) } } else WeightStepper::format
    var text by rememberSaveable(identity) { mutableStateOf(format(value)) }
    val interactionSource = remember(identity) { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value, focused) {
        if (!focused && text.isNotBlank()) text = format(value)
    }
    val trackField = partnerName?.let { stringResource(R.string.set_edit_partner_field, it, field) } ?: field
    val decrease = stringResource(R.string.sheet_decrease, trackField.lowercase())
    val increase = stringResource(R.string.sheet_increase, trackField.lowercase())
    val fieldContent: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = textOverride ?: text,
            onValueChange = { draft ->
                if (onTextChange != null) onTextChange(draft) else {
                    text = draft
                    val parsed = if (duration) parseSheetDuration(draft)?.toString() else draft
                    parsed?.let { resolveStepperCommit(it, minimum, maximum, round) }?.let(onChange)
                }
            },
            label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            textStyle = DisplayXl,
            singleLine = true,
            isError = isError,
            modifier = fieldModifier.heightIn(min = 80.dp),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    duration -> KeyboardType.Ascii
                    decimal -> KeyboardType.Decimal
                    else -> KeyboardType.Number
                },
            ),
        )
    }
    val buttons: @Composable (Boolean) -> Unit = { increaseValue ->
        IconButton(
            onClick = {
                focusManager.clearFocus()
                val next = round(value + if (increaseValue) step(value) else -step(value)).coerceIn(minimum, maximum)
                text = format(next)
                onChange(next)
            },
            modifier = Modifier.size(48.dp),
        ) {
            if (increaseValue) Icon(Icons.Filled.Add, increase)
            else Icon(painterResource(R.drawable.ic_remove), decrease)
        }
    }
    BoxWithConstraints(modifier) {
        if (maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fieldContent(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    buttons(false)
                    buttons(true)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons(false)
                fieldContent(Modifier.weight(1f))
                buttons(true)
            }
        }
    }
}

/** Duration drafts use the same canonical seconds as the existing steppers. */
internal fun parseSheetDuration(text: String): Double? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val minutes = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val seconds = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return (minutes.toDouble() * 60 + seconds).takeIf { it <= Int.MAX_VALUE }
}
