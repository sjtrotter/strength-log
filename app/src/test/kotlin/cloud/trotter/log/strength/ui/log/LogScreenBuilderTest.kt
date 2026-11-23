package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Sides
import cloud.trotter.log.strength.data.mapping.withSides
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.Bodyweight
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogScreenBuilderTest {

    private fun set(
        exerciseId: String,
        name: String,
        kind: SetKind,
        weightLb: Double,
        reps: Int,
        sessionId: Long = 1,
        seconds: Int = 0,
    ) = SessionSetEntity(
        id = 0, sessionId = sessionId, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
        setIndex = 0, kind = kind.name, weightLb = weightLb, reps = reps, done = true, seconds = seconds,
    )

    // --- date / day / bodyweight formatting -----------------------------------

    @Test
    fun dateDisplay_formats_epoch_millis_in_the_given_zone() {
        // 2026-07-06T12:00:00Z
        val millis = 1783339200000L
        assertEquals("Jul 6, 2026", LogScreenBuilder.dateDisplay(millis, ZoneOffset.UTC))
    }

    @Test
    fun dateDisplay_stays_English_under_a_non_English_format_locale() {
        val previous = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE)
            assertEquals("Jul 6, 2026", LogScreenBuilder.dateDisplay(1783339200000L, ZoneOffset.UTC))
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous)
        }
    }

    @Test
    fun bodyweightDisplay_converts_to_the_display_unit() {
        assertEquals("182", LogScreenBuilder.bodyweightDisplay(182.0, WeightUnit.LB))
        // Delegates to WeightStepper/WeightUnit (SSOT) rather than reimplementing
        // the lb/kg conversion — pin the delegation, not an arbitrary lb→kg number.
        assertEquals(
            WeightStepper.format(Bodyweight.display(220.0, WeightUnit.KG)),
            LogScreenBuilder.bodyweightDisplay(220.0, WeightUnit.KG),
        )
    }

    @Test
    fun bodyweightDisplay_is_absent_for_a_session_that_recorded_none() {
        // Imported history: the row drops the BW line rather than
        // printing a "0" the session never weighed.
        assertNull(LogScreenBuilder.bodyweightDisplay(null, WeightUnit.LB))
    }

    // --- backfill offer copy ---------------------------------------------------

    @Test
    fun backfillLabel_counts_the_workouts_and_keeps_its_plurals_straight() {
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.LogBackfill(false, 12), LogScreenBuilder.backfillLabel(12, running = false))
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.LogBackfill(false, 1), LogScreenBuilder.backfillLabel(1, running = false))
    }

    @Test
    fun backfillLabel_says_what_it_is_doing_while_it_runs() {
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.LogBackfill(true, 12), LogScreenBuilder.backfillLabel(12, running = true))
    }

    // --- grouping by exercise --------------------------------------------------

    @Test
    fun groupByExercise_preserves_first_appearance_order_and_labels_kinds() {
        val sets = listOf(
            set("bb_back_squat", "Barbell Back Squat", SetKind.RAMP, 130.0, 5),
            set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 235.0, 5),
            set("leg_curl", "Seated Leg Curl", SetKind.WORK, 90.0, 10),
            set("bb_back_squat", "Barbell Back Squat", SetKind.BACKOFF, 175.0, 8),
        )

        val groups = LogScreenBuilder.groupByExercise(sets, WeightUnit.LB)

        assertEquals(2, groups.size)
        assertEquals("Barbell Back Squat", groups[0].exerciseName)
        // #644: single values use the same explicit measurements as sides.
        assertEquals(
            listOf("R1" to "130 lb × 5", "TOP" to "235 lb × 5", "B/O" to "175 lb × 8"),
            groups[0].sets.map { it.kindLabel to it.weightRepsDisplay },
        )
        assertEquals("Seated Leg Curl", groups[1].exerciseName)
        // #644: single values use the same explicit measurements as sides.
        assertEquals(listOf("1" to "90 lb × 10"), groups[1].sets.map { it.kindLabel to it.weightRepsDisplay })
    }

    @Test
    fun groupByExercise_separates_a_supersets_partner_into_its_own_group() {
        // Same slot/session, but the main lift and its superset partner carry
        // different exerciseIds (TrackerRepository.finishRun's convention) —
        // "grouped by exercise" means they must not merge into one group.
        val sets = listOf(
            set("ez_curl", "EZ-Bar Curl", SetKind.WORK, 60.0, 12),
            set("rope_pushdown", "Rope Pushdown", SetKind.WORK, 50.0, 15),
            set("ez_curl", "EZ-Bar Curl", SetKind.WORK, 60.0, 11),
            set("rope_pushdown", "Rope Pushdown", SetKind.WORK, 50.0, 14),
        )

        val groups = LogScreenBuilder.groupByExercise(sets, WeightUnit.LB)

        assertEquals(listOf("EZ-Bar Curl", "Rope Pushdown"), groups.map { it.exerciseName })
        // #644: single values use the same explicit measurements as sides.
        assertEquals(listOf("1" to "60 lb × 12", "2" to "60 lb × 11"), groups[0].sets.map { it.kindLabel to it.weightRepsDisplay })
        // #644: single values use the same explicit measurements as sides.
        assertEquals(listOf("1" to "50 lb × 15", "2" to "50 lb × 14"), groups[1].sets.map { it.kindLabel to it.weightRepsDisplay })
    }

    @Test
    fun groupByExercise_of_an_empty_session_is_an_empty_list() {
        assertEquals(emptyList(), LogScreenBuilder.groupByExercise(emptyList(), WeightUnit.LB))
    }

    @Test
    fun groupByExercise_reads_a_hold_from_a_positive_seconds_value() {
        val sets = listOf(set("plank", "Plank", SetKind.WORK, 0.0, 0, seconds = 45))
        val groups = LogScreenBuilder.groupByExercise(sets, WeightUnit.LB)
        // #644: single values use the same explicit measurements as sides.
        assertEquals("45 s", groups.single().sets.single().weightRepsDisplay)
    }

    @Test
    fun groupByExercise_renders_a_legacy_reps_shaped_TIMED_row_as_reps_never_0s() {
        // Logged before "plank" was reclassified to TIMED: history is excluded
        // from the live exercise_log fixup, so this row is still weight 0 /
        // reps 45 / seconds 0 — must read as reps.
        val sets = listOf(set("plank", "Plank", SetKind.WORK, 0.0, 45))
        val groups = LogScreenBuilder.groupByExercise(sets, WeightUnit.LB)
        // #644: single values use the same explicit measurements as sides.
        assertEquals("45 reps", groups.single().sets.single().weightRepsDisplay)
    }

    @Test fun `a set with sides reads left then right`() {
        val legacy = set("side_plank", "Side Plank", SetKind.WORK, 0.0, 0, seconds = 40)
        val split = legacy.withSides(Sides(SideResult(0.0, 0, 40), SideResult(0.0, 0, 52)))
        val rows = LogScreenBuilder.groupByExercise(listOf(split, legacy), WeightUnit.LB).single().sets
        assertEquals("L 40 s · R 52 s", rows[0].weightRepsDisplay)
        // #644: single values use the same explicit measurements as sides.
        assertEquals("40 s", rows[1].weightRepsDisplay)
    }

    @Test fun `a hold with a side at zero still reads as a hold`() {
        val hold = set("side_plank", "Side Plank", SetKind.WORK, 0.0, 0)
            .withSides(Sides(SideResult(0.0, 0, 18), SideResult(0.0, 0, 0)))
        val row = LogScreenBuilder.groupByExercise(listOf(hold), WeightUnit.LB).single().sets.single()
        assertEquals(TrackingType.TIMED, row.tracking)
        assertEquals("L 18 s · R 0 s", row.weightRepsDisplay)
    }

}
