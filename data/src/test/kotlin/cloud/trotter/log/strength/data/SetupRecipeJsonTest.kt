package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.LiftTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.RecentNumber
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RecentRace
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.WeekLoad
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.planner.RunReplan
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.domain.standards.TestRef

class SetupRecipeJsonTest {
    private val answers = SetupAnswers(goal = LiftGoal.MUSCLE, days = 4, minutes = 75,
        experience = LiftExperience.UNDER_A_YEAR, olderAdult = true, unit = WeightUnit.KG,
        steps = LoadSteps.defaults(WeightUnit.KG), equipment = EquipmentPreset.DUMBBELLS.equipment,
        mains = Planner.defaultMains(EquipmentPreset.DUMBBELLS.equipment),
        recent = mapOf(Planner.defaultMains(EquipmentPreset.DUMBBELLS.equipment)[2] to RecentNumber(80.0, 8, Effort.GOOD)))
    private val settings = PlanSettings(equipment = answers.equipment, loadSteps = answers.steps)

    @Test fun `lift tests are optional v2 recipe facts with no required weight`() {
        val lifts = listOf(LiftTest("bb_bench", 5), LiftTest("trap_dl", 3))
        val answers = SetupAnswers(lifts = lifts)
        val json = SetupRecipeJson.encode(answers)
        SetupRecipeJson.validate(json)
        assertEquals(lifts, SetupRecipeJson.lifts(json))
        assertEquals(lifts, SetupRecipeJson.decode(json, PlanSettings(), WeightUnit.LB)!!.lifts)
        val old = JsonObject(Json.parseToJsonElement(json).jsonObject - "lifts").toString()
        assertEquals(emptyList(), SetupRecipeJson.lifts(old))
        for (invalid in listOf("bb_bench" to 0, "bb_bench" to 11, "back_ext" to 5, "unknown" to 5)) {
            val bad = json.replace("\"exerciseId\":\"bb_bench\",\"reps\":5",
                "\"exerciseId\":\"${invalid.first}\",\"reps\":${invalid.second}")
            assertFailsWith<IllegalArgumentException> { SetupRecipeJson.validate(bad) }
        }
    }

    @Test fun `answers round trip without gear`() {
        val json = SetupRecipeJson.encode(answers)
        assertEquals(answers, SetupRecipeJson.decode(json, settings, WeightUnit.KG))
        val keys = Json.parseToJsonElement(json).jsonObject.keys
        assertTrue(json.contains("\"v\":2"))
        assertTrue(keys.intersect(setOf("equipment", "steps", "unit")).isEmpty())
    }

    @Test fun `isV2`() {
        assertTrue(SetupRecipeJson.isV2(SetupRecipeJson.encode(answers)))
        for (json in listOf(OldWizardPlans.DEFAULT.recipeJson, "{}", """{"v":"2"}""", "not json", "[]", """{"v":2.0}""")) {
            assertFalse(SetupRecipeJson.isV2(json), json)
        }
    }

    @Test fun `setup and plan readers agree on absent empty and saved equipment`() {
        val json = SetupRecipeJson.encode(answers)
        for (equipment in listOf(null, emptySet(), EquipmentPreset.DUMBBELLS.equipment)) {
            val planSettings = PlanSettings(equipment = equipment)
            val expected = equipment ?: Equipment.entries.toSet()
            assertEquals(expected, assertNotNull(SetupRecipeJson.decode(json, planSettings, WeightUnit.KG)).equipment)
            for (recipe in listOf(null, json)) {
                val plan = PlanEntity(1, "Plan", PlanOrigin.GENERATED.name, 0, 0,
                    recipeJson = recipe, settingsJson = PlanSettingsJson.encode(planSettings))
                assertEquals(expected, plan.planSettings.equipmentOrAll)
            }
        }
    }

    @Test fun `sprint recipes round trip`() {
        // PR 7f: Sprint and Both sprint encoding refused → round trip with every sprint answer.
        for (branch in listOf(PlanBranch.SPRINT, PlanBranch.BOTH)) {
            val input = SetupAnswers(branch = branch, sprint = SprintAnswers(SprintEvent.YD40, true, true, true),
                both = if (branch == PlanBranch.BOTH) BothAnswers(sprint = true) else BothAnswers())
            val json = SetupRecipeJson.encode(input)
            val decoded = assertNotNull(SetupRecipeJson.decode(json, PlanSettings(), WeightUnit.LB))
            assertEquals(input, decoded)
            assertTrue(decoded.careful)
            assertFalse("run" in Json.parseToJsonElement(json).jsonObject)
        }
        for (branch in listOf(PlanBranch.RUN, PlanBranch.BOTH)) {
            assertNotNull(SetupRecipeJson.decode(SetupRecipeJson.encode(SetupAnswers(branch = branch)), PlanSettings(), WeightUnit.LB))
        }
    }

    @Test fun `sprint recipes validate strictly`() {
        val sprint = SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.SPRINT))
        assertEquals("""{"v":2,"branch":"SPRINT","goal":"GENERAL","days":3,"minutes":60,"experience":"NEW","sprint":{"event":"M100"}}""", sprint)
        val both = SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.BOTH, both = BothAnswers(sprint = true)))
        val invalid = listOf(sprint.replace(",\"sprint\":{\"event\":\"M100\"}", ""),
            sprint.replace("M100", "M50"), both.dropLast(1) + ",\"run\":{\"goal\":\"CARDIO_FITNESS\",\"experience\":\"NEW\"}}",
            sprint.dropLast(1) + ",\"runPhase\":\"LADDER\"}")
        for (json in invalid) {
            assertNull(SetupRecipeJson.decode(json, PlanSettings(), WeightUnit.LB))
            assertFailsWith<IllegalArgumentException> { SetupRecipeJson.validate(json) }
        }
        SetupRecipeJson.validate(sprint)
        SetupRecipeJson.validate(both)
        assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.SPRINT), RunPhase.LADDER) }
    }

    @Test fun `lift run and both recipes keep their bytes`() {
        val fields = "\"goal\":\"GENERAL\",\"days\":3,\"minutes\":60,\"experience\":\"NEW\""
        val run = "\"run\":{\"goal\":\"CARDIO_FITNESS\",\"experience\":\"NEW\"}"
        assertEquals("{\"v\":2,\"branch\":\"LIFT\",$fields}", SetupRecipeJson.encode(SetupAnswers()))
        assertEquals("{\"v\":2,\"branch\":\"RUN\",$fields,$run}", SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.RUN)))
        assertEquals("{\"v\":2,\"branch\":\"BOTH\",$fields,$run,\"both\":{}}",
            SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.BOTH)))
    }

    @Test fun `decode is tolerant and validate is strict`() {
        val json = SetupRecipeJson.encode(answers)
        val invalid = listOf(
            json.replace("\"v\":2", "\"v\":3"), json.replace("MUSCLE", "POWER"),
            json.replace("\"days\":4", "\"days\":7"), json.replace("\"minutes\":75", "\"minutes\":50"),
            json.replace("\"reps\":8", "\"reps\":11"), json.replace("80.0", "0"),
            json.replace("GOOD", "GRINDER"), json.replace("LIFT", "RUN"), json.replace("UNDER_A_YEAR", "EXPERT"),
        )
        for (payload in invalid) {
            assertNull(SetupRecipeJson.decode(payload, settings, WeightUnit.KG), payload)
            assertFailsWith<IllegalArgumentException>(payload) { SetupRecipeJson.validate(payload) }
        }
    }

    @Test fun `a v1 recipe still validates through PlanRecipeJson`() {
        PlanRecipeJson.validate(OldWizardPlans.DEFAULT.recipeJson)
        PlanRecipeJson.validate(SetupRecipeJson.encode(answers))
        assertFailsWith<IllegalArgumentException> {
            PlanRecipeJson.validate(SetupRecipeJson.encode(answers).replace("MUSCLE", "POWER"))
        }
    }

    @Test fun `accepted balance days round trip`() {
        // audit 2026-11-13 decision 12: acceptance survives reset and backup.
        val accepted = answers.copy(balanceDays = true)
        assertEquals(accepted, SetupRecipeJson.decode(SetupRecipeJson.encode(accepted), settings, WeightUnit.KG))
    }
    @Test fun `field readers are tolerant`() {
        assertEquals(true, SetupRecipeJson.olderAdult(SetupRecipeJson.encode(SetupAnswers(olderAdult = true))))
        assertEquals(false, SetupRecipeJson.olderAdult(SetupRecipeJson.encode(SetupAnswers())))
        val v1 = OldWizardPlans.DEFAULT.recipeJson
        assertNull(SetupRecipeJson.olderAdult(v1))
        assertEquals(LiftExperience.NEW, SetupRecipeJson.experience(SetupRecipeJson.encode(SetupAnswers())))
        assertNull(SetupRecipeJson.experience("""{"v":2,"experience":"GURU"}"""))
        assertNull(SetupRecipeJson.experience(v1))
        assertEquals(true, SetupRecipeJson.olderAdult("""{"v":2,"olderAdult":true,"experience":{}}"""))
        assertEquals(LiftExperience.NEW, SetupRecipeJson.experience("""{"v":2,"olderAdult":{},"experience":"NEW"}"""))
        assertEquals(false, SetupRecipeJson.olderAdult("""{"v":2,"olderAdult":{}}"""))
        for (json in listOf("not json", "[]", """{"v":"2"}""")) {
            assertNull(SetupRecipeJson.olderAdult(json))
            assertNull(SetupRecipeJson.experience(json))
        }
    }
    private val run = RunAnswers(goal = RunGoal.HALF_MARATHON, experience = RunExperience.SOME, event = RunEvent.HALF,
        dateEpochDay = 21_000, goalSeconds = 7200, recent = RecentRace(10_000, 3300), longest = Measure.Distance(12_000),
        activity = CardioActivity.TRAIL_RUN, runningInjury = true, strengthBlocks = 1)

    @Test fun `run and both answers round trip`() {
        for (branch in listOf(PlanBranch.RUN, PlanBranch.BOTH)) {
            val input = answers.copy(branch = branch, run = run,
                both = if (branch == PlanBranch.BOTH) BothAnswers(true, BothAnswers.Together.SEPARATE_DAYS) else BothAnswers())
            // 4b-ii-b: phased Run and Both recipe fixtures now supply HALF_BUILD instead of an absent phase.
            assertEquals(input, SetupRecipeJson.decode(SetupRecipeJson.encode(input, RunPhase.HALF_BUILD), settings, WeightUnit.KG))
        }
    }

    @Test fun `run recipes validate strictly`() {
        // 4b-ii-b: the strict run fixture now carries HALF_BUILD instead of an absent phase.
        val json = SetupRecipeJson.encode(answers.copy(branch = PlanBranch.RUN, run = run), RunPhase.HALF_BUILD)
        val invalid = listOf(
            SetupRecipeJson.encode(answers).replace("LIFT", "RUN"), json.replace("\"branch\":\"RUN\"", "\"branch\":\"LIFT\""),
            json.replace(",\"recentSeconds\":3300", ""),
            json.replace("\"longestMetres\":12000", "\"longestMetres\":12000,\"longestSeconds\":3600"),
            json.replace("\"strengthBlocks\":1", "\"strengthBlocks\":3"), json.replace("\"event\":\"HALF\"", "\"event\":\"K5\""),
            json.replace("TRAIL_RUN", "UNKNOWN_ACTIVITY"), json.replace("\"experience\":\"SOME\"", "\"experience\":\"EXPERT\""),
            json.replace("\"branch\":\"RUN\"", "\"branch\":\"BOTH\""),
        )
        for (payload in invalid) {
            assertNull(SetupRecipeJson.decode(payload, settings, WeightUnit.KG), payload)
            assertFailsWith<IllegalArgumentException>(payload) { SetupRecipeJson.validate(payload) }
        }
    }

    @Test fun `run recipes carry their phase`() {
        val input = answers.copy(branch = PlanBranch.RUN, run = run)
        val json = SetupRecipeJson.encode(input, RunPhase.HALF_BUILD)
        assertEquals(RunPhase.HALF_BUILD, SetupRecipeJson.phase(json))
        val updated = SetupRecipeJson.withPhase(json, RunPhase.BASE)
        assertEquals(RunPhase.BASE, SetupRecipeJson.phase(updated))
        assertEquals(input, SetupRecipeJson.decode(updated, settings, WeightUnit.KG))
        val originalFields = Json.parseToJsonElement(json).jsonObject - "runPhase"
        assertEquals(originalFields, Json.parseToJsonElement(updated).jsonObject - "runPhase")
        // PR 7a: a missing phase stores nothing to re-plan, even on a phased goal.
        val phaseFree = SetupRecipeJson.encode(input)
        assertNull(SetupRecipeJson.phase(phaseFree))
        assertEquals(input, SetupRecipeJson.decode(phaseFree, settings, WeightUnit.KG))
        assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(input, RunPhase.ULTRA_BUILD) }
        assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(answers, RunPhase.BASE) }
        assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.RUN), RunPhase.BASE) }
        val missing = json.replace(",\"runPhase\":\"HALF_BUILD\"", "")
        // PR 7a: absent HALF_BUILD is valid; the three off-path payloads stay invalid.
        SetupRecipeJson.validate(missing)
        assertEquals(input, SetupRecipeJson.decode(missing, settings, WeightUnit.KG))
        val invalid = listOf(json.replace("HALF_BUILD", "ULTRA_BUILD"),
            SetupRecipeJson.encode(answers).dropLast(1) + ",\"runPhase\":\"BASE\"}",
            SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.RUN)).dropLast(1) + ",\"runPhase\":\"BASE\"}")
        for (payload in invalid) {
            assertNull(SetupRecipeJson.phase(payload))
            assertNull(SetupRecipeJson.decode(payload, settings, WeightUnit.KG))
            assertFailsWith<IllegalArgumentException> { SetupRecipeJson.validate(payload) }
        }
    }

    @Test fun `lift recipes keep their encoded bytes`() {
        assertEquals("""{"v":2,"branch":"LIFT","goal":"GENERAL","days":3,"minutes":60,"experience":"NEW"}""",
            SetupRecipeJson.encode(SetupAnswers()))
    }

    @Test fun `a recipe may omit its run phase`() {
        val input = answers.copy(branch = PlanBranch.RUN, run = run)
        val json = SetupRecipeJson.encode(input)
        SetupRecipeJson.validate(json)
        assertNull(SetupRecipeJson.phase(json))
        assertEquals(input, SetupRecipeJson.decode(json, settings, WeightUnit.KG))
    }

    @Test fun `ladder only answers store LADDER alone`() {
        for (experience in listOf(RunExperience.NEW, RunExperience.SOME)) {
            val input = SetupAnswers(branch = PlanBranch.RUN, run = RunAnswers(experience = experience))
            val json = SetupRecipeJson.encode(input, RunPhase.LADDER)
            assertEquals(RunPhase.LADDER, SetupRecipeJson.phase(json))
            assertEquals(input, SetupRecipeJson.decode(json, PlanSettings(), WeightUnit.LB))
            assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(input, RunPhase.BASE) }
        }
        val rider = SetupAnswers(branch = PlanBranch.RUN, run = RunAnswers(activity = CardioActivity.STATIONARY_BIKE))
        val racer = SetupAnswers(branch = PlanBranch.RUN, run = RunAnswers(experience = RunExperience.RACING))
        for (input in listOf(rider, racer)) {
            assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(input, RunPhase.LADDER) }
        }
        val invalid = SetupRecipeJson.encode(rider).dropLast(1) + ",\"runPhase\":\"LADDER\"}"
        assertFailsWith<IllegalArgumentException> { SetupRecipeJson.validate(invalid) }
    }

    @Test fun `a Racing half recipe stored on LADDER still decodes`() {
        val input = SetupAnswers(branch = PlanBranch.RUN, days = 3,
            run = RunAnswers(RunGoal.HALF_MARATHON, RunExperience.RACING))
        assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(input, RunPhase.LADDER) }
        val json = SetupRecipeJson.encode(input).dropLast(1) + ",\"runPhase\":\"LADDER\"}"
        SetupRecipeJson.validate(json)
        assertEquals(RunPhase.LADDER, SetupRecipeJson.phase(json))
        assertEquals(input, SetupRecipeJson.decode(json, PlanSettings(), WeightUnit.LB))
        val stored = Planner.plan(input.copy(run = input.run.copy(experience = RunExperience.NEW))).program.let { p ->
            p.copy(days = p.days.map { day ->
                if (day.cardioRule?.ladderLevel == null) day else day.copy(cardioRule = CardioRule(null),
                    cardioBlock = RunWalkLadder.block(12, CardioActivity.ROAD_RUN))
            })
        }
        val known = KnownLoads.NONE.copy(longestRecent = mapOf(CardioFamily.RUN to RecentMaxima(null, 1800)),
            longestRunning = mapOf(CardioFamily.RUN to 1800))
        val result = assertNotNull(RunReplan.next(stored, input, RunPhase.LADDER, known, 21000,
            WeekLoad(RecentMaxima(null, 5400), RecentMaxima(null, 1800)), null))
        assertEquals(RunPhase.BASE, result.phase)
    }

    @Test fun `withPhase null removes the phase and keeps every other field`() {
        val json = SetupRecipeJson.encode(answers.copy(branch = PlanBranch.RUN, run = run), RunPhase.HALF_BUILD)
            .dropLast(1) + ",\"futureField\":{\"keep\":true}}"
        val updated = SetupRecipeJson.withPhase(json, null)
        assertFalse("runPhase" in Json.parseToJsonElement(updated).jsonObject)
        assertEquals(Json.parseToJsonElement(json).jsonObject - "runPhase", Json.parseToJsonElement(updated).jsonObject)
        SetupRecipeJson.validate(updated)
    }

    @Test fun `tests and their date round trip in the recipe`() {
        val input = answers.copy(tests = listOf(
            TestAnswer(TestEventGoal(
                TestRef("aft_hrp", 1, 120), 40), 24),
            TestAnswer(TestEventGoal(
                TestRef("custom_situp_window", 1, 60), 45))),
            testDateEpochDay = 21000)
        val json = SetupRecipeJson.encode(input)
        assertEquals(2, SetupRecipeJson.VERSION)
        assertEquals(input, SetupRecipeJson.decode(json, settings, WeightUnit.KG))
        assertEquals(Planner.plan(input), Planner.plan(assertNotNull(SetupRecipeJson.decode(json, settings, WeightUnit.KG))))
    }

    @Test fun `a sprint recipe with tests is invalid`() {
        val fields = """ ,"tests":[{"eventId":"aft_hrp","version":1,"windowSeconds":120,"required":40}] }"""
        for (branch in listOf(PlanBranch.SPRINT, PlanBranch.BOTH, PlanBranch.RUN)) {
            val input = SetupAnswers(branch = branch,
                both = if (branch == PlanBranch.BOTH) BothAnswers(sprint = true) else BothAnswers())
            val json = SetupRecipeJson.encode(input).dropLast(1) + fields
            assertFailsWith<IllegalArgumentException> { SetupRecipeJson.validate(json) }
            assertNull(SetupRecipeJson.decode(json, PlanSettings(), WeightUnit.LB))
        }
    }

}
