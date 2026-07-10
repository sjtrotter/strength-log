package io.github.sjtrotter.strengthlog.transfer.health

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.time.Instant
import java.time.ZoneId

/**
 * The pure session → [ActiveCaloriesBurnedRecord] mapping (session-start
 * capture brief). A session-level estimate only: `kcal = MET * bodyweight(kg)
 * * duration(hours)`, with [RESISTANCE_TRAINING_MET] the Compendium of
 * Physical Activities value for "resistance training, moderate effort"
 * (code 02050, weight lifting/free weight/nautilus/universal — general).
 * Per-set attribution isn't attempted: without heart-rate or power data this
 * app doesn't collect, splitting one MET-based estimate across individual
 * sets would manufacture precision the input doesn't support, so the whole
 * session gets one number.
 *
 * Returns `null` — never a fabricated record — when either guard fails:
 *  - **No real start.** `startedAt` must be a recorded session-start stamp,
 *    never [SessionRecordMapper]'s synthesized lead-in; a synthesized window
 *    is sized to fit the exercise segments, not to approximate how long the
 *    lifter actually trained, so it is not a defensible calorie duration.
 *  - **Sane duration.** Below 5 minutes is too short to be a real workout
 *    (dedupe/roundoff noise); above 6 hours smells like a stale stamp from a
 *    session ticked yesterday and finished today (see
 *    [io.github.sjtrotter.strengthlog.data.TrackerRepository.advanceDay]'s
 *    crash-ordering note) rather than one continuous session.
 */
object CaloriesRecordMapper {

    /** Compendium of Physical Activities MET for moderate-effort resistance
     *  training — a single accepted constant, not derived per exercise. */
    private const val RESISTANCE_TRAINING_MET = 5.0

    private const val MIN_DURATION_MILLIS = 5 * 60_000L
    private const val MAX_DURATION_MILLIS = 6 * 60 * 60_000L
    private const val MILLIS_PER_HOUR = 3_600_000.0

    /** A stable client record id so a retry/re-publish updates rather than
     *  duplicates the Health Connect entry (idempotency), distinct from the
     *  exercise session's own id so the two records never collide. */
    fun clientRecordId(sessionId: Long): String = "strengthlog-calories-$sessionId"

    fun toActiveCalories(
        session: WorkoutSessionEntity,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ActiveCaloriesBurnedRecord? {
        val startedAt = session.startedAt ?: return null
        val durationMillis = session.completedAt - startedAt
        if (durationMillis < MIN_DURATION_MILLIS || durationMillis > MAX_DURATION_MILLIS) return null

        val bodyweightKg = WeightUnit.KG.fromLb(session.bodyweightLb.toDouble())
        val hours = durationMillis / MILLIS_PER_HOUR
        val kcal = RESISTANCE_TRAINING_MET * bodyweightKg * hours

        val start = Instant.ofEpochMilli(startedAt)
        val end = Instant.ofEpochMilli(session.completedAt)
        return ActiveCaloriesBurnedRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            energy = Energy.kilocalories(kcal),
            metadata = Metadata.manualEntry(clientRecordId(session.id)),
        )
    }
}
