package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.mapping.sides
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SideDto
import cloud.trotter.log.strength.data.serialization.SidesDto
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupV33Test {
    private val codec = BackupCodec()
    private val sides = SidesDto(SideDto(0.0, 0, 40), SideDto(0.0, 0, 52))
    private val row = LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 40, sides = sides.toDomain())
    private val payload = SetJson.encodeSets(listOf(row))
    private fun document(version: Int = CURRENT_SCHEMA_VERSION, split: Boolean = true) = BackupDocument(
        schemaVersion = version,
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, onToday = true,
            days = listOf(ProgramDayBackup("A", "Day A", "", exercises = listOf(
                ProgramExerciseBackup(7, "side_plank", "STRAIGHT", 2, note = ""),
            ))))),
        sessions = listOf(SessionBackup(9, "A", "Day A", completedAt = 1000, bodyweightLb = 200,
            sets = listOf(SessionSetBackup(10, "side_plank", "Side Plank", "main", 0, "WORK", 0.0, 0, true,
                seconds = 40, sides = sides.takeIf { split })))),
    )
    private fun withLive(doc: BackupDocument, setsJson: String) = doc.copy(liveLogs = listOf(
        LiveLogBackup(1, "A", 7, "main", setsJson, updatedAt = 1000),
    ))

    @Test fun `case 43 v33 round trips each side results`() {
        val source = withLive(document(), payload)
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source, decoded)
        assertEquals(sides.toDomain(), decoded.toSnapshot().sessionSets.single().sides())
        assertEquals(40, decoded.toSnapshot().sessionSets.single().seconds)
        assertFalse(codec.encode(document(split = false)).contains("\"sides\""))
    }

    @Test fun `case 43 a v32 document restores sets with no sides`() {
        val decoded = codec.decode(codec.encode(document(version = 32, split = false)))
        val set = decoded.toSnapshot().sessionSets.single()
        assertNull(set.sides())
        assertEquals(List<Any?>(6) { null }, listOf(set.leftWeightLb, set.leftReps, set.leftSeconds,
            set.rightWeightLb, set.rightReps, set.rightSeconds))
        assertEquals(40, set.seconds)
        assertEquals(0.0, set.weightLb)
        assertEquals(0, set.reps)
        assertTrue(set.done)
    }

    @Test fun `case 43 a set that is not its weaker side is inconsistent`() {
        val source = document().let { doc -> doc.copy(sessions = doc.sessions.map { session ->
            session.copy(sets = session.sets.map { it.copy(seconds = 52) })
        }) }
        val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(source)) }
        assertTrue(error.message.orEmpty().contains("does not match its sides"))
    }

    @Test fun `older documents with sides are inconsistent`() {
        for (version in listOf(27, 30, 32)) {
            val plain = document(version, split = false)
            codec.decode(codec.encode(plain))
            codec.decode(codec.encode(withLive(plain, SetJson.encodeSets(listOf(row.copy(sides = null))))))
            for (source in listOf(document(version), withLive(plain, payload))) {
                val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(source)) }
                assertTrue(error.message.orEmpty().contains("require schema v33"))
            }
        }
    }

    @Test fun `negative side values are inconsistent`() {
        val bad = sides.copy(right = sides.right.copy(reps = -1))
        val source = document().let { doc -> doc.copy(sessions = doc.sessions.map { session ->
            session.copy(sets = session.sets.map { it.copy(sides = bad) })
        }) }
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(source)) }
    }

    @Test fun `live planned and parked payloads validate their sides`() {
        val badPayload = payload.replaceFirst("\"seconds\":40", "\"seconds\":52")
        val badRow = row.copy(seconds = 52)
        val planned = SessionPlanJson.encode(listOf(PlannedTrackDto(0, "side_plank", "main", sets = listOf(SetDto.of(badRow)))))
        val plain = document(split = false)
        val plannedDoc = plain.copy(sessions = plain.sessions.map { it.copy(plannedJson = planned) })
        val parkedDoc = plain.copy(plans = plain.plans.map { plan -> plan.copy(days = plan.days.map { day ->
            day.copy(exercises = day.exercises.map { it.copy(parkedLogsJson = ParkedLogsJson.encode(listOf(
                ParkedTrackDto("main", "side_plank", badPayload),
            ))) })
        }) })
        for (source in listOf(withLive(plain, badPayload), plannedDoc, parkedDoc)) {
            val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(source)) }
            assertTrue(error.message.orEmpty().contains("does not match its sides"))
        }
    }

    @Test fun `legacy shaping cannot hide sides in future payloads`() {
        val planned = SessionPlanJson.encode(listOf(PlannedTrackDto(0, "side_plank", "main", sets = listOf(SetDto.of(row)))))
        for (version in listOf(1, 12, 26, 32)) {
            val plain = document(version, split = false)
            val source = plain.copy(sessions = plain.sessions.map { it.copy(plannedJson = planned) })
            val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(source)) }
            assertTrue(error.message.orEmpty().contains("require schema v33"))
        }
    }
}
