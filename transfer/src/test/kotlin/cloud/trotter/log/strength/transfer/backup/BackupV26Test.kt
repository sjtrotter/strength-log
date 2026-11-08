package cloud.trotter.log.strength.transfer.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupV26Test {
    private val codec = BackupCodec()
    private fun document() = BackupDocument(
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "AUTHORED", 1000, days = listOf(
            ProgramDayBackup("A", "Day A", "", nextRunId = "open-run", exercises = listOf(
                ProgramExerciseBackup(7, "bb_back_squat", "STRAIGHT", 3, note = "Belt on top",
                    noteUpdatedAt = 1000),
            )),
        ))),
        runs = listOf(RunBackup("open-run", 1, "A", "Day A", "Plan", 2000, tracks = listOf(
            RunTrackBackup(11, 0, 7, "main", "hack_squat", plannedJson = "[]", setsJson = "[]",
                updatedAt = 3000, note = "Seat 4"),
        ))),
    )

    @Test fun `v26 round trip preserves lift note timestamps and run track notes`() {
        val snapshot = codec.decode(codec.encode(document())).toSnapshot()
        assertEquals(1000L, snapshot.exercises.single().noteUpdatedAt)
        assertEquals("Belt on top", snapshot.exercises.single().note)
        assertEquals("Seat 4", snapshot.runTracks.single().note)

        val exported = snapshot.toDocument()
        assertEquals(26, exported.schemaVersion)
        assertEquals(1000L, exported.plans.single().days.single().exercises.single().noteUpdatedAt)
        assertEquals("Seat 4", exported.runs.single().tracks.single().note)
        assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
    }

    @Test fun `v25 document without lift note fields decodes with defaults`() {
        val encoded = codec.encode(document().copy(schemaVersion = 25))
            .replace(",\"noteUpdatedAt\":1000", "")
            .replace(",\"note\":\"Seat 4\"", "")
        val decoded = codec.decode(encoded)
        assertEquals(25, decoded.schemaVersion)
        assertNull(decoded.plans.single().days.single().exercises.single().noteUpdatedAt)
        assertEquals("", decoded.runs.single().tracks.single().note)
        val snapshot = decoded.toSnapshot()
        assertEquals("Belt on top", snapshot.exercises.single().note)
        assertNull(snapshot.exercises.single().noteUpdatedAt)
        assertEquals("", snapshot.runTracks.single().note)
        assertEquals(snapshot, codec.decode(codec.encode(snapshot.toDocument())).toSnapshot())
    }

    @Test fun `older versions ignore future lift note fields even with the wrong type`() {
        for (version in 13..25) {
            val source = document().copy(schemaVersion = version)
            val snapshot = source.toSnapshot()
            assertNull(snapshot.exercises.single().noteUpdatedAt)
            assertEquals("", snapshot.runTracks.single().note)
            val poisoned = codec.encode(source)
                .replace("\"noteUpdatedAt\":1000", "\"noteUpdatedAt\":{}")
                .replace("\"note\":\"Seat 4\"", "\"note\":{}")
            val restored = codec.decode(poisoned).toSnapshot()
            assertEquals(snapshot, restored, "schema v$version")
        }
    }

    @Test fun `v26 without optional lift note fields decodes with defaults`() {
        val encoded = codec.encode(document())
            .replace(",\"noteUpdatedAt\":1000", "")
            .replace(",\"note\":\"Seat 4\"", "")
        val snapshot = codec.decode(encoded).toSnapshot()
        assertNull(snapshot.exercises.single().noteUpdatedAt)
        assertEquals("", snapshot.runTracks.single().note)
    }
}
