package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.mapping.toEntry
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The code-catalog + custom-overlay merge (PLAN.md A4). */
class ExerciseCatalogTest {

    private fun custom(id: String, name: String, pattern: MovementPattern) =
        CustomExerciseEntity(
            id = id,
            name = name,
            pattern = pattern.name,
            equipmentCsv = "MACHINE,CABLE",
            perHand = false,
            goalStartLb = 80.0,
        )

    @Test
    fun `code-only catalog matches the library`() {
        val catalog = ExerciseCatalog.CODE_ONLY
        assertEquals(ExerciseLibrary.entries.size, catalog.entries.size)
        assertSame(ExerciseLibrary.get("bb_back_squat"), catalog.get("bb_back_squat"))
    }

    @Test
    fun `custom entries are merged in and resolvable by id`() {
        val entity = custom("custom_abc123", "Cable Hack Squat", MovementPattern.SQUAT_BILATERAL)
        val catalog = ExerciseCatalog(listOf(entity.toEntry()))

        assertEquals(ExerciseLibrary.entries.size + 1, catalog.entries.size)
        val entry = catalog.get("custom_abc123")
        assertEquals("Cable Hack Squat", entry.name)
        assertEquals(GoalSource.Flat(80.0), entry.goal)
        // Catalog entries are still reachable.
        assertEquals("Barbell Back Squat", catalog.get("bb_back_squat").name)
    }

    @Test
    fun `custom ids never collide and never shadow a catalog entry`() {
        // Even a custom whose raw name matches a catalog one keeps a distinct id.
        val entity = custom("custom_squat", "Barbell Back Squat", MovementPattern.SQUAT_BILATERAL)
        val catalog = ExerciseCatalog(listOf(entity.toEntry()))
        assertTrue(catalog.get("custom_squat").id.startsWith(ExerciseCatalog.CUSTOM_ID_PREFIX))
        assertEquals(GoalSource.Std(io.github.sjtrotter.strengthlog.domain.model.StandardLift.SQUAT),
            catalog.get("bb_back_squat").goal)
    }

    @Test
    fun `customs sort after catalog entries within a pattern`() {
        val entity = custom("custom_zzz", "Custom Squat Machine", MovementPattern.SQUAT_BILATERAL)
        val catalog = ExerciseCatalog(listOf(entity.toEntry()))
        val ranked = catalog.byPattern(MovementPattern.SQUAT_BILATERAL)

        assertEquals("bb_back_squat", ranked.first().id) // rank-1 catalog entry stays first
        assertEquals("custom_zzz", ranked.last().id)      // custom lands at the end
    }

    @Test
    fun `substitutions include customs of the same pattern and exclude self`() {
        val entity = custom("custom_sub", "Custom Row Machine", MovementPattern.H_PULL)
        val catalog = ExerciseCatalog(listOf(entity.toEntry()))

        val subsForBarbellRow = catalog.substitutionsFor("bb_row").map { it.id }
        assertTrue("custom_sub" in subsForBarbellRow)
        assertTrue("bb_row" !in subsForBarbellRow)

        // And a custom can itself be substituted, offering the catalog entries.
        val subsForCustom = catalog.substitutionsFor("custom_sub").map { it.id }
        assertTrue("bb_row" in subsForCustom)
        assertTrue("custom_sub" !in subsForCustom)
    }

    @Test
    fun `find returns null for an unknown id`() {
        assertNull(ExerciseCatalog.CODE_ONLY.find("custom_nope"))
    }

    @Test
    fun `the mapper parses pattern and equipment enum names`() {
        val entry = custom("custom_map", "Mapper Check", MovementPattern.H_PUSH).toEntry()
        assertEquals(MovementPattern.H_PUSH, entry.pattern)
        assertEquals(
            listOf(
                io.github.sjtrotter.strengthlog.domain.model.Equipment.MACHINE,
                io.github.sjtrotter.strengthlog.domain.model.Equipment.CABLE,
            ),
            entry.equipment,
        )
        assertEquals(ExerciseCatalog.CUSTOM_SUBRANK, entry.subRank)
    }
}
