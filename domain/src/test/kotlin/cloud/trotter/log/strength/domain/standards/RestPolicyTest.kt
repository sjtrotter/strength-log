package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the signed-off rest defaults (user decision, 2026-07-20) and the one
 * resolver's semantics: category mapping for every SetKind x TrackingType,
 * override precedence, the 0..300 clamp, and 0 = no timer. RestPolicy is the
 * SSOT — if these numbers move, a real decision moved them.
 */
class RestPolicyTest {

    // --- defaults ------------------------------------------------------------

    @Test
    fun `each category carries its signed-off default`() {
        assertEquals(90, RestPolicy.defaultSeconds(RestCategory.RAMP))
        assertEquals(180, RestPolicy.defaultSeconds(RestCategory.TOP))
        assertEquals(120, RestPolicy.defaultSeconds(RestCategory.BACKOFF))
        assertEquals(90, RestPolicy.defaultSeconds(RestCategory.WORK))
        assertEquals(60, RestPolicy.defaultSeconds(RestCategory.LIGHT))
    }

    // --- categoryFor: every SetKind x TrackingType ---------------------------

    @Test
    fun `RAMP TOP BACKOFF map straight from their SetKind regardless of tracking`() {
        for (tracking in TrackingType.entries) {
            assertEquals(RestCategory.RAMP, RestPolicy.categoryFor(SetKind.RAMP, tracking))
            assertEquals(RestCategory.TOP, RestPolicy.categoryFor(SetKind.TOP, tracking))
            assertEquals(RestCategory.BACKOFF, RestPolicy.categoryFor(SetKind.BACKOFF, tracking))
        }
    }

    @Test
    fun `EXTRA is always LIGHT, for every tracking type`() {
        for (tracking in TrackingType.entries) {
            assertEquals(RestCategory.LIGHT, RestPolicy.categoryFor(SetKind.EXTRA, tracking))
        }
    }

    @Test
    fun `WORK is WORK when weighted but LIGHT when reps or timed`() {
        assertEquals(RestCategory.WORK, RestPolicy.categoryFor(SetKind.WORK, TrackingType.WEIGHTED))
        assertEquals(RestCategory.LIGHT, RestPolicy.categoryFor(SetKind.WORK, TrackingType.REPS))
        assertEquals(RestCategory.LIGHT, RestPolicy.categoryFor(SetKind.WORK, TrackingType.TIMED))
    }

    // --- effectiveRestSeconds ------------------------------------------------

    @Test
    fun `with no overrides the resolver returns each category's default`() {
        assertEquals(90, RestPolicy.effectiveRestSeconds(SetKind.RAMP, TrackingType.WEIGHTED))
        assertEquals(180, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED))
        assertEquals(120, RestPolicy.effectiveRestSeconds(SetKind.BACKOFF, TrackingType.WEIGHTED))
        assertEquals(90, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED))
        assertEquals(60, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.REPS))
        assertEquals(60, RestPolicy.effectiveRestSeconds(SetKind.EXTRA, TrackingType.WEIGHTED))
    }

    @Test
    fun `an override for the resolved category wins over the default`() {
        val overrides = mapOf(RestCategory.TOP to 210)
        assertEquals(210, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED, overrides))
        // A different category is untouched by that override.
        assertEquals(90, RestPolicy.effectiveRestSeconds(SetKind.RAMP, TrackingType.WEIGHTED, overrides))
    }

    @Test
    fun `an override applies through the category, so a WORK-reps set reads the LIGHT override`() {
        val overrides = mapOf(RestCategory.LIGHT to 45)
        assertEquals(45, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.REPS, overrides))
        // A weighted WORK set falls in WORK, not LIGHT — unaffected.
        assertEquals(90, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, overrides))
    }

    @Test
    fun `zero override means no timer`() {
        val overrides = mapOf(RestCategory.TOP to 0)
        assertEquals(0, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED, overrides))
    }

    @Test
    fun `the resolver clamps an out-of-range override to 0 and 300`() {
        assertEquals(0, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED, mapOf(RestCategory.TOP to -30)))
        assertEquals(300, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED, mapOf(RestCategory.TOP to 9999)))
        assertEquals(RestPolicy.MAX_REST_SECONDS, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED, mapOf(RestCategory.TOP to 301)))
    }

    @Test
    fun `MAX_REST_SECONDS is 300`() {
        assertEquals(300, RestPolicy.MAX_REST_SECONDS)
    }
    @Test
    fun `case 15 role and goal defaults and override precedence`() {
        assertEquals(180, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, role = SlotRole.MAIN, goal = LiftGoal.STRENGTH))
        assertEquals(120, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, role = SlotRole.MAIN, goal = LiftGoal.MUSCLE))
        assertEquals(60, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, role = SlotRole.ISOLATION, goal = LiftGoal.STRENGTH))
        assertEquals(150, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, mapOf(RestCategory.TOP to 150), SlotRole.MAIN, LiftGoal.STRENGTH))
        assertEquals(150, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, mapOf(RestCategory.TOP to 100), SlotRole.MAIN, LiftGoal.MUSCLE, slotOverride = 150))
    }

    @Test
    fun `ramps and backoffs keep their own buckets and ignore the slot override`() {
        for (goal in LiftGoal.entries) {
            assertEquals(90, RestPolicy.effectiveRestSeconds(SetKind.RAMP, TrackingType.WEIGHTED, role = SlotRole.MAIN, goal = goal, slotOverride = 150))
            assertEquals(120, RestPolicy.effectiveRestSeconds(SetKind.BACKOFF, TrackingType.WEIGHTED, role = SlotRole.MAIN, goal = goal, slotOverride = 150))
            assertEquals(75, RestPolicy.effectiveRestSeconds(SetKind.RAMP, TrackingType.WEIGHTED,
                mapOf(RestCategory.RAMP to 75), SlotRole.MAIN, goal, slotOverride = 150))
            assertEquals(100, RestPolicy.effectiveRestSeconds(SetKind.BACKOFF, TrackingType.WEIGHTED,
                mapOf(RestCategory.BACKOFF to 100), SlotRole.MAIN, goal, slotOverride = 150))
        }
    }

    @Test
    fun `Strength role defaults equal today's buckets`() {
        for (role in listOf(SlotRole.MAIN, SlotRole.SECONDARY, SlotRole.ACCESSORY, SlotRole.ISOLATION, SlotRole.CORE)) {
            assertEquals(RestPolicy.defaultSeconds(RestPolicy.categoryFor(role)), RestPolicy.defaultSeconds(role, LiftGoal.STRENGTH))
        }
    }

    @Test
    fun `a silent role goal pair falls back to the bucket`() {
        assertEquals(60, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, role = SlotRole.CORE, goal = LiftGoal.GENERAL))
    }
}
