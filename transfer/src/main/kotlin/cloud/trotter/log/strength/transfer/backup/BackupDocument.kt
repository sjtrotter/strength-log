package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.standards.RestSettings
import kotlinx.serialization.Serializable

/**
 * The schema version of the JSON produced by [BackupCodec.encode]. A reader
 * dispatches on the document's `schemaVersion` field (see [BackupCodec.decode]):
 * anything other than this value is rejected loudly with
 * [BackupError.UnsupportedSchemaVersion] rather than being misread. Bump this and
 * add a `when` branch when the on-disk shape changes; an older document is read
 * by the same decoder and reinterpreted only where a field's *meaning* changed
 * (v5's session bodyweight), never by a parallel set of old model classes.
 */
const val CURRENT_SCHEMA_VERSION: Int = 7

/** The domain's own rest-timer defaults, so [SettingsBackup]'s v3 defaults are
 *  read from the one place that owns them rather than restated as literals. */
private val REST_DEFAULTS = RestSettings()

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
    val cardioSessions: List<CardioSessionBackup> = emptyList(),
)

@Serializable
data class CardioSessionBackup(
    val id: Long,
    val dayId: String? = null,
    val mode: String,
    val hard: Boolean,
    val label: String,
    val startedAt: Long,
    val completedAt: Long,
    val seconds: Int,
    val stepsCompleted: Int,
)

/**
 * Every DataStore preference a user can set. Enum-valued facts are stored by
 * *name* (not ordinal) so reordering an enum later can't silently reinterpret an
 * old backup — the same forward-compat rule `:data` already applies to its
 * stored enums.
 *
 * Two stored keys are deliberately absent, and both are settings the user never
 * chose: the session-start stamp (transient — a restore can't be mid-workout)
 * and the one-shot legacy-TIMED fixup flag (the fixup only moves an unfixed set,
 * so re-running it after a restore is a no-op).
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
    /** The master rest-timer gate (v3). Defaulted to the domain's own default so
     *  a v1/v2 document — which has no rest keys at all — restores with the
     *  timer behaving exactly as it does on a fresh install. */
    val restTimerEnabled: Boolean = REST_DEFAULTS.enabled,
    /** The five per-category rest overrides (v3), in [RestCategory][cloud.trotter.log.strength.domain.standards.RestCategory]
     *  order. `null` is not "unknown" — it is the stored state: the user never
     *  pinned that bucket, so [RestPolicy][cloud.trotter.log.strength.domain.standards.RestPolicy]
     *  supplies the number. Carrying the absence rather than today's number is
     *  what lets a restored device keep following new defaults shipped in a
     *  later update, which is the whole point of the absent key in DataStore. */
    val restRampSeconds: Int? = null,
    val restTopSeconds: Int? = null,
    val restBackoffSeconds: Int? = null,
    val restWorkSeconds: Int? = null,
    val restLightSeconds: Int? = null,
    /** Keep-screen-on (v4). Defaulted to the same absent-means-off meaning the
     *  DataStore key has, so a v1-v3 document — written when this was a
     *  session-only flag that no backup could carry — restores with the screen
     *  behaving exactly as it does on a fresh install. */
    val keepScreenOn: Boolean = false,
    val topSetHelperSeen: Boolean = false,
    val supersetHelperSeen: Boolean = false,
    /** Temporary DataStore-backed session notes; no schema-version bump while
     *  Room/backup v7 is reserved by the parallel cardio work. */
    val sessionNotes: Map<Long, String> = emptyMap(),
    /** Theme preference. Defaulted so pre-light-theme backups follow SYSTEM. */
    val theme: String = "SYSTEM",
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
    val kind: String = "STRENGTH",
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
    /** The bodyweight the session recorded, or null when it recorded none —
     *  CSV-imported history (v5, #171). A pre-v5 document says the same thing
     *  with a 0, which [BackupCodec.decode] maps to null on the way in. */
    val bodyweightLb: Int? = null,
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
    /** The set's wrist-observed start/complete millis (#85). Null-defaulted so an
     *  older document restores them as "not observed" — which is what those sets
     *  are. The live logs need no counterpart: they carry `setsJson` verbatim. */
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
)
