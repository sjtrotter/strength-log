package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.standards.RestSettings
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The schema version of the JSON produced by [BackupCodec.encode]. A reader
 * dispatches on the document's `schemaVersion` field (see [BackupCodec.decode]):
 * newer versions are rejected loudly with
 * [BackupError.UnsupportedSchemaVersion] rather than being misread. Bump this and
 * add a `when` branch when the on-disk shape changes; an older document is read
 * by the same decoder and reinterpreted only where a field's *meaning* changed
 * (v5's session bodyweight), never by a parallel set of old model classes;
 * v8 nests the program under plans; v9 carries each plan's recipe and rotation;
 * v10 carries authored prescriptions; v11 carries authored cardio blocks;
 * v12 carries cardio activity and per-set results.
 * v13 carries workout runs, each day's run identity, and archived prescriptions.
 * v14 carries each slot's next-time choice and each session's note.
 * v15 adds each plan’s onToday membership.
 */
const val CURRENT_SCHEMA_VERSION: Int = 15

/** The domain's own rest-timer defaults, so [SettingsBackup]'s v3 defaults are
 *  read from the one place that owns them rather than restated as literals. */
private val REST_DEFAULTS = RestSettings()

/**
 * The portable, versioned full backup (PLAN.md A2): one self-contained document
 * carrying everything a user owns — preferences + wizard answers + display unit,
 * every custom exercise, every plan with its per-slot live logs, and the whole
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
    val plans: List<PlanBackup> = emptyList(),
    /** A v1–v7 document's one program, restored as one plan; empty in v8 and later. */
    val program: List<ProgramDayBackup> = emptyList(),
    val liveLogs: List<LiveLogBackup> = emptyList(),
    val sessions: List<SessionBackup> = emptyList(),
    val cardioSessions: List<CardioSessionBackup> = emptyList(),
    val runs: List<RunBackup> = emptyList(),
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
    /** The plan this session ran under, or null for pre-plans history and CSV imports. */
    val planId: Long? = null,
    /** Plan name copied at completion, so history survives a rename or archive. */
    val planName: String? = null,
    val activity: String? = null,
    val sets: List<CardioSetResultBackup> = emptyList(),
)

/** Position is list order; ids and the stored spec survive a full restore verbatim. */
@Serializable
data class CardioSetResultBackup(
    val id: Long,
    val specJson: String,
    val status: String,
    val elapsedSeconds: Int,
    val metres: Int? = null,
    val distanceSource: String? = null,
)

/**
 * Every DataStore preference a user can set. Enum-valued facts are stored by
 * *name* (not ordinal) so reordering an enum later can't silently reinterpret an
 * old backup — the same forward-compat rule `:data` already applies to its
 * stored enums.
 *
 * v1–v8 carry the one program's recipe here; v9 leaves those fields null and
 * carries each plan's recipe and rotation in [PlanBackup].
 *
 * Two stored keys are absent, and both are settings the user never chose: the
 * session-start stamp (transient — a restore can't be mid-workout) and the
 * one-shot legacy-TIMED fixup flag (the fixup only moves an unfixed set, so
 * re-running it after a restore is a no-op).
 */
@Serializable
data class SettingsBackup(
    val bodyweightLb: Int,
    val age: Int,
    val level: String,
    val emphasis: String? = null,
    val cardioMode: String? = null,
    val cardioPlacement: String? = null,
    val fiveKGoal: Boolean? = null,
    val daysPerWeek: Int? = null,
    val split: String? = null,
    val anchorScheme: String? = null,
    val deadliftVariant: String? = null,
    val equipment: List<String>? = null,
    val weightUnit: String,
    val wizardComplete: Boolean,
    val suggestedDay: String? = null,
    val selectedPlanId: Long? = null,
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
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
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

/** One saved plan with its ordered days (v8). Position is the list order. */
@Serializable
data class PlanBackup(
    val id: Long,
    val name: String,
    val origin: String,
    val createdAt: Long,
    val archived: Boolean = false,
    val days: List<ProgramDayBackup> = emptyList(),
    /** Plan settings serialized as PlanRecipeDto; also used to regenerate generated templates. */
    val recipeJson: String? = null,
    /** The plan's rotation position. */
    val suggestedDay: String? = null,
    val onToday: Boolean? = null,
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
    val cardioBlockJson: String? = null,
    val nextRunId: String = "",
    val closedAt: Long? = null,
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
    val prescriptionJson: String? = null,
    val keepToPlan: Boolean? = null,
)

/**
 * A slot's working values. [setsJson] is carried verbatim as the exact string
 * `:data` stores in `exercise_log.setsJson`: it is already the canonical encoding
 * (produced by the single `SetJson` codec), so passing it through unchanged makes
 * the backup a faithful copy with no risk of a re-encode drifting from the stored
 * form. A non-null [checkDate] identifies legacy results that need splitting on restore.
 */
@Serializable
data class LiveLogBackup(
    /** Null in v1–v7, where every log belongs to the one program. */
    val planId: Long? = null,
    val dayId: String,
    val programExerciseId: Long,
    val slot: String,
    val setsJson: String,
    val checkDate: String? = null,
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
     *  CSV-imported history (v5). A pre-v5 document says the same thing with
     *  a 0, which [BackupCodec.decode] maps to null on the way in. */
    val bodyweightLb: Int? = null,
    val sets: List<SessionSetBackup> = emptyList(),
    /** The plan this session ran under, or null for pre-plans history and CSV imports. */
    val planId: Long? = null,
    /** Plan name copied at completion, so history survives a rename or archive. */
    val planName: String? = null,
    val runId: String? = null,
    val plannedJson: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val note: String = "",
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
    /** The set's wrist-observed start/complete millis. Null-defaulted so an older
     *  document restores them as "not observed" — which is what those sets are.
     *  The live logs need no counterpart: they carry `setsJson` verbatim. */
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val trackPosition: Int? = null,
    val plannedIndex: Int? = null,
)

/** An open workout and its ordered captured tracks. */
@Serializable
data class RunBackup(
    val id: String,
    val planId: Long?,
    val dayId: String?,
    val dayTitle: String,
    val planName: String?,
    val startedAt: Long,
    val tracks: List<RunTrackBackup> = emptyList(),
)

/** A run track's prescription, actuals, and persistent identity. */
@Serializable
data class RunTrackBackup(
    val id: Long,
    val position: Int,
    val programExerciseId: Long?,
    val slot: String,
    val exerciseId: String,
    val retired: Boolean = false,
    val plannedJson: String,
    val setsJson: String,
    val updatedAt: Long,
)
