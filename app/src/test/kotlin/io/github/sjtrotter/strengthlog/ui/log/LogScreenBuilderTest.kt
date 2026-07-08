package io.github.sjtrotter.strengthlog.ui.log

import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class LogScreenBuilderTest {

    private fun set(exerciseId: String, name: String, kind: SetKind, weightLb: Double, reps: Int, sessionId: Long = 1) =
        SessionSetEntity(
            id = 0, sessionId = sessionId, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
            setIndex = 0, kind = kind.name, weightLb = weightLb, reps = reps, done = true,
        )

    // --- date / day / bodyweight formatting -----------------------------------

    @Test
    fun dateDisplay_formats_epoch_millis_in_the_given_zone() {
        // 2026-07-06T12:00:00Z
        val millis = 1783339200000L
        assertEquals("Jul 6, 2026", LogScreenBuilder.dateDisplay(millis, ZoneOffset.UTC))
    }

    @Test
    fun dayIndex_reads_the_leading_letter_of_the_day_id() {
        assertEquals(0, LogScreenBuilder.dayIndex("A"))
        assertEquals(3, LogScreenBuilder.dayIndex("D"))
        assertEquals(4, LogScreenBuilder.dayIndex("E"))
    }

    @Test
    fun dayIndex_falls_back_to_day_a_for_an_unrecognized_id() {
        assertEquals(0, LogScreenBuilder.dayIndex(""))
    }

    @Test
    fun bodyweightDisplay_converts_to_the_display_unit() {
        assertEquals("182", LogScreenBuilder.bodyweightDisplay(182, WeightUnit.LB))
        // Delegates to WeightStepper/WeightUnit (SSOT) rather than reimplementing
        // the lb/kg conversion — pin the delegation, not an arbitrary lb→kg number.
        assertEquals(
            WeightStepper.format(WeightUnit.KG.fromLb(220.0)),
            LogScreenBuilder.bodyweightDisplay(220, WeightUnit.KG),
        )
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
        assertEquals(
            listOf("R1" to "130×5", "TOP" to "235×5", "B/O" to "175×8"),
            groups[0].sets.map { it.kindLabel to it.weightRepsDisplay },
        )
        assertEquals("Seated Leg Curl", groups[1].exerciseName)
        assertEquals(listOf("1" to "90×10"), groups[1].sets.map { it.kindLabel to it.weightRepsDisplay })
    }

    @Test
    fun groupByExercise_separates_a_supersets_partner_into_its_own_group() {
        // Same slot/session, but the main lift and its superset partner carry
        // different exerciseIds (TrackerRepository.advanceDay's convention) —
        // "grouped by exercise" means they must not merge into one group.
        val sets = listOf(
            set("ez_curl", "EZ-Bar Curl", SetKind.WORK, 60.0, 12),
            set("rope_pushdown", "Rope Pushdown", SetKind.WORK, 50.0, 15),
            set("ez_curl", "EZ-Bar Curl", SetKind.WORK, 60.0, 11),
            set("rope_pushdown", "Rope Pushdown", SetKind.WORK, 50.0, 14),
        )

        val groups = LogScreenBuilder.groupByExercise(sets, WeightUnit.LB)

        assertEquals(listOf("EZ-Bar Curl", "Rope Pushdown"), groups.map { it.exerciseName })
        assertEquals(listOf("1" to "60×12", "2" to "60×11"), groups[0].sets.map { it.kindLabel to it.weightRepsDisplay })
        assertEquals(listOf("1" to "50×15", "2" to "50×14"), groups[1].sets.map { it.kindLabel to it.weightRepsDisplay })
    }

    @Test
    fun groupByExercise_of_an_empty_session_is_an_empty_list() {
        assertEquals(emptyList(), LogScreenBuilder.groupByExercise(emptyList(), WeightUnit.LB))
    }
}
