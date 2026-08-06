package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.standards.RestSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A v2 backup — written before the rest-timer prefs joined the payload — must
 * still restore. v3 only *added* defaulted fields, so a checked-in v2 fixture
 * decodes into the current shape with the rest keys at their v2 meaning: the
 * timer on, no bucket pinned. Backups are a user-facing data contract; a restore
 * must never lose or misread old data.
 *
 * The v1 direction has its own fixture and test ([BackupV1RestoreTest]); a v3
 * file into a build that only knows v2 stays loudly rejected by that old build —
 * see [BackupCodecTest]'s unsupported-version test.
 */
class BackupV2RestoreTest {

    private val codec = BackupCodec()

    private fun v2Json(): String =
        checkNotNull(javaClass.getResourceAsStream("/backup/v2_backup.json")) { "missing v2 fixture" }
            .readBytes()
            .toString(Charsets.UTF_8)

    /** What the v2 fixture must decode to: the same facts, with every v3 field at
     *  its default (timer enabled, no per-category override). */
    private val expected = BackupDocument(
        schemaVersion = 2,
        settings = SettingsBackup(
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
            suggestedDay = "A",
        ),
        customExercises = listOf(
            CustomExerciseBackup(
                id = "custom_abc",
                name = "Weighted Plank",
                pattern = "CORE_ANTI_EXT",
                equipmentCsv = "BODYWEIGHT",
                perHand = false,
                goalStartLb = 0.0,
                tracking = "TIMED",
                targetReps = null,
                targetSeconds = 45,
            ),
        ),
        program = listOf(
            ProgramDayBackup(
                dayId = "A",
                title = "Day A",
                emphasisLine = "Squat-focused",
                cardioJson = null,
                exercises = listOf(
                    ProgramExerciseBackup(1, "bb_back_squat", true, 4, "5/5/5/3", true, null, ""),
                    ProgramExerciseBackup(2, "custom_abc", false, 3, "", false, null, ""),
                ),
            ),
        ),
        liveLogs = listOf(
            LiveLogBackup(
                dayId = "A",
                programExerciseId = 2,
                slot = "main",
                setsJson = "[{\"weightLb\":0.0,\"reps\":0,\"seconds\":45,\"kind\":\"WORK\",\"done\":true}]",
                checkDate = "2026-07-11",
                updatedAt = 1000,
            ),
        ),
        sessions = listOf(
            SessionBackup(
                id = 1,
                dayId = "A",
                dayTitle = "Day A",
                startedAt = 1500,
                completedAt = 2000,
                bodyweightLb = 235,
                sets = listOf(
                    SessionSetBackup(1, "bb_back_squat", "Barbell Back Squat", "main", 0, "TOP", 235.0, 5, true),
                ),
            ),
        ),
    )

    @Test
    fun `a v2 document decodes byte-equivalently modulo the new defaults`() {
        assertEquals(expected, codec.decode(v2Json()))
    }

    @Test
    fun `the rest-timer fields land at their v2-meaning defaults`() {
        val settings = codec.decode(v2Json()).settings
        assertEquals(RestSettings().enabled, settings.restTimerEnabled)
        assertTrue(settings.restOverrides().isEmpty())
    }

    @Test
    fun `it restores into a snapshot whose rest settings are the app defaults`() {
        val snapshot = codec.decode(v2Json()).toSnapshot()
        assertEquals(RestSettings(), snapshot.restSettings)
    }

    @Test
    fun `everything else in the v2 document is preserved`() {
        val snapshot = codec.decode(v2Json()).toSnapshot()
        assertEquals(235, snapshot.answers.config.bodyweightLb)
        assertEquals("A", snapshot.suggestedDay)
        assertTrue(snapshot.wizardComplete)
        assertEquals("TIMED", snapshot.customExercises.single().tracking)
        assertEquals(45, snapshot.customExercises.single().targetSeconds)
        assertEquals(1500L, snapshot.sessions.single().startedAt)
    }
}
