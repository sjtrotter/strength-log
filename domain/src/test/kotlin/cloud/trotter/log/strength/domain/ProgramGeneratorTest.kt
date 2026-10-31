package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DayKind
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.SplitDefaults
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the program generator, rotation, and split templates (spec §6,
 * §12). Slot ids are pinned so the prototype-faithful shape can't silently drift.
 */
class ProgramGeneratorTest {
    @Test fun `rotation ignores standalone cardio days`() {
        val generated = ProgramGenerator.generate(WizardAnswers())
        val strength = generated.days.take(2)
        val cardio = ProgramGenerator.generate(WizardAnswers(
            cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        )).cardioDays.first()
        assertEquals(strength[1].id, Rotation.next(cloud.trotter.log.strength.domain.model.Program(strength + cardio), strength[0].id))
        assertEquals(strength[0].id, Rotation.next(cloud.trotter.log.strength.domain.model.Program(strength + cardio), strength[1].id))
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
                    for (pe in day.exercises.filterNot { it.isMain }) {
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
            assertEquals(1, day.exercises.count { it.isMain }, "one main on day ${day.id}")
            assertTrue(day.exercises.first().isMain, "main leads day ${day.id}")
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
        assertEquals("B", Rotation.next(program, "A"))
        assertEquals("C", Rotation.next(program, "B"))
        assertEquals("D", Rotation.next(program, "C"))
        assertEquals("A", Rotation.next(program, "D"))
    }

    // --- anchor schemes (spec §6.1 step 4) -----------------------------------

    private fun mainIds(program: Program) =
        program.days.mapNotNull { day -> day.exercises.firstOrNull { it.isMain }?.exerciseId }

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
                assertEquals("A", Rotation.next(program, "D"))
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

    // --- warm-up hint (spec §12) ---------------------------------------------

    @Test
    fun `exactly one warm-up hint per generated day`() {
        val programs = listOf(
            ProgramGenerator.generate(WizardAnswers()),
            ProgramGenerator.generate(
                WizardAnswers(daysPerWeek = 5, split = SplitTemplate.PPLUL),
            ),
            ProgramGenerator.generate(
                WizardAnswers(daysPerWeek = 6, split = SplitTemplate.PPL),
            ),
        )
        for (program in programs) {
            for (day in program.days) {
                assertEquals(1, day.exercises.count { it.hasWarmupHint }, "one warm-up hint on day ${day.id}")
            }
        }
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
        assertEquals(200, answers.config.bodyweightLb)
        assertEquals(answers.config.emphasis, answers.emphasis)
    }
}
