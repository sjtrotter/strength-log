package cloud.trotter.log.strength.transfer.csv

import cloud.trotter.log.strength.data.ImportedSession
import cloud.trotter.log.strength.data.SessionHistorySnapshot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetKind
import java.util.UUID

/**
 * Turns a confirmed [CsvImportPreview] into the entities
 * [cloud.trotter.log.strength.data.TrackerRepository.importSessionHistory]
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
    fun commit(
        preview: CsvImportPreview,
        approvedPatterns: Map<String, MovementPattern>,
        existingHistory: SessionHistorySnapshot? = null,
    ): CommitPlan {
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
                    // The Duration column is never read back into startedAt: this
                    // app's own export writes it (HistoryCsvWriter), but a foreign
                    // Strong/Hevy file's Duration has no verified format or
                    // semantics — treating it as a real start stamp would let
                    // untrusted input feed the HC-calories guard a fabricated
                    // window. An imported session simply has no recorded start.
                    startedAt = null,
                    completedAt = session.completedAt,
                    // CSV carries no bodyweight column, and today's configured
                    // bodyweight isn't what the lifter weighed back then.
                    bodyweightLb = null,
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
                        seconds = set.seconds,
                    )
                },
            )
        }
        val seen = existingHistory?.let { history ->
            val setsBySession = history.sessionSets.groupBy { it.sessionId }
            history.sessions.mapTo(mutableSetOf()) { session ->
                normalizedSessionFingerprint(session, setsBySession[session.id].orEmpty())
            }
        } ?: mutableSetOf()
        val newSessions = sessions.filter { seen.add(normalizedSessionFingerprint(it.session, it.sets)) }
        return CommitPlan(newSessions, newCustomExercises)
    }

    /**
     * Normalizes both native and imported history to exactly what CSV preserves:
     * title, whole-second completion time, and ordered exercise/value tuples.
     * Row ids and native-only set metadata are deliberately absent. Consequently,
     * two truly identical sessions completed within one second collide; that is
     * an acceptable limitation of the interchange format's second precision.
     */
    private fun normalizedSessionFingerprint(
        session: WorkoutSessionEntity,
        sets: List<SessionSetEntity>,
    ) = SessionFingerprint(
        dayTitle = session.dayTitle,
        completedAtSeconds = session.completedAt / 1_000,
        sets = sets.map { it.normalizedSetFingerprint() },
    )

    private fun SessionSetEntity.normalizedSetFingerprint() = SetFingerprint(
        exerciseName = exerciseName,
        weightLb = weightLb,
        reps = reps,
        seconds = seconds,
    )

    private data class SessionFingerprint(
        val dayTitle: String,
        val completedAtSeconds: Long,
        val sets: List<SetFingerprint>,
    )

    private data class SetFingerprint(
        val exerciseName: String,
        val weightLb: Double,
        val reps: Int,
        val seconds: Int,
    )

    private fun newCustomExercise(name: String, pattern: MovementPattern) = CustomExerciseEntity(
        id = ExerciseCatalog.CUSTOM_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""),
        name = name,
        pattern = pattern.name,
        equipmentCsv = "",
        perHand = false,
        goalStartLb = 0.0,
    )
}
