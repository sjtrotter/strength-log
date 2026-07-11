package io.github.sjtrotter.strengthlog.sync

import io.github.sjtrotter.strengthlog.data.LoggedSlot
import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
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
        assertEquals("106.59", ex.goalLabel) // additive label in the phone's unit (235 lb -> kg), the same number the watch derives
        assertEquals(235.0, ex.sets.single().weightLb) // canonical lb, not converted
        assertEquals(true, ex.sets.single().done)
    }

    // A catalog with synthetic REPS/TIMED entries. P2 reclassifies real ones;
    // until then this proves the watch projection is crash-safe ahead of that.
    private val trackingCatalog = ExerciseCatalog(
        listOf(
            ExerciseEntry("custom_pullup", "Pull-up", MovementPattern.V_PULL, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Reps(6), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
            ExerciseEntry("custom_plank", "Plank", MovementPattern.CORE_ANTI_EXT, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Time(45, 25.0), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
        ),
    )

    @Test
    fun `weighted goalLabel equals the number the watch already shows today`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg = cfg, catalog = catalog, unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        // Watch UI renders WeightStepper.format(unit.fromLb(goal)) == "235"; goalLabel must match.
        assertEquals(235.0, ex.goal)
        assertEquals("235", ex.goalLabel)
    }

    @Test
    fun `REPS and TIMED slots project without hitting goalFor's error branch`() {
        val program = Program(
            listOf(
                ProgramDay(
                    "A", "Core", "",
                    listOf(ProgramExercise("custom_pullup"), ProgramExercise("custom_plank")),
                    cardio = null,
                ),
            ),
        )
        val slots = listOf(
            ProgramSlot(1L, 0, ProgramExercise("custom_pullup")),
            ProgramSlot(2L, 1, ProgramExercise("custom_plank")),
        )
        val exercises = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg = cfg, catalog = trackingCatalog, unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises

        val reps = exercises.first { it.programExerciseId == 1L }
        assertEquals("6 reps", reps.goalLabel)
        assertEquals(0.0, reps.goal) // rep targets carry no weight — never "0 lb × 60"
        assertEquals("reps", reps.tracking) // enum name, lowercased — the watch picks a reps-only control

        val timed = exercises.first { it.programExerciseId == 2L }
        assertEquals("45s +25", timed.goalLabel)
        assertEquals(25.0, timed.goal) // the timed added-load rides the numeric goal
        assertEquals("timed", timed.tracking)
    }

    @Test
    fun `a weighted exercise projects tracking=weighted and carries each set's seconds`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg = cfg, catalog = catalog, unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals("weighted", ex.tracking)
    }

    @Test
    fun `a TIMED slot's logged seconds ride the wire`() {
        val program = Program(listOf(ProgramDay("A", "Core", "", listOf(ProgramExercise("custom_plank")), cardio = null)))
        val slots = listOf(ProgramSlot(2L, 0, ProgramExercise("custom_plank")))
        val logs = listOf(loggedSlot(2L, Slot.MAIN, listOf(LoggedSet(25.0, 0, SetKind.WORK, seconds = 45))))
        val timed = WatchSnapshotBuilder.build(
            program, "A", slots, logs, cfg = cfg, catalog = trackingCatalog, unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals("timed", timed.tracking)
        assertEquals(45, timed.sets.single().seconds)
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
