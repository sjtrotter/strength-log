package io.github.sjtrotter.strengthlog.domain.generator

import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.BICEPS
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CALF_GASTROC
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CALF_SOLEUS
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CORE_ANTI_EXT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CORE_ANTI_ROT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CORE_FLEX
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.HINGE
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.H_PULL
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.H_PUSH
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.KNEE_FLEXION
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.REAR_DELT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.SIDE_DELT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.SINGLE_LEG
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.SQUAT_BILATERAL
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.TRICEPS
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.V_PULL
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.V_PUSH
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SupersetPartner

/** A standalone "Cardio + Core" card that lives outside the strength rotation
 *  (spec §6.4). It never consumes a rotation advance, so it is kept off
 *  [Program.days] and in its own list. */
data class CardioDay(
    val id: String,
    val title: String,
    val cardio: CardioSuggestion,
    val core: ProgramExercise,
)

/** The generator's full output: the editable strength [program] plus any
 *  standalone cardio cards the placement asked for. */
data class GeneratedProgram(
    val program: Program,
    val cardioDays: List<CardioDay>,
)

/**
 * Turns [WizardAnswers] into a concrete, editable [Program] (spec §6.3). Each
 * day is a fixed skeleton of pattern slots; every slot is filled with the
 * library's rank-1 entry for that pattern (equipment-filtered), except anchor
 * mains, which come from the wizard's chosen scheme. Muscle-angle emphasis and
 * the core pattern rotate across the cycle (§12 "calves principle").
 */
object ProgramGenerator {

    private val DAY_IDS = ('A'..'Z').map(Char::toString)
    private val CORE_ROTATION = listOf(CORE_ANTI_EXT, CORE_ANTI_ROT, CORE_FLEX)
    private val LEG_MAIN_PATTERNS = setOf(SQUAT_BILATERAL, HINGE, SINGLE_LEG)
    private val INCLINE_CHEST_IDS = setOf("incline_db", "incline_bb")

    fun generate(answers: WizardAnswers): GeneratedProgram {
        val kinds = SplitDefaults.dayKinds(answers.split, answers.daysPerWeek)
        val anchors = anchorIds(answers)
        val occurrences = mutableMapOf<DayKind, Int>()

        val days = kinds.mapIndexed { i, kind ->
            val occ = occurrences.getOrDefault(kind, 0)
            occurrences[kind] = occ + 1
            buildDay(DAY_IDS[i], i, occ, kind, anchors, answers)
        }
        return GeneratedProgram(Program(days), standaloneCardio(answers))
    }

    // --- anchors -------------------------------------------------------------

    private fun anchorIds(a: WizardAnswers): List<String> {
        val dl = when (a.deadliftVariant) {
            DeadliftVariant.TRAP_BAR -> "trap_dl"
            DeadliftVariant.CONVENTIONAL -> "conv_dl"
            DeadliftVariant.SUMO -> "sumo_dl"
        }
        return when (a.anchorScheme) {
            AnchorScheme.PROTOTYPE -> listOf("bb_back_squat", "bb_bench", dl, "incline_db")
            AnchorScheme.BIG_4 -> listOf("bb_back_squat", "bb_bench", dl, "bb_row")
            AnchorScheme.FIVE_THREE_ONE -> listOf("bb_back_squat", "bb_bench", dl, "ohp")
        }
    }

    private fun anchorFor(pattern: MovementPattern, anchors: List<String>): String? =
        anchors.firstOrNull { ExerciseLibrary.get(it).pattern == pattern }

    // --- day dispatch --------------------------------------------------------

    private fun buildDay(
        id: String,
        dayIndex: Int,
        occ: Int,
        kind: DayKind,
        anchors: List<String>,
        answers: WizardAnswers,
    ): ProgramDay = when (kind) {
        DayKind.FULL_BODY -> fullBodyDay(id, dayIndex, anchors, answers)
        DayKind.UPPER -> upperDay(id, dayIndex, occ, anchors, answers)
        DayKind.LOWER -> lowerDay(id, dayIndex, occ, anchors, answers)
        DayKind.PUSH -> pushDay(id, dayIndex, answers)
        DayKind.PULL -> pullDay(id, dayIndex, anchors, answers)
        DayKind.LEGS -> legsDay(id, dayIndex, occ, anchors, answers)
    }

    // --- full body (spec §6.3, prototype A/B/C/D) ----------------------------

    private fun fullBodyDay(
        id: String,
        i: Int,
        anchors: List<String>,
        answers: WizardAnswers,
    ): ProgramDay {
        val equip = answers.equipment
        // 2–3 day splits use only the first N anchors; longer cycles reuse them.
        val used = if (answers.daysPerWeek <= anchors.size) {
            anchors.take(answers.daysPerWeek)
        } else {
            anchors
        }
        val anchorId = used[i % used.size]
        val mainPattern = ExerciseLibrary.get(anchorId).pattern
        val lowerMain = mainPattern in LEG_MAIN_PATTERNS

        val incline = i % 2 == 1
        val vertPull = i % 2 == 1
        val kneeFlex = i % 2 == 1
        val soleus = i % 2 == 1
        val singleLeg = i % 2 == 1

        val slots = mutableListOf(main(anchorId))

        // Opposing-region compound.
        slots += if (lowerMain) {
            accessory(pickChest(incline, equip))
        } else {
            accessory(pick(if (singleLeg) SINGLE_LEG else SQUAT_BILATERAL, equip))
        }

        // Hinge-or-squat leg complement. When the main is already the hinge, the
        // complement is a quad move; otherwise it is a posterior move so every
        // day carries a hinge OR knee-flexion (§12 hamstrings rule).
        slots += if (mainPattern == HINGE) {
            accessory(pick(if (singleLeg) SINGLE_LEG else SQUAT_BILATERAL, equip))
        } else {
            accessory(pick(if (kneeFlex) KNEE_FLEXION else HINGE, equip))
        }

        // A pull — or a push, when the main already is a pull.
        slots += if (mainPattern == H_PULL || mainPattern == V_PULL) {
            accessory(pickChest(incline, equip))
        } else {
            accessory(pick(if (vertPull) V_PULL else H_PULL, equip))
        }

        slots += fullBodyIsolation(i, soleus, equip)
        slots += accessory(pick(CORE_ROTATION[i % CORE_ROTATION.size], equip))

        return ProgramDay(
            id = id,
            title = "Full Body",
            emphasisLine = fullBodyEmphasis(incline, vertPull, kneeFlex, soleus, singleLeg),
            exercises = withWarmupHint(slots, mainPattern),
            cardio = finisher(answers, legHeavy = lowerMain),
        )
    }

    /** FB isolation slot rotates delts → arms → calves across days. */
    private fun fullBodyIsolation(i: Int, soleus: Boolean, equip: Set<Equipment>): ProgramExercise =
        when (i % 3) {
            0 -> accessory(pick(SIDE_DELT, equip))
            1 -> accessory(pick(BICEPS, equip))
            else -> accessory(pick(if (soleus) CALF_SOLEUS else CALF_GASTROC, equip))
        }

    private fun fullBodyEmphasis(
        incline: Boolean,
        vertPull: Boolean,
        kneeFlex: Boolean,
        soleus: Boolean,
        singleLeg: Boolean,
    ): String = listOf(
        if (incline) "incline press" else "flat press",
        if (vertPull) "vertical pull" else "horizontal pull",
        if (kneeFlex) "knee-flexion hamstrings" else "hip-hinge hamstrings",
        if (soleus) "soleus calves" else "gastroc calves",
        if (singleLeg) "single-leg quads" else "bilateral quads",
    ).joinToString(" · ")

    // --- upper / lower / push / pull / legs (spec §6.3) ----------------------

    private fun upperDay(
        id: String,
        i: Int,
        occ: Int,
        anchors: List<String>,
        answers: WizardAnswers,
    ): ProgramDay {
        val equip = answers.equipment
        val mainId = anchorFor(H_PUSH, anchors) ?: pick(H_PUSH, equip).id
        val slots = listOf(
            main(mainId),
            accessory(pick(H_PULL, equip)),
            accessory(pick(V_PUSH, equip)),
            accessory(pick(V_PULL, equip)),
            accessory(pick(if (occ % 2 == 0) SIDE_DELT else REAR_DELT, equip)),
            armsSuperset(equip),
            accessory(pick(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Upper",
            "horizontal + vertical push and pull · arms superset",
            withWarmupHint(slots, H_PUSH),
            finisher(answers, legHeavy = false),
        )
    }

    private fun lowerDay(
        id: String,
        i: Int,
        occ: Int,
        anchors: List<String>,
        answers: WizardAnswers,
    ): ProgramDay {
        val equip = answers.equipment
        // Alternate the main across weekly lower days; lead with the hinge so a
        // single lower day in a cycle still contributes one (§12).
        val hingeMain = occ % 2 == 0
        val mainPattern = if (hingeMain) HINGE else SQUAT_BILATERAL
        val otherPattern = if (hingeMain) SQUAT_BILATERAL else HINGE
        val mainId = anchorFor(mainPattern, anchors) ?: pick(mainPattern, equip).id
        val slots = listOf(
            main(mainId),
            accessory(pick(otherPattern, equip)),
            accessory(pick(SINGLE_LEG, equip)),
            accessory(pick(KNEE_FLEXION, equip)),
            accessory(pick(if (occ % 2 == 0) CALF_GASTROC else CALF_SOLEUS, equip)),
            accessory(pick(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Lower",
            "squat + hinge · single-leg · knee-flexion hamstrings",
            withWarmupHint(slots, mainPattern),
            finisher(answers, legHeavy = true),
        )
    }

    private fun pushDay(id: String, i: Int, answers: WizardAnswers): ProgramDay {
        val equip = answers.equipment
        val mainId = "bb_bench"
        val slots = listOf(
            main(mainId),
            accessory(pickChest(incline = true, equip)),
            accessory(pick(V_PUSH, equip)),
            accessory(pick(SIDE_DELT, equip)),
            armsSuperset(equip),
            accessory(pick(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Push",
            "flat + incline press · vertical press · side delts",
            withWarmupHint(slots, H_PUSH),
            finisher(answers, legHeavy = false),
        )
    }

    private fun pullDay(
        id: String,
        i: Int,
        anchors: List<String>,
        answers: WizardAnswers,
    ): ProgramDay {
        val equip = answers.equipment
        val mainId = anchorFor(H_PULL, anchors) ?: pick(H_PULL, equip).id
        val slots = listOf(
            main(mainId),
            accessory(pick(V_PULL, equip)),
            accessory(pick(REAR_DELT, equip)),
            armsSuperset(equip),
            accessory(pick(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Pull",
            "horizontal row · vertical pull · rear delts · arms superset",
            withWarmupHint(slots, H_PULL),
            finisher(answers, legHeavy = false),
        )
    }

    private fun legsDay(
        id: String,
        i: Int,
        occ: Int,
        anchors: List<String>,
        answers: WizardAnswers,
    ): ProgramDay {
        val equip = answers.equipment
        // Lead with the hinge so a solo legs day still carries one (§12).
        val mainPattern = if (occ % 2 == 0) HINGE else SQUAT_BILATERAL
        val mainId = anchorFor(mainPattern, anchors) ?: pick(mainPattern, equip).id
        val slots = listOf(
            main(mainId),
            accessory(pick(SINGLE_LEG, equip)),
            accessory(pick(KNEE_FLEXION, equip)),
            accessory(pick(if (occ % 2 == 0) CALF_GASTROC else CALF_SOLEUS, equip)),
            accessory(pick(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Legs",
            "${if (mainPattern == HINGE) "hinge" else "squat"} main · single-leg · knee-flexion hamstrings",
            withWarmupHint(slots, mainPattern),
            finisher(answers, legHeavy = true),
        )
    }

    // --- slot builders -------------------------------------------------------

    private fun main(id: String): ProgramExercise =
        ProgramExercise(
            exerciseId = id,
            isMain = true,
            targetSets = 6,
            repSchemeLabel = "ramp → top → back-off",
        )

    private fun accessory(entry: ExerciseEntry): ProgramExercise =
        ProgramExercise(exerciseId = entry.id, targetSets = 3, repSchemeLabel = "8–12")

    /** Arms superset: BICEPS primary paired with a TRICEPS partner (spec §6.3). */
    private fun armsSuperset(equip: Set<Equipment>): ProgramExercise =
        ProgramExercise(
            exerciseId = pick(BICEPS, equip).id,
            targetSets = 3,
            repSchemeLabel = "10–15",
            superset = SupersetPartner(pick(TRICEPS, equip).id),
        )

    /** Rank-1 entry for [pattern] whose equipment is all available, else the
     *  rank-1 entry regardless — a slot is never left empty (task requirement). */
    private fun pick(pattern: MovementPattern, equip: Set<Equipment>): ExerciseEntry {
        val ranked = ExerciseLibrary.byPattern(pattern)
        return ranked.firstOrNull { available(it, equip) } ?: ranked.first()
    }

    /** Chest press slot, biased flat or incline, still equipment-aware. */
    private fun pickChest(incline: Boolean, equip: Set<Equipment>): ExerciseEntry {
        val press = ExerciseLibrary.byPattern(H_PUSH)
        val pool = press.filter { (it.id in INCLINE_CHEST_IDS) == incline }
        return pool.firstOrNull { available(it, equip) } ?: pool.firstOrNull() ?: press.first()
    }

    private fun available(entry: ExerciseEntry, equip: Set<Equipment>): Boolean =
        entry.equipment.all { it in equip }

    /** Exactly one warm-up hint per day: the first accessory that loads a new
     *  pattern after the main (spec §12 warm-ups). */
    private fun withWarmupHint(
        slots: List<ProgramExercise>,
        mainPattern: MovementPattern,
    ): List<ProgramExercise> {
        var placed = false
        return slots.map { pe ->
            if (!placed && !pe.isMain && ExerciseLibrary.get(pe.exerciseId).pattern != mainPattern) {
                placed = true
                pe.copy(hasWarmupHint = true)
            } else {
                pe
            }
        }
    }

    // --- cardio --------------------------------------------------------------

    private fun finisher(answers: WizardAnswers, legHeavy: Boolean): CardioSuggestion? =
        CardioPlanner.finisher(answers.cardio, legHeavy)

    /** Standalone Cardio + Core cards for SEPARATE_DAYS / BOTH placements. */
    private fun standaloneCardio(answers: WizardAnswers): List<CardioDay> {
        val prefs = answers.cardio
        if (prefs.mode == CardioMode.NONE) return emptyList()
        val wantsStandalone =
            prefs.placement == CardioPlacement.SEPARATE_DAYS || prefs.placement == CardioPlacement.BOTH
        if (!wantsStandalone) return emptyList()
        val cardio = CardioPlanner.standalone(prefs)
        // Two easy Zone-2 + core sessions a week is a sane maintenance default.
        return (0 until STANDALONE_CARDIO_DAYS).map { k ->
            CardioDay(
                id = "C${k + 1}",
                title = "Cardio + Core",
                cardio = cardio,
                core = accessory(pick(CORE_ROTATION[k % CORE_ROTATION.size], answers.equipment)),
            )
        }
    }

    private const val STANDALONE_CARDIO_DAYS = 2
}
