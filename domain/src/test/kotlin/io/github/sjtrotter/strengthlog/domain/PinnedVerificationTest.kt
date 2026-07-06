package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.seeding.SetEditor
import io.github.sjtrotter.strengthlog.domain.seeding.SetSeeder
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.standards.StrengthStandards
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The permanent regression contract from spec §11.1. Every number here is
 * prototype-calibrated; a diff that changes any of them is wrong unless the
 * spec constants were deliberately re-verified.
 */
class PinnedVerificationTest {

    private val cfg = LifterConfig(bodyweightLb = 235, age = 40)

    private fun goalOf(id: String) = GoalCalculator.goalFor(ExerciseLibrary.get(id), cfg)

    private fun seedMain(id: String) =
        SetSeeder.seed(ProgramExercise(id, isMain = true), goalOf(id), cfg)

    private fun row(s: LoggedSet) = Triple(s.weightLb, s.reps, s.kind)

    @Test
    fun `ageDerate at 40 is 0_97`() {
        assertEquals(0.97, StrengthStandards.ageDerate(40), 1e-9)
    }

    @Test
    fun `main lift GOALs`() {
        assertEquals(235.0, goalOf("bb_back_squat"), 1e-9)
        assertEquals(195.0, goalOf("bb_bench"), 1e-9)
        assertEquals(255.0, goalOf("trap_dl"), 1e-9)
        assertEquals(75.0, goalOf("incline_db"), 1e-9) // per-hand
    }

    @Test
    fun `squat seeded sequence`() {
        assertEquals(
            listOf(
                Triple(130.0, 5, SetKind.RAMP),
                Triple(165.0, 5, SetKind.RAMP),
                Triple(190.0, 5, SetKind.RAMP),
                Triple(210.0, 3, SetKind.RAMP),
                Triple(235.0, 5, SetKind.TOP),
                Triple(175.0, 8, SetKind.BACKOFF),
            ),
            seedMain("bb_back_squat").map(::row),
        )
    }

    @Test
    fun `bench seeded sequence`() {
        assertEquals(
            listOf(
                Triple(105.0, 5, SetKind.RAMP),
                Triple(135.0, 5, SetKind.RAMP),
                Triple(155.0, 5, SetKind.RAMP),
                Triple(175.0, 3, SetKind.RAMP),
                Triple(195.0, 5, SetKind.TOP),
                Triple(145.0, 8, SetKind.BACKOFF),
            ),
            seedMain("bb_bench").map(::row),
        )
    }

    @Test
    fun `cascade squat TOP to 245`() {
        val seeded = seedMain("bb_back_squat")
        val topIndex = seeded.indexOfFirst { it.kind == SetKind.TOP }
        val cascaded = SetEditor.editWeight(seeded, topIndex, 245.0)
        assertEquals(
            listOf(
                Triple(135.0, 5, SetKind.RAMP),
                Triple(170.0, 5, SetKind.RAMP),
                Triple(195.0, 5, SetKind.RAMP),
                Triple(220.0, 3, SetKind.RAMP),
                Triple(245.0, 5, SetKind.TOP),
                Triple(185.0, 8, SetKind.BACKOFF),
            ),
            cascaded.map(::row),
        )
    }

    @Test
    fun `accessory GOAL derivations`() {
        assertEquals(170.0, goalOf("rdl"), 1e-9) // 0.72 x squat
        assertEquals(330.0, goalOf("leg_press"), 1e-9) // 1.4 x squat
        assertEquals(155.0, goalOf("cs_row"), 1e-9) // 0.8 x bench
    }

    @Test
    fun `perHand FracOfStd fraction is already per hand, never halved again`() {
        // db_bench = frac 0.4 x BENCH (perHand): 0.4 x 195 = 78 -> 80/hand.
        assertEquals(80.0, goalOf("db_bench"), 1e-9)
    }
}
