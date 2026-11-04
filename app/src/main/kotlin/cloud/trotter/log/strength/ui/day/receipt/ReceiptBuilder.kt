package cloud.trotter.log.strength.ui.day.receipt

import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.RunTrack
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import cloud.trotter.log.strength.domain.records.PersonalBests
import java.time.Instant
import java.time.ZoneId

object ReceiptBuilder {
    fun strength(
        tracks: List<RunTrack>,
        slots: List<ProgramSlot>,
        names: (String) -> String,
        standing: Map<String, PersonalRecord>,
    ): List<ReceiptGroup> = buildList {
        for (slot in slots.sortedBy { it.position }) {
            val captured = tracks.filter { it.programExerciseId == slot.programExerciseId }.sortedBy { it.position }
            val mains = captured.filter { it.slot == Slot.MAIN }
            val main = mains.firstOrNull { !it.retired }
                ?: mains.singleOrNull()?.takeIf { it.dropped && it.sets.none { set -> set.done } }
            val partners = captured.filter {
                it.slot == Slot.SS && ((!it.retired && !it.dropped) || it.sets.any { set -> set.done })
            }
            val partner = partners.firstOrNull { !it.retired } ?: partners.lastOrNull()
            val singles = mains.filter { it.retired && it.sets.any { set -> set.done } } +
                partners.filter { it != partner || main == null }
            for (track in singles.sortedBy { it.position }) {
                add(ReceiptGroup(null, listOf(row(track, slot.exercise.structure, names(track.exerciseId), standing))))
            }
            if (main != null) {
                val rows = listOfNotNull(main, partner).map {
                    row(it, slot.exercise.structure, names(it.exerciseId), standing)
                }
                // Either half can be skipped, so the rounds are the half that did more.
                val rounds = partner?.let { maxOf(main.sets.count { it.done }, it.sets.count { set -> set.done }) }
                add(ReceiptGroup(rounds, rows))
            }
        }
        val slotIds = slots.map { it.programExerciseId }.toSet()
        for (track in tracks.filter { it.programExerciseId !in slotIds }.sortedBy { it.position }) {
            add(ReceiptGroup(null, listOf(row(track, SetStructure.STRAIGHT, names(track.exerciseId), standing))))
        }
    }

    private fun row(
        track: RunTrack,
        structure: SetStructure,
        name: String,
        standing: Map<String, PersonalRecord>,
    ): ReceiptRow {
        val done = track.sets.filter { it.done }
        if (done.isEmpty()) {
            val tracking = track.planned.firstOrNull()?.let(PersonalBests::tracking) ?: TrackingType.WEIGHTED
            return ReceiptRow(name, tracking, ReceiptRow.Shape.SKIPPED, emptyList(), emptyList(), 0, null, null)
        }
        val bestIndex = PersonalBests.bestIndex(done)
        val tracking = PersonalBests.tracking(done[bestIndex ?: 0])
        val indices = done.indices.filter { PersonalBests.tracking(done[it]) == tracking }
        val sets = done.map { it.receiptSet() }
        val record = bestIndex?.let { record(done, it, standing[track.exerciseId]) }
        if (structure.hasTopSet && tracking == TrackingType.WEIGHTED) {
            val top = highestIndex(done, indices.filter { done[it].kind == SetKind.TOP })
            val index = top ?: requireNotNull(highestIndex(done, indices))
            val shape = if (top == null) ReceiptRow.Shape.HEAVIEST_NO_TOP else ReceiptRow.Shape.TOP_SET
            return ReceiptRow(name, tracking, shape, sets, listOf(ValueLine(done[index].weightLb, listOf(index))), 0, null, record)
        }
        val lines = valueLines(done, indices, tracking)
        val lighterSets = if (tracking == TrackingType.WEIGHTED && indices.map { done[it].weightLb }.distinct().size >= 3) {
            sets.size - 1
        } else 0
        val planned = track.planned.firstOrNull()?.let {
            PlannedSets(track.planned.size, it.weightLb, it.reps, it.seconds)
        }
        return ReceiptRow(name, tracking, ReceiptRow.Shape.STRAIGHT, sets, lines, lighterSets, planned, record)
    }

    private fun valueLines(done: List<LoggedSet>, indices: List<Int>, tracking: TrackingType): List<ValueLine> {
        if (tracking != TrackingType.WEIGHTED) return listOf(ValueLine(null, indices))
        val byWeight = indices.groupBy { done[it].weightLb }.toSortedMap(reverseOrder())
        if (byWeight.size <= 2) return byWeight.map { (weight, sets) -> ValueLine(weight, sets) }
        val index = requireNotNull(highestIndex(done, indices))
        return listOf(ValueLine(done[index].weightLb, listOf(index)))
    }

    private fun highestIndex(done: List<LoggedSet>, indices: List<Int>): Int? =
        indices.maxWithOrNull { a, b -> PersonalBests.compare(done[a], done[b]) }

    private fun record(done: List<LoggedSet>, bestIndex: Int, standing: PersonalRecord?): RecordMark? {
        val previous = standing?.let { LoggedSet(it.weightLb, it.reps, SetKind.WORK, done = true, seconds = it.seconds) }
        val kinds = PersonalBests.prKinds(done[bestIndex], previous)
        return kinds.firstOrNull()?.let {
            RecordMark(it, kinds.getOrNull(1), bestIndex, requireNotNull(previous).receiptSet())
        }
    }

    private fun LoggedSet.receiptSet(): ReceiptSet = ReceiptSet(weightLb, reps, seconds)

    fun cardio(
        activity: CardioActivity,
        results: List<CardioSetResult>,
        standing: Map<CardioRecordKey, CardioSetResult>,
    ): List<CardioReceiptRow> {
        val rows = mutableListOf<CardioReceiptRow>()
        val workRows = mutableMapOf<Measure, Int>()
        var latestWork: Int? = null
        var previousRole: CardioRole? = null
        for (result in results) {
            val role = result.spec.role
            when (role) {
                CardioRole.RECOVERY -> latestWork?.let { index ->
                    val row = rows[index]
                    rows[index] = row.copy(recoverySeconds = row.recoverySeconds + result.elapsedSeconds)
                }
                CardioRole.WORK -> {
                    val index = workRows.getOrPut(result.spec.end) {
                        rows.add(cardioRow(result))
                        rows.lastIndex
                    }
                    val row = rows[index]
                    rows[index] = row.copy(count = row.count + 1, intervals = row.intervals + result.interval())
                    latestWork = index
                }
                CardioRole.WARMUP, CardioRole.COOLDOWN -> {
                    if (previousRole == role) {
                        val row = rows.last()
                        rows[rows.lastIndex] = row.copy(intervals = row.intervals + result.interval())
                    } else {
                        rows += cardioRow(result).copy(intervals = listOf(result.interval()))
                    }
                }
            }
            previousRole = role
        }
        for (record in CardioBests.records(activity, results, standing)) {
            val index = workRows.getValue(record.key.end)
            val row = rows[index]
            val mark = CardioRecordMark(
                record.kind,
                row.intervals.indexOf(record.best.interval()),
                record.previous.elapsedSeconds.takeIf { record.key.end is Measure.Distance },
                record.previous.metres.takeIf { record.key.end is Measure.Time },
            )
            rows[index] = row.copy(record = mark)
        }
        return rows
    }

    private fun cardioRow(result: CardioSetResult): CardioReceiptRow {
        val work = result.spec.role == CardioRole.WORK
        return CardioReceiptRow(
            role = result.spec.role,
            count = if (work) 0 else 1,
            endSeconds = if (work) (result.spec.end as? Measure.Time)?.seconds else null,
            endMetres = if (work) (result.spec.end as? Measure.Distance)?.metres else null,
            intervals = emptyList(),
            recoverySeconds = emptyList(),
            record = null,
        )
    }

    private fun CardioSetResult.interval(): CardioInterval = CardioInterval(status, elapsedSeconds, metres)

    fun allPlansDone(
        plans: List<PlanEntity>,
        sessions: List<WorkoutSessionEntity>,
        cardio: List<CardioSessionEntity>,
        completedAt: Long,
        zone: ZoneId,
    ): Boolean {
        val onToday = plans.filter { !it.archived && it.onToday }
        if (onToday.size < 2) return false
        val today = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()
        val completedPlans = sessions.filter { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() == today }
            .map { it.planId }.toSet() +
            cardio.filter { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() == today }.map { it.planId }
        return onToday.all { it.id in completedPlans }
    }
}
