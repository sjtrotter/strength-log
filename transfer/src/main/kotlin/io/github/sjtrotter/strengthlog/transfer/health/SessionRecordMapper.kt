package io.github.sjtrotter.strengthlog.transfer.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import java.time.Instant
import java.time.ZoneId

/**
 * The pure session → [ExerciseSessionRecord] mapping (#17). Deterministic and
 * side-effect free: the same completed session always produces the same record,
 * so the whole write contract is unit-testable without a Health Connect provider
 * (the publisher is just the thin availability/permission/degrade wrapper around
 * this).
 *
 * One STRENGTH_TRAINING session carries one [ExerciseSegment] per distinct
 * exercise, in first-performed order, each segment's `repetitions` the sum of
 * that exercise's rep counts across its sets — the same "group by exercise"
 * shape the Log screen shows. We don't record per-set timestamps, so the
 * segments evenly partition the session window; the window itself is the stored
 * `startedAt`..`completedAt`, or a synthesized lead-in when `startedAt` is null
 * (history rows created by `advanceDay` carry no start time).
 */
object SessionRecordMapper {

    /** Nominal per-exercise duration used to synthesize a session window when the
     *  session has no recorded start, and to guarantee every segment spans at
     *  least a whole millisecond even for a tiny real window. */
    private const val SEGMENT_SECONDS = 300L

    fun toExerciseSession(
        session: WorkoutSessionEntity,
        sets: List<SessionSetEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ExerciseSessionRecord {
        val repsByExercise = LinkedHashMap<String, Int>()
        for (set in sets) {
            repsByExercise[set.exerciseId] = (repsByExercise[set.exerciseId] ?: 0) + set.reps
        }
        val exerciseCount = repsByExercise.size

        val end = session.completedAt
        val minWindow = maxOf(exerciseCount, 1) * SEGMENT_SECONDS * 1_000L
        val recordedStart = session.startedAt
        val start = if (recordedStart != null && end - recordedStart >= minWindow) {
            recordedStart
        } else {
            end - minWindow
        }

        val offset = zone.rules.getOffset(Instant.ofEpochMilli(start))
        val sliceMillis = (end - start) / maxOf(exerciseCount, 1)

        val segments = repsByExercise.entries.mapIndexed { index, entry ->
            val segStart = start + index * sliceMillis
            // The last segment closes exactly on the session end so rounding
            // never leaves a sub-millisecond gap or an over-run past the session.
            val segEnd = if (index == exerciseCount - 1) end else start + (index + 1) * sliceMillis
            ExerciseSegment(
                startTime = Instant.ofEpochMilli(segStart),
                endTime = Instant.ofEpochMilli(segEnd),
                segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN,
                repetitions = entry.value,
            )
        }

        return ExerciseSessionRecord(
            startTime = Instant.ofEpochMilli(start),
            startZoneOffset = offset,
            endTime = Instant.ofEpochMilli(end),
            endZoneOffset = zone.rules.getOffset(Instant.ofEpochMilli(end)),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = session.dayTitle,
            segments = segments,
            metadata = Metadata.manualEntry(),
        )
    }
}
