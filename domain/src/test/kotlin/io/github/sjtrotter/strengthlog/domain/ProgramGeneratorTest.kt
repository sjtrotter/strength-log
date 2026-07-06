package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DayKind
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.Rotation
import io.github.sjtrotter.strengthlog.domain.generator.SplitDefaults
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the program generator, rotation, and split templates (spec §6,
 * §12). Slot ids are pinned so the prototype-faithful shape can't silently drift.
 */
class ProgramGeneratorTest {

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
            ).program
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
            ).program
            assertEquals(kinds.map(::titleOf), titles(program), "alternative split for $days days")
        }
    }

    // --- prototype 4-day full-body shape (spec §6.3) -------------------------

    @Test
    fun `next-next-next reproduces the prototype 4-day full-body A B C D`() {
        val program = ProgramGenerator.generate(WizardAnswers()).program
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
                ).program
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
        val dayA = ProgramGenerator.generate(WizardAnswers()).program.days[0]
        assertTrue("rdl" in ids(dayA), "hinge complement is the RDL, not a deadlift at main GOAL")
        assertTrue("db_bench" in ids(dayA), "opposing compound is the DB bench, not the barbell main")
    }

    @Test
    fun `default 4-day cycle trains arms as a superset with both biceps and triceps`() {
        val program = ProgramGenerator.generate(WizardAnswers()).program
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
        val program = ProgramGenerator.generate(WizardAnswers()).program
        for (day in program.days) {
            assertEquals(1, day.exercises.count { it.isMain }, "one main on day ${day.id}")
            assertTrue(day.exercises.first().isMain, "main leads day ${day.id}")
        }
    }

    @Test
    fun `full-body core pattern rotates anti-ext to anti-rot to flex across days`() {
        val program = ProgramGenerator.generate(WizardAnswers()).program
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
        val program = ProgramGenerator.generate(WizardAnswers()).program
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
        val prototype = ProgramGenerator.generate(WizardAnswers()).program
        assertEquals(listOf("bb_back_squat", "bb_bench", "trap_dl", "incline_db"), mainIds(prototype))

        val big4 = ProgramGenerator.generate(
            WizardAnswers(anchorScheme = AnchorScheme.BIG_4),
        ).program
        assertTrue("bb_row" in mainIds(big4), "big-4 anchors a barbell row main")

        val fiveThreeOne = ProgramGenerator.generate(
            WizardAnswers(anchorScheme = AnchorScheme.FIVE_THREE_ONE),
        ).program
        assertTrue("ohp" in mainIds(fiveThreeOne), "5/3/1 anchors an OHP main")
    }

    @Test
    fun `deadlift variant swaps the hinge anchor`() {
        fun hingeMain(variant: DeadliftVariant): String =
            ProgramGenerator.generate(WizardAnswers(deadliftVariant = variant)).program
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
        assertTrue(out.program.days.all { it.cardio == null })
        assertTrue(out.cardioDays.isEmpty())
    }

    @Test
    fun `hard cardio only on press-main and leg-light days`() {
        val program = ProgramGenerator.generate(WizardAnswers()).program
        // A squat, C hinge → leg-heavy → easy; B bench, D incline press → hard.
        assertEquals(false, program.days[0].cardio?.hard)
        assertEquals(true, program.days[1].cardio?.hard)
        assertEquals(false, program.days[2].cardio?.hard)
        assertEquals(true, program.days[3].cardio?.hard)
    }

    @Test
    fun `separate days removes finishers and emits standalone cardio cards`() {
        val out = ProgramGenerator.generate(
            WizardAnswers(cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS)),
        )
        assertTrue(out.program.days.all { it.cardio == null }, "no finishers on strength days")
        assertTrue(out.cardioDays.isNotEmpty(), "standalone cardio cards present")
        assertTrue(out.cardioDays.all { !it.cardio.hard }, "standalone cardio is easy Zone 2")
        // Standalone cards live outside the rotation.
        assertTrue(out.cardioDays.none { card -> card.id in out.program.days.map { it.id } })
    }

    @Test
    fun `every leg day carries a hinge or knee-flexion and the cycle contains both`() {
        val program = ProgramGenerator.generate(WizardAnswers()).program
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
        val all = ProgramGenerator.generate(WizardAnswers()).program
        // Day B's knee-flexion slot is the machine seated leg curl by default.
        assertEquals("seated_curl", ids(all.days[1])[2])

        val noMachine = ProgramGenerator.generate(
            WizardAnswers(equipment = Equipment.entries.toSet() - Equipment.MACHINE),
        ).program
        // With machines gone it falls down subRank to the bodyweight ball curl.
        assertEquals("ball_curl", ids(noMachine.days[1])[2])
    }

    // --- warm-up hint (spec §12) ---------------------------------------------

    @Test
    fun `exactly one warm-up hint per generated day`() {
        val programs = listOf(
            ProgramGenerator.generate(WizardAnswers()).program,
            ProgramGenerator.generate(
                WizardAnswers(daysPerWeek = 5, split = SplitTemplate.PPLUL),
            ).program,
            ProgramGenerator.generate(
                WizardAnswers(daysPerWeek = 6, split = SplitTemplate.PPL),
            ).program,
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
        ).program
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
        ).program
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
