package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LayoffKind
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun referenceAnswers() = SetupAnswers(
    goal = LiftGoal.STRENGTH, days = 4, minutes = 60, equipment = EquipmentPreset.FULL_GYM.equipment,
    unit = WeightUnit.LB, experience = LiftExperience.A_YEAR_OR_MORE,
    mains = listOf("bb_back_squat", "trap_dl", "bb_bench", "ohp", "bb_row"),
    recent = mapOf("bb_back_squat" to RecentNumber(275.0, 5, Effort.GOOD),
        "bb_bench" to RecentNumber(185.0, 5, Effort.GOOD), "trap_dl" to RecentNumber(315.0, 5, Effort.GOOD),
        "ohp" to RecentNumber(115.0, 5, Effort.GOOD)),
)

class PlannerTest {
    private fun slot(draft: PlanDraft, day: String, id: String): ProgramExercise =
        draft.program.days.single { it.id == day }.exercises.single { it.exerciseId == id }
    private fun rows(draft: PlanDraft, day: String, id: String): List<LoggedSet> {
        val index = draft.program.days.single { it.id == day }.exercises.indexOfFirst { it.exerciseId == id }
        return draft.working.getValue(SlotKey(day, index))
    }
    private fun row(set: LoggedSet) = Triple(set.weightLb, set.reps, set.kind)
    private fun rest(rule: SlotRule, goal: LiftGoal, kind: SetKind = SetKind.WORK,
                     overrides: Map<RestCategory, Int> = emptyMap()) =
        RestPolicy.effectiveRestSeconds(kind, TrackingType.WEIGHTED, overrides, rule, goal)

    @Test fun `case 1 reference rotation keeps every main and second exposure without reducing dose`() {
        val draft = Planner.plan(referenceAnswers())
        assertEquals(listOf("A" to "Lower", "B" to "Upper", "C" to "Lower", "D" to "Rest", "E" to "Upper"),
            draft.program.days.map { it.id to it.title })
        assertEquals(listOf(ProgramDayKind.STRENGTH, ProgramDayKind.STRENGTH, ProgramDayKind.STRENGTH,
            ProgramDayKind.REST, ProgramDayKind.STRENGTH), draft.program.days.map { it.kind })
        assertTrue(draft.program.days.single { it.id == "D" }.exercises.isEmpty())
        fun ids(role: SlotRole) = draft.program.strengthDays.map { day ->
            day.exercises.filter { it.rule?.role == role }.map { it.exerciseId }
        }
        assertEquals(listOf(listOf("bb_back_squat"), listOf("bb_bench", "bb_row"), listOf("trap_dl"), listOf("ohp")),
            ids(SlotRole.MAIN))
        assertEquals(listOf(listOf("trap_dl"), listOf("ohp"), listOf("bb_back_squat"), listOf("bb_bench", "bb_row")),
            ids(SlotRole.SECONDARY))
        assertEquals(referenceAnswers().mains!!.toSet(), ids(SlotRole.MAIN).flatten().toSet())
        assertEquals(5, ids(SlotRole.MAIN).flatten().size)
        assertEquals(listOf(2700, 3525, 2700, 3195),
            draft.program.strengthDays.map { SessionBudget.seconds(it, LiftGoal.STRENGTH) })
        assertEquals(3, slot(draft, "E", "bb_bench").targetSets)
        assertEquals(2, slot(draft, "E", "bb_row").targetSets)
        val weekly = SetCount.weekly(draft.program)
        assertEquals(6.0, weekly[Muscle.CHEST])
        assertEquals(8.0, weekly[Muscle.FRONT_DELT])
        assertEquals(11.5, weekly[Muscle.TRICEPS])
        assertTrue(draft.reasons.isEmpty())
        assertEquals(6, draft.deloadEveryCycles)
        for (day in draft.program.strengthDays) for (slot in day.exercises) {
            assertTrue(slot.rule != null)
            slot.superset?.let { assertTrue(it.rule != null) }
        }
    }

    @Test fun `case 2 recent squat seeds the Strength scheme and secondary effort`() {
        val draft = Planner.plan(referenceAnswers())
        assertEquals(listOf(Triple(165.0, 5, SetKind.RAMP), Triple(220.0, 3, SetKind.RAMP),
            Triple(275.0, 5, SetKind.TOP), Triple(250.0, 5, SetKind.BACKOFF), Triple(250.0, 5, SetKind.BACKOFF)),
            rows(draft, "A", "bb_back_squat").map(::row))
        val rule = requireNotNull(slot(draft, "A", "bb_back_squat").rule)
        assertEquals(RepTarget.Fixed(5), rule.target)
        assertEquals(2, rule.rir)
        assertEquals(2, rule.everyExposures)
        assertEquals(0.025, rule.incrementPct)
        assertEquals(Scheme.STRENGTH, rule.scheme)
        assertFalse(rule.state.calibrating)
        assertEquals(List(2) { Triple(270.0, 5, SetKind.WORK) }, rows(draft, "C", "bb_back_squat").map(::row))
        assertEquals(3, slot(draft, "C", "bb_back_squat").rule!!.rir)
        assertEquals(45.0, rows(draft, "B", "bb_row").single { it.kind == SetKind.TOP }.weightLb)
        assertTrue(slot(draft, "B", "bb_row").rule!!.state.calibrating)
    }

    @Test fun `case 3 recent bench seeds its ramps top and backoffs`() {
        assertEquals(listOf(Triple(110.0, 5, SetKind.RAMP), Triple(150.0, 3, SetKind.RAMP),
            Triple(185.0, 5, SetKind.TOP), Triple(165.0, 5, SetKind.BACKOFF), Triple(165.0, 5, SetKind.BACKOFF)),
            rows(Planner.plan(referenceAnswers()), "B", "bb_bench").map(::row))
    }

    @Test fun `case 15 new mains and isolation use the timer resolver and override precedence`() {
        val strength = Planner.plan(SetupAnswers(goal = LiftGoal.STRENGTH))
        val squat = slot(strength, "A", "bb_back_squat")
        assertEquals(SetStructure.STRAIGHT, squat.structure)
        assertEquals(3, squat.targetSets)
        val rule = requireNotNull(squat.rule)
        assertEquals(RepTarget.Fixed(5), rule.target)
        assertEquals(3, rule.rir)
        assertEquals(180, rest(rule, LiftGoal.STRENGTH))
        val muscle = Planner.plan(SetupAnswers(goal = LiftGoal.MUSCLE))
        val muscleRule = requireNotNull(slot(muscle, "A", "bb_back_squat").rule)
        assertEquals(120, rest(muscleRule, LiftGoal.MUSCLE))
        val curl = slot(muscle, "B", "ez_curl")
        assertEquals(SlotRole.ISOLATION, curl.rule!!.role)
        assertEquals(60, rest(curl.rule!!, LiftGoal.MUSCLE))
        assertEquals(150, rest(rule, LiftGoal.STRENGTH, overrides = mapOf(RestCategory.TOP to 150)))
        assertEquals(150, rest(muscleRule.copy(restSeconds = 150), LiftGoal.MUSCLE,
            overrides = mapOf(RestCategory.TOP to 100)))
    }

    @Test fun `case 16 editing the seeded squat top cascades through the Strength scheme`() {
        val draft = Planner.plan(referenceAnswers())
        assertEquals(listOf(170.0, 230.0, 285.0, 255.0, 255.0),
            SetEditor.editWeight(rows(draft, "A", "bb_back_squat"), 2, 285.0,
                scheme = slot(draft, "A", "bb_back_squat").rule!!.scheme).map { it.weightLb })
    }

    @Test fun `case 23 older plans keep balance first and one squat track for layoff recurrence`() {
        for (goal in LiftGoal.entries) for (experience in LiftExperience.entries) {
            val answers = referenceAnswers().copy(goal = goal, experience = experience, olderAdult = true, days = 2)
            val draft = Planner.plan(answers)
            for (day in draft.program.strengthDays) {
                assertEquals(listOf("single_leg_stance", "farmers_carry"), day.exercises.take(2).map { it.exerciseId })
                assertTrue(day.exercises.take(2).all { it.rule?.role == SlotRole.BALANCE })
                assertEquals(30, day.exercises.first().rule!!.restSeconds)
                assertTrue(ExerciseLibrary.get(day.exercises.first().exerciseId).eachSide)
                for (slot in day.exercises) {
                    assertTrue(slot.rule!!.rir >= 3)
                    slot.superset?.let { assertTrue(it.rule!!.rir >= 3) }
                    assertFalse(slot.rule!!.role == SlotRole.SECONDARY)
                    if (slot.rule!!.role == SlotRole.MAIN) assertEquals(120, slot.rule!!.restSeconds)
                }
            }
            assertEquals(1, draft.program.strengthDays.flatMap { it.exercises }.count { it.exerciseId == "bb_back_squat" })
            val recurrence = Progression.expectedRecurrenceDays(2, 2, 1)
            assertEquals(7, recurrence)
            val rule = slot(draft, "A", "bb_back_squat").rule!!
            val working = rows(draft, "A", "bb_back_squat")
            fun opening(day: Long) = Progression.atStart(rule, working, 1000, recurrence,
                Progression.Context(WeightUnit.LB, ExerciseLibrary.get("bb_back_squat").equipment.toSet(),
                    LoadSteps.defaults(WeightUnit.LB), day), olderOrNew = true)
            assertEquals(LayoffKind.None, opening(1010).layoff)
            assertEquals(working, opening(1010).rows)
            val recalibrated = opening(1025)
            assertEquals(LayoffKind.Recalibrate, recalibrated.layoff)
            val expected = answers.steps.nearest(working.first().weightLb * 0.75,
                ExerciseLibrary.get("bb_back_squat").equipment, answers.unit)
            assertTrue(recalibrated.rows.all { it.weightLb == expected })
            val kb = Planner.plan(answers.copy(equipment = EquipmentPreset.KETTLEBELLS.equipment, mains = null))
            assertTrue(kb.program.strengthDays.all { day ->
                day.exercises.filter { it.rule?.role == SlotRole.BALANCE }.map { it.exerciseId } == listOf("single_leg_stance")
            })
        }
    }

    @Test fun `new cadence increments and General full body rules follow setup`() {
        val strength = Planner.plan(SetupAnswers(goal = LiftGoal.STRENGTH))
        assertNull(strength.deloadEveryCycles)
        val squat = slot(strength, "A", "bb_back_squat").rule!!
        assertEquals(1, squat.everyExposures)
        assertEquals(0.05, squat.incrementPct)
        assertEquals(0.025, slot(strength, "C", "bb_bench").rule!!.incrementPct)
        val muscle = Planner.plan(SetupAnswers(goal = LiftGoal.MUSCLE))
        assertTrue(muscle.program.strengthDays.flatMap { it.exercises }.all { slot ->
            slot.rule!!.rir >= 2 && (slot.superset?.rule?.rir ?: 2) >= 2
        })
        val general = Planner.plan(SetupAnswers(days = 4))
        assertEquals(listOf("Full Body", "Full Body", "Full Body", "Rest", "Full Body"), general.program.days.map { it.title })
        for (day in general.program.strengthDays) for (slot in day.exercises) {
            assertFalse(slot.rule!!.role == SlotRole.SECONDARY)
            if (slot.rule!!.role == SlotRole.MAIN) {
                assertEquals(SetStructure.STRAIGHT, slot.structure)
                assertEquals(2, slot.targetSets)
                assertEquals(RepTarget.Range(8, 15, 8), slot.rule!!.target)
                assertEquals(3, slot.rule!!.rir)
            }
        }
    }

    @Test fun `Muscle six days assigns mains to Push Pull Legs twice with rest after the third`() {
        val draft = Planner.plan(referenceAnswers().copy(goal = LiftGoal.MUSCLE, days = 6))
        assertEquals(listOf("Push", "Pull", "Legs", "Rest", "Push", "Pull", "Legs"), draft.program.days.map { it.title })
        assertEquals(listOf(listOf("bb_bench"), listOf("bb_row"), listOf("bb_back_squat"), listOf("ohp"),
            emptyList(), listOf("trap_dl")), draft.program.strengthDays.map { day ->
            day.exercises.filter { it.rule?.role == SlotRole.MAIN }.map { it.exerciseId }
        })
    }

    @Test fun `equipment presets choose the ranked eligible main rows`() {
        val expected = mapOf(
            EquipmentPreset.FULL_GYM to listOf("bb_back_squat", "trap_dl", "bb_bench", "ohp", "bb_row"),
            EquipmentPreset.HOME_BARBELL to listOf("bb_back_squat", "conv_dl", "bb_bench", "ohp", "bb_row"),
            EquipmentPreset.DUMBBELLS to listOf("goblet_squat", "db_rdl", "db_bench", "db_shoulder", "db_row"),
            EquipmentPreset.KETTLEBELLS to listOf("kb_goblet_squat", "kb_rdl", "kb_floor_press", "kb_press", "kb_row"),
            EquipmentPreset.MACHINES_ONLY to listOf("hack_squat", "machine_hip_thrust", "machine_chest", "machine_shoulder", "cs_row"),
            EquipmentPreset.BODYWEIGHT to listOf("sit_to_stand", "back_ext", "pushup", "pike_pushup", "pullup"),
        )
        for ((preset, ids) in expected) assertEquals(ids, Planner.defaultMains(preset.equipment), preset.name)
        assertEquals("back_ext_45", Planner.defaultMains(EquipmentPreset.MACHINES_ONLY.equipment,
            excluded = setOf("machine_hip_thrust", "cable_pullthrough"))[1])
        val answers = SetupAnswers(excluded = setOf("bb_bench"))
        assertEquals("db_bench", Planner.defaultMains(answers.equipment, answers.excluded)[2])
        assertTrue(Planner.plan(answers).program.strengthDays.flatMap { it.exercises }.none {
            it.exerciseId == "bb_bench" || it.superset?.exerciseId == "bb_bench"
        })
    }

    @Test fun `a lone kettlebell pull recurs as an accessory when second exposures are absent`() {
        for (goal in LiftGoal.entries) for (older in listOf(false, true)) {
            if (goal != LiftGoal.GENERAL && !older) continue
            val draft = Planner.plan(SetupAnswers(goal = goal, days = 2, minutes = 90,
                equipment = EquipmentPreset.KETTLEBELLS.equipment, olderAdult = older))
            assertTrue(draft.reasons.isEmpty())
            val pulls = draft.program.strengthDays.flatMap { it.exercises }.filter { it.exerciseId == "kb_row" }
            assertEquals(1, pulls.count { it.rule?.role == SlotRole.MAIN })
            assertEquals(1, pulls.count { it.rule?.role == SlotRole.ACCESSORY })
            assertTrue(draft.program.strengthDays.flatMap { it.exercises }.none { it.rule?.role == SlotRole.SECONDARY })
            val exposures = SetCount.exposures(draft.program)
            assertEquals(2, exposures[Muscle.LATS])
            assertEquals(2, exposures[Muscle.BICEPS])
        }
    }

    @Test fun `a kettlebell pull with two exposures is not reused as an accessory`() {
        val draft = Planner.plan(SetupAnswers(goal = LiftGoal.STRENGTH, days = 5, minutes = 90,
            equipment = EquipmentPreset.KETTLEBELLS.equipment, experience = LiftExperience.A_YEAR_OR_MORE))
        assertTrue(draft.reasons.isEmpty())
        val pulls = draft.program.strengthDays.flatMap { day ->
            day.exercises.filter { it.exerciseId == "kb_row" }.map { day.id to it.rule?.role }
        }
        assertEquals(listOf("B" to SlotRole.MAIN, "E" to SlotRole.SECONDARY), pulls)
        val exposures = SetCount.exposures(draft.program)
        assertEquals(2, exposures[Muscle.UPPER_BACK])
        assertEquals(2, exposures[Muscle.LATS])
    }

    @Test fun `a timed chosen main falls back to the default for its row`() {
        for (preset in EquipmentPreset.entries) {
            val answers = SetupAnswers(equipment = preset.equipment, mains = listOf("wall_sit"))
            val draft = Planner.plan(answers)
            val mains = draft.program.strengthDays.flatMap { it.exercises }.filter { it.rule?.role == SlotRole.MAIN }
            assertEquals(listOf(Planner.defaultMains(preset.equipment).first()), mains.map { it.exerciseId }, preset.name)
        }
    }

    @Test fun `Reset day reproduces the full draft including reasons and index keys`() {
        for (answers in listOf(referenceAnswers(), referenceAnswers().copy(days = 1, minutes = 30))) {
            val full = Planner.plan(answers)
            for (day in full.program.days) {
                val draft = requireNotNull(Planner.day(answers, day.id))
                assertEquals(day, draft.day)
                assertEquals(full.working.filterKeys { it.dayId == day.id }.mapKeys { it.key.index }, draft.working)
                assertEquals(full.reasons.filter { when (it) {
                    is PlanReason.ShortSession -> it.dayId == day.id
                    is PlanReason.OverBudget -> it.dayId == day.id
                    is PlanReason.RunCapped -> it.dayId == day.id
                    PlanReason.DateTooClose, is PlanReason.GoalCheck -> false
                } }, draft.reasons)
            }
            assertNull(Planner.day(answers, "Z"))
        }
    }

    @Test fun `recent numbers override known loads and planning is deterministic`() {
        val answers = referenceAnswers()
        val known = KnownLoads(mapOf("bb_back_squat" to LoggedSet(225.0, 5, SetKind.WORK, rir = 2),
            "bb_row" to LoggedSet(135.0, 5, SetKind.WORK, rir = 2)))
        val draft = Planner.plan(answers, known)
        assertEquals(275.0, rows(draft, "A", "bb_back_squat").single { it.kind == SetKind.TOP }.weightLb)
        assertEquals(135.0, rows(draft, "B", "bb_row").single { it.kind == SetKind.TOP }.weightLb)
        assertEquals(draft, Planner.plan(answers, known))
    }

    @Test fun `experienced and under a year targets follow the role table`() {
        for (goal in LiftGoal.entries) for (experience in LiftExperience.entries) {
            val answers = SetupAnswers(goal = goal, experience = experience, minutes = 90)
            val draft = Planner.plan(answers)
            val main = slot(draft, "A", "bb_back_squat")
            val experienced = experience == LiftExperience.A_YEAR_OR_MORE
            val ramped = goal == LiftGoal.STRENGTH && experienced
            assertEquals(if (ramped) SetStructure.RAMP_TOP_BACKOFF else SetStructure.STRAIGHT, main.structure)
            assertEquals(when {
                ramped -> 5
                goal == LiftGoal.GENERAL -> 2
                else -> 3
            }, main.targetSets)
            val rule = requireNotNull(main.rule)
            assertEquals(when (goal) {
                LiftGoal.STRENGTH -> RepTarget.Fixed(5)
                LiftGoal.MUSCLE -> if (experienced) RepTarget.Range(6, 10, 6) else RepTarget.Range(8, 12, 8)
                LiftGoal.GENERAL -> RepTarget.Range(8, 15, 8)
            }, rule.target)
            assertEquals(if (experienced) 2 else 1, rule.everyExposures)
            assertEquals(if (experienced) 0.025 else 0.05, rule.incrementPct)
            assertEquals(Scheme.STRENGTH, rule.scheme)
            assertEquals(if (experienced) 6 else null, draft.deloadEveryCycles)
        }
    }

    @Test fun `bodyweight mains stay straight and use catalog reps while accessory doses keep their rest`() {
        val answers = SetupAnswers(goal = LiftGoal.STRENGTH, minutes = 90,
            experience = LiftExperience.A_YEAR_OR_MORE, equipment = EquipmentPreset.BODYWEIGHT.equipment)
        val draft = Planner.plan(answers)
        for (day in draft.program.strengthDays) for ((index, slot) in day.exercises.withIndex()) {
            assertEquals(SetStructure.STRAIGHT, slot.structure)
            val entry = ExerciseLibrary.get(slot.exerciseId)
            val reps = entry.goal as? GoalSource.Reps
            if (reps != null) {
                assertEquals(RepTarget.Fixed(reps.targetReps), slot.rule!!.target)
                assertTrue(draft.working.getValue(SlotKey(day.id, index)).all { it.reps == reps.targetReps })
            }
            if (slot.rule!!.role == SlotRole.MAIN) assertEquals(3, slot.targetSets)
        }
        val dose = liftSlot(SlotRole.ACCESSORY, ExerciseLibrary.get("sit_to_stand"), answers)
        assertEquals(2, dose.targetSets)
        assertEquals(60, dose.rule!!.restSeconds)
    }

    @Test fun `setup and chosen mains reject invalid inputs`() {
        for (days in listOf(0, 7)) assertFailsWith<IllegalArgumentException> { SetupAnswers(days = days) }
        assertFailsWith<IllegalArgumentException> { SetupAnswers(minutes = 31) }
        for (reps in listOf(0, 11)) assertFailsWith<IllegalArgumentException> { RecentNumber(100.0, reps, Effort.GOOD) }
        for (ids in listOf(listOf("unknown"), listOf("bb_bench", "db_bench"), listOf("plank"))) {
            assertFailsWith<IllegalArgumentException> { Planner.plan(SetupAnswers(mains = ids)) }
        }
        assertFailsWith<IllegalArgumentException> {
            Planner.plan(SetupAnswers(mains = listOf("bb_bench"), excluded = setOf("bb_bench")))
        }
        assertFailsWith<IllegalArgumentException> {
            Planner.plan(SetupAnswers(mains = listOf("bb_bench"), equipment = EquipmentPreset.BODYWEIGHT.equipment))
        }
    }
}
