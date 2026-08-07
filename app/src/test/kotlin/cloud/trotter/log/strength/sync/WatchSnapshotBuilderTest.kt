package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.LoggedSlot
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
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

    // --- the cycle the watch's outer ring draws (dial v3 §1) ---------------

    @Test
    fun `stamps every day of the program, in order, with the names the watch says`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val snapshot = WatchSnapshotBuilder.build(
            program, "A", slots,
            logs = emptyList(), cfg = cfg, catalog = catalog, unit = WeightUnit.LB, revision = 1L,
        )!!

        assertEquals(listOf("A", "B"), snapshot.cycle.map { it.dayId })
        assertEquals(listOf("Day A — Squat", "Day B — Bench"), snapshot.cycle.map { it.title })
        val squat = snapshot.cycle.first().exercises.single()
        assertEquals("Squat", squat.name) // the colloquial name, as the bands use
        assertEquals(3, squat.setCount) // the day's target, not a logged count
    }

    @Test
    fun `the cycle is the program, not the suggested day — it doesn't move with it`() {
        val slots = listOf(ProgramSlot(20L, 0, ProgramExercise("bb_bench", isMain = true)))
        val fromB = WatchSnapshotBuilder.build(
            program, "B", slots,
            logs = emptyList(), cfg = cfg, catalog = catalog, unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(listOf("A", "B"), fromB.cycle.map { it.dayId })
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

    // --- the watch gets the name a lifter says (curved-bands brief §2) ------

    @Test
    fun `an entry with a colloquial name sends it, and the phone keeps the full one`() {
        val pe = ProgramExercise("conv_dl", isMain = true)
        val slots = listOf(ProgramSlot(40L, 0, pe))
        val program = Program(listOf(ProgramDay("A", "Pull", "", listOf(pe), cardio = null)))

        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg = cfg, catalog = catalog,
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("Deadlift", ex.name)
        assertEquals("Conventional Deadlift", catalog.get("conv_dl").name)
    }

    @Test
    fun `an entry without one keeps its full name, custom exercises included`() {
        val pe = ProgramExercise("hack_squat", isMain = true)
        val custom = ProgramExercise("custom_pullup", isMain = true)
        val slots = listOf(ProgramSlot(41L, 0, pe), ProgramSlot(42L, 1, custom))
        val program = Program(listOf(ProgramDay("A", "Legs", "", listOf(pe, custom), cardio = null)))

        val exercises = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg = cfg, catalog = trackingCatalog,
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises

        assertEquals("Hack Squat", exercises[0].name)
        assertEquals("Pull-up", exercises[1].name)
    }

    @Test
    fun `the superset partner is shortened at the same stamp point`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("oh_tri_ext"))
        val slots = listOf(ProgramSlot(43L, 0, pe))
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe), cardio = null)))

        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg = cfg, catalog = catalog,
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("EZ-Bar Curl", ex.name) // no short form; unchanged
        assertEquals("Overhead Extension", ex.supersetPartnerName)
    }

    @Test
    fun `stamps ssTracking from the superset partner's own entry`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("custom_pullup"))
        val slots = listOf(ProgramSlot(30L, 0, pe))
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe), cardio = null)))

        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg, catalog = trackingCatalog, unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("weighted", ex.tracking) // main track unaffected
        assertEquals("reps", ex.ssTracking) // the partner's own tracking, not the main's
    }

    @Test
    fun `ssTracking defaults to weighted when there is no superset partner`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs = emptyList(), cfg = cfg, catalog = catalog, unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals("weighted", ex.ssTracking)
    }

    @Test
    fun `stamps each main set's rest from RestPolicy when the master toggle is on`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val logs = listOf(
            loggedSlot(10L, Slot.MAIN, listOf(
                LoggedSet(130.0, 5, SetKind.RAMP),
                LoggedSet(235.0, 5, SetKind.TOP),
                LoggedSet(210.0, 5, SetKind.BACKOFF),
            )),
        )
        val sets = WatchSnapshotBuilder.build(
            program, "A", slots, logs, cfg, catalog, WeightUnit.LB, revision = 1L,
            restSettings = RestSettings(enabled = true),
        )!!.day.exercises.single().sets
        assertEquals(90, sets[0].restAfterSeconds)  // RAMP default
        assertEquals(180, sets[1].restAfterSeconds) // TOP default
        assertEquals(120, sets[2].restAfterSeconds) // BACKOFF default
    }

    @Test
    fun `a per-category override reaches the stamped rest`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val logs = listOf(loggedSlot(10L, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP))))
        val sets = WatchSnapshotBuilder.build(
            program, "A", slots, logs, cfg, catalog, WeightUnit.LB, revision = 1L,
            restSettings = RestSettings(enabled = true, overrides = mapOf(RestCategory.TOP to 210)),
        )!!.day.exercises.single().sets
        assertEquals(210, sets.single().restAfterSeconds)
    }

    @Test
    fun `the master toggle off zeroes every stamped rest`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", isMain = true)))
        val logs = listOf(
            loggedSlot(10L, Slot.MAIN, listOf(LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(235.0, 5, SetKind.TOP))),
        )
        val sets = WatchSnapshotBuilder.build(
            program, "A", slots, logs, cfg, catalog, WeightUnit.LB, revision = 1L,
            restSettings = RestSettings(enabled = false),
        )!!.day.exercises.single().sets
        assertEquals(0, sets[0].restAfterSeconds)
        assertEquals(0, sets[1].restAfterSeconds)
    }

    @Test
    fun `a superset partner's rows are stamped 0 rest so the round has one rest`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        val slots = listOf(ProgramSlot(30L, 0, pe))
        val logs = listOf(
            loggedSlot(30L, Slot.MAIN, listOf(LoggedSet(60.0, 12, SetKind.WORK))),
            loggedSlot(30L, Slot.SS, listOf(LoggedSet(50.0, 15, SetKind.WORK))),
        )
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe), cardio = null)))
        val ex = WatchSnapshotBuilder.build(
            program, "A", slots, logs, cfg, catalog, WeightUnit.LB, revision = 1L,
            restSettings = RestSettings(enabled = true),
        )!!.day.exercises.single()
        assertEquals(90, ex.sets.single().restAfterSeconds) // weighted WORK on the main track
        assertEquals(0, ex.ssSets.single().restAfterSeconds) // partner carries none
    }

    @Test
    fun alternates_follow_substitution_rank_and_exclude_the_current_exercise() {
        val expected = catalog.substitutionsFor("bb_back_squat").take(WatchSnapshotBuilder.MAX_ALTERNATES)

        val alternates = WatchSnapshotBuilder.alternatesFor(
            "bb_back_squat", catalog, Equipment.entries.toSet(),
        )

        assertEquals(expected.map { it.id }, alternates.map { it.exerciseId })
        assertEquals(expected.map { it.subRank }, alternates.map { catalog.get(it.exerciseId).subRank })
        assertEquals(false, alternates.any { it.exerciseId == "bb_back_squat" })
    }

    @Test
    fun alternates_are_capped_at_the_watch_limit() {
        val candidates = catalog.substitutionsFor("bb_back_squat")
        assertEquals(true, candidates.size > WatchSnapshotBuilder.MAX_ALTERNATES)

        val alternates = WatchSnapshotBuilder.alternatesFor(
            "bb_back_squat", catalog, Equipment.entries.toSet(),
        )

        assertEquals(WatchSnapshotBuilder.MAX_ALTERNATES, alternates.size)
        assertEquals(candidates.take(WatchSnapshotBuilder.MAX_ALTERNATES).map { it.id }, alternates.map { it.exerciseId })
    }

    @Test
    fun restricted_equipment_drops_alternates_that_need_other_gear() {
        val alternates = WatchSnapshotBuilder.alternatesFor(
            "bb_back_squat", catalog, setOf(Equipment.BODYWEIGHT),
        )

        assertEquals(listOf("wall_sit"), alternates.map { it.exerciseId })
        assertEquals(true, alternates.all { alternate ->
            catalog.get(alternate.exerciseId).equipment.all { it == Equipment.BODYWEIGHT }
        })
    }

    @Test
    fun an_unknown_exercise_has_no_alternates_and_does_not_throw() {
        val pe = ProgramExercise("custom_nonexistent")
        val customProgram = Program(listOf(ProgramDay("A", "Custom", "", listOf(pe), cardio = null)))
        val slots = listOf(ProgramSlot(50L, 0, pe))

        val exercise = WatchSnapshotBuilder.build(
            customProgram, "A", slots, emptyList(), cfg, catalog, WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals(emptyList(), exercise.alternates)
    }

    @Test
    fun superset_partner_rows_do_not_contribute_alternates() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        val armsProgram = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe), cardio = null)))
        val slots = listOf(ProgramSlot(30L, 0, pe))

        val exercise = WatchSnapshotBuilder.build(
            armsProgram, "A", slots, emptyList(), cfg, catalog, WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals(
            WatchSnapshotBuilder.alternatesFor("ez_curl", catalog, Equipment.entries.toSet()),
            exercise.alternates,
        )
        assertEquals(false, exercise.alternates.any { alternate ->
            catalog.get(alternate.exerciseId).pattern == catalog.get("rope_pushdown").pattern
        })
    }

    @Test
    fun `returns null when there is no suggested day or it is not in the program`() {
        assertNull(WatchSnapshotBuilder.build(program, null, emptyList(), emptyList(), cfg, catalog, WeightUnit.LB, 1L))
        assertNull(WatchSnapshotBuilder.build(program, "Z", emptyList(), emptyList(), cfg, catalog, WeightUnit.LB, 1L))
    }
}
