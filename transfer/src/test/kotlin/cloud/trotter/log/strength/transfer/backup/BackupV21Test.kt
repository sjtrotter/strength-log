package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.DistanceUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupV21Test {
    private val codec = BackupCodec()
    private fun block(seconds: Int) = CardioBlockJson.encode(CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds)))))
    private val day = ProgramDayBackup("A", "Run", "", cardioBlockJson = block(1200),
        cardioWorkingJson = block(1260), cardioKeepToPlan = true, cardioProgressBy = "TIME", cardioIncreasePercent = 7)
    private fun document(note: String = "Easy") = BackupDocument(
        settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1, distanceUnit = "KM"),
        plans = listOf(PlanBackup(1, "Running", "AUTHORED", 1000, days = listOf(day), onToday = true,
            offerStrengthIncreases = false, offerCardioIncreases = true)),
        cardioSessions = listOf(CardioSessionBackup(
            id = 7, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Road run",
            startedAt = 1000, completedAt = 2413000, seconds = 2412, stepsCompleted = 1,
            activity = "ROAD_RUN", note = note,
        )),
    )

    @Test fun `v21 export and restore preserve the cardio note and distance choice`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(21, exported.schemaVersion)
        assertEquals("Easy", exported.cardioSessions.single().note)
        assertEquals("KM", exported.settings.distanceUnit)
        assertEquals(day, exported.plans.single().days.single())
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        assertEquals("Easy", restored.cardioSessions.single().note)
        assertEquals(DistanceUnit.KM, restored.distanceUnit)
        assertEquals(snapshot, restored)
    }

    @Test fun `v18 through v20 without cardio notes restore through v21 export`() {
        for (version in 18..20) {
            val encoded = codec.encode(document("").copy(schemaVersion = version)).replace(",\"note\":\"\"", "")
            val decoded = codec.decode(encoded)
            assertEquals(version, decoded.schemaVersion)
            val snapshot = decoded.toSnapshot()
            assertEquals("", snapshot.cardioSessions.single().note)
            assertEquals(if (version >= 19) DistanceUnit.KM else null, snapshot.distanceUnit)
            assertEquals(if (version >= 20) block(1260) else null, snapshot.days.single().cardioWorkingJson)
            assertEquals(if (version >= 20) "TIME" else null, snapshot.days.single().cardioProgressBy)
            assertEquals(if (version >= 20) 7 else null, snapshot.days.single().cardioIncreasePercent)
            assertEquals(version >= 20, snapshot.days.single().cardioKeepToPlan)
            assertEquals(version < 20, snapshot.plans.single().offerStrengthIncreases)
            assertEquals(version >= 20, snapshot.plans.single().offerCardioIncreases)
            val exported = snapshot.toDocument()
            assertEquals(21, exported.schemaVersion)
            assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
        }
    }

    @Test fun `older versions ignore future cardio notes even with the wrong type`() {
        for (version in 9..20) {
            val encoded = codec.encode(document().copy(schemaVersion = version))
            val poisoned = encoded.replace("\"note\":\"Easy\"", "\"note\":{}")
            val expected = codec.decode(encoded).toSnapshot()
            assertEquals(expected, codec.decode(poisoned).toSnapshot(), "schema v$version")
            assertEquals(expected, document().copy(schemaVersion = version).toSnapshot(), "schema v$version")
            assertEquals("", expected.cardioSessions.single().note)
        }
    }

    @Test fun `v21 rejects malformed cardio notes`() {
        val encoded = codec.encode(document()).replace("\"note\":\"Easy\"", "\"note\":{}")
        assertFailsWith<BackupError.Malformed> { codec.decode(encoded) }
    }

    @Test fun `v21 without a cardio note restores an empty note`() {
        val encoded = codec.encode(document("")).replace(",\"note\":\"\"", "")
        assertEquals("", codec.decode(encoded).toSnapshot().cardioSessions.single().note)
    }
}
