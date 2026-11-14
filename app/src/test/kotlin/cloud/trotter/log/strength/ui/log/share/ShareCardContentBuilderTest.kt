package cloud.trotter.log.strength.ui.log.share

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The share card's content model (docs/briefs/session-share.md §2): a pure
 * function of a session/its sets/the display unit, so every shape the card can
 * draw — weighted/timed/reps lifts, every exercise, volume/duration
 * formatting, and above all §2's "never bodyweight" allowlist — is pinned here
 * on the JVM, with no [ShareCardPainter] Canvas involved.
 */
class ShareCardContentBuilderTest {

    // 2026-07-08 is a Wednesday.
    private fun session(
        dayId: String = "A",
        dayTitle: String = "Lower",
        startedAt: Long? = 1_783_500_000_000L,
        completedAt: Long = 1_783_502_280_000L, // startedAt + 38 min
        bodyweightLb: Double = 182.0,
    ) = WorkoutSessionEntity(
        id = 1, dayId = dayId, dayTitle = dayTitle, startedAt = startedAt, completedAt = completedAt,
        bodyweightLb = bodyweightLb,
    )

    private fun weightedSet(
        exerciseId: String,
        name: String,
        weightLb: Double,
        reps: Int,
        done: Boolean = true,
        slot: String = Slot.MAIN,
    ) =
        SessionSetEntity(
            id = 0, sessionId = 1, exerciseId = exerciseId, exerciseName = name, slot = slot, setIndex = 0,
            kind = SetKind.WORK.name, weightLb = weightLb, reps = reps, done = done,
        )

    private fun timedSet(exerciseId: String, name: String, seconds: Int, weightLb: Double = 0.0) = SessionSetEntity(
        id = 0, sessionId = 1, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN, setIndex = 0,
        kind = SetKind.WORK.name, weightLb = weightLb, reps = 0, done = true, seconds = seconds,
    )

    private fun repsSet(exerciseId: String, name: String, reps: Int) = SessionSetEntity(
        id = 0, sessionId = 1, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN, setIndex = 0,
        kind = SetKind.WORK.name, weightLb = 0.0, reps = reps, done = true,
    )

    // --- header ------------------------------------------------------------

    @Test
    fun dayCKeepsTheThirdAccentWithoutTheOriginalProgram() {
        val content = ShareCardContentBuilder.build(session(dayId = "C"), emptyList(), WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("DAY C · LOWER", content.dayLine)
        assertEquals(2, content.dayIndex)
    }

    @Test
    fun dateAndDayLinesReadCapsFromTheSessionHeader() {
        val content = ShareCardContentBuilder.build(session(), emptyList(), WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("WEDNESDAY · JUL 8", content.dateLine)
        assertEquals("DAY A · LOWER", content.dayLine)
        assertEquals(0, content.dayIndex)
    }

    // --- lift lines: weighted / timed / reps --------------------------------

    @Test
    fun aWeightedLiftShowsItsHeaviestDoneSetAsWeightTimesReps() {
        val sets = listOf(
            weightedSet("bb_back_squat", "Barbell Back Squat", 130.0, 5),
            weightedSet("bb_back_squat", "Barbell Back Squat", 235.0, 5),
            weightedSet("bb_back_squat", "Barbell Back Squat", 175.0, 8),
        )
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals(listOf(ShareExerciseLine("BARBELL BACK SQUAT", "235 × 5")), content.exerciseLines)
    }

    @Test
    fun aLiftWithOnlyDoneRampsHasNoLine() {
        val sets = listOf(
            weightedSet("bb_back_squat", "Barbell Back Squat", 250.0, 1).copy(kind = SetKind.RAMP.name),
        )
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals(emptyList(), content.exerciseLines)
    }

    @Test
    fun aHeavierRampDoesNotReplaceTheBestQualifyingSet() {
        val sets = listOf(
            weightedSet("bb_back_squat", "Barbell Back Squat", 250.0, 1).copy(kind = SetKind.RAMP.name),
            weightedSet("bb_back_squat", "Barbell Back Squat", 245.0, 5).copy(kind = SetKind.TOP.name),
        )
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals(listOf(ShareExerciseLine("BARBELL BACK SQUAT", "245 × 5")), content.exerciseLines)
    }

    @Test
    fun mixedTrackingUsesTheNewestCountingType() {
        val sets = listOf(
            weightedSet("plank", "Plank", 25.0, 5).copy(kind = SetKind.RAMP.name),
            repsSet("plank", "Plank", 60),
            timedSet("plank", "Plank", 90),
        )
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("1:30", content.exerciseLines.single().value)
    }

    @Test
    fun weightedTieBreaksOnReps() {
        val sets = listOf(
            weightedSet("bb_bench", "Barbell Bench Press", 185.0, 5),
            weightedSet("bb_bench", "Barbell Bench Press", 185.0, 8),
        )
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("185 × 8", content.exerciseLines.single().value)
    }

    @Test
    fun aTimedLiftAlwaysShowsMinutesColonSeconds() {
        val sets = listOf(timedSet("plank", "Plank", 45), timedSet("plank", "Plank", 30))
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("0:45", content.exerciseLines.single().value)
    }

    @Test
    fun aTimedLiftPastAMinuteStillReadsMinutesColonSeconds() {
        val sets = listOf(timedSet("farmers_carry", "Farmer's Carry", 90))
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("1:30", content.exerciseLines.single().value)
    }

    @Test
    fun aRepsOnlyLiftShowsTimesReps() {
        val sets = listOf(repsSet("pushup", "Push-Up", 12), repsSet("pushup", "Push-Up", 15))
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("×15", content.exerciseLines.single().value)
    }

    @Test
    fun onlyDoneSetsCountAndAnExerciseWithNoneDoneIsLeftOff() {
        val sets = listOf(
            weightedSet("bb_back_squat", "Barbell Back Squat", 235.0, 5),
            weightedSet("bb_deadlift", "Barbell Deadlift", 315.0, 5, done = false),
        )
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals(listOf("BARBELL BACK SQUAT"), content.exerciseLines.map { it.name })
    }

    @Test
    fun exerciseLinesConvertToTheDisplayUnit() {
        // 220.46226218 canonical lb is exactly 100 kg (WeightUnit's own lb-per-kg
        // constant), so the formatted value is unambiguous.
        val sets = listOf(weightedSet("bb_back_squat", "Barbell Back Squat", 220.46226218, 5))
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.KG, ZoneOffset.UTC)
        assertEquals("100 × 5", content.exerciseLines.single().value)
    }

    @Test
    fun sixOrFewerLiftsShowAllOfThem() {
        for (count in 0..6) {
            val sets = (1..count).map { weightedSet("ex$it", "Exercise $it", 100.0, 5) }
            val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
            assertEquals(count, content.exerciseLines.size)
        }
    }

    @Test
    fun nineLiftsShowEveryExercisePerOwnerReport20261113() {
        val sets = (1..9).map { weightedSet("ex$it", "Exercise $it", 100.0, 5) }
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals((1..9).map { ShareExerciseLine("EXERCISE $it", "100 × 5") }, content.exerciseLines)
    }

    @Test
    fun cardioBlocksFollowLiftsWithTheUsersDistanceUnitAndClockDuration() {
        val sets = listOf(weightedSet("squat", "Squat", 235.0, 5))
        val blocks = listOf(ShareCardioBlock("Road run", 1500, 5000), ShareCardioBlock("Outdoor walk", 601, 1000))
        val miles = ShareCardContentBuilder.build(session(), sets, WeightUnit.KG, ZoneOffset.UTC,
            cardioBlocks = blocks, distanceUnit = DistanceUnit.MI)
        assertEquals(listOf(
            ShareExerciseLine("ROAD RUN", "3.11 MI · 25:00"),
            ShareExerciseLine("OUTDOOR WALK", "0.62 MI · 10:01"),
        ), miles.exerciseLines.drop(1))
        assertEquals(3, miles.exerciseLines.size)
        val kilometers = ShareCardContentBuilder.build(session(), emptyList(), WeightUnit.LB, ZoneOffset.UTC,
            cardioBlocks = blocks, distanceUnit = DistanceUnit.KM)
        assertEquals("5 KM · 25:00", kilometers.exerciseLines.first().value)
    }

    @Test
    fun cardioWithoutDistanceShowsOnlyItsRecordedDuration() {
        val content = ShareCardContentBuilder.build(session(), emptyList(), WeightUnit.LB,
            cardioBlocks = listOf(ShareCardioBlock("Stationary bike", 3605, null)))
        assertEquals(listOf(ShareExerciseLine("STATIONARY BIKE", "60:05")), content.exerciseLines)
    }

    // --- footer: sets / duration / volume ------------------------------------

    @Test
    fun footerCountsDoneRoundsAndDurationInWholeMinutesAndGroupedTonnage() {
        val sets = listOf(
            weightedSet("bb_back_squat", "Barbell Back Squat", 235.0, 5),
            weightedSet("pullup", "Pull-Up", 200.0, 5, slot = Slot.SS),
        )
        // 38-minute session (session()'s default startedAt/completedAt).
        val content = ShareCardContentBuilder.build(session(), sets, WeightUnit.LB, ZoneOffset.UTC)
        // The share card counts superset rounds, not rows.
        assertEquals("1 SET · 38 MIN · 2,175 LB", content.footerLine)
    }

    @Test
    fun footerOmitsDurationWhenNoStartWasRecorded() {
        val sets = listOf(weightedSet("bb_back_squat", "Barbell Back Squat", 235.0, 5))
        val content = ShareCardContentBuilder.build(session(startedAt = null), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("1 SET · 1,175 LB", content.footerLine)
    }

    @Test
    fun footerVolumeIsInTheDisplayUnitWithTheUnitsName() {
        // 220.46226218 canonical lb is exactly 100 kg, so the converted tonnage
        // (weight × reps, then to kg) lands on a clean 1,000 with no rounding
        // ambiguity in the assertion.
        val sets = listOf(weightedSet("bb_back_squat", "Barbell Back Squat", 220.46226218, 10))
        val content = ShareCardContentBuilder.build(session(startedAt = null), sets, WeightUnit.KG, ZoneOffset.UTC)
        assertEquals("1 SET · 1,000 KG", content.footerLine)
    }

    @Test
    fun footerVolumeCountsOnlyDoneSets() {
        val sets = listOf(
            weightedSet("bb_back_squat", "Barbell Back Squat", 235.0, 5),
            weightedSet("bb_back_squat", "Barbell Back Squat", 500.0, 5, done = false),
        )
        val content = ShareCardContentBuilder.build(session(startedAt = null), sets, WeightUnit.LB, ZoneOffset.UTC)
        assertEquals("1 SET · 1,175 LB", content.footerLine)
    }

    // --- §2 acceptance: bodyweight never appears ------------------------------

    @Test
    fun bodyweightNeverAppearsAnywhereOnTheCard() {
        val sets = listOf(
            weightedSet("bb_back_squat", "Barbell Back Squat", 235.0, 5),
            timedSet("plank", "Plank", 45),
            repsSet("pushup", "Push-Up", 12),
        )
        // A distinctive bodyweight value that would stand out if it leaked in.
        val content = ShareCardContentBuilder.build(session(bodyweightLb = 999.0), sets, WeightUnit.LB, ZoneOffset.UTC)

        val allText = listOf(content.dateLine, content.dayLine, content.footerLine) +
            content.exerciseLines.flatMap { listOf(it.name, it.value) }

        for (line in allText) {
            assertFalse(line.contains("999"), "bodyweight leaked into: $line")
            assertFalse(line.contains("BW", ignoreCase = true), "bodyweight leaked into: $line")
            assertFalse(line.contains("BODYWEIGHT", ignoreCase = true), "bodyweight leaked into: $line")
        }
    }
}
