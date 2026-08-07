package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A v3 backup — written before the keep-screen-on preference joined the
 * payload — must still restore. v4 only added a defaulted field, so a checked-in
 * v3 fixture decodes into the current shape with keepScreenOn at its v3 meaning:
 * false. Backups are a user-facing data contract; a restore must never lose or
 * misread old data.
 */
class BackupV3RestoreTest {

    private val codec = BackupCodec()

    private fun v3Json(): String =
        checkNotNull(javaClass.getResourceAsStream("/backup/v3_backup.json")) { "missing v3 fixture" }
            .readBytes()
            .toString(Charsets.UTF_8)

    /** What the v3 fixture must decode to: the same facts, with the v4 field at
     *  its default (keep screen on disabled). */
    private val expected = BackupDocument(
        schemaVersion = 3,
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
            restTimerEnabled = false,
            restTopSeconds = 210,
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
    fun `a v3 document decodes byte-equivalently modulo the new default`() {
        assertEquals(expected, codec.decode(v3Json()))
    }

    @Test
    fun `the keep-screen-on field lands at its v3-meaning default`() {
        val settings = codec.decode(v3Json()).settings
        assertFalse(settings.keepScreenOn)
    }

    @Test
    fun `it restores into a snapshot with keep screen on disabled`() {
        val snapshot = codec.decode(v3Json()).toSnapshot()
        assertFalse(snapshot.keepScreenOn)
    }

    @Test
    fun `the v3 rest settings survive the restore`() {
        val snapshot = codec.decode(v3Json()).toSnapshot()
        assertEquals(
            RestSettings(enabled = false, overrides = mapOf(RestCategory.TOP to 210)),
            snapshot.restSettings,
        )
    }
}
