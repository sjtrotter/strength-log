package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V1MainsTest {
    @Test fun `the frozen mains equal the old generator for every v1 input`() {
        assertEquals(SplitTemplate.entries.map { it.name }, V1Split.entries.map { it.name })
        assertEquals(AnchorScheme.entries.map { it.name }, V1Anchors.entries.map { it.name })
        assertEquals(DeadliftVariant.entries.map { it.name }, V1Deadlift.entries.map { it.name })
        val mismatches = mutableListOf<String>()
        var mismatchCount = 0
        for (days in 2..6) for (split in V1Split.entries) for (anchors in V1Anchors.entries) {
            for (deadlift in V1Deadlift.entries) for (mask in 0 until (1 shl Equipment.entries.size)) {
                val equipment = Equipment.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
                val expected = ProgramGenerator.mainLiftIds(WizardAnswers(
                    daysPerWeek = days, split = SplitTemplate.valueOf(split.name),
                    anchorScheme = AnchorScheme.valueOf(anchors.name),
                    deadliftVariant = DeadliftVariant.valueOf(deadlift.name), equipment = equipment,
                ))
                val actual = V1Mains.of(days, split, anchors, deadlift, equipment)
                if (expected != actual) {
                    mismatchCount++
                    if (mismatches.size < 20) mismatches +=
                        "$days $split $anchors $deadlift $equipment expected $expected actual $actual"
                }
            }
        }
        assertTrue(mismatchCount == 0, "$mismatchCount mismatches\n${mismatches.joinToString("\n")}")
    }

    @Test fun `pinned frozen mains`() {
        fun check(days: Int, split: V1Split, anchors: V1Anchors, deadlift: V1Deadlift,
            equipment: Set<Equipment>, vararg expected: String) {
            assertEquals(expected.toList(), V1Mains.of(days, split, anchors, deadlift, equipment),
                "$days $split $anchors $deadlift $equipment")
        }
        val all = Equipment.entries.toSet()
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all,
            "bb_back_squat", "bb_bench", "trap_dl", "incline_db")
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, setOf(Equipment.MACHINE),
            "hack_squat", "machine_chest", "machine_hip_thrust", "pec_deck")
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR,
            setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
            "goblet_squat", "db_bench", "db_rdl", "incline_db")
        check(4, V1Split.UPPER_LOWER, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all,
            "bb_bench", "trap_dl", "bb_back_squat")
        check(6, V1Split.PPL, V1Anchors.BIG_4, V1Deadlift.CONVENTIONAL, all,
            "bb_bench", "bb_row", "conv_dl", "bb_back_squat")
        check(3, V1Split.PPL, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all, "bb_bench", "bb_row", "trap_dl")
        check(2, V1Split.FULL_BODY, V1Anchors.FIVE_THREE_ONE, V1Deadlift.SUMO, all, "bb_back_squat", "bb_bench")
        check(6, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all,
            "bb_back_squat", "bb_bench", "trap_dl", "incline_db")
        for (days in listOf(2, 5)) check(days, V1Split.PPLUL, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR,
            setOf(Equipment.MACHINE), "machine_chest", "cs_row", "machine_hip_thrust")
        check(3, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, setOf(Equipment.KETTLEBELL),
            "kb_goblet_squat", "kb_floor_press", "kb_rdl")
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, emptySet())
    }
}
