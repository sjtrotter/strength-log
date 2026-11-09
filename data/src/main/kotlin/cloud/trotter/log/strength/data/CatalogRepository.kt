package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.mapping.toEntry
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CatalogRepository(private val customExerciseDao: CustomExerciseDao) {
    val catalogFlow: Flow<ExerciseCatalog> =
        customExerciseDao.observeAll().map { rows -> ExerciseCatalog(rows.map { it.toEntry() }) }

    /**
     * [tracking] selects which of [goalStartLb]/[targetReps]/[targetSeconds] is
     * the live GOAL (mirrors [cloud.trotter.log.strength.data.mapping.toEntry]):
     * WEIGHTED reads [goalStartLb] as the flat starting weight; REPS reads
     * [targetReps]; TIMED reads [targetSeconds] plus [goalStartLb] as any
     * optional added load. Defaulted so every pre-tracking-types call site
     * (weighted-only) keeps compiling unchanged.
     */
    suspend fun addCustomExercise(
        name: String,
        pattern: MovementPattern,
        equipment: List<Equipment>,
        perHand: Boolean,
        goalStartLb: Double,
        tracking: TrackingType = TrackingType.WEIGHTED,
        targetReps: Int? = null,
        targetSeconds: Int? = null,
    ): String {
        val id = ExerciseCatalog.CUSTOM_ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
        customExerciseDao.upsert(
            CustomExerciseEntity(
                id = id,
                name = name.trim(),
                pattern = pattern.name,
                equipmentCsv = equipment.joinToString(",") { it.name },
                perHand = perHand,
                goalStartLb = goalStartLb,
                tracking = tracking.name,
                targetReps = targetReps,
                targetSeconds = targetSeconds,
            ),
        )
        return id
    }

    suspend fun removeCustomExercise(id: String) = customExerciseDao.delete(id)

    internal suspend fun snapshot(): ExerciseCatalog = readableCatalog(customExerciseDao.getAll())
}

/** The catalog a write path reads: a custom exercise row this build cannot read is left out, so the edit treats it as
 *  an unknown exercise instead of failing. */
internal fun readableCatalog(rows: List<CustomExerciseEntity>): ExerciseCatalog =
    ExerciseCatalog(rows.mapNotNull { runCatching { it.toEntry() }.getOrNull() })
