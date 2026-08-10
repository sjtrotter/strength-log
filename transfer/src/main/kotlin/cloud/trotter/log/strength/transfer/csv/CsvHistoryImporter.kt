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
            history.sessions.mapTo(mutableSetOf()) { session ->
                sessionFingerprint(session, history.sessionSets.filter { it.sessionId == session.id })
            }
        } ?: mutableSetOf()
        val newSessions = sessions.filter { seen.add(sessionFingerprint(it.session, it.sets)) }
        return CommitPlan(newSessions, newCustomExercises)
    }

    /** Row ids are deliberately absent: a CSV re-import necessarily receives new
     * ids, while its title, completion stamp, and ordered set contents are stable. */
    private fun sessionFingerprint(session: WorkoutSessionEntity, sets: List<SessionSetEntity>) =
        SessionFingerprint(session.dayTitle, session.completedAt, sets.map { it.contentFingerprint() })

    private fun SessionSetEntity.contentFingerprint() = SetFingerprint(
        exerciseName = exerciseName,
        slot = slot,
        setIndex = setIndex,
        kind = kind,
        weightLb = weightLb,
        reps = reps,
        done = done,
        seconds = seconds,
        startedAtMillis = startedAtMillis,
        completedAtMillis = completedAtMillis,
    )

    private data class SessionFingerprint(
        val dayTitle: String,
        val completedAt: Long,
        val sets: List<SetFingerprint>,
    )

    private data class SetFingerprint(
        val exerciseName: String,
        val slot: String,
        val setIndex: Int,
        val kind: String,
        val weightLb: Double,
        val reps: Int,
        val done: Boolean,
        val seconds: Int,
        val startedAtMillis: Long?,
        val completedAtMillis: Long?,
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
