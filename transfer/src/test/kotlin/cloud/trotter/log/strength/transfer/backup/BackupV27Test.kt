package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupV27Test {
    private val codec = BackupCodec()
    private val rule = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, 0.05, Scheme.PROTOTYPE,
        state = SlotState(calibrating = true, successStreak = 2))
    private fun document() = BackupDocument(
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1, bodyweightLbPrecise = 200.0, effortScale = "REPS_LEFT"),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, onToday = true,
            settingsJson = PlanSettingsJson.encode(PlanSettings(LiftGoal.MUSCLE, LoadSteps(2.5, 5.0, 10.0))),
            autoApply = true, lightCycleRemaining = 4, deloadEveryCycles = 6, cyclesCompleted = 12,
            days = listOf(ProgramDayBackup("A", "Day A", "", nextRunId = "next",
                cardioRuleJson = CardioRuleJson.encode(CardioRule(3)), exercises = listOf(
                    ProgramExerciseBackup(7, "bb_back_squat", "RAMP_TOP_BACKOFF", 6, note = "",
                        keepToPlan = false, ruleJson = SlotRuleJson.encode(rule)),
                ))))),
        sessions = listOf(SessionBackup(9, "A", "Day A", completedAt = 1000, bodyweightLb = 200,
            bodyweightLbPrecise = 200.0, roughDay = true, sets = listOf(
                SessionSetBackup(10, "bb_back_squat", "Squat", "main", 0, "TOP", 235.0, 5, true, rir = 1),
            ))),
        cardioSessions = listOf(CardioSessionBackup(12, "C", "OUTDOOR_RUN", false, "Easy", 1000, 905000, 904, 1,
            effort = "GOOD", sets = listOf(CardioSetResultBackup(13,
                CardioBlockJson.encodeSet(CardioSetSpec(CardioRole.WORK, Measure.Time(900))), "FINISHED", 904,
                metresAtEnd = 3470, secondsAtEnd = 900, centis = 452, timingMethod = "PHONE", startProtocol = "THREE_POINT")))),
    )

    @Test fun `v27 preserves runCap false through codec snapshot and export`() {
        val source = document().let { doc -> doc.copy(plans = doc.plans.map {
            it.copy(settingsJson = PlanSettingsJson.encode(PlanSettings(runCap = false)))
        }) }
        val decoded = codec.decode(codec.encode(source)).toSnapshot().toDocument()
        assertEquals(27, decoded.schemaVersion)
        assertEquals(false, PlanSettingsJson.decode(decoded.plans.single().settingsJson)!!.runCap)
        assertEquals(source.plans, decoded.plans)
    }

    @Test fun `v27 round trip preserves every new field`() {
        val source = document().let { doc ->
            doc.copy(cardioSessions = doc.cardioSessions.map { it.copy(increaseApplied = true) })
        }
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source, decoded)
        val snapshot = decoded.toSnapshot()
        assertTrue(snapshot.cardioSessions.single().increaseApplied)
        assertEquals(EffortScale.REPS_LEFT, snapshot.effortScale)
        assertEquals(rule, SlotRuleJson.decode(snapshot.exercises.single().ruleJson))
        val exported = snapshot.toDocument()
        assertEquals(27, exported.schemaVersion)
        assertEquals(source.plans, exported.plans)
        assertEquals(source.sessions, exported.sessions)
        assertEquals(source.cardioSessions, exported.cardioSessions)
        assertTrue(exported.cardioSessions.single().increaseApplied)
        assertEquals(source.settings.effortScale, exported.settings.effortScale)
        assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
    }

    @Test fun `a released v27 document without increaseApplied decodes as false`() {
        val root = Json.parseToJsonElement(codec.encode(document())).jsonObject
        val sessions = root.getValue("cardioSessions").jsonArray
        val legacy = JsonObject(root + ("cardioSessions" to JsonArray(sessions.map {
            JsonObject(it.jsonObject - "increaseApplied")
        }))).toString()
        assertFalse("increaseApplied" in legacy)

        val decoded = codec.decode(legacy)

        assertEquals(27, decoded.schemaVersion)
        assertFalse(decoded.cardioSessions.single().increaseApplied)
        assertFalse(decoded.toSnapshot().cardioSessions.single().increaseApplied)
    }

    @Test fun `Undo is never exported`() {
        val snapshot = document().toSnapshot()
        val withUndo = snapshot.copy(sessions = snapshot.sessions.map { it.copy(progressionUndoJson = "x") },
            cardioSessions = snapshot.cardioSessions.map { it.copy(progressionUndoJson = "cardio journal") })
        val json = codec.encode(withUndo.toDocument())
        assertFalse("progressionUndoJson" in json)
        val restored = codec.decode(json).toSnapshot()
        assertNull(restored.sessions.single().progressionUndoJson)
        assertNull(restored.cardioSessions.single().progressionUndoJson)
        assertEquals(snapshot, restored)
    }

    private val fields = setOf("effortScale", "settingsJson", "autoApply", "lightCycleRemaining", "deloadEveryCycles",
        "cyclesCompleted", "cardioRuleJson", "ruleJson", "partnerRuleJson", "roughDay", "rir", "effort", "increaseApplied", "metresAtEnd", "secondsAtEnd",
        "centis", "timingMethod", "startProtocol")

    private fun alter(element: JsonElement, key: String?, poison: Boolean): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { alter(it, key, poison) })
        is JsonObject -> JsonObject(element.mapNotNull { (name, value) ->
            if (name in fields && (key == null || name == key)) {
                if (poison) name to JsonObject(emptyMap()) else null
            } else name to alter(value, key, poison)
        }.toMap())
        else -> element
    }

    @Test fun `v26 document without v27 fields decodes with defaults`() {
        val source = document().copy(schemaVersion = 26)
        val json = alter(Json.parseToJsonElement(codec.encode(source)), null, false).toString()
        val snapshot = codec.decode(json).toSnapshot()
        assertEquals(source.toSnapshot(), snapshot)
        assertEquals(EffortScale.WORDS, snapshot.effortScale)
        assertNull(snapshot.plans.single().settingsJson)
        assertFalse(snapshot.plans.single().autoApply)
        assertEquals(0, snapshot.plans.single().lightCycleRemaining)
        assertNull(snapshot.plans.single().deloadEveryCycles)
        assertEquals(0, snapshot.plans.single().cyclesCompleted)
        assertNull(snapshot.days.single().cardioRuleJson)
        assertNull(snapshot.exercises.single().ruleJson)
        assertFalse(snapshot.sessions.single().roughDay)
        assertNull(snapshot.sessionSets.single().rir)
        assertNull(snapshot.cardioSessions.single().effort)
        assertFalse(snapshot.cardioSessions.single().increaseApplied)
        val set = snapshot.cardioSetResults.single()
        assertEquals(listOf(null, null, null, null, null),
            listOf(set.metresAtEnd, set.secondsAtEnd, set.centis, set.timingMethod, set.startProtocol))
    }

    @Test fun `older versions ignore v27 fields even with the wrong type`() {
        for (version in 13..26) {
            val root = Json.parseToJsonElement(codec.encode(document().copy(schemaVersion = version)))
            val expected = codec.decode(root.toString()).toSnapshot()
            for (key in fields) {
                assertEquals(expected, codec.decode(alter(root, key, true).toString()).toSnapshot(), "v$version $key")
            }
        }
    }

    private fun rejectPlan(transform: (PlanBackup) -> PlanBackup) {
        val doc = document()
        reject(doc.copy(plans = doc.plans.map(transform)))
    }
    private fun rejectRule(json: String) = rejectPlan { plan ->
        plan.copy(days = plan.days.map { day -> day.copy(exercises = day.exercises.map { it.copy(ruleJson = json) }) })
    }
    private fun rejectSet(transform: (CardioSetResultBackup) -> CardioSetResultBackup) {
        val doc = document()
        reject(doc.copy(cardioSessions = doc.cardioSessions.map { it.copy(sets = it.sets.map(transform)) }))
    }
    private fun rejectRir(value: Int) {
        val doc = document()
        reject(doc.copy(sessions = doc.sessions.map { it.copy(sets = it.sets.map { set -> set.copy(rir = value) }) }))
    }
    private fun reject(doc: BackupDocument) {
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(doc)) }
    }

    @Test fun `validation rejects rule junk`() {
        rejectRule("junk")
    }

    @Test fun `validation rejects rule rir 6`() {
        rejectRule(SlotRuleJson.encode(rule).replace("\"rir\":2", "\"rir\":6"))
    }

    @Test fun `validation rejects rule exposures cadence 4`() {
        rejectRule(SlotRuleJson.encode(rule).replace("\"everyExposures\":1", "\"everyExposures\":4"))
    }

    @Test fun `validation rejects rule increment 20 percent`() {
        rejectRule(SlotRuleJson.encode(rule).replace("0.025", "0.2"))
    }

    @Test fun `validation rejects rule negative drop`() {
        rejectRule(SlotRuleJson.encode(rule).replace("0.05", "-0.01"))
    }

    @Test fun `validation rejects rule unknown role`() {
        rejectRule(SlotRuleJson.encode(rule).replace("MAIN", "BOSS"))
    }

    @Test fun `validation rejects cardio ladder level 13`() {
        rejectPlan { it.copy(days = it.days.map { day -> day.copy(cardioRuleJson = """{"ladderLevel":13}""") }) }
    }

    @Test fun `validation rejects unknown defaultsFor`() {
        rejectPlan { it.copy(settingsJson = """{"defaultsFor":"POWER"}""") }
    }

    @Test fun `validation rejects zero load step`() {
        rejectPlan { it.copy(settingsJson = """{"loadSteps":{"barLb":0,"dumbbellLb":5,"stackLb":10}}""") }
    }

    @Test fun `validation rejects negative light cycle`() {
        rejectPlan { it.copy(lightCycleRemaining = -1) }
    }

    @Test fun `validation rejects deload cadence 3`() {
        rejectPlan { it.copy(deloadEveryCycles = 3) }
    }

    @Test fun `validation rejects deload cadence 9`() {
        rejectPlan { it.copy(deloadEveryCycles = 9) }
    }

    @Test fun `validation rejects negative cycles completed`() {
        rejectPlan { it.copy(cyclesCompleted = -1) }
    }

    @Test fun `validation rejects set rir 6`() {
        rejectRir(6)
    }

    @Test fun `validation rejects set negative rir`() {
        rejectRir(-1)
    }

    @Test fun `validation rejects unknown cardio effort`() {
        val doc = document()
        reject(doc.copy(cardioSessions = doc.cardioSessions.map { it.copy(effort = "MAX") }))
    }

    @Test fun `validation rejects negative metresAtEnd`() {
        rejectSet { it.copy(metresAtEnd = -1) }
    }

    @Test fun `validation rejects negative secondsAtEnd`() {
        rejectSet { it.copy(secondsAtEnd = -1) }
    }

    @Test fun `validation rejects centis without method`() {
        rejectSet { it.copy(timingMethod = null) }
    }

    @Test fun `validation rejects lowercase timing method`() {
        rejectSet { it.copy(timingMethod = "phone") }
    }

    @Test fun `validation rejects an unknown timing method`() {
        rejectSet { it.copy(timingMethod = "STOPWATCH") }
    }

    @Test fun `validation rejects an unknown start protocol`() {
        rejectSet { it.copy(startProtocol = "CROUCH") }
    }

    @Test fun `validation rejects zero centis`() {
        rejectSet { it.copy(centis = 0) }
    }

    @Test fun `validation rejects unknown effort scale`() {
        val doc = document()
        reject(doc.copy(settings = doc.settings.copy(effortScale = "NUMBERS")))
    }
    @Test fun `partner rules round trip at v27 and versions before 27 drop them`() {
        val source = document()
        fun withPartner(partnerId: String?, json: String?) = source.copy(plans = source.plans.map { plan ->
            plan.copy(days = plan.days.map { day -> day.copy(exercises = day.exercises.map {
                it.copy(supersetExerciseId = partnerId, partnerRuleJson = json)
            }) })
        })
        val json = SlotRuleJson.encode(rule)
        val partner = withPartner("cable_row", json)
        val snapshot = codec.decode(codec.encode(partner)).toSnapshot()
        assertEquals(json, snapshot.exercises.single().partnerRuleJson)
        assertEquals(json, snapshot.toDocument().plans.single().days.single().exercises.single().partnerRuleJson)
        assertNull(codec.decode(codec.encode(partner.copy(schemaVersion = 26))).toSnapshot().exercises.single().partnerRuleJson)
        reject(withPartner("cable_row", "junk"))
        reject(withPartner(null, json))
    }

}
