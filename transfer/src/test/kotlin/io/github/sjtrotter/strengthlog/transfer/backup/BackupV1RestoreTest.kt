package io.github.sjtrotter.strengthlog.transfer.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A v1 backup — written before tracking types existed — must still restore. The
 * v2 additions were all defaulted fields (session-set `seconds`, custom-exercise
 * tracking/targets), so a checked-in v1 fixture decodes byte-equivalently into the
 * current shape with those fields at their v1-meaning defaults. Backups are a
 * user-facing data contract; a restore must never lose or misread old data.
 *
 * The opposite direction (a v2 file into a build that only knows v1) stays loudly
 * rejected by that old build — see [BackupCodecTest]'s unsupported-version test.
 */
class BackupV1RestoreTest {

    private val codec = BackupCodec()

    private fun v1Json(): String =
        checkNotNull(javaClass.getResourceAsStream("/backup/v1_backup.json")) { "missing v1 fixture" }
            .readBytes()
            .toString(Charsets.UTF_8)

    /** What the v1 fixture must decode to: the same facts, with every v2 field at
     *  its default (tracking WEIGHTED, null targets, seconds 0). */
    private val expected = BackupDocument(
        schemaVersion = 1,
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
            CustomExerciseBackup("custom_abc", "Cable Hack Squat", "SQUAT_BILATERAL", "MACHINE,CABLE", false, 80.0),
        ),
        program = listOf(
            ProgramDayBackup(
                dayId = "A",
                title = "Day A",
                emphasisLine = "Squat-focused",
                cardioJson = null,
                exercises = listOf(
                    ProgramExerciseBackup(1, "bb_back_squat", true, 4, "5/5/5/3", true, null, ""),
                    ProgramExerciseBackup(2, "plank", false, 3, "", false, null, ""),
                ),
            ),
        ),
        liveLogs = listOf(
            LiveLogBackup(
                dayId = "A",
                programExerciseId = 2,
                slot = "main",
                setsJson = "[{\"weightLb\":0.0,\"reps\":45,\"kind\":\"WORK\",\"done\":true}]",
                checkDate = "2026-07-11",
                updatedAt = 1000,
            ),
        ),
        sessions = listOf(
            SessionBackup(
                id = 1,
                dayId = "A",
                dayTitle = "Day A",
                startedAt = null,
                completedAt = 2000,
                bodyweightLb = 235,
                sets = listOf(
                    SessionSetBackup(1, "bb_back_squat", "Barbell Back Squat", "main", 0, "TOP", 235.0, 5, true),
                ),
            ),
        ),
    )

    @Test
    fun `a v1 document decodes byte-equivalently modulo the new defaults`() {
        assertEquals(expected, codec.decode(v1Json()))
    }

    @Test
    fun `the new fields land at their v1-meaning defaults`() {
        val decoded = codec.decode(v1Json())
        assertEquals("WEIGHTED", decoded.customExercises.single().tracking)
        assertNull(decoded.customExercises.single().targetReps)
        assertNull(decoded.customExercises.single().targetSeconds)
        assertEquals(0, decoded.sessions.single().sets.single().seconds)
    }

    @Test
    fun `it restores into a snapshot with the defaulted fields`() {
        val snapshot = codec.decode(v1Json()).toSnapshot()
        assertEquals("WEIGHTED", snapshot.customExercises.single().tracking)
        assertEquals(0, snapshot.sessionSets.single().seconds)
    }
}
