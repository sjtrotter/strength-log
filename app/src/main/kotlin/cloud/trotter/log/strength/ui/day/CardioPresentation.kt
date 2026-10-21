package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.Done

/** History's stored label is a title-cased activity, independent of the former interval labels. */
internal fun activityLabel(activity: CardioActivity): String =
    activity.name.lowercase(java.util.Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase() }

@Composable
internal fun cardioActivityName(activity: CardioActivity): String = stringResource(when (activity) {
    CardioActivity.ROAD_RUN -> R.string.cardio_road_run
    CardioActivity.TRAIL_RUN -> R.string.cardio_trail_run
    CardioActivity.TRACK_RUN -> R.string.cardio_track_run
    CardioActivity.TREADMILL_RUN -> R.string.cardio_treadmill_run
    CardioActivity.OUTDOOR_WALK -> R.string.cardio_outdoor_walk
    CardioActivity.TREADMILL_WALK -> R.string.cardio_treadmill_walk
    CardioActivity.OUTDOOR_RIDE -> R.string.cardio_outdoor_ride
    CardioActivity.STATIONARY_BIKE -> R.string.cardio_stationary_bike
    CardioActivity.ELLIPTICAL -> R.string.cardio_elliptical
    CardioActivity.ROWER -> R.string.cardio_rower
})

@Composable
private fun roleName(role: CardioRole): String = stringResource(when (role) {
    CardioRole.WARMUP -> R.string.cardio_warmup
    CardioRole.WORK -> R.string.cardio_work
    CardioRole.RECOVERY -> R.string.cardio_recovery
    CardioRole.COOLDOWN -> R.string.cardio_cooldown
})

@Composable
private fun statusName(status: CardioSetStatus?, running: Boolean): String = stringResource(when (status) {
    CardioSetStatus.FINISHED -> R.string.cardio_finished
    CardioSetStatus.SHORTENED -> R.string.cardio_shortened
    CardioSetStatus.SKIPPED -> R.string.cardio_skipped
    null -> if (running) R.string.cardio_running else R.string.cardio_ready
})

@Composable
private fun measureText(measure: Measure): String = when (measure) {
    is Measure.Time -> formatCardioTime(measure.seconds)
    is Measure.Distance -> stringResource(R.string.cardio_metres, measure.metres)
}

internal fun formatCardioTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
internal fun CardioSummary(sets: List<CardioSetResult>, seconds: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.day_cardio_logged, formatCardioTime(seconds)))
        Text(stringResource(R.string.cardio_outcomes,
            sets.count { it.status == CardioSetStatus.FINISHED },
            sets.count { it.status == CardioSetStatus.SHORTENED },
            sets.count { it.status == CardioSetStatus.SKIPPED }))
        val performed = sets.filter { it.status != CardioSetStatus.SKIPPED }
        val distance = performed.mapNotNull { it.metres }
        Text(when {
            distance.isEmpty() -> stringResource(R.string.cardio_distance_unknown)
            distance.size == performed.size -> stringResource(R.string.cardio_distance_total, distance.sumOf { it.toLong() })
            else -> stringResource(R.string.cardio_distance_recorded, distance.sumOf { it.toLong() })
        })
    }
}

@Composable
internal fun CardioPage(
    cardio: CardioCardState,
    dayId: String,
    accent: Color,
    actions: DayActions,
    startEnabled: Boolean,
    onEnd: () -> Unit,
) {
    val block = cardio.block ?: return
    val run = cardio.run
    val results = run?.resultSets ?: cardio.loggedSets
    val completed = run?.complete == true || (run == null && results.isNotEmpty())
    val position = run?.position ?: if (completed) block.sets.size else 0
    val spec = block.sets.getOrNull(position)
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Text(cardioActivityName(block.activity), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.cardio_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.cardio_skip)) },
                            enabled = run?.running != true && !completed && startEnabled,
                            onClick = { menu = false; actions.onSkipCardioSet() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.cardio_end)) },
                            enabled = !completed && (run != null || startEnabled),
                            onClick = { menu = false; onEnd() })
                    }
                }
            }
            if (cardio.finishesDay) Text(stringResource(R.string.cardio_finishes_day, dayId))
            if (spec != null) {
                Text(roleName(spec.role))
                Text(stringResource(R.string.cardio_set_of, position + 1, block.sets.size))
                Text(spec.aim?.let { stringResource(R.string.cardio_end_aim, measureText(spec.end), measureText(it)) }
                    ?: measureText(spec.end))
                when (val end = spec.end) {
                    is Measure.Time -> Text(formatCardioTime(if (run?.running == true) cardio.stepSecondsLeft else end.seconds),
                        style = DisplayXl, color = accent)
                    is Measure.Distance -> {
                        Text(stringResource(R.string.cardio_target))
                        Text(stringResource(R.string.cardio_metres, end.metres), style = DisplayXl, color = accent)
                        Text(stringResource(R.string.day_cardio_elapsed, formatCardioTime(cardio.elapsedSeconds)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                block.sets.forEachIndexed { index, set ->
                    val result = results.getOrNull(index)
                    val current = index == position
                    val status = statusName(result?.status, current && run?.running == true)
                    val description = stringResource(R.string.cardio_segment_description, index + 1, roleName(set.role), status)
                    val color = when {
                        result?.status == CardioSetStatus.FINISHED -> Done
                        result?.status == CardioSetStatus.SHORTENED -> MaterialTheme.colorScheme.onSurfaceVariant
                        current -> accent
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Box(Modifier.weight(1f).height(8.dp)
                        .then(if (result?.status == CardioSetStatus.SKIPPED) Modifier.border(1.dp, MaterialTheme.colorScheme.outline)
                            else Modifier.background(color))
                        .semantics { contentDescription = description })
                }
            }
            if (run?.running == true) {
                (spec?.end as? Measure.Distance)?.let {
                    Button(onClick = actions.onReachCardioTarget) { Text(stringResource(R.string.cardio_reached, it.metres)) }
                }
                TextButton(onClick = actions.onStopCardio) { Text(stringResource(R.string.day_cardio_stop_button)) }
            } else if (run?.complete != true) {
                Button(onClick = actions.onStartCardio, enabled = startEnabled) { Text(stringResource(R.string.day_cardio_start_button)) }
            }
            if (run?.saveFailed == true) {
                Text(stringResource(R.string.cardio_save_failed))
                Button(onClick = actions.onRetryCardioSave) { Text(stringResource(R.string.cardio_retry)) }
            }
            TextButton(onClick = actions.onOpenCardioSets) { Text(stringResource(R.string.cardio_all_sets)) }
            run?.drafts?.filter { !it.saved }?.forEach { draft ->
                TextButton(onClick = { actions.onRequestCardioDistance(draft.position) }) {
                    Text(stringResource(R.string.cardio_enter_distance))
                }
            }
            if (run?.sessionId != null) {
                CardioSummary(run.resultSets, run.results.sumOf { it.seconds })
            } else if (run == null) cardio.loggedSeconds?.let {
                if (cardio.loggedSets.isNotEmpty()) CardioSummary(cardio.loggedSets, it)
                else Text(stringResource(R.string.day_cardio_logged, formatCardioTime(it)))
            }
        }
    }
}

@Composable
private fun cardioResultText(result: CardioSetResult): String {
    val status = statusName(result.status, false)
    val aim = result.spec.aim ?: return stringResource(R.string.cardio_result_elapsed, status, formatCardioTime(result.elapsedSeconds))
    val met = if (result.status == CardioSetStatus.SKIPPED) null else when (aim) {
        is Measure.Time -> result.elapsedSeconds <= aim.seconds
        is Measure.Distance -> result.metres?.let { it >= aim.metres }
    }
    val aimStatus = stringResource(when (met) {
        true -> R.string.cardio_aim_met
        false -> R.string.cardio_aim_missed
        null -> R.string.cardio_aim_unknown
    })
    val actual = when (aim) {
        is Measure.Time -> formatCardioTime(result.elapsedSeconds)
        is Measure.Distance -> result.metres?.toString() ?: stringResource(R.string.cardio_unknown_value)
    }
    return stringResource(R.string.cardio_result_aim, status, actual, measureText(aim), aimStatus)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardioSetsSheet(cardio: CardioCardState, actions: DayActions) {
    val block = cardio.block ?: return
    val run = cardio.run
    val results = run?.resultSets ?: cardio.loggedSets
    AppModalBottomSheet(onDismissRequest = actions.onCloseAllSets,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item { Text(stringResource(R.string.cardio_all_sets), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(block.sets) { index, set ->
                val result = results.getOrNull(index)
                val status = statusName(result?.status, run?.running == true && index == run.position)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.cardio_segment_description, index + 1, roleName(set.role), status)) },
                    supportingContent = {
                        Column {
                            Text(set.aim?.let { stringResource(R.string.cardio_end_aim, measureText(set.end), measureText(it)) }
                                ?: measureText(set.end))
                            result?.let {
                                Text(cardioResultText(it))
                                val metres = it.metres
                                if (metres != null && set.aim !is Measure.Distance) {
                                    Text(stringResource(R.string.cardio_distance_total, metres))
                                }
                            }
                        }
                    },
                    trailingContent = {
                        if (run?.drafts?.any { it.position == index } == true) {
                            TextButton(onClick = { actions.onCloseAllSets(); actions.onRequestCardioDistance(index) }) {
                                Text(stringResource(R.string.cardio_enter_distance))
                            }
                        }
                    },
                )
            }
            item { TextButton(onClick = actions.onAddCardioSet, enabled = run?.complete != true && (run != null || results.isEmpty())) { Text(stringResource(R.string.cardio_add_set)) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardioDistanceSheet(run: CardioRun, position: Int, actions: DayActions) {
    val draft = run.drafts.firstOrNull { it.position == position } ?: return
    AppModalBottomSheet(onDismissRequest = actions.onCloseSetEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (draft.stopped) R.string.cardio_set_stopped else R.string.cardio_times_up),
                style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.cardio_entry_identity, cardioActivityName(run.block.activity), position + 1))
            Row {
                IconButton(modifier = Modifier.size(48.dp), onClick = {
                    actions.onCardioDistanceChange(((draft.metres ?: 1) - 1).takeIf { it > 0 }?.toString().orEmpty())
                }) { Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.cardio_decrease_distance)) }
                OutlinedTextField(value = draft.text, onValueChange = actions.onCardioDistanceChange,
                    label = { Text(stringResource(R.string.cardio_distance_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !draft.valid, modifier = Modifier.weight(1f))
                IconButton(modifier = Modifier.size(48.dp), onClick = {
                    actions.onCardioDistanceChange(((draft.metres ?: 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toString())
                }) { Icon(Icons.Default.Add, stringResource(R.string.cardio_increase_distance)) }
            }
            if (!draft.valid) Text(stringResource(R.string.cardio_distance_invalid), color = MaterialTheme.colorScheme.error)
            Button(onClick = actions.onSaveCardioDistance, enabled = draft.valid) { Text(stringResource(R.string.cardio_done)) }
        }
    }
}
