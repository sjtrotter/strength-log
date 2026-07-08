package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.ImportedSession
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import java.util.UUID

/**
 * Turns a confirmed [CsvImportPreview] into the entities
 * [io.github.sjtrotter.strengthlog.data.TrackerRepository.importSessionHistory]
 * writes (issue #16). Pure: no repository, no suspend — [CsvHistoryService]
 * calls this and then hands the result to the repository in one transaction.
 */
object CsvHistoryImporter {

    /** What [commit] built: the sessions ready to append, and any brand-new
     *  custom exercises they need created alongside them. */
    data class CommitPlan(
        val sessions: List<ImportedSession>,
        val newCustomExercises: List<CustomExerciseEntity>,
    )

    /**
     * Resolves every [CsvImportPreview.unmatchedNames] entry via
     * [approvedPatterns] (keyed by [UnmatchedExerciseName.name], matched the
     * same case/whitespace-insensitive way the preview matched the catalog),
     * creates one custom exercise per approved name, and builds the session
     * rows to append. Throws [CsvImportError.MissingApproval] — committing
     * nothing — if any unmatched name has no entry in [approvedPatterns].
     */
    fun commit(preview: CsvImportPreview, approvedPatterns: Map<String, MovementPattern>): CommitPlan {
        val approvedByNormalizedName = approvedPatterns.mapKeys { normalizeExerciseName(it.key) }
        val missing = preview.unmatchedNames
            .filter { normalizeExerciseName(it.name) !in approvedByNormalizedName }
            .map { it.name }
        if (missing.isNotEmpty()) throw CsvImportError.MissingApproval(missing)

        val newCustomExercises = preview.unmatchedNames.map { unmatched ->
            newCustomExercise(unmatched.name, approvedByNormalizedName.getValue(normalizeExerciseName(unmatched.name)))
        }
        val customIdByNormalizedName = newCustomExercises.associate { normalizeExerciseName(it.name) to it.id }

        val sessions = preview.sessions.map { session ->
            ImportedSession(
                session = WorkoutSessionEntity(
                    id = 0,
                    // No CSV row carries a program day id — this is a synthetic
                    // marker, never resolved against the live program.
                    dayId = "csv:${session.dayTitle}",
                    dayTitle = session.dayTitle,
                    startedAt = null,
                    completedAt = session.completedAt,
                    bodyweightLb = 0, // CSV carries no bodyweight column
                ),
                sets = session.sets.map { set ->
                    val exerciseId = set.exerciseId
                        ?: customIdByNormalizedName.getValue(normalizeExerciseName(set.exerciseName))
                    SessionSetEntity(
                        id = 0,
                        sessionId = 0, // stamped by TrackerRepository.importSessionHistory
                        exerciseId = exerciseId,
                        exerciseName = set.exerciseName,
                        slot = Slot.MAIN,
                        setIndex = set.setIndex,
                        // CSV has no per-set kind (ramp/top/backoff); imported sets
                        // are logged, completed performance, so WORK is the honest
                        // default.
                        kind = SetKind.WORK.name,
                        weightLb = set.weightLb,
                        reps = set.reps,
                        done = true,
                    )
                },
            )
        }
        return CommitPlan(sessions, newCustomExercises)
    }

    private fun newCustomExercise(name: String, pattern: MovementPattern) = CustomExerciseEntity(
        id = ExerciseCatalog.CUSTOM_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""),
        name = name,
        pattern = pattern.name,
        equipmentCsv = "",
        perHand = false,
        goalStartLb = 0.0,
    )
}
