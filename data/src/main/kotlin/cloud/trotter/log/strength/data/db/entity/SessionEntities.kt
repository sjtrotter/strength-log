package cloud.trotter.log.strength.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A record of one completed workout, appended when the user taps
 * "DONE — advance" (PLAN.md A1) and later editable in the history log.
 */
@Entity(tableName = "workout_session")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val dayId: String,
    /** Denormalized so the record survives a later day rename. */
    val dayTitle: String,
    val startedAt: Long?,
    val completedAt: Long,
    /** Null when the session recorded no bodyweight — CSV-imported history carries
     *  no bodyweight column (#171). "Unknown" must stay distinguishable from a
     *  number: a 0 here reads as a weightless lifter to the calorie estimate and
     *  as a real value to the backup, which is what made imported history
     *  unrestorable. Added in DB v4. */
    val bodyweightLb: Int?,
)

/**
 * One performed set within a [WorkoutSessionEntity] (PLAN.md A1). [exerciseName]
 * is denormalized so history stays honest after the program is edited or the
 * exercise is deleted; [kind] is stored by enum name for the same
 * forward-compatibility reason as [SetDto].
 */
@Entity(
    tableName = "session_set",
    indices = [Index("sessionId")],
)
data class SessionSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val sessionId: Long,
    val exerciseId: String,
    val exerciseName: String,
    val slot: String,
    val setIndex: Int,
    val kind: String,
    val weightLb: Double,
    val reps: Int,
    val done: Boolean,
    /** Hold/carry duration for TIMED tracks; 0 (ignored) for WEIGHTED/REPS.
     *  Added in DB v2 with a DEFAULT 0 so pre-v2 history reads back as weight×reps. */
    val seconds: Int = 0,
    /** When this set was started and ticked (wall-clock millis), carried over from
     *  the live log's [cloud.trotter.log.strength.domain.model.LoggedSet].
     *  Nullable, added in DB v3: history archived before per-set timing existed —
     *  and every set ticked on the phone — carries no stamp, and "not observed"
     *  must stay distinguishable from "zero". Active time and actual rest are
     *  derived from these on read; nothing derived is stored. */
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
)
