package io.github.sjtrotter.strengthlog.transfer.backup

import io.github.sjtrotter.strengthlog.data.FullSnapshot
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramDayEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
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

    // --- embedded payloads (the strings Room stores and decodes on every read) --

    @Test
    fun `a live log with unparseable setsJson is rejected`() {
        val doc = document(liveLogs = listOf(LiveLogBackup("A", 1, "main", "garbage", "2026-07-06", 0L)))
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `a live log with an unknown SetKind is rejected`() {
        val doc = document(
            liveLogs = listOf(
                LiveLogBackup("A", 1, "main", """[{"weightLb":100.0,"reps":5,"kind":"BOGUS","done":false}]""", "2026-07-06", 0L),
            ),
        )
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `a day with unparseable cardioJson is rejected`() {
        val doc = document(
            program = listOf(
                ProgramDayBackup(
                    dayId = "A", title = "Day A", emphasisLine = "", cardioJson = "{ not cardio",
                    exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", true, 4, "", false, null, "")),
                ),
            ),
        )
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(doc)) }
    }

    // --- duplicate primary keys (would otherwise abort the restore transaction
    // --- with a raw SQLite error instead of a typed one) ------------------------

    @Test
    fun `duplicate program exercise ids are rejected`() {
        val doc = document(
            program = listOf(
                ProgramDayBackup(
                    dayId = "A", title = "Day A", emphasisLine = "",
                    exercises = listOf(
                        ProgramExerciseBackup(1, "bb_back_squat", true, 4, "", false, null, ""),
                        ProgramExerciseBackup(1, "bb_bench", false, 3, "", false, null, ""),
                    ),
                ),
            ),
            liveLogs = emptyList(),
            sessions = emptyList(),
        )
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `duplicate session ids are rejected`() {
        val session = SessionBackup(id = 7, dayId = "A", dayTitle = "Day A", completedAt = 1L, bodyweightLb = 235)
        val doc = document(sessions = listOf(session, session.copy(completedAt = 2L)))
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `duplicate session set ids are rejected`() {
        val set = SessionSetBackup(11, "bb_back_squat", "Barbell Back Squat", "main", 0, "TOP", 235.0, 5, true)
        val doc = document(
            sessions = listOf(
                SessionBackup(
                    id = 7, dayId = "A", dayTitle = "Day A", completedAt = 1L, bodyweightLb = 235,
                    sets = listOf(set, set.copy(setIndex = 1)),
                ),
            ),
        )
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    @Test
    fun `duplicate live log keys are rejected`() {
        val log = LiveLogBackup("A", 1, "main", "[]", "2026-07-06", 0L)
        val doc = document(liveLogs = listOf(log, log.copy(updatedAt = 1L)))
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    // --- numeric and slot sanity -------------------------------------------------

    @Test
    fun `impossible numeric values are rejected`() {
        val goodSet = SessionSetBackup(11, "bb_back_squat", "Barbell Back Squat", "main", 0, "TOP", 235.0, 5, true)
        val goodSession = SessionBackup(id = 7, dayId = "A", dayTitle = "Day A", completedAt = 1L, bodyweightLb = 235, sets = listOf(goodSet))
        val badDocs = listOf(
            document(settings = settings().copy(bodyweightLb = 0)),
            document(settings = settings().copy(age = -1)),
            document(
                program = listOf(
                    ProgramDayBackup(
                        dayId = "A", title = "Day A", emphasisLine = "",
                        exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", true, 0, "", false, null, "")),
                    ),
                ),
                liveLogs = emptyList(),
            ),
            document(sessions = listOf(goodSession.copy(bodyweightLb = -5))),
            document(sessions = listOf(goodSession.copy(sets = listOf(goodSet.copy(weightLb = -1.0))))),
            document(sessions = listOf(goodSession.copy(sets = listOf(goodSet.copy(reps = -1))))),
            document(sessions = listOf(goodSession.copy(sets = listOf(goodSet.copy(setIndex = -1))))),
        )
        badDocs.forEach { doc ->
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
        }
    }

    @Test
    fun `an unknown slot kind is rejected`() {
        val badLog = document(liveLogs = listOf(LiveLogBackup("A", 1, "sideways", "[]", "2026-07-06", 0L)))
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(badLog)) }

        val badSet = document(
            sessions = listOf(
                SessionBackup(
                    id = 7, dayId = "A", dayTitle = "Day A", completedAt = 1L, bodyweightLb = 235,
                    sets = listOf(SessionSetBackup(11, "bb_back_squat", "Squat", "sideways", 0, "TOP", 235.0, 5, true)),
                ),
            ),
        )
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(badSet)) }
    }

    @Test
    fun `a session set with an unknown kind is rejected`() {
        val doc = document(
            sessions = listOf(
                SessionBackup(
                    id = 7, dayId = "A", dayTitle = "Day A", completedAt = 1L, bodyweightLb = 235,
                    sets = listOf(SessionSetBackup(11, "bb_back_squat", "Squat", "main", 0, "BOGUS", 235.0, 5, true)),
                ),
            ),
        )
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    // --- full-cycle determinism ----------------------------------------------------

    @Test
    fun `export import export is byte-identical`() {
        val snapshot = FullSnapshot(
            answers = WizardAnswers(),
            unit = WeightUnit.KG,
            wizardComplete = true,
            suggestedDay = "A",
            customExercises = listOf(
                CustomExerciseEntity(custom.id, custom.name, custom.pattern, custom.equipmentCsv, custom.perHand, custom.goalStartLb),
            ),
            days = listOf(
                ProgramDayEntity("A", 0, "Day A", "Squat-focused", null),
                ProgramDayEntity("B", 1, "Day B", "Bench-focused", """{"label":"Zone 2","detail":"20-30 min","hard":false}"""),
            ),
            exercises = listOf(
                ProgramExerciseEntity(1, "A", 0, "bb_back_squat", true, 4, "5/5/5/3", true, null, "belt"),
                ProgramExerciseEntity(2, "A", 1, custom.id, false, 3, "8-12", false, "bb_bench", ""),
                ProgramExerciseEntity(3, "B", 0, "bb_bench", true, 4, "6-10", false, null, ""),
            ),
            logs = listOf(
                ExerciseLogEntity("A", 1, "main", """[{"weightLb":235.0,"reps":5,"kind":"TOP","done":true}]""", "2026-07-06", 1_000L),
            ),
            sessions = listOf(WorkoutSessionEntity(7, "A", "Day A", null, 5_000L, 210)),
            sessionSets = listOf(SessionSetEntity(11, 7, "bb_back_squat", "Barbell Back Squat", "main", 0, "TOP", 235.0, 5, true)),
        )
        val first = codec.encode(snapshot.toDocument())
        val second = codec.encode(codec.decode(first).toSnapshot().toDocument())
        assertEquals(first, second)
    }
}
