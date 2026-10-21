package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
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
import cloud.trotter.log.strength.domain.units.SecondsStepper
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.resolveStepperCommit
import cloud.trotter.log.strength.ui.text.resolve
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
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
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
            )
            val plateText = plate?.let { stringResource(R.string.set_edit_plates, it) }
            val effectText = stringResource(
                if (row.isTop) R.string.set_edit_top_cascades else R.string.set_edit_only_this,
            )
            Text(
                text = listOfNotNull(plateText, effectText).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = dayAccent(dayIndex),
                    contentColor = onDayAccent(dayIndex),
                ),
            ) {
                Text(stringResource(R.string.set_edit_done))
            }
        }
    }
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
                label = stringResource(R.string.set_edit_weight, unit.name.lowercase()),
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
                label = stringResource(R.string.set_edit_seconds),
                field = stringResource(R.string.set_edit_seconds),
                partnerName = partnerName,
                modifier = Modifier.fillMaxWidth(),
                minimum = 0.0,
                step = { SecondsStepper.increment(it.toInt()).toDouble() },
                round = {
                    val increment = SecondsStepper.increment(it.toInt())
                    Math.round(it / increment) * increment.toDouble()
                },
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
    textOverride: String? = null,
    onTextChange: ((String) -> Unit)? = null,
) {
    var text by rememberSaveable(identity) { mutableStateOf(WeightStepper.format(value)) }
    val interactionSource = remember(identity) { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value, focused) {
        if (!focused) text = WeightStepper.format(value)
    }
    val trackField = partnerName?.let { stringResource(R.string.set_edit_partner_field, it, field) } ?: field
    val amount = if (step(value) == 1.0) stringResource(R.string.set_edit_one) else WeightStepper.format(step(value))
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                focusManager.clearFocus()
                onChange(round(value - step(value)).coerceAtLeast(minimum))
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.set_edit_less, trackField, amount))
        }
        OutlinedTextField(
            value = textOverride ?: text,
            onValueChange = {
                if (onTextChange != null) onTextChange(it) else {
                    text = it
                    resolveStepperCommit(it, minimum, Double.POSITIVE_INFINITY, round)?.let(onChange)
                }
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            ),
        )
        IconButton(
            onClick = {
                focusManager.clearFocus()
                onChange(round(value + step(value)).coerceAtLeast(minimum))
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.Add, stringResource(R.string.set_edit_more, trackField, amount))
        }
    }
}
