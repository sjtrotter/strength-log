package io.github.sjtrotter.strengthlog.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-created exercise (PLAN.md A4). The code catalog is the seed truth and
 * this table is the user overlay; the two are merged behind `ExerciseCatalog`.
 * [id] is always prefixed `custom_` so it can never collide with a catalog id.
 *
 * A custom exercise's GOAL is always a flat starting weight ([goalStartLb]); the
 * bodyweight-ratio standards only anchor the fixed main lifts.
 * [pattern] and [equipmentCsv] hold enum *names* (comma-separated for equipment).
 */
@Entity(tableName = "custom_exercise")
data class CustomExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pattern: String,
    val equipmentCsv: String,
    val perHand: Boolean,
    val goalStartLb: Double,
)
