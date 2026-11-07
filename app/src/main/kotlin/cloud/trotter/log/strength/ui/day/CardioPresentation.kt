package cloud.trotter.log.strength.ui.day

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.cardio.cardioActivityLabel
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.SheetCommitButton
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import java.time.Instant
import java.time.ZoneId

/** History's stored label is a title-cased activity, independent of the former interval labels. */
internal fun activityLabel(activity: CardioActivity): String =
    cardioActivityLabel(activity)

@Composable
internal fun cardioActivityName(activity: CardioActivity): String = stringResource(cardioActivityNameRes(activity))

@StringRes
internal fun cardioActivityNameRes(activity: CardioActivity): Int = when (activity) {
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
}

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
internal fun measureText(measure: Measure, activity: CardioActivity?): String = when (measure) {
    is Measure.Time -> formatCardioTime(measure.seconds)
    is Measure.Distance -> distanceUnitFor(activity).format(measure.metres)
}

internal fun formatCardioTime(seconds: Int): String = ClockText.minutesSeconds(seconds)

internal fun CardioBlock.plannedTimeDetail(): String {
    if (sets.any { it.end !is Measure.Time }) return ""
    return formatCardioTime(sets.sumOf { (it.end as Measure.Time).seconds })
}

/** The day's cardio session committed on the civil day of [nowMillis]: what the day screen shows as logged. */
internal fun List<CardioSessionEntity>.loggedToday(planId: Long?, dayId: String?, nowMillis: Long): CardioSessionEntity? {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return firstOrNull {
        it.planId == planId && it.dayId == dayId &&
            Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() == today
    }
}

@Composable
internal fun CardioSummary(sets: List<CardioSetResult>, seconds: Int, activity: CardioActivity?, modifier: Modifier = Modifier) {
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
            distance.size == performed.size -> stringResource(R.string.cardio_distance_total, distanceUnitFor(activity).format(distance.sum()))
            else -> stringResource(R.string.cardio_distance_recorded, distanceUnitFor(activity).format(distance.sum()))
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
internal fun CardioDistanceSheet(
    run: CardioRun,
    position: Int,
    actions: DayActions,
    distanceUnit: DistanceUnit,
    input: CardioDistanceInput? = null,
) {
    val draft = run.drafts.firstOrNull { it.position == position } ?: return
    val unit = DistanceUnit.forActivity(distanceUnit, run.block.activity)
    val text = input?.takeIf { it.unit == unit }?.text
        ?: draft.metres()?.let(unit::inputText) ?: draft.text
    val valid = draft.valid()
    AppModalBottomSheet(
        onDismissRequest = actions.onCloseSetEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        footer = {
            SheetCommitButton(
                label = stringResource(if (draft.text.isBlank()) R.string.sheet_without_distance else R.string.sheet_save_distance),
                onClick = { actions.onSaveCardioDistance(run.action(position)) },
                enabled = valid,
            )
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (draft.stopped) R.string.cardio_set_stopped else R.string.cardio_times_up),
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.cardio_entry_identity, cardioActivityName(run.block.activity), position + 1))
            }
        },
        body = {
            NumericSetField(
                identity = "distance:${run.action(position)}",
                value = text.toDoubleOrNull() ?: 0.0,
                label = stringResource(R.string.cardio_distance_label, unit.symbol),
                field = stringResource(R.string.sheet_distance_field),
                partnerName = null,
                modifier = Modifier.fillMaxWidth(),
                minimum = 0.0,
                maximum = unit.fromMetres(200_000),
                decimal = unit != DistanceUnit.M,
                step = { if (unit == DistanceUnit.M) 1.0 else 0.05 },
                round = { WeightStepper.format(it).toDouble() },
                onChange = { actions.onCardioDistanceChange(it.takeIf { value -> value > 0 }?.let(WeightStepper::format).orEmpty()) },
                textOverride = text,
                onTextChange = actions.onCardioDistanceChange,
                isError = !valid,
            )
            if (!valid) Text(stringResource(R.string.cardio_distance_invalid), color = MaterialTheme.colorScheme.error)
        },
    )
}
