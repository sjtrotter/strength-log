package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.library.buildWeightedPairIndex
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.H_PUSH
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.V_PULL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WeightedPairIndexTest {

    private fun entry(
        id: String,
        pattern: MovementPattern,
        goal: GoalSource,
        weightedPairId: String? = null,
    ) = ExerciseEntry(id, id, pattern, listOf(Equipment.BODYWEIGHT), false, goal, 1, weightedPairId = weightedPairId)

    @Test
    fun `valid pair builds the reverse index target to source`() {
        val entries = listOf(
            entry("bw_pullup", V_PULL, GoalSource.Reps(6), weightedPairId = "wt_pullup"),
            entry("wt_pullup", V_PULL, GoalSource.Flat(25.0)),
        )
        val index = buildWeightedPairIndex(entries)
        assertEquals(mapOf("wt_pullup" to "bw_pullup"), index)
    }

    @Test
    fun `unresolved pair target fails loudly`() {
        val entries = listOf(entry("bw_pullup", V_PULL, GoalSource.Reps(6), weightedPairId = "ghost"))
        assertFailsWith<IllegalStateException> { buildWeightedPairIndex(entries) }
    }

    @Test
    fun `cross-pattern pair fails`() {
        val entries = listOf(
            entry("bw_pushup", H_PUSH, GoalSource.Reps(15), weightedPairId = "wt_pullup"),
            entry("wt_pullup", V_PULL, GoalSource.Flat(25.0)),
        )
        assertFailsWith<IllegalArgumentException> { buildWeightedPairIndex(entries) }
    }

    @Test
    fun `non-injective mapping fails when two sources share a target`() {
        val entries = listOf(
            entry("bw_pullup", V_PULL, GoalSource.Reps(6), weightedPairId = "wt_pullup"),
            entry("bw_chinup", V_PULL, GoalSource.Reps(6), weightedPairId = "wt_pullup"),
            entry("wt_pullup", V_PULL, GoalSource.Flat(25.0)),
        )
        assertFailsWith<IllegalArgumentException> { buildWeightedPairIndex(entries) }
    }

    @Test
    fun `chain where a target declares its own pair fails acyclicity`() {
        val entries = listOf(
            entry("bw_pullup", V_PULL, GoalSource.Reps(6), weightedPairId = "wt_pullup"),
            entry("wt_pullup", V_PULL, GoalSource.Flat(25.0), weightedPairId = "wt_pullup_heavy"),
            entry("wt_pullup_heavy", V_PULL, GoalSource.Flat(45.0)),
        )
        assertFailsWith<IllegalArgumentException> { buildWeightedPairIndex(entries) }
    }

    @Test
    fun `real catalog has no pairs yet in P1`() {
        // P2 populates the links; P1 only ships the machinery.
        assertEquals(emptyList(), ExerciseLibrary.entries.mapNotNull { it.weightedPairId })
        assertNull(ExerciseLibrary.bodyweightPairFor("dips"))
    }
}
