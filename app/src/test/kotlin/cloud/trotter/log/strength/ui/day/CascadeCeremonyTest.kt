package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The cascade ceremony's derivation (docs/briefs/journal.md §2): a lift's
 * standing high before the advance versus what the just-recorded session did
 * with it. The non-firing cases matter as much as the firing one — this is a
 * moment, and a moment that repeats is a bug.
 */
class CascadeCeremonyTest {

    private fun set(
        exerciseId: String,
        name: String,
        kind: SetKind,
        weightLb: Double,
        done: Boolean = true,
        setIndex: Int = 0,
    ) = SessionSetEntity(
        id = 0, sessionId = 1, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
        setIndex = setIndex, kind = kind.name, weightLb = weightLb, reps = 5, done = done,
    )

    private fun ceremony(
        previousHighs: Map<String, Double>,
        sets: List<SessionSetEntity>,
    ) = CascadeCeremonyBuilder.from(previousHighs, sets, dayIndex = 0, unit = WeightUnit.LB)

    // --- the moment -----------------------------------------------------------

    @Test
    fun fires_when_a_completed_top_set_beats_the_lift_s_standing_high() {
        // Spec §11's cascade: 235 was the standing top set, 245 is the new one.
        val event = assertNotNull(
            ceremony(
                mapOf("bb_back_squat" to 235.0),
                listOf(
                    set("bb_back_squat", "Barbell Back Squat", SetKind.RAMP, 135.0),
                    set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, setIndex = 4),
                    set("bb_back_squat", "Barbell Back Squat", SetKind.BACKOFF, 185.0, setIndex = 5),
                ),
            ),
        )

        val lift = event.lifts.single()
        assertEquals("Barbell Back Squat", lift.name)
        assertEquals("235", lift.metDisplay)
        assertEquals("245", lift.newDisplay)
        assertEquals(0, event.dayIndex)
    }

    @Test
    fun stacks_every_lift_that_cascaded_on_the_same_advance_in_performed_order() {
        val event = assertNotNull(
            ceremony(
                mapOf("bb_bench" to 195.0, "bb_row" to 165.0),
                listOf(
                    set("bb_bench", "Barbell Bench Press", SetKind.TOP, 200.0),
                    set("bb_row", "Barbell Row", SetKind.TOP, 170.0, setIndex = 1),
                ),
            ),
        )

        assertEquals(listOf("Barbell Bench Press", "Barbell Row"), event.lifts.map { it.name })
    }

    @Test
    fun converts_both_numbers_to_the_display_unit() {
        val event = assertNotNull(
            CascadeCeremonyBuilder.from(
                previousHighs = mapOf("bb_back_squat" to 220.462),
                sessionSets = listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 231.485)),
                dayIndex = 2,
                unit = WeightUnit.KG,
            ),
        )
        assertEquals("100", event.lifts.single().metDisplay)
        assertEquals("105", event.lifts.single().newDisplay)
    }

    // --- the silences ---------------------------------------------------------

    @Test
    fun stays_silent_when_the_top_set_only_matched_the_standing_high() {
        assertNull(
            ceremony(
                mapOf("bb_back_squat" to 235.0),
                listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 235.0)),
            ),
        )
    }

    @Test
    fun stays_silent_on_a_lift_s_very_first_session() {
        // An all-time high with nothing behind it is a starting point, not a
        // progression — the journal marks it, the app doesn't celebrate it.
        assertNull(
            ceremony(emptyMap(), listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0))),
        )
    }

    @Test
    fun stays_silent_when_the_heavier_top_set_was_never_ticked() {
        assertNull(
            ceremony(
                mapOf("bb_back_squat" to 235.0),
                listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, done = false)),
            ),
        )
    }

    @Test
    fun stays_silent_for_accessories_however_heavy() {
        assertNull(
            ceremony(
                mapOf("db_curl" to 30.0),
                listOf(set("db_curl", "Dumbbell Curl", SetKind.WORK, 45.0)),
            ),
        )
    }

    @Test
    fun compares_against_the_heaviest_completed_top_set_in_the_session() {
        // A ramp edit can leave a lighter TOP row behind the heavier one; the
        // session's best is what the standing high is measured against.
        val event = assertNotNull(
            ceremony(
                mapOf("bb_back_squat" to 235.0),
                listOf(
                    set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0),
                    set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 230.0, setIndex = 1),
                ),
            ),
        )
        assertEquals("245", event.lifts.single().newDisplay)
    }

    // --- the before-picture ---------------------------------------------------

    @Test
    fun allTimeHighs_reduces_the_history_rows_to_one_high_per_lift() {
        val highs = CascadeCeremonyBuilder.allTimeHighs(
            listOf(
                TopSetRow(1, 1_000L, "bb_back_squat", 225.0),
                TopSetRow(2, 2_000L, "bb_back_squat", 235.0),
                TopSetRow(3, 3_000L, "bb_back_squat", 230.0),
                TopSetRow(4, 4_000L, "bb_bench", 190.0),
            ),
        )

        assertEquals(mapOf("bb_back_squat" to 235.0, "bb_bench" to 190.0), highs)
        assertEquals(emptyMap(), CascadeCeremonyBuilder.allTimeHighs(emptyList()))
    }
}
