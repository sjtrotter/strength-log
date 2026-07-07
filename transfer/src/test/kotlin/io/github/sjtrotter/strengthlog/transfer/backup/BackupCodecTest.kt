package io.github.sjtrotter.strengthlog.transfer.backup

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the A2 backup core: deterministic encoding, versioned
 * decode dispatch, and the defensive validation that must reject a bad file
 * before it can ever reach the database. The repository-level round-trip and
 * "DB untouched after a failed import" guarantees live in the instrumented
 * [BackupRoundTripTest] / [ImportRejectionTest].
 */
class BackupCodecTest {

    private val codec = BackupCodec()

    private fun settings(suggestedDay: String? = "A") = SettingsBackup(
        bodyweightLb = 235,
        age = 40,
        level = "INTERMEDIATE",
        emphasis = "BALANCED",
        cardioMode = "OUTDOOR_RUN",
        cardioPlacement = "FINISHERS",
        fiveKGoal = true,
        daysPerWeek = 4,
        split = "FULL_BODY",
        anchorScheme = "PROTOTYPE",
        deadliftVariant = "TRAP_BAR",
        equipment = listOf("BARBELL", "DUMBBELL"),
        weightUnit = "LB",
        wizardComplete = true,
        suggestedDay = suggestedDay,
    )

    private val custom = CustomExerciseBackup(
        id = ExerciseCatalog.CUSTOM_ID_PREFIX + "abc123",
        name = "Cable Hack Squat",
        pattern = "SQUAT_BILATERAL",
        equipmentCsv = "MACHINE,CABLE",
        perHand = false,
        goalStartLb = 80.0,
    )

    private fun document(
        settings: SettingsBackup = settings(),
        customExercises: List<CustomExerciseBackup> = listOf(custom),
        program: List<ProgramDayBackup> = listOf(
            ProgramDayBackup(
                dayId = "A",
                title = "Day A",
                emphasisLine = "Squat-focused",
                cardioJson = null,
                exercises = listOf(
                    ProgramExerciseBackup(1, "bb_back_squat", true, 4, "5/5/5/3", true, null, ""),
                    // A slot that supersets and references the backup's own custom exercise.
                    ProgramExerciseBackup(2, custom.id, false, 3, "8-12", false, "bb_bench", "note"),
                ),
            ),
        ),
        liveLogs: List<LiveLogBackup> = listOf(
            LiveLogBackup("A", 1, "main", """[{"weightLb":235.0,"reps":5,"kind":"TOP","done":true}]""", "2026-07-06", 1_000L),
        ),
        sessions: List<SessionBackup> = listOf(
            SessionBackup(
                id = 7, dayId = "A", dayTitle = "Day A", startedAt = null, completedAt = 5_000L, bodyweightLb = 235,
                sets = listOf(SessionSetBackup(11, "bb_back_squat", "Barbell Back Squat", "main", 0, "TOP", 235.0, 5, true)),
            ),
        ),
    ) = BackupDocument(
        settings = settings,
        customExercises = customExercises,
        program = program,
        liveLogs = liveLogs,
        sessions = sessions,
    )

    @Test
    fun `encode is deterministic`() {
        val doc = document()
        assertEquals(codec.encode(doc), codec.encode(doc))
    }

    @Test
    fun `encode then decode round-trips the document`() {
        val doc = document()
        assertEquals(doc, codec.decode(codec.encode(doc)))
    }

    @Test
    fun `a valid backup referencing its own custom exercise passes validation`() {
        // The superset partner (custom.id) resolves only via the backup's own
        // customExercises, proving they join the resolvable set.
        val decoded = codec.decode(codec.encode(document()))
        assertTrue(decoded.customExercises.any { it.id == custom.id })
    }

    @Test
    fun `an unknown schema version is rejected loudly`() {
        val text = codec.encode(document()).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":999")
        val e = assertFailsWith<BackupError.UnsupportedSchemaVersion> { codec.decode(text) }
        assertEquals(999, e.found)
        assertEquals(CURRENT_SCHEMA_VERSION, e.supported)
    }

    @Test
    fun `corrupt JSON is rejected as malformed`() {
        assertFailsWith<BackupError.Malformed> { codec.decode("{ not json at all") }
    }

    @Test
    fun `truncated JSON is rejected as malformed`() {
        val text = codec.encode(document())
        assertFailsWith<BackupError.Malformed> { codec.decode(text.substring(0, text.length / 2)) }
    }

    @Test
    fun `a document missing schemaVersion is malformed`() {
        assertFailsWith<BackupError.Malformed> { codec.decode("""{"settings":{}}""") }
    }

    @Test
    fun `a non-object top level is malformed`() {
        assertFailsWith<BackupError.Malformed> { codec.decode("[1,2,3]") }
    }

    @Test
    fun `an oversized file is rejected before parsing`() {
        val tiny = BackupCodec(maxBytes = 64)
        val text = codec.encode(document())
        assertTrue(text.length > 64)
        val e = assertFailsWith<BackupError.TooLarge> { tiny.decode(text) }
        assertEquals(64, e.maxBytes)
    }

    @Test
    fun `a dangling program exercise id is rejected`() {
        val doc = document(
            program = listOf(
                ProgramDayBackup(
                    dayId = "A", title = "Day A", emphasisLine = "",
                    exercises = listOf(ProgramExerciseBackup(1, "not_a_real_exercise", false, 3, "", false, null, "")),
                ),
            ),
            liveLogs = emptyList(),
            sessions = emptyList(),
            customExercises = emptyList(),
        )
        val e = assertFailsWith<BackupError.DanglingExerciseReference> { codec.decode(codec.encode(doc)) }
        assertEquals("not_a_real_exercise", e.exerciseId)
    }

    @Test
    fun `a dangling superset id is rejected`() {
        val doc = document(
            program = listOf(
                ProgramDayBackup(
                    dayId = "A", title = "Day A", emphasisLine = "",
                    exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", true, 3, "", false, "ghost", "")),
                ),
            ),
            liveLogs = emptyList(),
            sessions = emptyList(),
            customExercises = emptyList(),
        )
        val e = assertFailsWith<BackupError.DanglingExerciseReference> { codec.decode(codec.encode(doc)) }
        assertEquals("ghost", e.exerciseId)
    }

    @Test
    fun `a custom exercise without the custom prefix is rejected`() {
        val doc = document(customExercises = listOf(custom.copy(id = "abc123")))
        assertFailsWith<BackupError.InvalidCustomExercise> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `a custom exercise with an unknown pattern is rejected`() {
        val doc = document(customExercises = listOf(custom.copy(pattern = "NOT_A_PATTERN")))
        assertFailsWith<BackupError.InvalidCustomExercise> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `a suggestedDay that is not a program day is rejected`() {
        val doc = document(settings = settings(suggestedDay = "Z"))
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `an out-of-range daysPerWeek is rejected`() {
        val doc = document(settings = settings().copy(daysPerWeek = 9))
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `a live log pointing at a non-existent slot is rejected`() {
        val doc = document(liveLogs = listOf(LiveLogBackup("A", 999, "main", "[]", "2026-07-06", 0L)))
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `a session set may reference an unresolvable exercise id`() {
        // History denormalizes the name, so a since-deleted exercise id is valid.
        val doc = document(
            sessions = listOf(
                SessionBackup(
                    id = 1, dayId = "A", dayTitle = "Day A", completedAt = 1L, bodyweightLb = 235,
                    sets = listOf(SessionSetBackup(1, "custom_deleted", "Deleted Move", "main", 0, "WORK", 100.0, 8, true)),
                ),
            ),
        )
        // Must not throw despite 'custom_deleted' resolving to nothing.
        assertEquals(doc, codec.decode(codec.encode(doc)))
    }
}
