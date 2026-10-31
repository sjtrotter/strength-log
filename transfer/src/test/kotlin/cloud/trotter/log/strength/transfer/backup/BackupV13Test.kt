package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Run fields round-trip in v13 and remain unknown to older backup versions. */
class BackupV13Test {
    private val codec = BackupCodec()
    private val planned = listOf(LoggedSet(100.0, 5, SetKind.WORK))
    private val actual = listOf(planned.single().copy(done = true, plannedIndex = 0, watchEditedAt = 3000))
    private val track = RunTrackBackup(11, 0, 7, "main", "bb_back_squat", false,
        SetJson.encodeSets(planned), SetJson.encodeSets(actual), 3000)
    private val partner = track.copy(id = 12, slot = "ss", exerciseId = "ez_curl", retired = true)
    private val run = RunBackup("open-run", 1, "A", "Day A", "Plan", 1000, listOf(track, partner))
    private val session = SessionBackup(
        id = 20, dayId = "A", dayTitle = "Day A", startedAt = 100, completedAt = 500, bodyweightLb = 200,
        sets = listOf(SessionSetBackup(30, "bb_back_squat", "Squat", "main", 0, "WORK", 100.0, 5, true,
            trackPosition = 0, plannedIndex = 0)),
        planId = 1, planName = "Plan", runId = "finished-run",
        plannedJson = SessionPlanJson.encode(listOf(PlannedTrackDto(0, "bb_back_squat", "main", sets = planned.map(SetDto::of)))),
    )
    private fun document() = BackupDocument(
        settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, days = listOf(
            ProgramDayBackup("A", "Day A", "", nextRunId = run.id, closedAt = 500,
                exercises = listOf(ProgramExerciseBackup(7, "bb_back_squat", false, 3, "10", false, "ez_curl", ""))),
            ProgramDayBackup("B", "Day B", "", nextRunId = "preview-B"),
        ))),
        runs = listOf(run), sessions = listOf(session),
    )
    private fun decode(document: BackupDocument) = codec.decode(codec.encode(document))

    /** [F] */
    @Test fun `v13 round trip maps runs tracks day identities and archive fields`() {
        val before = document()
        val decoded = decode(before)
        assertEquals(13, decoded.schemaVersion)
        assertEquals(before, decoded)
        val snapshot = decoded.toSnapshot()
        assertEquals(run.id, snapshot.runs.single().id)
        assertEquals(listOf(11L, 12L), snapshot.runTracks.map { it.id })
        assertTrue(snapshot.runTracks.all { it.runId == run.id })
        assertEquals(listOf(track.plannedJson, partner.plannedJson), snapshot.runTracks.map { it.plannedJson })
        assertEquals(listOf(track.setsJson, partner.setsJson), snapshot.runTracks.map { it.setsJson })
        assertEquals(listOf(run.id, "preview-B"), snapshot.days.map { it.nextRunId })
        assertEquals(500L, snapshot.days.first().closedAt)
        assertEquals(session.runId, snapshot.sessions.single().runId)
        assertEquals(session.plannedJson, snapshot.sessions.single().plannedJson)
        assertEquals(0, snapshot.sessionSets.single().trackPosition)
        assertEquals(0, snapshot.sessionSets.single().plannedIndex)
        assertEquals(before, snapshot.toDocument())
        assertEquals(codec.encode(before), codec.encode(snapshot.toDocument()))
    }

    /** [F] */
    @Test fun `v1 through v12 strip run fields even with incorrect types`() {
        val legacySettings = codec.decode(checkNotNull(javaClass.getResource("/backup/v7_backup.json")).readText()).settings
        for (version in 1..12) {
            val baseline = document().let { current ->
                current.copy(
                    schemaVersion = version,
                    settings = if (version < 9) legacySettings.copy(selectedPlanId = if (version >= 8) 1 else null, suggestedDay = null) else current.settings,
                    plans = if (version < 8) emptyList() else current.plans,
                    program = if (version < 8) current.plans.single().days else emptyList(),
                )
            }
            val expected = decode(baseline)
            val root = Json.parseToJsonElement(codec.encode(baseline)).jsonObject
            fun poisonDays(value: JsonArray) = JsonArray(value.map { day ->
                JsonObject(day.jsonObject + mapOf("nextRunId" to JsonPrimitive(3), "closedAt" to JsonObject(emptyMap())))
            })
            val poisoned = JsonObject(root.toMutableMap().apply {
                put("runs", JsonPrimitive(5))
                put("program", poisonDays(root.getValue("program").jsonArray))
                put("plans", JsonArray(root.getValue("plans").jsonArray.map { plan ->
                    JsonObject(plan.jsonObject + ("days" to poisonDays(plan.jsonObject.getValue("days").jsonArray)))
                }))
                put("sessions", JsonArray(root.getValue("sessions").jsonArray.map { row ->
                    JsonObject(row.jsonObject + mapOf(
                        "runId" to JsonPrimitive(3), "plannedJson" to JsonPrimitive(5),
                        "sets" to JsonArray(row.jsonObject.getValue("sets").jsonArray.map { set ->
                            JsonObject(set.jsonObject + mapOf("trackPosition" to JsonPrimitive("x"), "plannedIndex" to JsonObject(emptyMap())))
                        }),
                    ))
                }))
            })
            assertEquals(expected, codec.decode(poisoned.toString()), "schema v$version")
            assertTrue(expected.runs.isEmpty())
            assertEquals(expected.toSnapshot(), baseline.toSnapshot(), "schema v$version mapping")
        }
    }

    /** [F] */
    @Test fun `blank day run identities round trip until import mints them`() {
        val valid = document()
        val plan = valid.plans.single()
        val blankPreviews = valid.copy(plans = listOf(plan.copy(
            days = listOf(plan.days.first(), plan.days.last().copy(nextRunId = "")),
        )))
        assertEquals(blankPreviews, decode(blankPreviews))
        val blankDays = valid.copy(
            plans = listOf(plan.copy(days = plan.days.map { it.copy(nextRunId = "") })),
            runs = emptyList(),
        )
        assertEquals(blankDays, decode(blankDays))
        val whitespaceDays = blankDays.copy(plans = listOf(plan.copy(
            days = plan.days.map { it.copy(nextRunId = " ") },
        )))
        assertEquals(whitespaceDays, decode(whitespaceDays))
    }

    /** [F] */
    @Test fun `multiple detached blank run and duplicate non-blank day identities reject`() {
        val valid = document()
        val plan = valid.plans.single()
        val day = plan.days.first()
        val inconsistent = listOf(
            valid.copy(runs = listOf(run, run.copy(id = "another"))),
            valid.copy(runs = listOf(run.copy(id = "mismatch"))),
            valid.copy(runs = listOf(run.copy(id = ""))),
            valid.copy(
                plans = listOf(plan.copy(days = listOf(day.copy(nextRunId = "")))),
                runs = listOf(run.copy(id = "")),
            ),
            valid.copy(runs = listOf(run.copy(planId = 2))),
            valid.copy(plans = listOf(plan.copy(days = listOf(day, plan.days.last().copy(nextRunId = day.nextRunId))))),
            valid.copy(sessions = listOf(session, session.copy(id = 21, sets = emptyList()))),
            valid.copy(sessions = listOf(session.copy(runId = " "))),
            valid.copy(runs = listOf(run.copy(tracks = listOf(track.copy(slot = "other"))))),
        )
        inconsistent.forEach { assertFailsWith<BackupError.Inconsistent> { decode(it) } }
    }

    /** [F] */
    @Test fun `duplicate run track ids reject`() {
        val duplicate = run.copy(tracks = listOf(track, partner.copy(id = track.id)))
        assertFailsWith<BackupError.Inconsistent> { decode(document().copy(runs = listOf(duplicate))) }
    }

    /** [F] */
    @Test fun `duplicate run track keys with distinct ids reject`() {
        val duplicate = run.copy(tracks = listOf(track, track.copy(id = 13)))
        assertFailsWith<BackupError.Inconsistent> { decode(document().copy(runs = listOf(duplicate))) }
    }

    /** [F] */
    @Test fun `duplicate run track keys with null program exercise ids reject`() {
        val emptyTrack = track.copy(programExerciseId = null)
        val duplicate = run.copy(tracks = listOf(emptyTrack, emptyTrack.copy(id = 13)))
        assertFailsWith<BackupError.Inconsistent> { decode(document().copy(runs = listOf(duplicate))) }
    }

    /** [F] */
    @Test fun `session run id reused by an attached day rejects`() {
        val collision = document().copy(sessions = listOf(session.copy(runId = run.id)))
        assertFailsWith<BackupError.Inconsistent> { decode(collision) }
    }

    /** [F] */
    @Test fun `session run id reused by a preview day rejects`() {
        val valid = document().copy(runs = emptyList())
        val previewId = valid.plans.single().days.last().nextRunId
        val collision = valid.copy(sessions = listOf(session.copy(runId = previewId)))
        assertFailsWith<BackupError.Inconsistent> { decode(collision) }
    }

    /** [F] */
    @Test fun `undecodable track prescriptions and actuals are malformed`() {
        for (invalid in listOf(track.copy(setsJson = "x"), track.copy(plannedJson = "x"))) {
            assertFailsWith<BackupError.Malformed> { decode(document().copy(runs = listOf(run.copy(tracks = listOf(invalid))))) }
        }
    }
}
