package cloud.trotter.log.strength.ui.day.receipt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.SecondsStepper
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun receiptMeta(completedAt: Long, minutes: Int?): String {
    val date = remember(completedAt) {
        Instant.ofEpochMilli(completedAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))
    }
    return if (minutes == null) stringResource(R.string.operation_receipt_duration_missing, date) else stringResource(R.string.receipt_meta_minutes, date, minutes)
}

internal fun visibleWeight(lb: Double, unit: WeightUnit): String = SetFormatter.weight(lb, unit)

internal fun visibleSet(set: ReceiptSet, tracking: TrackingType, unit: WeightUnit): String =
    SetFormatter.display(tracking, set.weightLb, set.reps, set.seconds, unit)

internal fun ReceiptSet.tracking(): TrackingType = SetFormatter.trackingOfValues(weightLb, reps, seconds)

private fun PlannedSets.tracking(): TrackingType = SetFormatter.trackingOfValues(weightLb, reps, seconds)

@Composable
internal fun visiblePlan(plan: PlannedSets, unit: WeightUnit): String {
    val tracking = plan.tracking()
    val value = if (tracking == TrackingType.TIMED) SecondsStepper.format(plan.seconds) else plan.reps.toString()
    return if (tracking == TrackingType.WEIGHTED) {
        stringResource(R.string.receipt_planned_at, plan.count, value, visibleWeight(plan.weightLb, unit))
    } else stringResource(R.string.receipt_planned, plan.count, value)
}

@Composable
internal fun spokenWeight(lb: Double, unit: WeightUnit): String =
    WeightStepper.format(unit.fromLb(lb)) + " " + stringResource(
        if (unit == WeightUnit.LB) R.string.receipt_say_pounds else R.string.receipt_say_kilograms,
    )

@Composable
internal fun spokenDuration(seconds: Int): String = buildList {
    val minutes = seconds / 60
    val remainder = seconds % 60
    if (minutes > 0) add(pluralStringResource(R.plurals.receipt_say_minutes, minutes, minutes))
    if (remainder > 0 || minutes == 0) add(pluralStringResource(R.plurals.receipt_say_seconds, remainder, remainder))
}.joinToString(" ")

@Composable
internal fun spokenSet(set: ReceiptSet, tracking: TrackingType, unit: WeightUnit): String = when (tracking) {
    TrackingType.WEIGHTED -> stringResource(R.string.receipt_say_weight_for_reps, spokenWeight(set.weightLb, unit), set.reps)
    TrackingType.REPS -> stringResource(R.string.receipt_say_reps, set.reps)
    TrackingType.TIMED -> spokenDuration(set.seconds) + if (set.weightLb > 0) {
        stringResource(R.string.receipt_say_with_added, spokenWeight(set.weightLb, unit))
    } else ""
}

@Composable
internal fun spokenList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items.single()
    else -> stringResource(R.string.receipt_say_and, items.dropLast(1).joinToString(", "), items.last())
}

@Composable
internal fun prLabel(kind: PrKind): String = stringResource(when (kind) {
    PrKind.WEIGHT -> R.string.receipt_pr_weight
    PrKind.REPS -> R.string.receipt_pr_reps
    PrKind.HOLD -> R.string.receipt_pr_hold
    PrKind.FASTER -> R.string.receipt_pr_faster
    PrKind.FARTHER -> R.string.receipt_pr_farther
})

@Composable
internal fun spokenRecord(kind: PrKind): String = stringResource(when (kind) {
    PrKind.WEIGHT -> R.string.receipt_say_record_weight
    PrKind.REPS -> R.string.receipt_say_record_reps
    PrKind.HOLD -> R.string.receipt_say_record_hold
    PrKind.FASTER -> R.string.receipt_say_record_faster
    PrKind.FARTHER -> R.string.receipt_say_record_farther
})

@Composable
private fun spokenRecords(mark: RecordMark): String {
    val first = spokenRecord(mark.kind)
    return mark.alsoKind?.let { spokenList(listOf(first, spokenRecord(it).replaceFirstChar(Char::lowercase))) } ?: first
}

@Composable
internal fun spokenRow(row: ReceiptRow, unit: WeightUnit, nextTime: ReceiptNextTime? = null): String {
    if (row.shape == ReceiptRow.Shape.SKIPPED) return stringResource(R.string.receipt_say_skipped, row.name)
    val work = when (row.shape) {
        ReceiptRow.Shape.TOP_SET -> row.lines.mapIndexed { index, line ->
            if (index == 0) stringResource(R.string.receipt_say_top_set,
                spokenSet(row.sets[line.setIndices.first()], row.tracking, unit))
            else stringResource(R.string.receipt_say_weight_group, spokenWeight(requireNotNull(line.weightLb), unit),
                spokenList(line.setIndices.map { row.sets[it].reps.toString() }))
        }.joinToString(" ") + if (row.lighterSets > 0) {
            " " + pluralStringResource(R.plurals.receipt_lighter_sets, row.lighterSets, row.lighterSets) + "."
        } else ""
        ReceiptRow.Shape.HEAVIEST_NO_TOP -> stringResource(R.string.receipt_say_top_set_not_logged) + " " +
            stringResource(R.string.receipt_say_heaviest, spokenSet(row.sets[row.lines.first().setIndices.first()], row.tracking, unit))
        else -> when {
            row.lighterSets > 0 -> stringResource(R.string.receipt_say_heaviest_lighter,
                spokenSet(row.sets[row.lines.first().setIndices.first()], row.tracking, unit),
                pluralStringResource(R.plurals.receipt_lighter_sets, row.lighterSets, row.lighterSets))
            row.tracking == TrackingType.WEIGHTED -> row.lines.map { line ->
                stringResource(R.string.receipt_say_weight_group, spokenWeight(requireNotNull(line.weightLb), unit),
                    spokenList(line.setIndices.map { row.sets[it].reps.toString() }))
            }.joinToString(" ")
            row.tracking == TrackingType.REPS -> stringResource(R.string.receipt_say_cardio_measure,
                stringResource(R.string.receipt_say_reps_list, spokenList(row.lines.flatMap { it.setIndices }.map { row.sets[it].reps.toString() })))
            else -> stringResource(R.string.receipt_say_holds,
                spokenList(row.lines.flatMap { it.setIndices }.map { spokenSet(row.sets[it], row.tracking, unit) }))
        }
    }
    val record = row.record?.let {
        val previous = spokenSet(it.previous, row.tracking, unit)
        if (row.shape == ReceiptRow.Shape.STRAIGHT) stringResource(R.string.receipt_say_record_on_set, spokenRecords(it), it.setIndex + 1, previous)
        else stringResource(R.string.receipt_say_record_up_from, spokenRecords(it), previous)
    }
    val next = nextTime?.let {
        val offer = it.offerLb
        if (offer == null) stringResource(R.string.receipt_say_next_time_repeat)
        else stringResource(R.string.receipt_say_next_time_add,
            WeightStepper.format(DayScreenBuilder.offerDelta(offer, it.openingLb, unit)) + " " + stringResource(
                if (unit == WeightUnit.LB) R.string.receipt_say_pounds else R.string.receipt_say_kilograms))
    }
    return listOfNotNull("${row.name}.", work, record, next).joinToString(" ")
}

@Composable
internal fun spokenSetLine(row: ReceiptRow, index: Int, unit: WeightUnit): String {
    val set = row.sets[index]
    val value = spokenSet(set, set.tracking(), unit)
    val mark = row.record?.takeIf { it.setIndex == index }
    return if (mark == null) stringResource(R.string.receipt_say_set, index + 1, value)
    else stringResource(R.string.receipt_say_set_record, index + 1, value,
        spokenRecords(mark).replaceFirstChar(Char::lowercase), spokenSet(mark.previous, row.tracking, unit))
}

@Composable
internal fun spokenPlan(plan: PlannedSets, unit: WeightUnit): String = when (plan.tracking()) {
    TrackingType.WEIGHTED -> stringResource(R.string.receipt_say_planned_weighted, plan.count, plan.reps, spokenWeight(plan.weightLb, unit))
    TrackingType.REPS -> stringResource(R.string.receipt_say_planned_reps, plan.count, plan.reps)
    TrackingType.TIMED -> stringResource(R.string.receipt_say_planned_holds, plan.count, spokenDuration(plan.seconds))
}

@Composable
internal fun cardioRowName(row: CardioReceiptRow, unit: DistanceUnit): String = when (row.role) {
    CardioRole.WARMUP -> stringResource(R.string.cardio_warmup)
    CardioRole.COOLDOWN -> stringResource(R.string.cardio_cooldown)
    CardioRole.RECOVERY -> stringResource(R.string.cardio_recovery)
    else -> {
        val finishedAlone = row.count == 1 && row.endSeconds != null &&
            row.intervals.singleOrNull()?.status == CardioSetStatus.FINISHED
        val measure = when {
            finishedAlone -> ClockText.minutesSeconds(row.intervals.single().elapsedSeconds)
            row.endSeconds != null -> ClockText.minutesSeconds(row.endSeconds)
            else -> visibleDistance(requireNotNull(row.endMetres), unit)
        }
        if (row.count > 1) stringResource(R.string.receipt_cardio_work_count, row.count, measure) else measure
    }
}

@Composable
internal fun cardioValueLabel(row: CardioReceiptRow): String = stringResource(
    if (row.endMetres != null) {
        if (row.count > 1) R.string.receipt_cardio_fastest else R.string.receipt_cardio_time
    } else if (row.count > 1) R.string.receipt_cardio_farthest else R.string.receipt_cardio_distance,
)

internal fun bestInterval(row: CardioReceiptRow): CardioInterval? {
    val finished = row.intervals.filter { it.status == CardioSetStatus.FINISHED }
    return if (row.endMetres != null) finished.minByOrNull { it.elapsedSeconds }
    else finished.filter { it.metres != null }.maxByOrNull { requireNotNull(it.metres) }
}

@Composable
internal fun visibleDistance(metres: Int, unit: DistanceUnit): String = unit.format(metres)

@Composable
internal fun spokenDistance(metres: Int, unit: DistanceUnit): String =
    (if (unit == DistanceUnit.M) String.format(Locale.US, "%,d", metres) else unit.inputText(metres)) + " " + stringResource(when (unit) {
        DistanceUnit.MI -> R.string.receipt_say_miles
        DistanceUnit.KM -> R.string.receipt_say_kilometers
        DistanceUnit.M -> R.string.receipt_say_meters
    })

@Composable
internal fun cardioValue(row: CardioReceiptRow, unit: DistanceUnit): String {
    if (row.intervals.all { it.status == CardioSetStatus.SKIPPED }) return stringResource(R.string.cardio_skipped)
    if (row.intervals.size == 1 && row.intervals.single().status == CardioSetStatus.SHORTENED) {
        return shortenedInterval(row, row.intervals.single(), unit)
    }
    if (row.role != CardioRole.WORK) return ClockText.minutesSeconds(row.intervals.sumOf { it.elapsedSeconds })
    val best = bestInterval(row)
    return if (row.endMetres != null) best?.let { ClockText.minutesSeconds(it.elapsedSeconds) }
        ?: stringResource(R.string.receipt_cardio_not_finished)
    else best?.metres?.let { visibleDistance(it, unit) } ?: stringResource(R.string.receipt_cardio_distance_missing)
}

@Composable
internal fun cardioPrevious(mark: CardioRecordMark, unit: DistanceUnit): String = mark.previousSeconds?.let(ClockText::minutesSeconds)
    ?: visibleDistance(requireNotNull(mark.previousMetres), unit)

@Composable
private fun spokenCardioPrevious(mark: CardioRecordMark, unit: DistanceUnit): String = mark.previousSeconds?.let { spokenDuration(it) }
    ?: spokenDistance(requireNotNull(mark.previousMetres), unit)

@Composable
internal fun spokenCardioRow(row: CardioReceiptRow, unit: DistanceUnit): String {
    if (row.intervals.all { it.status == CardioSetStatus.SKIPPED }) return stringResource(R.string.receipt_say_skipped, cardioRowName(row, unit))
    if (row.intervals.size == 1 && row.intervals.single().status == CardioSetStatus.SHORTENED) {
        return stringResource(R.string.receipt_say_cardio_value, cardioRowName(row, unit), shortenedInterval(row, row.intervals.single(), unit, spoken = true))
    }
    if (row.role != CardioRole.WORK) return stringResource(R.string.receipt_say_cardio_value,
        cardioRowName(row, unit), spokenDuration(row.intervals.sumOf { it.elapsedSeconds }))
    val finishedAlone = row.count == 1 && row.endSeconds != null &&
        row.intervals.singleOrNull()?.status == CardioSetStatus.FINISHED
    val measure = when {
        finishedAlone -> spokenDuration(row.intervals.single().elapsedSeconds)
        row.endSeconds != null -> spokenDuration(row.endSeconds)
        else -> spokenDistance(requireNotNull(row.endMetres), unit)
    }
    val name = if (row.count > 1) stringResource(R.string.receipt_say_cardio_by, row.count, measure) else measure
    val best = bestInterval(row)
    val value = if (best == null) {
        if (row.endMetres == null) stringResource(R.string.receipt_say_cardio_distance_missing)
        else stringResource(R.string.receipt_say_cardio_measure, stringResource(R.string.receipt_cardio_not_finished))
    } else stringResource(R.string.receipt_say_cardio_value, cardioValueLabel(row),
        if (row.endMetres != null) spokenDuration(best.elapsedSeconds) else spokenDistance(requireNotNull(best.metres), unit))
    val record = row.record?.let { stringResource(R.string.receipt_say_record_up_from, spokenRecord(it.kind), spokenCardioPrevious(it, unit)) }
    return listOfNotNull(stringResource(R.string.receipt_say_cardio_measure, name), value, record).joinToString(" ")
}

@Composable
internal fun cardioIntervalValue(row: CardioReceiptRow, interval: CardioInterval, unit: DistanceUnit): String {
    if (interval.status == CardioSetStatus.SKIPPED) return stringResource(R.string.cardio_skipped)
    val value = if (row.endMetres != null) ClockText.minutesSeconds(interval.elapsedSeconds)
        else interval.metres?.let { visibleDistance(it, unit) } ?: stringResource(R.string.receipt_cardio_not_entered)
    return if (interval.status == CardioSetStatus.SHORTENED) shortenedInterval(row, interval, unit) else value
}

@Composable
internal fun spokenInterval(row: CardioReceiptRow, index: Int, unit: DistanceUnit): String {
    val interval = row.intervals[index]
    if (interval.status == CardioSetStatus.SKIPPED) return stringResource(R.string.receipt_say_interval_skipped, index + 1)
    val value = if (row.endMetres != null) spokenDuration(interval.elapsedSeconds)
        else interval.metres?.let { spokenDistance(it, unit) } ?: stringResource(R.string.receipt_cardio_distance_missing)
    val record = row.record?.takeIf { it.intervalIndex == index }
    return when {
        record != null -> stringResource(R.string.receipt_say_interval_record, index + 1, value,
            spokenRecord(record.kind).replaceFirstChar(Char::lowercase), spokenCardioPrevious(record, unit))
        interval.status == CardioSetStatus.SHORTENED -> stringResource(R.string.operation_receipt_shortened_interval, index + 1, shortenedInterval(row, interval, unit, spoken = true))
        else -> stringResource(R.string.receipt_say_interval, index + 1, value)
    }
}

@Composable
internal fun recoveryText(seconds: List<Int>, spoken: Boolean = false): String {
    val each = seconds.distinct().size == 1
    val values = (if (each) seconds.take(1) else seconds).map { if (spoken) spokenDuration(it) else ClockText.minutesSeconds(it) }
    return stringResource(if (each) {
        if (spoken) R.string.receipt_say_recovery_each else R.string.receipt_cardio_recovery_each
    } else R.string.receipt_cardio_recovery, values.joinToString(", "))
}

@Composable
private fun shortenedInterval(row: CardioReceiptRow, interval: CardioInterval, unit: DistanceUnit, spoken: Boolean = false): String {
    val plannedSeconds = row.endSeconds
    val plannedMetres = row.endMetres
    val actual: String
    val planned: String
    if (plannedSeconds != null) {
        actual = if (spoken) spokenDuration(interval.elapsedSeconds) else ClockText.minutesSeconds(interval.elapsedSeconds)
        planned = if (spoken) spokenDuration(plannedSeconds) else ClockText.minutesSeconds(plannedSeconds)
    } else if (plannedMetres != null) {
        actual = interval.metres?.let { if (spoken) spokenDistance(it, unit) else visibleDistance(it, unit) }
            ?: stringResource(R.string.receipt_cardio_not_entered)
        planned = if (spoken) spokenDistance(plannedMetres, unit) else visibleDistance(plannedMetres, unit)
    } else {
        // Receipts restored from older bundles may not carry the captured target.
        return stringResource(R.string.operation_receipt_shortened_unknown,
            if (spoken) spokenDuration(interval.elapsedSeconds) else ClockText.minutesSeconds(interval.elapsedSeconds))
    }
    return stringResource(R.string.operation_receipt_shortened, actual, planned)
}
