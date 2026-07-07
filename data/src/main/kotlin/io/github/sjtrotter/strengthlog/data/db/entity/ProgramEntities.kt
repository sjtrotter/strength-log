package io.github.sjtrotter.strengthlog.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Slot discriminator for [ExerciseLogEntity]/[SessionSetEntity]. */
object Slot {
    /** The exercise's own set track. */
    const val MAIN = "main"

    /** The superset partner's set track (present only when the slot has a partner). */
    const val SS = "ss"
}

/** One day of the user's editable program, ordered by [position] (spec §7). */
@Entity(tableName = "program_day")
data class ProgramDayEntity(
    @PrimaryKey val dayId: String,
    val position: Int,
    val title: String,
    val emphasisLine: String,
    /** Serialized [CardioDto], or null when the day has no finisher. */
    val cardioJson: String?,
)

/** One exercise slot inside a day, ordered by [position] within its [dayId] (spec §7). */
@Entity(
    tableName = "program_exercise",
    indices = [Index("dayId")],
)
data class ProgramExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val dayId: String,
    val position: Int,
    val exerciseId: String,
    val isMain: Boolean,
    val targetSets: Int,
    val repSchemeLabel: String,
    val hasWarmupHint: Boolean,
    val supersetExerciseId: String?,
    val note: String,
)

/**
 * The live ACTUAL log for one exercise slot's set track (spec §7). This is the
 * working state that seeds once from GOAL and then persists as the lifter's
 * living record; it is overwritten in place on every edit.
 *
 * [checkDate] is the device-local `yyyy-MM-dd` the `done` flags belong to. On
 * read, if it isn't today the checks surface as cleared (weights/reps persist);
 * see `CheckmarkReset`.
 */
@Entity(
    tableName = "exercise_log",
    primaryKeys = ["dayId", "programExerciseId", "slot"],
    indices = [Index("dayId")],
)
data class ExerciseLogEntity(
    val dayId: String,
    val programExerciseId: Long,
    val slot: String,
    /** Serialized `List<SetDto>` (kotlinx.serialization). */
    val setsJson: String,
    val checkDate: String,
    val updatedAt: Long,
)
