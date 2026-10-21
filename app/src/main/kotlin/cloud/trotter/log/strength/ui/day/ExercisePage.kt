package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.text.resolve
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.LocalAppPalette
import cloud.trotter.log.strength.ui.theme.accentEmphasis
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

/** A filled M3 card containing the current prescription and ordinary Material actions. */
@Composable
internal fun ExercisePage(
    card: ExerciseCardState,
    dayIndex: Int,
    resting: Boolean,
    actions: DayActions,
    nextTitle: String?,
    onNext: () -> Unit,
    onEdit: (Int) -> Unit,
    onAllSets: () -> Unit,
    canSwap: Boolean,
    onSwap: () -> Unit,
    onSwapWeight: (Int, String) -> Unit,
    onNote: () -> Unit,
    timedRep: TimedRepState? = null,
    executionBusy: Boolean = false,
) {
    val accent = dayAccent(dayIndex)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onDayAccent(dayIndex))
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingWeightSwap by rememberSaveable(card.programExerciseId) { mutableStateOf(false) }
    val event = timedRep?.takeIf {
        it.event.programExerciseId == card.programExerciseId
    }
    val focusIndex = event?.event?.setIndex ?: card.focusIndex
    val focus = card.rows.getOrNull(focusIndex)
    val showingClock = card.timedRepDurationSeconds != null && !card.allDone
    val shown = if (card.allDone) card.rows.firstOrNull { it.isTop } ?: card.rows.lastOrNull() else focus
    Card(
        modifier = Modifier.testTag("exercise-page:${card.programExerciseId}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (card.allDone) {
                    Checkbox(checked = true, onCheckedChange = null)
                } else {
                    val target = card.headerTarget
                    Column(
                        // Share the space left after the menu with the title; generated headers keep their layout.
                        modifier = if (target is HeaderTarget.Prescribed) Modifier.weight(1f) else Modifier,
                        horizontalAlignment = Alignment.End,
                    ) {
                        val display = if (target is HeaderTarget.Prescribed && target.repAim != null) {
                            stringResource(R.string.day_target_aim, target.display, target.repAim)
                        } else target.display
                        val label = stringResource(
                            if (target is HeaderTarget.Goal) R.string.day_goal else R.string.day_target,
                            display,
                        )
                        Text(
                            text = buildAnnotatedString {
                                val start = label.lastIndexOf(display).coerceAtLeast(0)
                                withStyle(SpanStyle(color = variant)) { append(label.substring(0, start)) }
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                    append(label.substring(start))
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = if (target is HeaderTarget.Prescribed) {
                                Modifier.semantics { contentDescription = label }
                            } else Modifier,
                        )
                        val supporting = listOfNotNull(
                            card.lastTimeDisplay?.let { stringResource(R.string.day_last, it) },
                            if (target is HeaderTarget.Prescribed && target.eachSide) {
                                stringResource(R.string.day_target_each_side)
                            } else null,
                            (target as? HeaderTarget.Prescribed)?.resistance?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (supporting.isNotEmpty()) {
                            Text(
                                text = supporting,
                                style = MaterialTheme.typography.bodySmall,
                                color = variant,
                                maxLines = if (target is HeaderTarget.Prescribed) 1 else Int.MAX_VALUE,
                                overflow = if (target is HeaderTarget.Prescribed) TextOverflow.Ellipsis else TextOverflow.Clip,
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.day_exercise_menu, card.title))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canSwap) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.day_menu_swap)) },
                                onClick = {
                                    menuOpen = false
                                    onSwap()
                                },
                            )
                        }
                        card.weightSwap?.let { swap ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (swap.isRemove) R.string.day_remove_weight_button
                                            else R.string.day_add_weight_button,
                                        ),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    confirmingWeightSwap = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.day_menu_note)) },
                            onClick = {
                                menuOpen = false
                                onNote()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (shown != null) {
                Text(
                    text = when {
                        card.allDone && card.timedRepDurationSeconds != null -> setLabel(shown).resolve()
                        card.allDone -> stringResource(R.string.set_label_top)
                        event != null -> setLabel(shown).resolve()
                        else -> card.focusLabel.resolve()
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = variant,
                )
                val repsUnit = stringResource(R.string.unit_reps)
                val secondsUnit = stringResource(R.string.unit_seconds)
                val atWeight = stringResource(R.string.unit_at, WeightStepper.format(shown.weightDisplay))
                val editLabel = stringResource(R.string.set_edit_open)
                Text(
                    text = buildAnnotatedString {
                        if (showingClock) {
                            val seconds = event?.remainingSeconds ?: card.timedRepDurationSeconds
                            append("%d:%02d".format(seconds / 60, seconds % 60))
                        } else when (card.tracking) {
                            TrackingType.WEIGHTED -> {
                                append(WeightStepper.format(shown.weightDisplay))
                                withStyle(SpanStyle(fontSize = 44.sp, color = variant)) { append(" × ") }
                                append(shown.reps.toString())
                            }
                            TrackingType.REPS -> {
                                append(shown.reps.toString())
                                withStyle(SpanStyle(fontSize = 32.sp, color = variant)) { append(" $repsUnit") }
                            }
                            TrackingType.TIMED -> {
                                append(shown.seconds.toString())
                                withStyle(SpanStyle(fontSize = 32.sp, color = variant)) {
                                    append(" $secondsUnit")
                                    if (card.timedShowsWeight && shown.weightDisplay > 0) append(" $atWeight")
                                }
                            }
                        }
                    },
                    style = DisplayXl.copy(fontSize = 88.sp, lineHeight = 84.sp, fontFeatureSettings = "tnum"),
                    color = if (card.allDone) variant else accentEmphasis(dayIndex),
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = editLabel) { onEdit(shown.index) },
                )
                if (card.allDone && card.timedRepAim != null) {
                    Text(
                        stringResource(R.string.timed_rep_aim, shown.reps, card.timedRepAim),
                        style = MaterialTheme.typography.bodyMedium,
                        color = variant,
                    )
                }
                shown.partner?.let { partner ->
                    val partnerValue = setValue(
                        weight = partner.weightDisplay,
                        reps = partner.reps,
                        seconds = partner.seconds,
                        tracking = card.partnerTracking ?: TrackingType.WEIGHTED,
                        timedWeight = card.partnerTimedShowsWeight,
                    )
                    Text(
                        text = stringResource(R.string.day_then_partner, "${card.partnerTitle.orEmpty()} $partnerValue"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.rows.forEach { row ->
                    val label = setLabel(row).resolve()
                    val status = stringResource(
                        if (row.done) R.string.day_segment_done else R.string.day_segment_not_started,
                    )
                    Box(
                        modifier = Modifier.weight(1f).height(48.dp)
                            .semantics { contentDescription = "$label, $status" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(6.dp).background(
                                color = when {
                                    row.done -> LocalAppPalette.current.done
                                    row.index == focusIndex -> accent
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(2.dp),
                            ),
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (card.allDone) stringResource(R.string.day_sets_done, card.rows.size, card.rows.size)
                    else stringResource(R.string.day_set_of, focusIndex + 1, card.rows.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = variant,
                )
                card.nextSetDisplay?.takeUnless { card.allDone || focusIndex != card.focusIndex }?.let {
                    Text(
                        text = stringResource(R.string.day_next_set, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = variant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (card.allDone) {
                Text(card.summaryLine, style = MaterialTheme.typography.bodyLarge, color = variant)
                if (nextTitle != null) {
                    Button(
                        onClick = onNext,
                        colors = colors,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(56.dp),
                    ) {
                        Text(nextTitle)
                    }
                }
            } else if (focus != null && showingClock) {
                val modifier = Modifier.fillMaxWidth().height(56.dp)
                if (event?.event?.awaitingCount == false) {
                    TextButton(onClick = actions.onStopTimedRep, modifier = modifier) {
                        Text(stringResource(R.string.day_cardio_stop_button))
                    }
                } else {
                    val awaiting = event?.event?.awaitingCount == true
                    val label = stringResource(
                        if (awaiting) R.string.timed_rep_enter_reps else R.string.day_cardio_start_button,
                    )
                    val onClick = {
                        if (awaiting) actions.onRequestTimedRepCount()
                        else actions.onStartTimedRep(card.programExerciseId, focus.index)
                    }
                    val enabled = awaiting || (!executionBusy && timedRep == null)
                    if (resting) {
                        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
                    } else {
                        Button(onClick = onClick, enabled = enabled, colors = colors, modifier = modifier) { Text(label) }
                    }
                }
            } else if (focus != null) {
                val log = stringResource(
                    R.string.day_log_set,
                    setValue(focus.weightDisplay, focus.reps, focus.seconds, card.tracking, card.timedShowsWeight),
                )
                val onLog = { actions.onToggleDone(card.programExerciseId, focus.index, true, card.isSuperset) }
                if (resting) {
                    FilledTonalButton(onClick = onLog, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(log) }
                } else {
                    Button(onClick = onLog, colors = colors, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(log)
                    }
                }
            }
            TextButton(
                onClick = onAllSets,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.day_all_sets))
            }
        }
    }
    card.weightSwap?.takeIf { confirmingWeightSwap }?.let { swap ->
        WeightSwapConfirmDialog(
            swap = swap,
            onConfirm = {
                onSwapWeight(card.position, swap.targetExerciseId)
                confirmingWeightSwap = false
            },
            onDismiss = { confirmingWeightSwap = false },
        )
    }
}

internal fun setLabel(row: SetRowState): UiText.SetLabel =
    UiText.SetLabel(row.kind, if (row.kind == SetKind.RAMP) row.rampIndex else row.index + 1)

@Composable
internal fun setValue(
    weight: Double,
    reps: Int,
    seconds: Int,
    tracking: TrackingType,
    timedWeight: Boolean,
): String = when (tracking) {
    TrackingType.WEIGHTED -> "${WeightStepper.format(weight)} × $reps"
    TrackingType.REPS -> "$reps ${stringResource(R.string.unit_reps)}"
    TrackingType.TIMED -> "$seconds ${stringResource(R.string.unit_seconds)}" +
        if (timedWeight && weight > 0) " ${stringResource(R.string.unit_at, WeightStepper.format(weight))}" else ""
}
