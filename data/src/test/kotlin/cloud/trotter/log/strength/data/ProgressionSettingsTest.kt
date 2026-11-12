package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressionSettingsTest : FinishProgressionFixture() {
    @Test fun `converted plan switched to Automatic applies Finish and retains Offer preferences`() = runTest {
        val plan = converted(day("bb_bench"))
        val bench = id(plan, "bb_bench")
        lifter(plan, bench)
        repo.plans.setOfferStrengthIncreases(plan, false)
        repo.plans.setOfferCardioIncreases(plan, true)
        repo.plans.setAutoApply(plan, true)
        assertNull(rule(bench).nextTime)
        val run = start(plan)
        perform(run, plan, bench, 185.0)
        repo.applyToRun(run, plan, "A", listOf(TrackEdit(bench, Slot.MAIN) { actual ->
            actual.map { it.copy(rir = 2) }
        }))
        assertNotNull(repo.finishRun(run))
        assertEquals(work(w = 190.0), rows(plan, bench))
        repo.plans.setAutoApply(plan, false)
        val stored = assertNotNull(db.planDao().plan(plan))
        assertFalse(stored.offerStrengthIncreases)
        assertTrue(stored.offerCardioIncreases)
    }

    @Test fun `a matching Next time choice follows the plan and Ask on Automatic is explicit`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        repo.editRule(bench, Slot.MAIN, RuleEdit.NextTime(NextTimeMode.ASK))
        assertEquals(NextTimeMode.ASK, rule(bench).nextTime)
        repo.editRule(bench, Slot.MAIN, RuleEdit.NextTime(NextTimeMode.AUTO))
        assertNull(rule(bench).nextTime)
    }

    @Test fun `unprescribed Plan and Reset refuse without writing`() = runTest {
        val plan = converted(day("bb_back_squat"))
        val squat = id(plan, "bb_back_squat")
        val before = db.programDao().exercise(squat)
        val logs = db.programDao().allLogs()
        assertFalse(repo.setKeepToPlan(squat, true))
        assertFalse(repo.resetSlotToPlan(squat))
        assertEquals(before, db.programDao().exercise(squat))
        assertEquals(logs, db.programDao().allLogs())
    }

    @Test fun `legacy unprescribed Plan mode can restore its parked working rows and choose Ask`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        lifter(plan, bench)
        db.programDao().setParkedLogs(bench, ParkedLogsJson.encode(listOf(
            ParkedTrackDto(Slot.MAIN, "bb_bench", SetJson.encodeSets(work())))))
        db.programDao().deleteLogForSlot(plan, "A", bench, Slot.MAIN)
        db.programDao().setKeepToPlan(bench, true)
        assertTrue(repo.setKeepToPlan(bench, false))
        repo.editRule(bench, Slot.MAIN, RuleEdit.NextTime(NextTimeMode.ASK))
        assertEquals(work(), rows(plan, bench))
        assertEquals(NextTimeMode.ASK, rule(bench).nextTime)
        assertFalse(db.programDao().exercise(bench)!!.keepToPlan)
    }

    @Test fun `prescribed Plan parks rows and Reset refuses a run`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        repo.setPrescription(bench, Prescription(PrescribedTarget.LoadReps(175.0, 5)))
        lifter(plan, bench)
        assertTrue(repo.setKeepToPlan(bench, true))
        assertNotNull(db.programDao().exercise(bench)!!.parkedLogsJson)
        assertTrue(repo.setKeepToPlan(bench, false))
        assertEquals(work(), rows(plan, bench))
        start(plan)
        assertFalse(repo.resetSlotToPlan(bench))
    }

    @Test fun `Rest edits use the timer resolver and clearing uses role and bucket defaults`() = runTest {
        val plan = converted(day("bb_back_squat"))
        val squat = id(plan, "bb_back_squat")
        repo.editRule(squat, Slot.MAIN, RuleEdit.Rest(150))
        assertEquals(150, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, emptyMap(), rule(squat), LiftGoal.STRENGTH))
        repo.editRule(squat, Slot.MAIN, RuleEdit.Rest(null))
        assertEquals(180, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED, emptyMap(), rule(squat), LiftGoal.STRENGTH))
        assertEquals(200, RestPolicy.effectiveRestSeconds(SetKind.WORK, TrackingType.WEIGHTED,
            mapOf(RestCategory.TOP to 200), rule(squat), LiftGoal.STRENGTH))
        repo.editRule(squat, Slot.MAIN, RuleEdit.RepsLeft(3))
        repo.editRule(squat, Slot.MAIN, RuleEdit.Increase(0.05))
        assertEquals(3, rule(squat).rir)
        assertEquals(0.05, rule(squat).incrementPct)
    }

    @Test fun `structure and prescription carry all four settings including old defaults and swap carries only Next time`() = runTest {
        val plan = authored()
        repo.plans.setDefaultsFor(plan, LiftGoal.GENERAL)
        val squat = add(plan, "hack_squat")
        assertEquals(SlotRole.ACCESSORY, rule(squat).role)
        assertEquals(2, rule(squat).rir)
        repo.editRule(squat, Slot.MAIN, RuleEdit.Rest(150))
        repo.editRule(squat, Slot.MAIN, RuleEdit.NextTime(NextTimeMode.ASK))
        val before = rule(squat)
        repo.setStructure(squat, SetStructure.RAMP_TOP)
        assertEquals(SlotRole.MAIN, rule(squat).role)
        assertSettings(before, rule(squat))
        repo.setPrescription(squat, Prescription(PrescribedTarget.LoadReps(185.0, 5)))
        assertSettings(before, rule(squat))
        repo.swapExerciseById("A", squat, "bb_back_squat")
        assertEquals(NextTimeMode.ASK, rule(squat).nextTime)
        assertNull(rule(squat).restSeconds)
        assertEquals(3, rule(squat).rir)
    }

    @Test fun `partner swap carries Next time and resets the replaced lift settings`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        repo.setSupersetPartner("A", bench, "cable_row")
        repo.editRule(bench, Slot.SS, RuleEdit.NextTime(NextTimeMode.ASK))
        repo.editRule(bench, Slot.SS, RuleEdit.Rest(150))
        repo.editRule(bench, Slot.SS, RuleEdit.RepsLeft(3))
        repo.editRule(bench, Slot.SS, RuleEdit.Increase(0.05))
        repo.setSupersetPartner("A", bench, "db_row")
        val replacement = cloud.trotter.log.strength.data.serialization.SlotRuleJson.decode(
            db.programDao().exercise(bench)!!.partnerRuleJson)!!
        assertEquals(NextTimeMode.ASK, replacement.nextTime)
        assertNull(replacement.restSeconds)
        assertEquals(2, replacement.rir)
        assertEquals(0.0125, replacement.incrementPct)
    }

    @Test fun `plan settings merge their JSON and light week accepts Off and six rotations`() = runTest {
        val plan = authored()
        repo.plans.setRunCap(plan, false)
        repo.plans.setDefaultsFor(plan, LiftGoal.MUSCLE)
        val stored = db.planDao().plan(plan)!!
        assertEquals(LiftGoal.MUSCLE, stored.planSettings.defaultsFor)
        assertFalse(stored.planSettings.runCap)
        assertEquals(stored.planSettings, PlanSettingsJson.decode(stored.settingsJson))
        repo.plans.setLightWeek(plan, 6)
        assertEquals(6, db.planDao().plan(plan)!!.deloadEveryCycles)
        repo.plans.setLightWeek(plan, null)
        assertNull(db.planDao().plan(plan)!!.deloadEveryCycles)
        for (invalid in listOf(3, 9)) assertTrue(runCatching { repo.plans.setLightWeek(plan, invalid) }.isFailure)
    }

    @Test fun `Run cap opens at 5500 meters when on and keeps 6000 when off`() = runTest {
        val planId = authored()
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(5000))
        val session = CardioSessionEntity(id = 1, planId = planId, dayId = "A", mode = "OUTDOOR_RUN",
            activity = CardioActivity.ROAD_RUN.name, hard = false, label = "Run", startedAt = clock.millis() - 3600000,
            completedAt = clock.millis() - 1800000, seconds = 1800, stepsCompleted = 1)
        val result = CardioSetResult(spec, CardioSetStatus.FINISHED, 1800, metres = 5000,
            distanceSource = cloud.trotter.log.strength.domain.model.DistanceSource.MANUAL)
        val day = ProgramDay("A", "Run", "", emptyList(), cardioBlock = CardioBlock(CardioActivity.ROAD_RUN,
            listOf(spec.copy(end = Measure.Distance(6000)))))
        fun distance(plan: cloud.trotter.log.strength.data.db.entity.PlanEntity) = CardioJudge.opening(day, plan,
            listOf(session), mapOf(1L to listOf(result)), clock.millis(), Math.floorDiv(clock.millis(), 86400000))!!.sets.single().end
        assertEquals(Measure.Distance(5500), distance(db.planDao().plan(planId)!!))
        repo.plans.setRunCap(planId, false)
        assertEquals(Measure.Distance(6000), distance(db.planDao().plan(planId)!!))
    }

    private fun assertSettings(before: cloud.trotter.log.strength.domain.progression.SlotRule,
                               after: cloud.trotter.log.strength.domain.progression.SlotRule) {
        assertEquals(before.nextTime, after.nextTime)
        assertEquals(before.restSeconds, after.restSeconds)
        assertEquals(before.rir, after.rir)
        assertEquals(before.incrementPct, after.incrementPct)
    }
}
