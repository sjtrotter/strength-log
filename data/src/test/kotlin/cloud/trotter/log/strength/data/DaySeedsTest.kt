package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaySeedsTest {
    private val cfg = LifterConfig() // bw 235, age 40, INTERMEDIATE, BALANCED
    private val catalog = ExerciseCatalog.CODE_ONLY

    @Test
    fun `authored seeds and previews use the prescription even for main or unknown catalog entries`() {
        val prescription = Prescription(PrescribedTarget.LoadReps(37.5, 8), eachSide = true)
        val slots = listOf("bb_back_squat", "unknown").mapIndexed { index, exerciseId ->
            ProgramSlot(index + 1L, index, ProgramExercise(exerciseId, targetSets = 2, prescription = prescription))
        }
        val expected = List(2) { LoggedSet(37.5, 8, SetKind.WORK) }
        val writes = DaySeeds.plan(slots, emptyMap(), LifterConfig(), ExerciseCatalog.CODE_ONLY)
        assertEquals(slots.map { it.programExerciseId }, writes.keys.map { it.first })
        assertTrue(writes.all { (key, sets) -> sets == expected && key.second == Slot.MAIN })
        slots.forEach { assertEquals(expected, DaySeeds.previewMain(it, LifterConfig(), ExerciseCatalog.CODE_ONLY)) }
        val existing = slots.associate { (it.programExerciseId to Slot.MAIN) to 1 }
        assertTrue(DaySeeds.plan(slots, existing, LifterConfig(), ExerciseCatalog.CODE_ONLY).isEmpty())
    }

    // --- seeding-once --------------------------------------------------------

    @Test
    fun seedPlan_seeds_main_and_superset_partner_when_no_log_exists() {
        val slot = ProgramSlot(
            programExerciseId = 7,
            position = 0,
            exercise = ProgramExercise(
                exerciseId = "ez_curl",
                targetSets = 3,
                superset = SupersetPartner("rope_pushdown"),
            ),
        )
        val plan = DaySeeds.plan(listOf(slot), existing = emptyMap(), config = cfg, catalog = catalog)

        assertEquals(2, plan.size)
        val main = plan.entries.first { it.key.second == Slot.MAIN }
        val partner = plan.entries.first { it.key.second == Slot.SS }
        assertEquals(7L, main.key.first)
        assertEquals(3, main.value.size)
        assertEquals(60.0, main.value.first().weightLb)       // ez_curl flat GOAL 60
        assertEquals(3, partner.value.size)
        assertEquals(50.0, partner.value.first().weightLb)    // rope_pushdown flat GOAL 50
    }

    @Test
    fun seedPlan_never_reseeds_an_existing_slot() {
        val slot = ProgramSlot(2, 0, ProgramExercise(exerciseId = "ez_curl", targetSets = 3))
        val existing = mapOf((2L to Slot.MAIN) to 3)
        assertTrue(DaySeeds.plan(listOf(slot), existing, cfg, catalog).isEmpty())
    }

    @Test
    fun seedPlan_aligns_a_late_added_partner_to_the_live_main_track_not_the_seed() {
        // A partner attached to a slot the lifter has been training: the main track
        // has grown to 4 rows with EXTRA sets, while a fresh seed would be 2.
        val slot = ProgramSlot(
            programExerciseId = 9,
            position = 0,
            exercise = ProgramExercise(
                exerciseId = "ez_curl",
                targetSets = 2,
                superset = SupersetPartner("rope_pushdown"),
            ),
        )
        val existing = mapOf((9L to Slot.MAIN) to 4)
        val plan = DaySeeds.plan(listOf(slot), existing, cfg, catalog)

        val partner = plan.entries.single()
        assertEquals(Slot.SS, partner.key.second)
        assertEquals(4, partner.value.size)
        assertTrue(partner.value.all { it.weightLb == 50.0 })
    }

    // Synthetic REPS/TIMED entries, independent of the live catalog. `find` is a
    // plain id lookup, so custom entries resolve.
    private val trackingCatalog = ExerciseCatalog(
        listOf(
            ExerciseEntry("custom_pullup", "Pull-up", MovementPattern.V_PULL, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Reps(6), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
            ExerciseEntry("custom_plank", "Plank", MovementPattern.CORE_ANTI_EXT, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Time(45, 25.0), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
        ),
    )

    @Test
    fun seedPlan_routes_a_REPS_entry_through_targetFor_without_throwing() {
        val slot = ProgramSlot(3, 0, ProgramExercise("custom_pullup", targetSets = 3))
        val plan = DaySeeds.plan(listOf(slot), emptyMap(), cfg, trackingCatalog)
        // No goalFor error() branch: all-WORK rows at the rep target, zero weight/seconds.
        assertEquals(List(3) { LoggedSet(0.0, 6, SetKind.WORK, seconds = 0) }, plan.values.single())
    }

    @Test
    fun seedPlan_routes_a_TIMED_partner_through_targetFor_without_throwing() {
        // A weighted main with a TIMED superset partner — the partner path must
        // use targetFor because goalFor rejects TIMED entries.
        val slot = ProgramSlot(
            4, 0,
            ProgramExercise("ez_curl", targetSets = 3, superset = SupersetPartner("custom_plank")),
        )
        val plan = DaySeeds.plan(listOf(slot), emptyMap(), cfg, trackingCatalog)
        val partner = plan.entries.first { it.key.second == Slot.SS }
        assertEquals(List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 45) }, partner.value)
    }

    @Test
    fun seedPlan_weighted_seed_is_unchanged_by_the_targetFor_routing() {
        // The routed path must produce the exact same rows as the goalFor->seed
        // path for a WEIGHTED slot.
        val pe = ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)
        val slot = ProgramSlot(1, 0, pe)
        val routed = DaySeeds.plan(listOf(slot), emptyMap(), cfg, catalog).values.single()
        val direct = SetSeeder.seed(pe, GoalCalculator.goalFor(catalog.get("bb_back_squat"), cfg), cfg)
        assertEquals(direct, routed)
    }

    @Test
    fun seedPlan_seeds_main_lift_full_ramp_sequence() {
        val slot = ProgramSlot(1, 0, ProgramExercise(exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6))
        val plan = DaySeeds.plan(listOf(slot), emptyMap(), cfg, catalog)
        val weights = plan.values.single().map { it.weightLb }
        // Pinned §11 squat seed: 130/165/190/210 · TOP 235 · B/O 175.
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0), weights)
    }

}
