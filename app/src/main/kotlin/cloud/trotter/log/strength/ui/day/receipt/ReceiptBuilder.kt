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
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import cloud.trotter.log.strength.domain.records.PersonalBests
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import java.time.Instant
import java.time.ZoneId

object ReceiptBuilder {
    fun strength(
        tracks: List<RunTrack>,
        slots: List<ProgramSlot>,
        names: (String) -> String,
        standing: Map<String, PersonalRecord>,
        targets: Map<Long, List<LoggedSet>> = emptyMap(),
    ): List<ReceiptGroup> = strengthWithTracks(tracks, slots, names, standing, targets).map { it.first }

    internal fun strengthWithTracks(
        tracks: List<RunTrack>,
        slots: List<ProgramSlot>,
        names: (String) -> String,
        standing: Map<String, PersonalRecord>,
        targets: Map<Long, List<LoggedSet>> = emptyMap(),
    ): List<Pair<ReceiptGroup, List<RunTrack>>> = buildList {
        for (slot in slots.sortedBy { it.position }) {
            val captured = tracks.filter { it.programExerciseId == slot.programExerciseId }.sortedBy { it.position }
            val mains = captured.filter { it.slot == Slot.MAIN }
            // A skipped lift is a dropped MAIN with no live MAIN, as OpenRun.isLiftSkipped reads it;
            // one with done work is already a single row.
            val main = mains.firstOrNull { !it.retired }
                ?: mains.lastOrNull { it.dropped }?.takeIf { it.sets.none { set -> set.done } }
            // Skipping the first half promotes its partner; that partner was kept, not skipped.
            val promoted = mains.any { it.retired && it.dropped }
            val partners = captured.filter {
                it.slot == Slot.SS && ((!it.retired && !it.dropped) || it.sets.any { set -> set.done } ||
                    it.dropped && (!promoted || it.exerciseId != main?.exerciseId))
            }
            val partner = partners.firstOrNull { !it.retired }
                ?: partners.lastOrNull()?.takeIf { !promoted || it.exerciseId != main?.exerciseId }
            val singles = mains.filter { it != main && it.retired && (it.dropped || it.sets.any { set -> set.done }) } +
                partners.filter { it != partner || main == null }
            for (track in singles.sortedBy { it.position }) {
                add(ReceiptGroup(null, listOf(row(track, track.structure(), names(track.exerciseId), standing, plan(track, slot, targets)))) to listOf(track))
            }
            if (main != null) {
                val rowTracks = listOfNotNull(main, partner)
                val rows = rowTracks.map {
                    row(it, it.structure(), names(it.exerciseId), standing, plan(it, slot, targets))
                }
                add(ReceiptGroup(if (partner == null) null else main.sets.count { it.done }, rows) to rowTracks)
            }
        }
        val slotIds = slots.map { it.programExerciseId }.toSet()
        for (track in tracks.filter { it.programExerciseId !in slotIds }.sortedBy { it.position }) {
            add(ReceiptGroup(null, listOf(row(track, SetStructure.STRAIGHT, names(track.exerciseId), standing, plan(track, null, targets)))) to listOf(track))
        }
    }

    /** A superset partner has no structure of its own; it is seeded as straight work sets. */
    private fun RunTrack.structure(): SetStructure = when {
        slot == Slot.SS -> SetStructure.STRAIGHT
        sets.any { it.kind == SetKind.BACKOFF } -> SetStructure.RAMP_TOP_BACKOFF
        sets.any { it.kind == SetKind.RAMP || it.kind == SetKind.TOP } -> SetStructure.RAMP_TOP
        else -> SetStructure.STRAIGHT
    }

    private fun plan(track: RunTrack, slot: ProgramSlot?, targets: Map<Long, List<LoggedSet>>): PlannedSets? {
        val plannedId = if (track.slot == Slot.SS) slot?.exercise?.superset?.exerciseId else slot?.exercise?.exerciseId
        val exercise = slot?.exercise?.takeIf { track.slot == Slot.MAIN && it.exerciseId == track.exerciseId }
        val rows = if (slot != null && track.exerciseId != plannedId) track.planned.ifEmpty { track.sets }
            else exercise?.prescription?.let { SetSeeder.seedPrescribed(exercise.targetSets, it) }
            ?: targets[track.id] ?: track.sets
        return rows.firstOrNull()?.let { PlannedSets(rows.size, it.weightLb, it.reps, it.seconds) }
    }

    private fun row(
        track: RunTrack,
        structure: SetStructure,
        name: String,
        standing: Map<String, PersonalRecord>,
        planned: PlannedSets?,
    ): ReceiptRow {
        val done = track.sets.filter { it.done }
        if (done.isEmpty()) {
            val tracking = planned?.let { PersonalBests.tracking(LoggedSet(it.weightLb, it.reps, SetKind.WORK, seconds = it.seconds)) }
                ?: TrackingType.WEIGHTED
            return ReceiptRow(name, tracking, ReceiptRow.Shape.SKIPPED, emptyList(), emptyList(), 0, planned, null)
        }
        val bestIndex = PersonalBests.bestIndex(done)
        val tracking = PersonalBests.tracking(done[bestIndex ?: 0])
        val indices = done.indices.filter { PersonalBests.tracking(done[it]) == tracking }
        val sets = done.map { it.receiptSet() }
        val record = bestIndex?.let { record(done, it, standing[track.exerciseId]) }
        if (structure.hasTopSet && tracking == TrackingType.WEIGHTED) {
            val top = highestIndex(done, indices.filter { done[it].kind == SetKind.TOP })
            if (top != null) {
                val rest = indices.filter { it != top && done[it].kind != SetKind.RAMP }
                val lines = listOf(ValueLine(done[top].weightLb, listOf(top))) + valueLines(done, rest, tracking)
                val lighterSets = if (rest.map { done[it].weightLb }.distinct().size > 2) rest.size - 1 else 0
                return ReceiptRow(name, tracking, ReceiptRow.Shape.TOP_SET, sets, lines, lighterSets, planned, record)
            }
            // With no top set, a record set must be the one shown: these rows never open.
            val index = record?.setIndex ?: requireNotNull(highestIndex(done, indices))
            return ReceiptRow(name, tracking, ReceiptRow.Shape.HEAVIEST_NO_TOP, sets, listOf(ValueLine(done[index].weightLb, listOf(index))), 0, planned, record)
        }
        val lines = valueLines(done, indices, tracking)
        val lighterSets = if (tracking == TrackingType.WEIGHTED && indices.map { done[it].weightLb }.distinct().size >= 3) {
            sets.size - 1
        } else 0
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
                CardioRole.RECOVERY -> {
                    if (result.status != CardioSetStatus.FINISHED) {
                        rows += cardioRow(result).copy(intervals = listOf(result.interval()))
                    } else latestWork?.let { index ->
                        val row = rows[index]
                        rows[index] = row.copy(recoverySeconds = row.recoverySeconds + result.elapsedSeconds)
                    }
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
                    val previous = rows.lastOrNull()
                    if (previousRole == role && previous != null &&
                        previous.intervals.all { it.status == CardioSetStatus.FINISHED } &&
                        result.status == CardioSetStatus.FINISHED) {
                        rows[rows.lastIndex] = previous.copy(intervals = previous.intervals + result.interval())
                    } else rows += cardioRow(result).copy(intervals = listOf(result.interval()))
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
            endSeconds = (result.spec.end as? Measure.Time)?.seconds,
            endMetres = (result.spec.end as? Measure.Distance)?.metres,
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
