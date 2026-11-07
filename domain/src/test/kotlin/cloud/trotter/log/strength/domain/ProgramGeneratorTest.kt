package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.DayKind
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.LiftEntry
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.SplitDefaults
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SlotSeeds
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the program generator, rotation, and split templates (spec §6,
 * §12). Slot ids are pinned so the prototype-faithful shape can't silently drift.
 */
class ProgramGeneratorTest {
    @Test
    fun `skipping recent lifts reproduces the generated program`() {
        val answers = WizardAnswers()
        val program = ProgramGenerator.generate(answers)
        assertEquals(program, ProgramGenerator.generate(WizardAnswers(lifts = emptyMap())))
        assertTrue(program.days.flatMap { it.exercises }.all { it.prescription == null })
        val squat = program.days.first().exercises.first()
        val sets = assertNotNull(SlotSeeds.main(squat, ExerciseLibrary.get(squat.exerciseId), answers.config))
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0), sets.map { it.weightLb })
    }

    @Test
    fun `an entered lift seeds its main`() {
        val answers = WizardAnswers(lifts = mapOf("bb_back_squat" to LiftEntry(225.0, 5)))
        val program = ProgramGenerator.generate(answers)
        val baseline = ProgramGenerator.generate(WizardAnswers())
        program.days.zip(baseline.days).forEach { (day, original) ->
            day.exercises.zip(original.exercises).forEach { (slot, before) ->
                if (slot.exerciseId == "bb_back_squat") {
                    assertEquals(Prescription(PrescribedTarget.LoadReps(225.0, 5)), slot.prescription)
                    assertEquals(SetStructure.RAMP_TOP_BACKOFF, slot.structure)
                    assertEquals(6, slot.targetSets)
                    val top = assertNotNull(SlotSeeds.main(slot, ExerciseLibrary.get(slot.exerciseId), answers.config))
                        .single { it.kind == SetKind.TOP }
                    assertEquals(225.0, top.weightLb)
                    assertEquals(5, top.reps)
                    assertEquals(235.0, GoalCalculator.goalFor(slot, answers.config))
                } else {
                    assertEquals(before, slot)
                }
            }
        }
    }

    @Test
    fun `novices start barbell mains at the empty bar as straight sets`() {
        for (unit in WeightUnit.entries) {
            val cfg = LifterConfig(level = ExperienceLevel.NOVICE, weightUnit = unit)
            val program = ProgramGenerator.generate(WizardAnswers(config = cfg))
            val mains = program.days.map { it.exercises.first() }
            assertTrue(mains.all { it.structure == SetStructure.STRAIGHT && it.targetSets == 3 })
            val barLb = if (unit == WeightUnit.LB) 45.0 else WeightUnit.KG.toLb(20.0)
            for (slot in mains.filter { it.exerciseId in setOf("bb_back_squat", "bb_bench") }) {
                assertEquals(Prescription(PrescribedTarget.LoadReps(barLb, 5)), slot.prescription)
                val sets = assertNotNull(SlotSeeds.main(slot, ExerciseLibrary.get(slot.exerciseId), cfg))
                assertEquals(List(3) { Triple(barLb, 5, SetKind.WORK) }, sets.map { Triple(it.weightLb, it.reps, it.kind) })
            }
            val trap = mains.single { it.exerciseId == "trap_dl" }
            assertEquals(
                Prescription(PrescribedTarget.LoadReps(GoalCalculator.goalFor(ExerciseLibrary.get("trap_dl"), cfg), 5)),
                trap.prescription,
            )
        }
    }

    @Test
    fun `a novice's entered lift wins over the bar`() {
        val answers = WizardAnswers(
            config = LifterConfig(level = ExperienceLevel.NOVICE),
            lifts = mapOf("bb_back_squat" to LiftEntry(95.0, 8)),
        )
        val squat = ProgramGenerator.generate(answers).days.first().exercises.first()
        assertEquals(SetStructure.STRAIGHT, squat.structure)
        assertEquals(3, squat.targetSets)
        assertEquals(Prescription(PrescribedTarget.LoadReps(95.0, 8)), squat.prescription)
    }

    @Test
    fun `main lift ids are each day's main once in day order`() {
        val expected = listOf("bb_back_squat", "bb_bench", "trap_dl", "incline_db")
        assertEquals(expected, ProgramGenerator.mainLiftIds(WizardAnswers()))
        assertEquals(expected, ProgramGenerator.mainLiftIds(WizardAnswers(config = LifterConfig(level = ExperienceLevel.NOVICE))))
    }

    @Test fun `rotation ignores standalone cardio days`() {
        val generated = ProgramGenerator.generate(WizardAnswers())
        val strength = generated.days.take(2)
        val cardio = ProgramGenerator.generate(WizardAnswers(
            cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        )).cardioDays.first()
        assertEquals(strength[1].id, Rotation.nextFor(cloud.trotter.log.strength.domain.model.Program(strength + cardio), strength[0].id))
        assertEquals(strength[0].id, Rotation.nextFor(cloud.trotter.log.strength.domain.model.Program(strength + cardio), strength[1].id))
    }

    private fun ids(day: ProgramDay) = day.exercises.map { it.exerciseId }

    private fun patternsIn(day: ProgramDay): List<MovementPattern> =
        day.exercises.map { ExerciseLibrary.get(it.exerciseId).pattern }

    private fun titleOf(kind: DayKind) = when (kind) {
        DayKind.FULL_BODY -> "Full Body"
        DayKind.UPPER -> "Upper"
        DayKind.LOWER -> "Lower"
        DayKind.PUSH -> "Push"
        DayKind.PULL -> "Pull"
        DayKind.LEGS -> "Legs"
    }

    private fun titles(program: Program) = program.days.map { it.title }

    // --- §6.2 split templates ------------------------------------------------

    @Test
    fun `default splits emit the section 6_2 day templates for 2 to 6 days`() {
        val expected = mapOf(
            2 to List(2) { DayKind.FULL_BODY },
            3 to List(3) { DayKind.FULL_BODY },
            4 to List(4) { DayKind.FULL_BODY },
            5 to listOf(DayKind.PUSH, DayKind.PULL, DayKind.LEGS, DayKind.UPPER, DayKind.LOWER),
            6 to listOf(DayKind.PUSH, DayKind.PULL, DayKind.LEGS, DayKind.PUSH, DayKind.PULL, DayKind.LEGS),
        )
        for ((days, kinds) in expected) {
            val split = SplitDefaults.defaultFor(days)
            val program = ProgramGenerator.generate(
                WizardAnswers(daysPerWeek = days, split = split),
            )
            assertEquals(kinds.map(::titleOf), titles(program), "default split for $days days")
        }
    }

    @Test
    fun `alternative splits emit the section 6_2 day templates`() {
        // Day 2 has no alternative.
        assertNull(SplitDefaults.alternativeFor(2))

        val expected = mapOf(
            3 to listOf(DayKind.PUSH, DayKind.PULL, DayKind.LEGS),
            4 to listOf(DayKind.UPPER, DayKind.LOWER, DayKind.UPPER, DayKind.LOWER),
            5 to List(5) { DayKind.FULL_BODY },
            6 to listOf(DayKind.UPPER, DayKind.LOWER, DayKind.UPPER, DayKind.LOWER, DayKind.UPPER, DayKind.LOWER),
        )
        for ((days, kinds) in expected) {
            val split = SplitDefaults.alternativeFor(days)!!
            val program = ProgramGenerator.generate(
                WizardAnswers(daysPerWeek = days, split = split),
            )
            assertEquals(kinds.map(::titleOf), titles(program), "alternative split for $days days")
        }
    }

    // --- prototype 4-day full-body shape (spec §6.3) -------------------------

    @Test
    fun `next-next-next reproduces the prototype 4-day full-body A B C D`() {
        val program = ProgramGenerator.generate(WizardAnswers())
        assertEquals(listOf("A", "B", "C", "D"), program.days.map { it.id })
        assertTrue(program.days.all { it.title == "Full Body" })

        assertEquals(
            listOf("bb_back_squat", "db_bench", "rdl", "cs_row", "cable_lateral", "plank"),
            ids(program.days[0]),
        )
        assertEquals(
            listOf("bb_bench", "bss", "seated_curl", "pullup", "ez_curl", "pallof"),
            ids(program.days[1]),
        )
        assertEquals(
            listOf("trap_dl", "db_bench", "hack_squat", "cs_row", "standing_calf", "cable_crunch"),
            ids(program.days[2]),
        )
        assertEquals(
            listOf("incline_db", "bss", "seated_curl", "pullup", "cable_lateral", "plank"),
            ids(program.days[3]),
        )
        // Day B's arms slot is the biceps+triceps superset (prototype-proven §8.2).
        assertEquals("rope_pushdown", program.days[1].exercises[4].superset?.exerciseId)
    }

    @Test
    fun `accessory slots are never Std-sourced across all splits and day counts`() {
        for (days in 2..6) {
            for (split in listOfNotNull(SplitDefaults.defaultFor(days), SplitDefaults.alternativeFor(days))) {
                val program = ProgramGenerator.generate(
                    WizardAnswers(daysPerWeek = days, split = split),
                )
                for (day in program.days) {
                    for (pe in day.exercises.filterNot { it.structure.hasTopSet }) {
                        for (id in listOfNotNull(pe.exerciseId, pe.superset?.exerciseId)) {
                            assertTrue(
                                ExerciseLibrary.get(id).goal !is GoalSource.Std,
                                "Std-sourced accessory $id on day ${day.id} ($split, $days days)",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `default day A uses fraction-sourced accessories, not main-capable lifts`() {
        val dayA = ProgramGenerator.generate(WizardAnswers()).days[0]
        assertTrue("rdl" in ids(dayA), "hinge complement is the RDL, not a deadlift at main GOAL")
        assertTrue("db_bench" in ids(dayA), "opposing compound is the DB bench, not the barbell main")
    }

    @Test
    fun `default 4-day cycle trains arms as a superset with both biceps and triceps`() {
        val program = ProgramGenerator.generate(WizardAnswers())
        val supersets = program.days.flatMap { d -> d.exercises.filter { it.superset != null } }
        assertTrue(supersets.isNotEmpty(), "cycle contains at least one superset slot")
        val patterns = program.days.flatMap { d ->
            d.exercises.flatMap { pe ->
                listOfNotNull(pe.exerciseId, pe.superset?.exerciseId)
                    .map { ExerciseLibrary.get(it).pattern }
            }
        }
        assertTrue(MovementPattern.BICEPS in patterns, "cycle trains biceps")
        assertTrue(MovementPattern.TRICEPS in patterns, "cycle trains triceps")
    }

    @Test
    fun `every full-body day has exactly one main anchor`() {
        val program = ProgramGenerator.generate(WizardAnswers())
        for (day in program.days) {
            assertEquals(1, day.exercises.count { it.structure.hasTopSet }, "one main on day ${day.id}")
            assertTrue(day.exercises.first().structure.hasTopSet, "main leads day ${day.id}")
        }
    }

    @Test
    fun `full-body core pattern rotates anti-ext to anti-rot to flex across days`() {
        val program = ProgramGenerator.generate(WizardAnswers())
        val corePatterns = program.days.map { day ->
            day.exercises.map { ExerciseLibrary.get(it.exerciseId).pattern }
                .first { it.name.startsWith("CORE_") }
        }
        assertEquals(
            listOf(
                MovementPattern.CORE_ANTI_EXT,
                MovementPattern.CORE_ANTI_ROT,
                MovementPattern.CORE_FLEX,
                MovementPattern.CORE_ANTI_EXT,
            ),
            corePatterns,
        )
    }

    // --- rotation (spec principle 1) -----------------------------------------

    @Test
    fun `rotation advances and wraps`() {
        val program = ProgramGenerator.generate(WizardAnswers())
        assertEquals("B", Rotation.nextFor(program, "A"))
        assertEquals("C", Rotation.nextFor(program, "B"))
        assertEquals("D", Rotation.nextFor(program, "C"))
        assertEquals("A", Rotation.nextFor(program, "D"))
    }

    // --- anchor schemes (spec §6.1 step 4) -----------------------------------

    private fun mainIds(program: Program) =
        program.days.mapNotNull { day -> day.exercises.firstOrNull { it.structure.hasTopSet }?.exerciseId }

    @Test
    fun `anchor schemes map to the right main lifts`() {
        val prototype = ProgramGenerator.generate(WizardAnswers())
        assertEquals(listOf("bb_back_squat", "bb_bench", "trap_dl", "incline_db"), mainIds(prototype))

        val big4 = ProgramGenerator.generate(
            WizardAnswers(anchorScheme = AnchorScheme.BIG_4),
        )
        assertTrue("bb_row" in mainIds(big4), "big-4 anchors a barbell row main")

        val fiveThreeOne = ProgramGenerator.generate(
            WizardAnswers(anchorScheme = AnchorScheme.FIVE_THREE_ONE),
        )
        assertTrue("ohp" in mainIds(fiveThreeOne), "5/3/1 anchors an OHP main")
    }

    @Test
    fun `deadlift variant swaps the hinge anchor`() {
        fun hingeMain(variant: DeadliftVariant): String =
            ProgramGenerator.generate(WizardAnswers(deadliftVariant = variant))
                .let { mainIds(it) }
                .first { ExerciseLibrary.get(it).pattern == MovementPattern.HINGE }

        assertEquals("trap_dl", hingeMain(DeadliftVariant.TRAP_BAR))
        assertEquals("conv_dl", hingeMain(DeadliftVariant.CONVENTIONAL))
        assertEquals("sumo_dl", hingeMain(DeadliftVariant.SUMO))
    }

    // --- cardio rules (spec §6.4) --------------------------------------------

    @Test
    fun `mode NONE emits no cardio anywhere`() {
        val out = ProgramGenerator.generate(
            WizardAnswers(cardio = CardioPrefs(mode = CardioMode.NONE)),
        )
        assertTrue(out.days.all { it.cardioBlock == null })
        assertTrue(out.cardioDays.isEmpty())
    }

    @Test
    fun `hard cardio only on press-main and leg-light days`() {
        val program = ProgramGenerator.generate(WizardAnswers())
        // A squat, C hinge → leg-heavy → easy; B bench, D incline press → hard.
        assertEquals(false, program.days[0].cardioBlock?.sets?.any { it.hard })
        assertEquals(true, program.days[1].cardioBlock?.sets?.any { it.hard })
        assertEquals(false, program.days[2].cardioBlock?.sets?.any { it.hard })
        assertEquals(true, program.days[3].cardioBlock?.sets?.any { it.hard })
    }

    @Test
    fun `separate days removes finishers and emits standalone cardio cards`() {
        val out = ProgramGenerator.generate(
            WizardAnswers(cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS)),
        )
        assertTrue(out.strengthDays.all { it.cardioBlock == null }, "no finishers on strength days")
        assertTrue(out.cardioDays.isNotEmpty(), "standalone cardio cards present")
        assertEquals(listOf("C1", "C2"), out.cardioDays.map { it.id })
        assertTrue(out.cardioDays.all { day -> day.cardioBlock!!.sets.none { it.hard } }, "standalone cardio is easy Zone 2")
        assertEquals(listOf("plank", "pallof"), out.cardioDays.map { it.exercises.single().exerciseId })
        assertTrue(out.cardioDays.all { it.emphasisLine == "Lift first, then run. 20–30 min easy, conversational pace." })
        // Standalone cards stay outside the strength rotation.
        assertTrue(out.cardioDays.none { card -> card.id in out.strengthDays.map { it.id } })
    }

    @Test
    fun `typed generated cardio matches shipped finishers and standalone sets for every mode and fiveK flag`() {
        for (mode in CardioMode.entries) {
            for (fiveK in listOf(false, true)) {
                val program = ProgramGenerator.generate(WizardAnswers(cardio = CardioPrefs(
                    mode = mode, placement = CardioPlacement.BOTH, fiveKGoal = fiveK,
                )))
                val activity = CardioBlocks.activityOf(mode)
                if (activity == null) {
                    assertTrue(program.days.all { it.cardioBlock == null })
                    assertTrue(program.cardioDays.isEmpty())
                    continue
                }
                program.strengthDays.forEachIndexed { index, day ->
                    val hard = index % 2 == 1 && mode != CardioMode.LOW_IMPACT
                    val suggestion = CardioSuggestion("", "", hard)
                    assertEquals(CardioBlocks.fromLegacy(suggestion, fiveK, activity), day.cardioBlock)
                }
                assertEquals(listOf("C1", "C2"), program.cardioDays.map { it.id })
                program.cardioDays.forEach { day ->
                    assertEquals(CardioBlocks.fromLegacy(CardioSuggestion("", "", false), fiveK, activity), day.cardioBlock)
                }
                assertEquals("A", Rotation.nextFor(program, "D"))
            }
        }
    }

    @Test
    fun `every leg day carries a hinge or knee-flexion and the cycle contains both`() {
        val program = ProgramGenerator.generate(WizardAnswers())
        val hinge = MovementPattern.HINGE
        val kneeFlex = MovementPattern.KNEE_FLEXION
        for (day in program.days) {
            val pats = patternsIn(day)
            assertTrue(hinge in pats || kneeFlex in pats, "day ${day.id} needs hinge or knee-flexion")
        }
        val allPatterns = program.days.flatMap(::patternsIn)
        assertTrue(hinge in allPatterns, "cycle contains a hinge")
        assertTrue(kneeFlex in allPatterns, "cycle contains a knee-flexion")
    }

    // --- equipment profile (PLAN.md A4) --------------------------------------

    @Test
    fun `generated plans use only ticked equipment`() {
        val all = Equipment.entries.toSet()
        val equipmentSets = listOf(
            all - setOf(Equipment.BARBELL, Equipment.TRAP_BAR),
            setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.BENCH, Equipment.RACK, Equipment.PULLUP_BAR),
            all - setOf(Equipment.MACHINE, Equipment.CABLE, Equipment.TRAP_BAR, Equipment.EZ_BAR, Equipment.KETTLEBELL),
            all - Equipment.BODYWEIGHT,
        )
        for (days in 2..6) {
            for (split in listOfNotNull(SplitDefaults.defaultFor(days), SplitDefaults.alternativeFor(days))) {
                for (equipment in equipmentSets) {
                    val program = ProgramGenerator.generate(
                        WizardAnswers(daysPerWeek = days, split = split, equipment = equipment),
                    )
                    for (day in program.days) {
                        for (pe in day.exercises) {
                            for (id in listOfNotNull(pe.exerciseId, pe.superset?.exerciseId)) {
                                val entry = ExerciseLibrary.get(id)
                                assertTrue(entry.availableWith(equipment), "Unavailable $id on day ${day.id} ($split, $days days, $equipment)")
                                if (!pe.structure.hasTopSet) {
                                    assertTrue(entry.goal !is GoalSource.Std, "Std-sourced accessory $id")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an unavailable anchor becomes its pattern's best available weighted lift`() {
        val answers = WizardAnswers(equipment = Equipment.entries.toSet() - setOf(Equipment.BARBELL, Equipment.TRAP_BAR))
        val program = ProgramGenerator.generate(answers)
        val first = ExerciseLibrary.get(ids(program.days.first()).first())

        assertTrue(first.id != "bb_back_squat")
        assertEquals(MovementPattern.SQUAT_BILATERAL, first.pattern)
        assertEquals(TrackingType.WEIGHTED, first.tracking)
        assertTrue(first.availableWith(answers.equipment))
        assertEquals(
            ExerciseLibrary.byPattern(MovementPattern.SQUAT_BILATERAL)
                .first { it.tracking == TrackingType.WEIGHTED && it.availableWith(answers.equipment) }.id,
            first.id,
        )
        for (id in ProgramGenerator.availableAnchorIds(answers)) {
            val entry = ExerciseLibrary.get(id)
            assertTrue(Equipment.BARBELL !in entry.equipment && Equipment.TRAP_BAR !in entry.equipment)
        }
    }

    @Test
    fun `a day nothing available can fill is left out`() {
        val answers = WizardAnswers(equipment = emptySet(), cardio = CardioPrefs(mode = CardioMode.NONE))
        assertTrue(ProgramGenerator.generate(answers).days.isEmpty())

        val cardioOnly = ProgramGenerator.generate(answers.copy(
            cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        ))
        assertEquals(listOf("C1", "C2"), cardioOnly.days.map { it.id })
        assertTrue(cardioOnly.days.all { it.exercises.isEmpty() && it.cardioBlock != null })
    }

    @Test
    fun `filtered plans keep compact letters while existing days reset to their original templates`() {
        val answers = WizardAnswers(
            daysPerWeek = 3, split = SplitTemplate.PPL, equipment = setOf(Equipment.PULLUP_BAR),
            cardio = CardioPrefs(mode = CardioMode.NONE),
        )
        val compact = ProgramGenerator.generate(answers)
        assertEquals(listOf("A", "B"), compact.days.map { it.id })
        assertEquals(listOf("Pull", "Legs"), titles(compact))
        assertEquals(listOf("pullup"), ids(compact.days[0]))
        assertEquals(listOf("hanging_raise"), ids(compact.days[1]))
        val original = ProgramGenerator.generate(answers.copy(equipment = Equipment.entries.toSet()))
        for ((index, stored) in original.days.drop(1).withIndex()) {
            val edited = stored.copy(title = "Renamed", exercises = emptyList())
            assertEquals(compact.days[index].copy(id = stored.id), ProgramGenerator.templateFor(answers, edited))
        }
        for (day in compact.days) assertEquals(day, ProgramGenerator.templateFor(answers, day))
        assertNull(ProgramGenerator.templateFor(answers, original.days[0]))
        assertNull(ProgramGenerator.templateFor(answers, original.days[1].copy(emphasisLine = "")))
    }

    @Test
    fun `shifted repeated days with identical emphasis have no unambiguous reset template`() {
        val answers = WizardAnswers(
            daysPerWeek = 6, split = SplitTemplate.UPPER_LOWER, equipment = setOf(Equipment.PULLUP_BAR),
            cardio = CardioPrefs(mode = CardioMode.NONE),
        )
        val stored = ProgramGenerator.generate(answers.copy(equipment = Equipment.entries.toSet())).days[2]
        val compact = ProgramGenerator.generate(answers).days.single { it.id == stored.id }
        assertEquals(stored.emphasisLine, compact.emphasisLine)
        assertNull(ProgramGenerator.templateFor(answers, stored))
    }

    @Test
    fun `full equipment anchors are the scheme anchors`() {
        for (scheme in AnchorScheme.entries) {
            for (variant in DeadliftVariant.entries) {
                val answers = WizardAnswers(anchorScheme = scheme, deadliftVariant = variant)
                assertEquals(ProgramGenerator.anchorIds(answers), ProgramGenerator.availableAnchorIds(answers))
            }
        }
    }

    @Test
    fun `excluding machines swaps a machine rank-1 pick for the best non-machine one`() {
        val all = ProgramGenerator.generate(WizardAnswers())
        // Day B's knee-flexion slot is the machine seated leg curl by default.
        assertEquals("seated_curl", ids(all.days[1])[2])

        val noMachine = ProgramGenerator.generate(
            WizardAnswers(equipment = Equipment.entries.toSet() - Equipment.MACHINE),
        )
        // With machines gone it falls down subRank to the bodyweight ball curl.
        assertEquals("ball_curl", ids(noMachine.days[1])[2])
    }

    @Test
    fun `arms superset pairs biceps and triceps on upper days`() {
        val program = ProgramGenerator.generate(
            WizardAnswers(daysPerWeek = 5, split = SplitTemplate.PPLUL),
        )
        val upper = program.days.first { it.title == "Upper" }
        val superset = upper.exercises.first { it.superset != null }
        assertEquals(MovementPattern.BICEPS, ExerciseLibrary.get(superset.exerciseId).pattern)
        assertEquals(
            MovementPattern.TRICEPS,
            ExerciseLibrary.get(superset.superset!!.exerciseId).pattern,
        )
    }

    @Test
    fun `push day gets triceps only and pull day biceps only, no supersets`() {
        val program = ProgramGenerator.generate(
            WizardAnswers(daysPerWeek = 6, split = SplitTemplate.PPL),
        )
        for (day in program.days.filter { it.title == "Push" || it.title == "Pull" }) {
            assertTrue(day.exercises.none { it.superset != null }, "no superset on ${day.title} day")
            val pats = patternsIn(day)
            if (day.title == "Push") {
                assertTrue(MovementPattern.TRICEPS in pats, "push day trains triceps")
                assertTrue(MovementPattern.BICEPS !in pats, "push day skips biceps")
            } else {
                assertTrue(MovementPattern.BICEPS in pats, "pull day trains biceps")
                assertTrue(MovementPattern.TRICEPS !in pats, "pull day skips triceps")
            }
        }
    }

    @Test
    fun `experience level and emphasis flow through the config unchanged`() {
        val answers = WizardAnswers(
            config = LifterConfig(bodyweightLb = 200, age = 45),
        )
        assertEquals(200.0, answers.config.bodyweightLb)
        assertEquals(answers.config.emphasis, answers.emphasis)
    }

    @Test
    fun `cardio first with no lifting is three lettered run days`() {
        val program = ProgramGenerator.generate(WizardAnswers(cardioFirst = CardioFirst()))
        assertEquals(listOf("A", "B", "C"), program.days.map { it.id })
        assertEquals(listOf("Easy", "Intervals", "Long"), titles(program))
        assertTrue(program.days.all { it.kind == ProgramDayKind.STRENGTH && it.exercises.isEmpty() && it.emphasisLine.isEmpty() })
        val easy = assertNotNull(program.days[0].cardioBlock)
        assertEquals(CardioActivity.ROAD_RUN, easy.activity)
        assertEquals(listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800))), easy.sets)
        assertTrue(easy.openEnded)
        val intervals = assertNotNull(program.days[1].cardioBlock)
        assertEquals(11, intervals.sets.size)
        assertEquals(CardioBlocks.fromLegacy(CardioSuggestion("", "", hard = true), true, CardioActivity.ROAD_RUN), intervals)
        assertEquals(listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(2700))), program.days[2].cardioBlock?.sets)
        assertEquals("B", Rotation.nextFor(program, "A"))
        assertEquals("C", Rotation.nextFor(program, "B"))
        assertEquals("A", Rotation.nextFor(program, "C"))
    }

    @Test
    fun `a distance answer gives distance sets`() {
        val program = ProgramGenerator.generate(WizardAnswers(cardioFirst = CardioFirst(comfortable = Measure.Distance(4828))))
        assertEquals(Measure.Distance(4828), program.days.first().cardioBlock?.sets?.single()?.end)
        assertEquals(Measure.Distance(7242), program.days.last().cardioBlock?.sets?.single()?.end)
        assertEquals(false, program.days.first().cardioBlock?.openEnded)
    }

    @Test
    fun `long time rounds to the minute`() {
        val program = ProgramGenerator.generate(WizardAnswers(cardioFirst = CardioFirst(comfortable = Measure.Time(1250))))
        assertEquals(Measure.Time(1860), program.days.last().cardioBlock?.sets?.single()?.end)
    }

    @Test
    fun `two lift days interleave`() {
        val answers = WizardAnswers(daysPerWeek = 2, cardioFirst = CardioFirst(liftDays = 2),
            lifts = mapOf("bb_back_squat" to LiftEntry(225.0, 5)))
        val program = ProgramGenerator.generate(answers)
        assertEquals(listOf("Easy", "Full Body", "Intervals", "Full Body", "Long"), titles(program))
        assertEquals(listOf("A", "B", "C", "D", "E"), program.days.map { it.id })
        val lifts = program.days.filter { it.exercises.isNotEmpty() }
        assertEquals(2, lifts.size)
        assertTrue(lifts.all { it.cardioBlock == null && it.kind == ProgramDayKind.STRENGTH })
        assertEquals(Prescription(PrescribedTarget.LoadReps(225.0, 5)), lifts.first().exercises.first().prescription)
        assertTrue(program.cardioDays.isEmpty())
    }

    @Test
    fun `six sessions`() {
        val program = ProgramGenerator.generate(WizardAnswers(cardioFirst = CardioFirst(sessions = 6)))
        assertEquals(listOf("Easy", "Intervals", "Easy", "Intervals", "Easy", "Long"), titles(program))
    }

    @Test
    fun `one two four and five sessions use their planned sequences`() {
        val expected = mapOf(
            1 to listOf("Easy"),
            2 to listOf("Easy", "Long"),
            4 to listOf("Easy", "Intervals", "Easy", "Long"),
            5 to listOf("Easy", "Intervals", "Easy", "Easy", "Long"),
        )
        expected.forEach { (sessions, expectedTitles) ->
            val program = ProgramGenerator.generate(WizardAnswers(cardioFirst = CardioFirst(sessions = sessions)))
            assertEquals(expectedTitles, titles(program))
        }
    }

    @Test
    fun `remaining lift days follow the last cardio session`() {
        val program = ProgramGenerator.generate(WizardAnswers(daysPerWeek = 3, cardioFirst = CardioFirst(sessions = 1, liftDays = 3)))
        assertEquals(listOf("Easy", "Full Body", "Full Body", "Full Body"), titles(program))
        assertEquals(listOf("A", "B", "C", "D"), program.days.map { it.id })
    }

    @Test
    fun `long sessions respect measure bounds and retain the activity`() {
        for (comfortable in listOf(Measure.Time(1), Measure.Time(86400), Measure.Distance(200000))) {
            val program = ProgramGenerator.generate(WizardAnswers(cardioFirst = CardioFirst(
                activity = CardioActivity.STATIONARY_BIKE, comfortable = comfortable,
            )))
            assertTrue(program.days.all { it.cardioBlock?.activity == CardioActivity.STATIONARY_BIKE })
            assertEquals(when (comfortable) {
                is Measure.Time -> Measure.Time(if (comfortable.seconds == 1) 60 else 86400)
                is Measure.Distance -> Measure.Distance(200000)
            }, program.days.last().cardioBlock?.sets?.single()?.end)
        }
    }
    @Test fun `one rest follows the long run`() {
        val program = ProgramGenerator.generate(WizardAnswers(daysPerWeek = 2,
            cardioFirst = CardioFirst(liftDays = 2, restDays = 1)))
        assertEquals(listOf("Easy", "Full Body", "Intervals", "Full Body", "Long", "Rest"), titles(program))
        assertEquals(('A'..'F').map(Char::toString), program.days.map { it.id })
        val rest = program.days.last()
        assertEquals(ProgramDayKind.REST, rest.kind)
        assertTrue(rest.exercises.isEmpty())
        assertEquals(null, rest.cardioBlock)
    }

    @Test fun `two rests follow the first intervals and the long run`() {
        val program = ProgramGenerator.generate(WizardAnswers(daysPerWeek = 2,
            cardioFirst = CardioFirst(liftDays = 2, restDays = 2)))
        assertEquals(listOf("Easy", "Full Body", "Intervals", "Rest", "Full Body", "Long", "Rest"), titles(program))
        assertEquals(('A'..'G').map(Char::toString), program.days.map { it.id })
        assertEquals(listOf("D", "G"), program.days.filter { it.kind == ProgramDayKind.REST }.map { it.id })
    }

    @Test fun `one session with one rest ends with rest`() {
        for (liftDays in CardioFirst.LIFT_DAY_CHOICES) {
            val program = ProgramGenerator.generate(WizardAnswers(daysPerWeek = maxOf(2, liftDays),
                cardioFirst = CardioFirst(sessions = 1, liftDays = liftDays, restDays = 1)))
            assertEquals(listOf("Easy") + List(liftDays) { "Full Body" } + "Rest", titles(program))
            assertEquals(List(program.days.size) { ('A' + it).toString() }, program.days.map { it.id })
            val rest = program.days.filter { it.kind == ProgramDayKind.REST }.single()
            assertTrue(rest.exercises.isEmpty())
            assertNull(rest.cardioBlock)
        }
    }

    @Test fun `one session with two rests places both after easy and at the end`() {
        for (liftDays in CardioFirst.LIFT_DAY_CHOICES) {
            val program = ProgramGenerator.generate(WizardAnswers(daysPerWeek = maxOf(2, liftDays),
                cardioFirst = CardioFirst(sessions = 1, liftDays = liftDays, restDays = 2)))
            assertEquals(listOf("Easy", "Rest") + List(liftDays) { "Full Body" } + "Rest", titles(program))
            assertEquals(List(program.days.size) { ('A' + it).toString() }, program.days.map { it.id })
            val rests = program.days.filter { it.kind == ProgramDayKind.REST }
            assertEquals(2, rests.size)
            assertTrue(rests.all { it.exercises.isEmpty() && it.cardioBlock == null })
        }
    }

    @Test fun `without intervals two rests follow easy and long`() {
        val program = ProgramGenerator.generate(WizardAnswers(cardioFirst = CardioFirst(sessions = 2, restDays = 2)))
        assertEquals(listOf("Easy", "Rest", "Long", "Rest"), titles(program))
        assertEquals(listOf("B", "D"), program.days.filter { it.kind == ProgramDayKind.REST }.map { it.id })
    }

}
