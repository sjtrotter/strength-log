package io.github.sjtrotter.strengthlog.transfer.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pure session → [ExerciseSessionRecord] mapping (#17). Runs under
 * Robolectric only because the androidx.health record types are Android
 * artifacts — there is no provider and no emulator involved (D10). Everything
 * asserted here is deterministic from the input session/sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionRecordMapperTest {

    private val zone = ZoneId.of("America/New_York")

    private fun session(startedAt: Long?, completedAt: Long) = WorkoutSessionEntity(
        id = 1, dayId = "A", dayTitle = "Lower — squat focus",
        startedAt = startedAt, completedAt = completedAt, bodyweightLb = 180,
    )

    private fun set(exerciseId: String, reps: Int, slot: String = Slot.MAIN, done: Boolean = true) = SessionSetEntity(
        id = 0, sessionId = 1, exerciseId = exerciseId, exerciseName = exerciseId, slot = slot,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 100.0, reps = reps, done = done,
    )

    @Test
    fun mapsStrengthTypeAndTitle() {
        val record = SessionRecordMapper.toExerciseSession(
            session(startedAt = null, completedAt = 1_000_000L),
            listOf(set("bb_back_squat", 5)),
            zone,
        )
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, record.exerciseType)
        assertEquals("Lower — squat focus", record.title)
    }

    @Test
    fun oneSegmentPerExercise_repsSummedInFirstPerformedOrder() {
        val record = SessionRecordMapper.toExerciseSession(
            session(startedAt = null, completedAt = 2_000_000L),
            listOf(
                set("bb_back_squat", 5),
                set("bb_back_squat", 5),
                set("bb_back_squat", 3),
                set("seated_leg_curl", 10),
                set("seated_leg_curl", 10),
            ),
            zone,
        )
        // Two distinct exercises → two segments, squat first (first performed).
        assertEquals(2, record.segments.size)
        assertEquals(13, record.segments[0].repetitions) // 5+5+3
        assertEquals(20, record.segments[1].repetitions) // 10+10
        assertTrue(record.segments.all { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN })
    }

    @Test
    fun onlyDoneSetsCountTowardReps() {
        val record = SessionRecordMapper.toExerciseSession(
            session(startedAt = null, completedAt = 2_000_000L),
            listOf(
                set("bb_back_squat", 5, done = true),
                set("bb_back_squat", 8, done = false), // an unchecked seeded set: excluded
                set("seated_leg_curl", 10, done = false), // fully unchecked exercise: 0-rep segment kept
            ),
            zone,
        )
        assertEquals(2, record.segments.size)
        assertEquals(5, record.segments[0].repetitions) // only the done squat set
        assertEquals(0, record.segments[1].repetitions) // curl never checked off
    }

    @Test
    fun clientRecordIdIsStablePerSession() {
        assertEquals("strengthlog-session-42", SessionRecordMapper.clientRecordId(42L))
    }

    @Test
    fun segmentsAreOrderedNonOverlappingAndWithinSession() {
        val record = SessionRecordMapper.toExerciseSession(
            session(startedAt = null, completedAt = 3_000_000L),
            listOf(set("a", 5), set("b", 5), set("c", 5)),
            zone,
        )
        val segments = record.segments
        // Inside the session window, contiguous, and the last closes exactly on the end.
        assertTrue(record.startTime <= segments.first().startTime)
        assertEquals(record.endTime, segments.last().endTime)
        for (i in 1 until segments.size) {
            assertTrue(segments[i - 1].endTime <= segments[i].startTime)
            assertTrue(segments[i - 1].startTime < segments[i - 1].endTime)
        }
    }

    @Test
    fun usesRecordedStartWhenWideEnough() {
        val start = 1_000_000L
        val end = start + 40L * 60_000L // 40 minutes — wider than the synthesized minimum
        val record = SessionRecordMapper.toExerciseSession(
            session(startedAt = start, completedAt = end),
            listOf(set("a", 5)),
            zone,
        )
        assertEquals(start, record.startTime.toEpochMilli())
        assertEquals(end, record.endTime.toEpochMilli())
    }

    @Test
    fun synthesizesStartWhenNoneRecorded() {
        val end = 5_000_000L
        val record = SessionRecordMapper.toExerciseSession(
            session(startedAt = null, completedAt = end),
            listOf(set("a", 5), set("b", 5)),
            zone,
        )
        // Window is synthesized behind the end; end still honored exactly.
        assertEquals(end, record.endTime.toEpochMilli())
        assertTrue(record.startTime.toEpochMilli() < end)
    }

    private fun timedSet(exerciseId: String, seconds: Int) = SessionSetEntity(
        id = 0, sessionId = 1, exerciseId = exerciseId, exerciseName = exerciseId, slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 0.0, reps = 0, done = true, seconds = seconds,
    )

    @Test
    fun timedExerciseDegradesToADurationSegmentWithNoRepCount() {
        // A REPS exercise still contributes its rep sum; a TIMED hold contributes a
        // segment that spans time but carries no reps (HC segments never carry
        // weight, so "no weight" is automatic). The session write never breaks.
        val record = SessionRecordMapper.toExerciseSession(
            session(startedAt = null, completedAt = 2_000_000L),
            listOf(set("pushup", 15), set("pushup", 12), timedSet("plank", 45)),
            zone,
        )
        assertEquals(2, record.segments.size)
        assertEquals(27, record.segments[0].repetitions) // REPS: 15 + 12
        assertEquals(0, record.segments[1].repetitions) // TIMED: a hold, no rep count
        assertTrue(record.segments[1].endTime.isAfter(record.segments[1].startTime))
    }
}
