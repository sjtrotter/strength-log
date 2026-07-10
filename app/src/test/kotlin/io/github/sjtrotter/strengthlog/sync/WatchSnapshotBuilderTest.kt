package io.github.sjtrotter.strengthlog.sync

import io.github.sjtrotter.strengthlog.data.LoggedSlot
import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.model.SupersetPartner
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phone->watch projection (pure). It must present exactly what the day screen
 * shows — canonical-lb weights, phone-computed goals, aligned superset rows — and
 * publish only the suggested day.
 */
class WatchSnapshotBuilderTest {

    private val catalog = ExerciseCatalog.CODE_ONLY
    private val cfg = LifterConfig() // the pinned §11 lifter (235/40/INTERMEDIATE/BALANCED)

    private val program = Program(
        listOf(
            ProgramDay("A", "Day A — Squat", "quads", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null),
            ProgramDay("B", "Day B — Bench", "chest", listOf(ProgramExercise("bb_bench", isMain = true)), cardio = null),
        ),
    )

    private fun loggedSlot(id: Long, slot: String, sets: List<LoggedSet>) =
        LoggedSlot(id, slot, sets, checkDate = "2026-07-09")

    @Test
    fun `projects the suggested day with canonical weights, goal and accent index`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val logs = listOf(
            loggedSlot(10L, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP, done = true))),
        )

        val snapshot = WatchSnapshotBuilder.build(
            program = program,
            suggestedDayId = "A",
            slots = slots,
            logs = logs,
            cfg = cfg,
            catalog = catalog,
            unit = WeightUnit.KG,
            revision = 3L,
        )!!

        assertEquals("A", snapshot.suggestedDayId)
        assertEquals(0, snapshot.day.accentIndex)
        assertEquals("kg", snapshot.unit) // unit label only; weights stay canonical lb
        assertEquals(3L, snapshot.revision)
        assertEquals("quads", snapshot.day.emphasisLine) // carries the day's real focus, not filler text
        val ex = snapshot.day.exercises.single()
        assertEquals(10L, ex.programExerciseId)
        assertEquals(235.0, ex.goal) // GOAL is phone-computed and matches spec §11
        assertEquals(235.0, ex.sets.single().weightLb) // canonical lb, not converted
        assertEquals(true, ex.sets.single().done)
    }

    @Test
    fun `uses day B's accent index when it is the suggested day`() {
        val slots = listOf(ProgramSlot(20L, 0, ProgramExercise("bb_bench", isMain = true)))
        val snapshot = WatchSnapshotBuilder.build(
            program, "B", slots,
            logs = emptyList(), cfg = cfg, catalog = catalog, unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(1, snapshot.day.accentIndex)
        assertEquals(195.0, snapshot.day.exercises.single().goal) // bench GOAL 195 (§11)
    }

    @Test
    fun `carries a superset partner's aligned rows and name`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        val slots = listOf(ProgramSlot(30L, 0, pe))
        val logs = listOf(
            loggedSlot(30L, Slot.MAIN, listOf(LoggedSet(60.0, 12, SetKind.WORK), LoggedSet(60.0, 11, SetKind.WORK))),
            loggedSlot(30L, Slot.SS, listOf(LoggedSet(50.0, 15, SetKind.WORK), LoggedSet(50.0, 14, SetKind.WORK))),
        )
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe), cardio = null)))

        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs, cfg, catalog, WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("Rope Pushdown", ex.supersetPartnerName)
        assertEquals(ex.sets.size, ex.ssSets.size)
        assertEquals(50.0, ex.ssSets.first().weightLb)
    }

    @Test
    fun `returns null when there is no suggested day or it is not in the program`() {
        assertNull(WatchSnapshotBuilder.build(program, null, emptyList(), emptyList(), cfg, catalog, WeightUnit.LB, 1L))
        assertNull(WatchSnapshotBuilder.build(program, "Z", emptyList(), emptyList(), cfg, catalog, WeightUnit.LB, 1L))
    }
}
