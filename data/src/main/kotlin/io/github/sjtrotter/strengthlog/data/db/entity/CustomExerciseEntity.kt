package io.github.sjtrotter.strengthlog.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-created exercise (PLAN.md A4). The code catalog is the seed truth and
 * this table is the user overlay; the two are merged behind `ExerciseCatalog`.
 * [id] is always prefixed `custom_` so it can never collide with a catalog id.
 *
 * [tracking] (a [io.github.sjtrotter.strengthlog.domain.library.TrackingType] name)
 * decides how the GOAL is read (added in DB v2, DEFAULT 'WEIGHTED'):
 * WEIGHTED uses [goalStartLb], REPS uses [targetReps], TIMED uses [targetSeconds]
 * with [goalStartLb] as any added load. [pattern] and [equipmentCsv] hold enum
 * *names* (comma-separated for equipment).
 */
@Entity(tableName = "custom_exercise")
data class CustomExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pattern: String,
    val equipmentCsv: String,
    val perHand: Boolean,
    val goalStartLb: Double,
    val tracking: String = "WEIGHTED",
    val targetReps: Int? = null,
    val targetSeconds: Int? = null,
)
