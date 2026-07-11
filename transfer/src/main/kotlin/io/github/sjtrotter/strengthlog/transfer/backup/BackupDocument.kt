package io.github.sjtrotter.strengthlog.transfer.backup

import kotlinx.serialization.Serializable

/**
 * The schema version of the JSON produced by [BackupCodec.encode]. A reader
 * dispatches on the document's `schemaVersion` field (see [BackupCodec.decode]):
 * anything other than this value is rejected loudly with
 * [BackupError.UnsupportedSchemaVersion] rather than being misread. Bump this and
 * add a `when` branch when the on-disk shape changes; no migration machinery is
 * built ahead of a real second version.
 */
const val CURRENT_SCHEMA_VERSION: Int = 2

/**
 * The portable, versioned full backup (PLAN.md A2): one self-contained document
 * carrying everything a user owns — preferences + wizard answers + display unit,
 * every custom exercise, the program with its per-slot live logs, and the whole
 * session history.
 *
 * Field order here is the on-disk field order (kotlinx.serialization emits in
 * declaration order), and every list is filled from a query with an explicit
 * `ORDER BY`, so encoding the same state twice is byte-identical.
 *
 * Surrogate ids ([ProgramExerciseBackup.id], [SessionBackup.id], set ids) are
 * carried verbatim because live logs and session sets key on them; preserving
 * them makes a restore an exact reproduction rather than a lossy re-import.
 * Positions are *not* stored — they are the list order, so a restore reassigns
 * them and the document never disagrees with itself (SSOT).
 */
@Serializable
data class BackupDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val settings: SettingsBackup,
    val customExercises: List<CustomExerciseBackup> = emptyList(),
    val program: List<ProgramDayBackup> = emptyList(),
    val liveLogs: List<LiveLogBackup> = emptyList(),
    val sessions: List<SessionBackup> = emptyList(),
)

/**
 * The whole DataStore surface. Enum-valued facts are stored by *name* (not
 * ordinal) so reordering an enum later can't silently reinterpret an old backup —
 * the same forward-compat rule `:data` already applies to its stored enums.
 */
@Serializable
data class SettingsBackup(
    val bodyweightLb: Int,
    val age: Int,
    val level: String,
    val emphasis: String,
    val cardioMode: String,
    val cardioPlacement: String,
    val fiveKGoal: Boolean,
    val daysPerWeek: Int,
    val split: String,
    val anchorScheme: String,
    val deadliftVariant: String,
    val equipment: List<String>,
    val weightUnit: String,
    val wizardComplete: Boolean,
    val suggestedDay: String? = null,
)

/** A user-created exercise (mirror of the `custom_exercise` row). The tracking
 *  fields are defaulted so a v1 document (which predates them) restores as a
 *  WEIGHTED exercise with no rep/time target — exactly the v1 meaning. */
@Serializable
data class CustomExerciseBackup(
    val id: String,
    val name: String,
    val pattern: String,
    val equipmentCsv: String,
    val perHand: Boolean,
    val goalStartLb: Double,
    val tracking: String = "WEIGHTED",
    val targetReps: Int? = null,
    val targetSeconds: Int? = null,
)

/** One program day with its ordered exercise slots. */
@Serializable
data class ProgramDayBackup(
    val dayId: String,
    val title: String,
    val emphasisLine: String,
    val cardioJson: String? = null,
    val exercises: List<ProgramExerciseBackup> = emptyList(),
)

@Serializable
data class ProgramExerciseBackup(
    val id: Long,
    val exerciseId: String,
    val isMain: Boolean,
    val targetSets: Int,
    val repSchemeLabel: String,
    val hasWarmupHint: Boolean,
    val supersetExerciseId: String? = null,
    val note: String,
)

/**
 * A slot's live ACTUAL log. [setsJson] is carried verbatim as the exact string
 * `:data` stores in `exercise_log.setsJson`: it is already the canonical encoding
 * (produced by the single `SetJson` codec), so passing it through unchanged makes
 * the backup a faithful copy with no risk of a re-encode drifting from the stored
 * form. [checkDate] rides along so the daily-checkmark reset behaves identically
 * after a restore.
 */
@Serializable
data class LiveLogBackup(
    val dayId: String,
    val programExerciseId: Long,
    val slot: String,
    val setsJson: String,
    val checkDate: String,
    val updatedAt: Long,
)

/** One completed workout with its denormalized performed sets. */
@Serializable
data class SessionBackup(
    val id: Long,
    val dayId: String,
    val dayTitle: String,
    val startedAt: Long? = null,
    val completedAt: Long,
    val bodyweightLb: Int,
    val sets: List<SessionSetBackup> = emptyList(),
)

@Serializable
data class SessionSetBackup(
    val id: Long,
    val exerciseId: String,
    val exerciseName: String,
    val slot: String,
    val setIndex: Int,
    val kind: String,
    val weightLb: Double,
    val reps: Int,
    val done: Boolean,
    /** Defaulted so a v1 document (no `seconds` key) restores each set at 0 —
     *  its exact v1 meaning of weight×reps. */
    val seconds: Int = 0,
)
