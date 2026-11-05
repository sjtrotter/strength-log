package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupV18Test {
    private val codec = BackupCodec()
    private val working = """[ { "weightLb": 245.0, "reps": 5, "kind": "TOP", "done": false } ]"""
    private val parked = ParkedLogsJson.encode(listOf(
        ParkedTrackDto(Slot.MAIN, "bb_back_squat", working),
        ParkedTrackDto(Slot.SS, "ez_curl", SetJson.encodeSets(listOf(LoggedSet(55.0, 10, SetKind.WORK)))),
    ))

    private fun document(parkedLogsJson: String? = parked) = BackupDocument(
        settings = SettingsBackup(211, 33, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1, bodyweightLbPrecise = 210.5),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, onToday = true, days = listOf(
            ProgramDayBackup("A", "Day A", "", exercises = listOf(
                ProgramExerciseBackup(7, "bb_back_squat", "RAMP_TOP_BACKOFF", 6,
                    supersetExerciseId = "ez_curl", note = "", keepToPlan = true, parkedLogsJson = parkedLogsJson),
            )),
        ))),
        sessions = listOf(SessionBackup(9, "A", "Day A", completedAt = 2000,
            bodyweightLb = 211, bodyweightLbPrecise = 210.5)),
    )

    @Test fun `v18 export and restore preserve parked working values verbatim`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(18, exported.schemaVersion)
        assertEquals(parked, exported.plans.single().days.single().exercises.single().parkedLogsJson)

        val restored = codec.decode(codec.encode(exported)).toSnapshot()

        assertEquals(parked, restored.exercises.single().parkedLogsJson)
        assertEquals(snapshot, restored)
        assertEquals(210.5, restored.answers.config.bodyweightLb)
        assertEquals(210.5, restored.sessions.single().bodyweightLb)
    }

    @Test fun `v17 without parked values restores null through v18 export`() {
        val legacy = document(null).copy(schemaVersion = 17)
        val encoded = codec.encode(legacy).replace(",\"parkedLogsJson\":null", "")
        val snapshot = codec.decode(encoded).toSnapshot()
        assertNull(snapshot.exercises.single().parkedLogsJson)
        assertEquals(210.5, snapshot.answers.config.bodyweightLb)
        assertEquals(210.5, snapshot.sessions.single().bodyweightLb)
        val exported = snapshot.toDocument()
        assertEquals(18, exported.schemaVersion)
        assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
    }

    @Test fun `v18 without the optional parked field restores null`() {
        val encoded = codec.encode(document(null)).replace(",\"parkedLogsJson\":null", "")
        assertNull(codec.decode(encoded).toSnapshot().exercises.single().parkedLogsJson)
    }

    @Test fun `older versions ignore future parked fields even with the wrong type`() {
        for (version in 9..17) {
            val encoded = codec.encode(document(null).copy(schemaVersion = version))
            val poisoned = encoded.replace("\"parkedLogsJson\":null", "\"parkedLogsJson\":{}")
            val expected = codec.decode(encoded).toSnapshot()
            assertEquals(expected, codec.decode(poisoned).toSnapshot(), "schema v$version")
            assertNull(expected.exercises.single().parkedLogsJson)
        }
    }

    @Test fun `malformed parked payloads are rejected before restore`() {
        val invalidSets = ParkedLogsJson.encode(listOf(ParkedTrackDto(Slot.MAIN, "bb_back_squat", "bad sets")))
        for (payload in listOf("bad park", invalidSets)) {
            assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(document(payload))) }
        }
    }
}
