package io.github.sjtrotter.strengthlog.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An immutable record of one completed workout, appended when the user taps
 * "DONE — advance" (PLAN.md A1). Written once, never mutated, so it needs no
 * sync/consistency handling and is the source for CSV/Health-Connect export.
 */
@Entity(tableName = "workout_session")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val dayId: String,
    /** Denormalized so the record survives a later day rename. */
    val dayTitle: String,
    val startedAt: Long?,
    val completedAt: Long,
    val bodyweightLb: Int,
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
)
