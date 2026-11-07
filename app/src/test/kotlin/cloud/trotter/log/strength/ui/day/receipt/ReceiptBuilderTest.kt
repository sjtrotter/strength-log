package cloud.trotter.log.strength.ui.day.receipt

import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.RunTrack
import cloud.trotter.log.strength.data.setCounts
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.PrKind
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptBuilderTest {
    @Test
    fun `ramped squat keeps every done set and marks the top weight record`() {
        val groups = ReceiptBuilder.strength(
            listOf(squat()), listOf(slot(structure = SetStructure.RAMP_TOP_BACKOFF)),
            { "Barbell Back Squat" }, baseline(240.0, 5),
        )
        assertEquals(1, groups.size)
        assertNull(groups.single().rounds)
        val row = groups.single().rows.single()
        assertEquals("Barbell Back Squat", row.name)
        assertEquals(TrackingType.WEIGHTED, row.tracking)
        assertEquals(ReceiptRow.Shape.TOP_SET, row.shape)
        assertEquals(listOf(ValueLine(245.0, listOf(4)), ValueLine(185.0, listOf(5))), row.lines)
        assertEquals(RecordMark(PrKind.WEIGHT, null, 4, ReceiptSet(240.0, 5, 0)), row.record)
        assertEquals(PlannedSets(6, 130.0, 5, 0), row.planned)
        assertEquals(0, row.lighterSets)
        assertEquals(listOf(
            ReceiptSet(130.0, 5, 0), ReceiptSet(165.0, 5, 0), ReceiptSet(190.0, 5, 0),
            ReceiptSet(210.0, 3, 0), ReceiptSet(245.0, 5, 0), ReceiptSet(185.0, 8, 0),
        ), row.sets)
    }

    @Test
    fun `a ramped row lists its back-off after the top set`() {
        val row = row(squat(), SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(listOf(ValueLine(245.0, listOf(4)), ValueLine(185.0, listOf(5))), row.lines)
    }

    @Test
    fun `extra sets at the top weight join the second line`() {
        val track = squat(topWeight = 95.0).let { track ->
            track.copy(sets = track.sets.map { if (it.kind == SetKind.BACKOFF) it.copy(weightLb = 95.0, reps = 5) else it })
        }
        val row = row(track, SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(listOf(ValueLine(95.0, listOf(4)), ValueLine(95.0, listOf(5))), row.lines)
    }

    @Test
    fun `strengthWithTracks pairs each row with its track`() {
        val retired = track(exerciseId = "db_press", sets = listOf(work(60.0, 8)), retired = true, position = 2)
        val main = track(exerciseId = "bb_bench", sets = List(3) { work(185.0, 5) })
        val partner = track(exerciseId = "bb_row", sets = List(2) { work(155.0, 8) }, slot = Slot.SS, position = 1)
        val removed = track(exerciseId = "removed", sets = listOf(work(35.0, 10)), programExerciseId = null, position = 3)
        val tracks = listOf(partner, removed, retired, main)
        val slots = listOf(supersetSlot())
        val paired = ReceiptBuilder.strengthWithTracks(tracks, slots, { it }, emptyMap())

        assertEquals(listOf(listOf(retired), listOf(main, partner), listOf(removed)), paired.map { it.second })
        paired.forEach { (group, rowTracks) ->
            assertEquals(group.rows.size, rowTracks.size)
            assertEquals(group.rows.map { it.name }, rowTracks.map { it.exerciseId })
        }
        assertEquals(ReceiptBuilder.strength(tracks, slots, { it }, emptyMap()), paired.map { it.first })
    }

    @Test
    fun `a tied top set is not a record`() {
        assertNull(row(squat(topWeight = 240.0), SetStructure.RAMP_TOP_BACKOFF, baseline(240.0, 5)).record)
    }

    @Test
    fun `a first session or unchecked records baseline never marks a record`() {
        val groups = ReceiptBuilder.strength(
            listOf(squat()), listOf(slot(structure = SetStructure.RAMP_TOP_BACKOFF)), { it }, emptyMap(),
        )
        assertTrue(groups.flatMap { it.rows }.all { it.record == null })
    }

    @Test
    fun `a missing top shows the heaviest ramp without awarding it a record`() {
        val row = row(squat(topDone = false), SetStructure.RAMP_TOP_BACKOFF, baseline(240.0, 5))
        assertEquals(ReceiptRow.Shape.HEAVIEST_NO_TOP, row.shape)
        assertEquals(listOf(ValueLine(210.0, listOf(3))), row.lines)
        assertEquals(5, row.sets.size)
        assertEquals(0, row.lighterSets)
        assertEquals(PlannedSets(6, 130.0, 5, 0), row.planned)
        assertNull(row.record)
    }

    @Test
    fun `an equal weight top can earn a rep record`() {
        val row = row(squat(topWeight = 235.0), SetStructure.RAMP_TOP_BACKOFF, baseline(235.0, 3))
        assertEquals(RecordMark(PrKind.REPS, null, 4, ReceiptSet(235.0, 3, 0)), row.record)
    }

    @Test
    fun `a top improving both dimensions keeps weight as the label`() {
        val row = row(squat(topReps = 6), SetStructure.RAMP_TOP_BACKOFF, baseline(240.0, 5))
        assertEquals(RecordMark(PrKind.WEIGHT, PrKind.REPS, 4, ReceiptSet(240.0, 5, 0)), row.record)
    }

    @Test
    fun `ramp top without backoff stays closed`() {
        val track = squat().copy(sets = squat().sets.dropLast(1))
        val row = row(track, SetStructure.RAMP_TOP, targets = track.planned.dropLast(1).map { it.copy(reps = 8) })
        assertEquals(ReceiptRow.Shape.TOP_SET, row.shape)
        assertEquals(listOf(ValueLine(245.0, listOf(4))), row.lines)
        assertEquals(PlannedSets(5, 130.0, 8, 0), row.planned)
        assertEquals(5, listOf(track).setCounts().total)
    }

    @Test
    fun `two top sets after an edit use the domain ranking`() {
        val row = row(track(sets = listOf(
            work(245.0, 4, kind = SetKind.TOP), work(245.0, 6, kind = SetKind.TOP),
        )), SetStructure.RAMP_TOP)
        assertEquals(listOf(ValueLine(245.0, listOf(1)), ValueLine(245.0, listOf(0))), row.lines)
    }

    @Test
    fun `straight curls group two weights and keep the plan and first record set`() {
        val row = row(track(
            exerciseId = "db_curl",
            sets = listOf(work(35.0, 10), work(35.0, 10), work(30.0, 8)),
            planned = List(3) { work(35.0, 10, done = false) },
        ), standing = baseline(35.0, 9, exerciseId = "db_curl"))
        assertEquals(ReceiptRow.Shape.STRAIGHT, row.shape)
        assertEquals(listOf(ValueLine(35.0, listOf(0, 1)), ValueLine(30.0, listOf(2))), row.lines)
        assertEquals(0, row.lighterSets)
        assertEquals(PlannedSets(3, 35.0, 10, 0), row.planned)
        assertEquals(RecordMark(PrKind.REPS, null, 0, ReceiptSet(35.0, 9, 0)), row.record)
    }

    @Test
    fun `three or more weights collapse to the single heaviest set`() {
        val row = row(track(exerciseId = "db_lateral_raise", sets = listOf(
            work(25.0, 12), work(20.0, 10), work(15.0, 10), work(10.0, 12),
        )))
        assertEquals(listOf(ValueLine(25.0, listOf(0))), row.lines)
        assertEquals(3, row.lighterSets)
        assertEquals(4, row.sets.size)
    }

    @Test
    fun `ascending straight sets still put the heaviest weight first`() {
        val row = row(track(sets = listOf(work(30.0, 8), work(35.0, 10), work(35.0, 10))))
        assertEquals(listOf(ValueLine(35.0, listOf(1, 2)), ValueLine(30.0, listOf(0))), row.lines)
    }

    @Test
    fun `one weight keeps every rep count on one line`() {
        val row = row(track(sets = listOf(work(35.0, 10), work(35.0, 10), work(35.0, 8))))
        assertEquals(listOf(ValueLine(35.0, listOf(0, 1, 2))), row.lines)
        assertEquals(0, row.lighterSets)
    }

    @Test
    fun `pull-ups use reps and mark the best set`() {
        val row = row(track(exerciseId = "pullup", sets = listOf(work(reps = 12), work(reps = 10), work(reps = 9))),
            standing = baseline(0.0, 10, exerciseId = "pullup"))
        assertEquals(TrackingType.REPS, row.tracking)
        assertEquals(listOf(ValueLine(null, listOf(0, 1, 2))), row.lines)
        assertEquals(RecordMark(PrKind.REPS, null, 0, ReceiptSet(0.0, 10, 0)), row.record)
    }

    @Test
    fun `weighted pull-ups use stored weight`() {
        val row = row(track(exerciseId = "weighted_pullup", sets = listOf(work(25.0, 8), work(25.0, 8), work(25.0, 6))))
        assertEquals(TrackingType.WEIGHTED, row.tracking)
        assertEquals(listOf(ValueLine(25.0, listOf(0, 1, 2))), row.lines)
    }

    @Test
    fun `plank holds keep the timed plan and hold record`() {
        val row = row(track(
            exerciseId = "plank", sets = listOf(work(seconds = 75), work(seconds = 70)),
            planned = List(2) { work(seconds = 60, done = false) },
        ), SetStructure.RAMP_TOP_BACKOFF, baseline(0.0, 0, seconds = 70, exerciseId = "plank"),
            targets = List(2) { work(seconds = 60, done = false) })
        assertEquals(ReceiptRow.Shape.STRAIGHT, row.shape)
        assertEquals(TrackingType.TIMED, row.tracking)
        assertEquals(listOf(ValueLine(null, listOf(0, 1))), row.lines)
        assertEquals(RecordMark(PrKind.HOLD, null, 0, ReceiptSet(0.0, 0, 70)), row.record)
        assertEquals(PlannedSets(2, 0.0, 0, 60), row.planned)
    }

    @Test
    fun `a loaded longer hold labels hold before weight`() {
        val row = row(track(exerciseId = "plank", sets = listOf(work(25.0, seconds = 90))),
            standing = baseline(0.0, 0, seconds = 75, exerciseId = "plank"))
        assertEquals(RecordMark(PrKind.HOLD, PrKind.WEIGHT, 0, ReceiptSet(0.0, 0, 75)), row.record)
    }

    @Test
    fun `mixed legacy sets retain reps but only the newest counting type forms lines or records`() {
        val row = row(track(exerciseId = "plank", sets = listOf(work(reps = 60), work(seconds = 60))),
            standing = baseline(0.0, 50, exerciseId = "plank"))
        assertEquals(TrackingType.TIMED, row.tracking)
        assertEquals(listOf(ReceiptSet(0.0, 60, 0), ReceiptSet(0.0, 0, 60)), row.sets)
        assertEquals(listOf(ValueLine(null, listOf(1))), row.lines)
        assertNull(row.record)
    }

    @Test
    fun `superset rounds count main work and partner keeps its own plan and lines`() {
        val bench = track(exerciseId = "bb_bench", sets = listOf(work(185.0, 5), work(185.0, 5), work(185.0, 4)))
        val partner = track(exerciseId = "bb_row", slot = Slot.SS, position = 1,
            sets = listOf(work(155.0, 8), work(155.0, 8), work(135.0, 10)),
            planned = List(3) { work(155.0, 8, done = false) })
        val group = ReceiptBuilder.strength(listOf(bench, partner), listOf(supersetSlot()), { it }, emptyMap()).single()
        assertEquals(3, group.rounds)
        assertEquals(listOf("bb_bench", "bb_row"), group.rows.map { it.name })
        assertEquals(listOf(ValueLine(155.0, listOf(0, 1)), ValueLine(135.0, listOf(2))), group.rows[1].lines)
        assertEquals(PlannedSets(3, 155.0, 8, 0), group.rows[1].planned)
    }

    @Test
    fun `rounds count the main half even when the partner did more`() {
        val bench = track(exerciseId = "bb_bench", sets = listOf(work(185.0, 5)))
        val partner = track(exerciseId = "bb_row", slot = Slot.SS, position = 1,
            sets = listOf(work(155.0, 8), work(155.0, 8), work(155.0, 8)))
        val group = ReceiptBuilder.strength(listOf(bench, partner), listOf(supersetSlot()), { it }, emptyMap()).single()
        assertEquals(1, group.rounds)
    }

    @Test
    fun `skipping the first superset half before logging keeps the skipped row without duplicating its promoted partner`() {
        val receipt = skippedSupersetReceiptFixture()
        val groups = receipt.groups
        assertEquals(listOf("bb_bench", "bb_row"), groups.flatMap { group -> group.rows.map { it.name } })
        assertTrue(groups.all { it.rounds == null && it.rows.size == 1 })
        assertEquals(ReceiptRow.Shape.SKIPPED, groups.first().rows.single().shape)
        assertTrue(receipt.isPartial)
        assertEquals(3, receipt.loggedSetCount)
        assertEquals(6, receipt.plannedSetCount)
    }

    @Test
    fun `skipping the first half after two rounds leaves the promoted partner as its own lifts`() {
        // dropSupersetHalf(keep = SS): partner retired and dropped, its exercise swapped into MAIN.
        val bench = track(exerciseId = "bb_bench", sets = listOf(work(185.0, 5), work(185.0, 5)),
            retired = true, dropped = true)
        val oldPartner = track(exerciseId = "bb_row", slot = Slot.SS, position = 1,
            sets = listOf(work(155.0, 8), work(155.0, 8)), retired = true, dropped = true)
        val promoted = track(exerciseId = "bb_row", position = 2, sets = listOf(work(155.0, 8)))
        val groups = ReceiptBuilder.strength(listOf(bench, oldPartner, promoted), listOf(supersetSlot()), { it }, emptyMap())
        assertEquals(listOf("bb_bench", "bb_row", "bb_row"), groups.flatMap { g -> g.rows.map { it.name } })
        assertTrue(groups.all { it.rounds == null && it.rows.size == 1 })
    }

    @Test
    fun `dropping the second half of a same-exercise superset keeps the pair`() {
        val first = track(exerciseId = "bb_back_squat", sets = listOf(work(225.0, 5), work(225.0, 5)))
        val second = track(exerciseId = "bb_back_squat", slot = Slot.SS, position = 1,
            sets = listOf(work(185.0, 8), work(185.0, 8)), retired = true, dropped = true)
        val group = ReceiptBuilder.strength(listOf(first, second), listOf(supersetSlot()), { it }, emptyMap()).single()
        assertEquals(2, group.rounds)
        assertEquals(2, group.rows.size)
    }

    @Test
    fun `a weighted partner of a ramped lift is straight sets`() {
        val bench = track(exerciseId = "bb_bench", sets = listOf(work(225.0, 5, kind = SetKind.TOP)))
        val partner = track(exerciseId = "db_curl", slot = Slot.SS, position = 1,
            sets = listOf(work(35.0, 10), work(35.0, 10), work(30.0, 8)),
            planned = List(3) { work(35.0, 10, done = false) })
        val slot = ProgramSlot(1, 0, ProgramExercise("bb_bench", SetStructure.RAMP_TOP_BACKOFF,
            superset = SupersetPartner("db_curl")))
        val rows = ReceiptBuilder.strength(listOf(bench, partner), listOf(slot), { it }, emptyMap()).single().rows
        assertEquals(listOf(ReceiptRow.Shape.TOP_SET, ReceiptRow.Shape.STRAIGHT), rows.map { it.shape })
        assertEquals(PlannedSets(3, 35.0, 10, 0), rows[1].planned)
        assertEquals(2, rows[1].lines.size)
    }

    @Test
    fun `a lift swapped and then skipped still shows as skipped`() {
        val bench = track(exerciseId = "bb_bench", sets = emptyList(), retired = true)
        val press = track(exerciseId = "db_bench_press", position = 1, sets = emptyList(), retired = true, dropped = true)
        val row = ReceiptBuilder.strength(listOf(bench, press), listOf(slot()), { it }, emptyMap()).single().rows.single()
        assertEquals("db_bench_press", row.name)
        assertEquals(ReceiptRow.Shape.SKIPPED, row.shape)
    }

    @Test
    fun `with no top set a record on the back-off set is the set shown`() {
        val row = row(squat(topDone = false), SetStructure.RAMP_TOP_BACKOFF, baseline(180.0, 8))
        assertEquals(ReceiptRow.Shape.HEAVIEST_NO_TOP, row.shape)
        assertEquals(listOf(ValueLine(185.0, listOf(4))), row.lines)
        assertEquals(4, row.record?.setIndex)
    }

    @Test
    fun `a dropped empty partner keeps its skipped row and contributes no live sets`() {
        val bench = track(exerciseId = "bb_bench", sets = List(3) { work(185.0, 5) },
            planned = List(3) { work(185.0, 5, done = false) })
        val partner = track(exerciseId = "bb_row", slot = Slot.SS, sets = emptyList(), retired = true, dropped = true,
            planned = List(3) { work(155.0, 8, done = false) })
        val group = ReceiptBuilder.strength(listOf(bench, partner), listOf(supersetSlot()), { it }, emptyMap()).single()
        assertEquals(listOf("bb_bench", "bb_row"), group.rows.map { it.name })
        assertEquals(ReceiptRow.Shape.SKIPPED, group.rows[1].shape)
        assertTrue(group.rows[1].sets.isEmpty())
        val receipt = receiptFixture().copy(groups = listOf(group))
        assertEquals(3, receipt.loggedSetCount)
        assertEquals(3, listOf(bench, partner).setCounts().total)
        assertEquals(0, listOf(bench, partner).setCounts().remaining)
        assertNull(group.rows[1].planned)
    }

    @Test
    fun `a dropped retired main still names the skipped lift`() {
        val row = row(track(exerciseId = "face_pull", sets = emptyList(), retired = true, dropped = true))
        assertEquals("face_pull", row.name)
        assertEquals(ReceiptRow.Shape.SKIPPED, row.shape)
        assertTrue(row.sets.isEmpty())
        assertTrue(row.lines.isEmpty())
        assertNull(row.planned)
        assertNull(row.record)
    }

    @Test
    fun `an unreached live lift is skipped with tracking from its current rows`() {
        val receiptRow = row(track(exerciseId = "plank", sets = listOf(work(seconds = 60, done = false)),
            planned = listOf(work(seconds = 60, done = false))))
        assertEquals(ReceiptRow.Shape.SKIPPED, receiptRow.shape)
        assertEquals(TrackingType.TIMED, receiptRow.tracking)
        assertTrue(receiptRow.sets.isEmpty())
        assertTrue(receiptRow.lines.isEmpty())
        assertEquals(PlannedSets(1, 0.0, 0, 60), receiptRow.planned)
        assertNull(receiptRow.record)
        val unplanned = row(track(sets = emptyList()))
        assertEquals(TrackingType.WEIGHTED, unplanned.tracking)
    }

    @Test
    fun `replacement rows keep their Finish prescription separate from retired done work`() {
        val receipt = swappedReceiptFixture()
        assertEquals(listOf(3, 3), receipt.groups.map { it.rows.single().planned?.count })
        assertEquals(5, receipt.loggedSetCount)
        assertEquals(3, requireNotNull(receipt.groups.last().rows.single().planned).count)
    }

    @Test
    fun `unfinished replacement sets still make the receipt partial`() {
        val receipt = swappedReceiptFixture(replacementLogged = 2)
        assertEquals(4, receipt.loggedSetCount)
        assertEquals(3, requireNotNull(receipt.groups.last().rows.single().planned).count)
        assertTrue(receipt.isPartial)
    }

    @Test
    fun `a partially logged dropped track preserves work and contributes no live sets`() {
        val planned = List(3) { work(185.0, 5, done = false) }
        val dropped = track(planned = planned, sets = planned.mapIndexed { index, set -> set.copy(done = index < 2) },
            retired = true, dropped = true)
        val groups = ReceiptBuilder.strength(listOf(dropped), listOf(slot()), { it }, emptyMap())
        val receipt = receiptFixture().copy(groups = groups)
        assertEquals(2, receipt.loggedSetCount)
        assertEquals(0, listOf(dropped).setCounts().total)
        assertEquals(2, listOf(dropped).setCounts().done)
    }

    @Test
    fun `Planned uses the Finish prescription even when a done tail keeps more live rows`() {
        val original = List(6) { work(40.0, 5, done = false) }
        val track = track(planned = original, sets = original.take(4).mapIndexed { index, set ->
            set.copy(done = index == 3)
        })
        val slot = slot().let { it.copy(exercise = it.exercise.copy(targetSets = 2,
            prescription = Prescription(PrescribedTarget.LoadReps(30.0, 8)))) }

        val row = ReceiptBuilder.strength(listOf(track), listOf(slot), { it }, emptyMap()).single().rows.single()

        assertEquals(PlannedSets(2, 30.0, 8, 0), row.planned)
        assertEquals(listOf(ReceiptSet(40.0, 5, 0)), row.sets)
        assertEquals(4, listOf(track).setCounts().total)
    }

    @Test
    fun `retired main work precedes its replacement and empty retired tracks disappear`() {
        val retired = track(exerciseId = "bb_bench", sets = List(2) { work(185.0, 5) }, retired = true, position = 5)
        val live = track(exerciseId = "db_press", sets = List(3) { work(60.0, 8) }, position = 0)
        val empty = track(exerciseId = "machine_press", sets = emptyList(), retired = true, position = 1)
        val groups = ReceiptBuilder.strength(listOf(live, empty, retired), listOf(slot()), { it }, emptyMap())
        assertEquals(listOf("bb_bench", "db_press"), groups.map { it.rows.single().name })
        assertEquals(listOf(2, 3), groups.map { it.rows.single().sets.size })
        assertTrue(groups.all { it.rounds == null })
    }

    @Test
    fun `retired main work gets a single group before the current superset`() {
        val retired = track(exerciseId = "db_press", sets = listOf(work(60.0, 8)), retired = true, position = 2)
        val live = track(exerciseId = "bb_bench", sets = List(3) { work(185.0, 5) })
        val partner = track(exerciseId = "bb_row", sets = List(2) { work(155.0, 8) }, slot = Slot.SS)
        val groups = ReceiptBuilder.strength(listOf(live, partner, retired), listOf(supersetSlot()), { it }, emptyMap())
        assertEquals(listOf("db_press"), groups[0].rows.map { it.name })
        assertNull(groups[0].rounds)
        assertEquals(listOf("bb_bench", "bb_row"), groups[1].rows.map { it.name })
        assertEquals(3, groups[1].rounds)
    }

    @Test
    fun `retired partner work survives before the current pair`() {
        val main = track(exerciseId = "bb_bench", sets = List(3) { work(185.0, 5) })
        val retired = track(exerciseId = "cable_row", sets = List(2) { work(100.0, 10) },
            slot = Slot.SS, retired = true, position = 1)
        val partner = track(exerciseId = "bb_row", sets = List(3) { work(155.0, 8) }, slot = Slot.SS, position = 2)
        val groups = ReceiptBuilder.strength(listOf(main, retired, partner), listOf(supersetSlot()), { it }, emptyMap())
        assertEquals(2, groups.size)
        assertNull(groups[0].rounds)
        assertEquals("cable_row", groups[0].rows.single().name)
        assertEquals(2, groups[0].rows.single().sets.size)
        assertEquals(3, groups[1].rounds)
        assertEquals(listOf("bb_bench", "bb_row"), groups[1].rows.map { it.name })
    }

    @Test
    fun `removed slot tracks append after ordered slots as straight rows`() {
        val removed = track(exerciseId = "removed", sets = listOf(work(245.0, 5, kind = SetKind.TOP)),
            programExerciseId = null, position = 0)
        val later = track(exerciseId = "later", sets = listOf(work(35.0, 10)), programExerciseId = 2, position = 3)
        val first = track(exerciseId = "first", sets = listOf(work(35.0, 10)), position = 2)
        val removedLater = removed.copy(id = 4, exerciseId = "also_removed", programExerciseId = 99, position = 1)
        val groups = ReceiptBuilder.strength(listOf(later, removedLater, removed, first),
            listOf(slot(id = 2, position = 1), slot(position = 0)), { it }, emptyMap())
        assertEquals(listOf("first", "later", "removed", "also_removed"), groups.map { it.rows.single().name })
        assertEquals(ReceiptRow.Shape.STRAIGHT, groups[2].rows.single().shape)
    }

    @Test
    fun `two tracks for the same exercise both compare against the standing baseline`() {
        val retired = track(sets = listOf(work(245.0, 5)), retired = true)
        val live = track(sets = listOf(work(250.0, 5)), position = 1)
        val groups = ReceiptBuilder.strength(listOf(retired, live), listOf(slot()), { it }, baseline(240.0, 5))
        assertEquals(2, groups.size)
        assertEquals(List(2) { RecordMark(PrKind.WEIGHT, null, 0, ReceiptSet(240.0, 5, 0)) },
            groups.map { it.rows.single().record })
    }

    @Test
    fun `weights remain exact pounds for later kilogram display`() {
        val row = row(squat(topWeight = 231.485), SetStructure.RAMP_TOP_BACKOFF, baseline(220.462, 5))
        val record = requireNotNull(row.record)
        assertEquals(220.462, record.previous.weightLb, 0.0)
        assertEquals(231.485, row.sets[record.setIndex].weightLb, 0.0)
    }

    @Test
    fun `track intervals fold recovery into one work row and select the fastest record`() {
        val end = Measure.Distance(400)
        val intervals = listOf(101, 98, 104, 100, 99, 105).map { cardioResult(CardioRole.WORK, end, it) }
        val results = listOf(cardioResult(CardioRole.WARMUP, Measure.Time(600), 600)) +
            intervals.flatMap { listOf(it, cardioResult(CardioRole.RECOVERY, Measure.Time(90), 90)) } +
            cardioResult(CardioRole.COOLDOWN, Measure.Time(300), 300)
        val standing = CardioBests.bests(CardioActivity.TRACK_RUN, listOf(cardioResult(CardioRole.WORK, end, 102)))
        val rows = ReceiptBuilder.cardio(CardioActivity.TRACK_RUN, results, standing)
        assertEquals(listOf(
            CardioReceiptRow(CardioRole.WARMUP, 1, 600, null,
                listOf(CardioInterval(CardioSetStatus.FINISHED, 600, null)), emptyList(), null),
            CardioReceiptRow(CardioRole.WORK, 6, null, 400,
                listOf(101, 98, 104, 100, 99, 105).map { CardioInterval(CardioSetStatus.FINISHED, it, null) },
                List(6) { 90 }, CardioRecordMark(PrKind.FASTER, 1, 102, null)),
            CardioReceiptRow(CardioRole.COOLDOWN, 1, 300, null,
                listOf(CardioInterval(CardioSetStatus.FINISHED, 300, null)), emptyList(), null),
        ), rows)
        assertTrue(ReceiptBuilder.cardio(CardioActivity.TRACK_RUN, results, emptyMap()).all { it.record == null })
    }

    @Test
    fun `a time target marks the farther finished distance`() {
        val row = roadRow(metres = 5210)
        assertEquals(CardioRole.WORK, row.role)
        assertEquals(1, row.count)
        assertEquals(1800, row.endSeconds)
        assertNull(row.endMetres)
        assertEquals(listOf(CardioInterval(CardioSetStatus.FINISHED, 1800, 5210)), row.intervals)
        assertEquals(CardioRecordMark(PrKind.FARTHER, 0, null, 4980), row.record)
    }

    @Test
    fun `a blank cardio distance stays missing and cannot earn a record`() {
        val row = roadRow(metres = null)
        assertNull(row.intervals.single().metres)
        assertNull(row.record)
    }

    @Test
    fun `shortened cardio work keeps its status and cannot earn a record`() {
        val row = roadRow(metres = 5300, status = CardioSetStatus.SHORTENED)
        assertEquals(CardioSetStatus.SHORTENED, row.intervals.single().status)
        assertNull(row.record)
    }

    @Test
    fun `distinct work ends retain first appearance order and attach their own recovery`() {
        val fourHundred = cardioResult(CardioRole.WORK, Measure.Distance(400), 100)
        val eightHundred = cardioResult(CardioRole.WORK, Measure.Distance(800), 210)
        val recovery = cardioResult(CardioRole.RECOVERY, Measure.Time(90), 90)
        val rows = ReceiptBuilder.cardio(CardioActivity.TRACK_RUN,
            List(4) { fourHundred } + List(2) { eightHundred } + recovery, emptyMap())
        assertEquals(listOf(400, 800), rows.map { it.endMetres })
        assertEquals(listOf(4, 2), rows.map { it.count })
        assertEquals(emptyList<Int>(), rows[0].recoverySeconds)
        assertEquals(listOf(90), rows[1].recoverySeconds)
    }

    @Test
    fun `skipped warm-up remains a row and recovery before work is dropped`() {
        val skipped = cardioResult(CardioRole.WARMUP, Measure.Time(600), 0, status = CardioSetStatus.SKIPPED)
        val recovery = cardioResult(CardioRole.RECOVERY, Measure.Time(90), 90)
        val rows = ReceiptBuilder.cardio(CardioActivity.TRACK_RUN, listOf(recovery, skipped), emptyMap())
        assertEquals(listOf(CardioReceiptRow(CardioRole.WARMUP, 1, 600, null,
            listOf(CardioInterval(CardioSetStatus.SKIPPED, 0, null)), emptyList(), null)), rows)
    }

    @Test
    fun `consecutive warm-up and cooldown results share rows with count one`() {
        val warmup = cardioResult(CardioRole.WARMUP, Measure.Time(300), 300)
        val cooldown = cardioResult(CardioRole.COOLDOWN, Measure.Time(150), 150)
        val rows = ReceiptBuilder.cardio(CardioActivity.TRACK_RUN,
            listOf(warmup, warmup, cooldown, cooldown), emptyMap())
        assertEquals(listOf(CardioRole.WARMUP, CardioRole.COOLDOWN), rows.map { it.role })
        assertEquals(listOf(1, 1), rows.map { it.count })
        assertEquals(listOf(2, 2), rows.map { it.intervals.size })
    }

    @Test
    fun `partial strength keeps its planned count without changing logged work`() {
        val captured = squat().let { it.copy(sets = it.sets.mapIndexed { index, set -> set.copy(done = index < 2) }) }
        val groups = ReceiptBuilder.strength(listOf(captured), listOf(slot(structure = SetStructure.RAMP_TOP_BACKOFF)), { it }, emptyMap())
        val receipt = receiptFixture().copy(groups = groups)
        assertTrue(receipt.isPartial)
        assertEquals(2, receipt.loggedSetCount)
        assertEquals(6, receipt.plannedSetCount)
        assertEquals(2, groups.single().rows.single().sets.size)
        val complete = receipt.copy(groups = ReceiptBuilder.strength(listOf(squat()), listOf(slot(structure = SetStructure.RAMP_TOP_BACKOFF)), { it }, emptyMap()))
        assertFalse(complete.isPartial)
        assertEquals(6, complete.loggedSetCount)
        assertEquals(6, complete.plannedSetCount)
    }

    @Test
    fun `shortened warmup keeps the planned target and marks the receipt partial`() {
        val result = cardioResult(CardioRole.WARMUP, Measure.Time(600), 240, status = CardioSetStatus.SHORTENED)
        val rows = ReceiptBuilder.cardio(CardioActivity.ROAD_RUN, listOf(result), emptyMap())
        assertEquals(600, rows.single().endSeconds)
        assertEquals(240, rows.single().intervals.single().elapsedSeconds)
        assertTrue(cardioReceiptFixture().copy(cardioRows = rows).isPartial)
    }

    @Test
    fun `skipped recovery is visible and prevents a completion claim`() {
        val work = cardioResult(CardioRole.WORK, Measure.Time(300), 300)
        val recovery = cardioResult(CardioRole.RECOVERY, Measure.Time(90), 0, status = CardioSetStatus.SKIPPED)
        val rows = ReceiptBuilder.cardio(CardioActivity.ROAD_RUN, listOf(work, recovery), emptyMap())
        assertEquals(listOf(CardioRole.WORK, CardioRole.RECOVERY), rows.map { it.role })
        assertTrue(cardioReceiptFixture().copy(cardioRows = rows).isPartial)
    }

    @Test
    fun `two Today plans completed today are all done`() {
        assertTrue(allPlansDone(listOf(plan(1), plan(2)), listOf(session(1), session(2))))
    }

    @Test
    fun `one Today plan never gets the all plans message`() {
        assertFalse(allPlansDone(listOf(plan(1)), listOf(session(1))))
    }

    @Test
    fun `a session yesterday in the supplied zone leaves its plan unfinished`() {
        assertFalse(allPlansDone(listOf(plan(1), plan(2)),
            listOf(session(1), session(2).copy(completedAt = completedAt - 3_600_000))))
    }

    @Test
    fun `strength and cardio sessions both complete Today plans`() {
        val cardio = CardioSessionEntity(
            id = 2, dayId = "B", mode = CardioMode.OUTDOOR_RUN.name, hard = false, label = "Easy run",
            startedAt = completedAt - 1_800_000, completedAt = completedAt, seconds = 1800,
            stepsCompleted = 1, planId = 2,
        )
        assertTrue(allPlansDone(listOf(plan(1), plan(2)), listOf(session(1)), listOf(cardio)))
    }

    @Test
    fun `archived and off Today plans are ignored`() {
        val plans = listOf(plan(1), plan(2), plan(3).copy(archived = true), plan(4).copy(onToday = false))
        assertTrue(allPlansDone(plans, listOf(session(1), session(2))))
        assertFalse(allPlansDone(listOf(plan(1), plan(2).copy(archived = true)), listOf(session(1))))
    }

    private fun row(
        track: RunTrack,
        structure: SetStructure = SetStructure.STRAIGHT,
        standing: Map<String, PersonalRecord> = emptyMap(),
        targets: List<LoggedSet>? = null,
    ): ReceiptRow = ReceiptBuilder.strength(listOf(track), listOf(slot(structure = structure)), { it }, standing,
        targets?.let { mapOf(track.id to it) }.orEmpty())
        .single().rows.single()

    private fun track(
        sets: List<LoggedSet>,
        exerciseId: String = "bb_back_squat",
        planned: List<LoggedSet> = emptyList(),
        programExerciseId: Long? = 1,
        position: Int = 0,
        slot: String = Slot.MAIN,
        retired: Boolean = false,
        dropped: Boolean = false,
    ): RunTrack = RunTrack(
        id = position.toLong() + 1, position = position, programExerciseId = programExerciseId, slot = slot,
        exerciseId = exerciseId, retired = retired, planned = planned, sets = sets, dropped = dropped,
    )

    private fun slot(
        id: Long = 1,
        position: Int = 0,
        structure: SetStructure = SetStructure.STRAIGHT,
    ): ProgramSlot = ProgramSlot(id, position, ProgramExercise("bb_back_squat", structure))

    private fun supersetSlot(): ProgramSlot = ProgramSlot(1, 0,
        ProgramExercise("bb_bench", superset = SupersetPartner("bb_row")))

    private fun work(
        weight: Double = 0.0,
        reps: Int = 0,
        seconds: Int = 0,
        kind: SetKind = SetKind.WORK,
        done: Boolean = true,
    ): LoggedSet = LoggedSet(weight, reps, kind, done, seconds)

    private fun squat(topWeight: Double = 245.0, topReps: Int = 5, topDone: Boolean = true): RunTrack {
        val ramps = listOf(work(130.0, 5, kind = SetKind.RAMP), work(165.0, 5, kind = SetKind.RAMP),
            work(190.0, 5, kind = SetKind.RAMP), work(210.0, 3, kind = SetKind.RAMP))
        return track(
            sets = ramps + work(topWeight, topReps, kind = SetKind.TOP, done = topDone) + work(185.0, 8, kind = SetKind.BACKOFF),
            planned = (ramps + work(235.0, 5, kind = SetKind.TOP) + work(175.0, 8, kind = SetKind.BACKOFF))
                .map { it.copy(done = false) },
        )
    }

    private fun baseline(weight: Double, reps: Int, seconds: Int = 0, exerciseId: String = "bb_back_squat") =
        mapOf(exerciseId to PersonalRecord(exerciseId, weight, reps, 1_000, seconds))

    private fun cardioResult(
        role: CardioRole,
        end: Measure,
        seconds: Int,
        metres: Int? = null,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
    ): CardioSetResult = CardioSetResult(CardioSetSpec(role, end), status, seconds, metres,
        metres?.let { DistanceSource.MANUAL })

    private fun roadRow(metres: Int?, status: CardioSetStatus = CardioSetStatus.FINISHED): CardioReceiptRow {
        val standing = CardioBests.bests(CardioActivity.ROAD_RUN,
            listOf(cardioResult(CardioRole.WORK, Measure.Time(1800), 1800, 4980)))
        return ReceiptBuilder.cardio(CardioActivity.ROAD_RUN,
            listOf(cardioResult(CardioRole.WORK, Measure.Time(1800), 1800, metres, status)), standing).single()
    }

    private val completedAt = Instant.parse("2026-11-03T06:30:00Z").toEpochMilli()
    private val zone = ZoneId.of("America/Chicago")

    private fun plan(id: Long): PlanEntity = PlanEntity(id, "Plan $id", PlanOrigin.AUTHORED.name,
        position = id.toInt(), createdAt = 0, onToday = true)

    private fun session(planId: Long): WorkoutSessionEntity = WorkoutSessionEntity(
        id = planId, dayId = "A", dayTitle = "Lower", startedAt = null, completedAt = completedAt,
        bodyweightLb = null, planId = planId,
    )

    private fun allPlansDone(
        plans: List<PlanEntity>,
        sessions: List<WorkoutSessionEntity>,
        cardio: List<CardioSessionEntity> = emptyList(),
    ): Boolean = ReceiptBuilder.allPlansDone(plans, sessions, cardio, completedAt, zone)
}
