package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet

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
internal fun roleName(role: CardioRole): String = stringResource(when (role) {
    CardioRole.WARMUP -> R.string.cardio_warmup
    CardioRole.WORK -> R.string.cardio_work
    CardioRole.RECOVERY -> R.string.cardio_recovery
    CardioRole.COOLDOWN -> R.string.cardio_cooldown
})

@Composable
internal fun statusName(status: CardioSetStatus?, running: Boolean): String = stringResource(when (status) {
    CardioSetStatus.FINISHED -> R.string.cardio_finished
    CardioSetStatus.SHORTENED -> R.string.cardio_shortened
    CardioSetStatus.SKIPPED -> R.string.cardio_skipped
    null -> if (running) R.string.cardio_running else R.string.cardio_ready
})

@Composable
internal fun measureText(measure: Measure): String = when (measure) {
    is Measure.Time -> formatCardioTime(measure.seconds)
    is Measure.Distance -> stringResource(R.string.cardio_metres, measure.metres)
}

internal fun formatCardioTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

internal fun CardioBlock.plannedTimeDetail(): String {
    if (sets.any { it.end !is Measure.Time }) return ""
    return formatCardioTime(sets.sumOf { (it.end as Measure.Time).seconds })
}

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

internal val CardioCardState.completed: Boolean get() {
    val results = run?.resultSets ?: loggedSets
    return run?.complete == true || (run == null && results.isNotEmpty())
}

internal val CardioCardState.position: Int get() {
    val block = block ?: return 0
    return run?.position ?: if (completed) block.sets.size else 0
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
            Button(onClick = { actions.onSaveCardioDistance(run.action(position)) }, enabled = draft.valid) { Text(stringResource(R.string.cardio_done)) }
        }
    }
}
