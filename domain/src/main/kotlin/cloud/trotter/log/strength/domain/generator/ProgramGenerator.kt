package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.MovementPattern.BICEPS
import cloud.trotter.log.strength.domain.model.MovementPattern.CALF_GASTROC
import cloud.trotter.log.strength.domain.model.MovementPattern.CALF_SOLEUS
import cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_EXT
import cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_ROT
import cloud.trotter.log.strength.domain.model.MovementPattern.CORE_FLEX
import cloud.trotter.log.strength.domain.model.MovementPattern.HINGE
import cloud.trotter.log.strength.domain.model.MovementPattern.H_PULL
import cloud.trotter.log.strength.domain.model.MovementPattern.H_PUSH
import cloud.trotter.log.strength.domain.model.MovementPattern.KNEE_FLEXION
import cloud.trotter.log.strength.domain.model.MovementPattern.REAR_DELT
import cloud.trotter.log.strength.domain.model.MovementPattern.SIDE_DELT
import cloud.trotter.log.strength.domain.model.MovementPattern.SINGLE_LEG
import cloud.trotter.log.strength.domain.model.MovementPattern.SQUAT_BILATERAL
import cloud.trotter.log.strength.domain.model.MovementPattern.TRICEPS
import cloud.trotter.log.strength.domain.model.MovementPattern.V_PULL
import cloud.trotter.log.strength.domain.model.MovementPattern.V_PUSH
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner

/**
 * Turns [WizardAnswers] into a concrete, editable [Program] (spec §6.3). Each
 * day is a fixed skeleton of pattern slots. Anchor mains come from the wizard's
 * chosen scheme, substituting available weighted lifts by pattern when needed;
 * every other slot takes the pattern's best-ranked available entry that is
 * not Std-sourced (spec §4: accessory GOALs come from
 * flatGoal or a fraction of a main, never a full strength standard). Muscle-angle
 * emphasis and the core pattern rotate across the cycle (§12 "calves principle").
 * Slots with no available candidate are left out.
 */
object ProgramGenerator {

    private val DAY_IDS = ('A'..'Z').map(Char::toString)
    private val CORE_ROTATION = listOf(CORE_ANTI_EXT, CORE_ANTI_ROT, CORE_FLEX)
    private val LEG_MAIN_PATTERNS = setOf(SQUAT_BILATERAL, HINGE, SINGLE_LEG)
    private val INCLINE_CHEST_IDS = setOf("incline_db", "incline_bb")

    fun generate(answers: WizardAnswers): Program = generate(answers, preserveDayIds = false)

    private fun generate(answers: WizardAnswers, preserveDayIds: Boolean): Program {
        val kinds = SplitDefaults.dayKinds(answers.split, answers.daysPerWeek)
        val anchors = availableAnchorIds(answers)
        val occurrences = mutableMapOf<DayKind, Int>()

        val days = kinds.mapIndexed { i, kind ->
            val occ = occurrences.getOrDefault(kind, 0)
            occurrences[kind] = occ + 1
            buildDay(DAY_IDS[i], i, occ, kind, anchors, answers)
        }
        // A day nothing available can fill is left out.
        val remaining = (days + standaloneCardio(answers)).filter {
            it.exercises.isNotEmpty() || it.cardioBlock != null
        }
        val strengthDayDropped = days.any { it !in remaining }
        var strengthIndex = 0
        return Program(if (strengthDayDropped && !preserveDayIds) remaining.map { day ->
            if (day.kind == ProgramDayKind.CARDIO) day else day.copy(id = DAY_IDS[strengthIndex++])
        } else remaining)
    }

    /** Existing plans can use either the original letters or the compact letters.
     *  Titles and exercises are editable; emphasis and kind can disambiguate a shifted letter. */
    fun templateFor(answers: WizardAnswers, day: ProgramDay): ProgramDay? {
        val compact = generate(answers).days.firstOrNull { it.id == day.id }
        val original = generate(answers, preserveDayIds = true).days.firstOrNull { it.id == day.id }
        if (compact == original) return compact
        return listOfNotNull(compact, original).singleOrNull {
            it.emphasisLine == day.emphasisLine && it.kind == day.kind
        }
    }

    // --- anchors -------------------------------------------------------------

    /** The four anchor lift ids for [a]'s scheme/deadlift choice, in rotation
     *  order (spec §6.1 step 4). Public so the wizard's anchor-step preview reads
     *  the same table (SSOT). */
    fun anchorIds(a: WizardAnswers): List<String> {
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

    /** The scheme's anchors, each unavailable one replaced by its pattern's best
     *  available weighted lift or left out. */
    fun availableAnchorIds(a: WizardAnswers): List<String> {
        val result = mutableListOf<String>()
        for (id in anchorIds(a)) {
            val entry = ExerciseLibrary.get(id)
            val availableId = if (entry.availableWith(a.equipment)) id else {
                ExerciseLibrary.byPattern(entry.pattern).firstOrNull {
                    it.tracking == TrackingType.WEIGHTED && it.availableWith(a.equipment) && it.id !in result
                }?.id
            }
            if (availableId != null) result += availableId
        }
        return result
    }

    /**
     * The anchor ids that actually rotate for [a] (spec §6.1 step 4: "for
     * 2–3-day splits only the first N anchors are used in rotation"). Only the
     * full-body split narrows — and only when the day count is at or below the
     * anchor count; every other split assigns anchors by pattern match, not by
     * position, so its whole anchor set stays active. This is the SSOT
     * [fullBodyDay] builds its rotation from and the wizard's anchor-step
     * preview reads, so the preview can never disagree with the generated
     * program (SSOT — the narrowing rule lives in exactly one place).
     */
    fun activeAnchorIds(a: WizardAnswers): List<String> {
        val anchors = availableAnchorIds(a)
        return if (a.split == SplitTemplate.FULL_BODY && a.daysPerWeek <= anchors.size) {
            anchors.take(a.daysPerWeek)
        } else {
            anchors
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
        DayKind.FULL_BODY -> fullBodyDay(id, dayIndex, answers)
        DayKind.UPPER -> upperDay(id, dayIndex, occ, anchors, answers)
        DayKind.LOWER -> lowerDay(id, dayIndex, occ, anchors, answers)
        DayKind.PUSH -> pushDay(id, dayIndex, anchors, answers)
        DayKind.PULL -> pullDay(id, dayIndex, anchors, answers)
        DayKind.LEGS -> legsDay(id, dayIndex, occ, anchors, answers)
    }

    // --- full body (spec §6.3, prototype A/B/C/D) ----------------------------

    private fun fullBodyDay(
        id: String,
        i: Int,
        answers: WizardAnswers,
    ): ProgramDay {
        val equip = answers.equipment
        // 2–3 day splits use only the first N anchors; longer cycles reuse them.
        // The narrowing rule itself lives in [activeAnchorIds] (SSOT with the
        // wizard preview).
        val used = activeAnchorIds(answers)
        val anchorId = if (used.isEmpty()) null else used[i % used.size]
        val mainPattern: MovementPattern? = anchorId?.let { ExerciseLibrary.get(it).pattern }
        val lowerMain = mainPattern in LEG_MAIN_PATTERNS

        val incline = i % 2 == 1
        val vertPull = i % 2 == 1
        val kneeFlex = i % 2 == 1
        val soleus = i % 2 == 1
        val singleLeg = i % 2 == 1

        val slots = listOfNotNull(
            anchorId?.let(::main),

            // Opposing-region compound.
            if (lowerMain) {
                accessory(pickChest(incline, equip))
            } else {
                accessory(pickAccessory(if (singleLeg) SINGLE_LEG else SQUAT_BILATERAL, equip))
            },

            // Hinge-or-squat leg complement. When the main is already the hinge, the
            // complement is a quad move; otherwise it is a posterior move so every
            // day carries a hinge OR knee-flexion (§12 hamstrings rule).
            if (mainPattern == HINGE) {
                accessory(pickAccessory(if (singleLeg) SINGLE_LEG else SQUAT_BILATERAL, equip))
            } else {
                accessory(pickAccessory(if (kneeFlex) KNEE_FLEXION else HINGE, equip))
            },

            // A pull — or a push, when the main already is a pull.
            if (mainPattern == H_PULL || mainPattern == V_PULL) {
                accessory(pickChest(incline, equip))
            } else {
                accessory(pickAccessory(if (vertPull) V_PULL else H_PULL, equip))
            },

            fullBodyIsolation(i, soleus, equip),
            accessory(pickAccessory(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )

        return ProgramDay(
            id = id,
            title = "Full Body",
            emphasisLine = fullBodyEmphasis(incline, vertPull, kneeFlex, soleus, singleLeg),
            exercises = slots,
            cardioBlock = finisher(answers, legHeavy = lowerMain),
        )
    }

    /** FB isolation slot rotates delts → arms superset → calves across days
     *  (the prototype ran its arms as a superset — spec §8.2's worked examples). */
    private fun fullBodyIsolation(i: Int, soleus: Boolean, equip: Set<Equipment>): ProgramExercise? =
        when (i % 3) {
            0 -> accessory(pickAccessory(SIDE_DELT, equip))
            1 -> armsSuperset(equip)
            else -> accessory(pickAccessory(if (soleus) CALF_SOLEUS else CALF_GASTROC, equip))
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
        val mainId = anchorFor(H_PUSH, anchors) ?: pickMain(H_PUSH, equip)?.id
        val slots = listOfNotNull(
            mainId?.let(::main),
            accessory(pickAccessory(H_PULL, equip)),
            accessory(pickAccessory(V_PUSH, equip)),
            accessory(pickAccessory(V_PULL, equip)),
            accessory(pickAccessory(if (occ % 2 == 0) SIDE_DELT else REAR_DELT, equip)),
            armsSuperset(equip),
            accessory(pickAccessory(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Upper",
            "horizontal + vertical push and pull · arms superset",
            slots,
            cardioBlock = finisher(answers, legHeavy = false),
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
        val mainId = anchorFor(mainPattern, anchors) ?: pickMain(mainPattern, equip)?.id
        val slots = listOfNotNull(
            mainId?.let(::main),
            accessory(pickAccessory(otherPattern, equip)),
            accessory(pickAccessory(SINGLE_LEG, equip)),
            accessory(pickAccessory(KNEE_FLEXION, equip)),
            accessory(pickAccessory(if (occ % 2 == 0) CALF_GASTROC else CALF_SOLEUS, equip)),
            accessory(pickAccessory(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Lower",
            "squat + hinge · single-leg · knee-flexion hamstrings",
            slots,
            cardioBlock = finisher(answers, legHeavy = true),
        )
    }

    private fun pushDay(id: String, i: Int, anchors: List<String>, answers: WizardAnswers): ProgramDay {
        val equip = answers.equipment
        val mainId = anchorFor(H_PUSH, anchors) ?: pickMain(H_PUSH, equip)?.id
        val slots = listOfNotNull(
            mainId?.let(::main),
            accessory(pickChest(incline = true, equip)),
            accessory(pickAccessory(V_PUSH, equip)),
            accessory(pickAccessory(SIDE_DELT, equip)),
            accessory(pickAccessory(TRICEPS, equip)),
            accessory(pickAccessory(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Push",
            "flat + incline press · vertical press · side delts · triceps",
            slots,
            cardioBlock = finisher(answers, legHeavy = false),
        )
    }

    private fun pullDay(
        id: String,
        i: Int,
        anchors: List<String>,
        answers: WizardAnswers,
    ): ProgramDay {
        val equip = answers.equipment
        val mainId = anchorFor(H_PULL, anchors) ?: pickMain(H_PULL, equip)?.id
        val slots = listOfNotNull(
            mainId?.let(::main),
            accessory(pickAccessory(V_PULL, equip)),
            accessory(pickAccessory(REAR_DELT, equip)),
            accessory(pickAccessory(BICEPS, equip)),
            accessory(pickAccessory(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Pull",
            "horizontal row · vertical pull · rear delts · biceps",
            slots,
            cardioBlock = finisher(answers, legHeavy = false),
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
        val mainId = anchorFor(mainPattern, anchors) ?: pickMain(mainPattern, equip)?.id
        val slots = listOfNotNull(
            mainId?.let(::main),
            accessory(pickAccessory(SINGLE_LEG, equip)),
            accessory(pickAccessory(KNEE_FLEXION, equip)),
            accessory(pickAccessory(if (occ % 2 == 0) CALF_GASTROC else CALF_SOLEUS, equip)),
            accessory(pickAccessory(CORE_ROTATION[i % CORE_ROTATION.size], equip)),
        )
        return ProgramDay(
            id, "Legs",
            "${if (mainPattern == HINGE) "hinge" else "squat"} main · single-leg · knee-flexion hamstrings",
            slots,
            cardioBlock = finisher(answers, legHeavy = true),
        )
    }

    // --- slot builders -------------------------------------------------------

    private fun main(id: String): ProgramExercise {
        require(ExerciseLibrary.get(id).tracking == TrackingType.WEIGHTED) {
            "main slot '$id' must be WEIGHTED; only weighted lifts ramp/cascade (§2.1)"
        }
        return ProgramExercise(
            exerciseId = id,
            structure = SetStructure.RAMP_TOP_BACKOFF,
            targetSets = 6,
        )
    }

    private fun accessory(entry: ExerciseEntry?): ProgramExercise? =
        entry?.let { ProgramExercise(exerciseId = it.id, targetSets = 3) }

    /** Arms superset: BICEPS primary paired with a TRICEPS partner (spec §6.3). */
    private fun armsSuperset(equip: Set<Equipment>): ProgramExercise? {
        val biceps = pickAccessory(BICEPS, equip)
        val triceps = pickAccessory(TRICEPS, equip)
        return if (biceps != null && triceps != null) {
            ProgramExercise(
                exerciseId = biceps.id,
                targetSets = 3,
                superset = SupersetPartner(triceps.id),
            )
        } else accessory(biceps ?: triceps)
    }

    /** Best-ranked available weighted entry for a main slot, or no lift. */
    private fun pickMain(pattern: MovementPattern, equip: Set<Equipment>): ExerciseEntry? =
        ExerciseLibrary.byPattern(pattern).firstOrNull {
            it.tracking == TrackingType.WEIGHTED && it.availableWith(equip)
        }

    /** Accessory pick: skips Std-sourced (main-capable) entries
     *  — spec §4: accessory GOALs come from flatGoal or a fraction of a main,
     *  never a full strength-standard weight. */
    private fun pickAccessory(pattern: MovementPattern, equip: Set<Equipment>): ExerciseEntry? =
        bestAccessory(ExerciseLibrary.byPattern(pattern), equip)

    /** Chest press accessory slot, biased flat or incline, equipment-aware. */
    private fun pickChest(incline: Boolean, equip: Set<Equipment>): ExerciseEntry? {
        val press = ExerciseLibrary.byPattern(H_PUSH)
        val pool = press.filter { (it.id in INCLINE_CHEST_IDS) == incline }
        return bestAccessory(pool, equip) ?: bestAccessory(press, equip)
    }

    private fun bestAccessory(ranked: List<ExerciseEntry>, equip: Set<Equipment>): ExerciseEntry? =
        ranked.filter { it.goal !is GoalSource.Std }.firstOrNull { it.availableWith(equip) }

    // --- cardio --------------------------------------------------------------

    private fun finisher(answers: WizardAnswers, legHeavy: Boolean): CardioBlock? =
        CardioPlanner.finisher(answers.cardio, legHeavy)

    /** Standalone Cardio + Core cards for SEPARATE_DAYS / BOTH placements. */
    private fun standaloneCardio(answers: WizardAnswers): List<ProgramDay> {
        val prefs = answers.cardio
        if (prefs.mode == CardioMode.NONE) return emptyList()
        val wantsStandalone =
            prefs.placement == CardioPlacement.SEPARATE_DAYS || prefs.placement == CardioPlacement.BOTH
        if (!wantsStandalone) return emptyList()
        val cardio = CardioPlanner.standalone(prefs) ?: return emptyList()
        // Two easy Zone-2 + core sessions a week is a sane maintenance default.
        return (0 until STANDALONE_CARDIO_DAYS).map { k ->
            ProgramDay(
                id = "C${k + 1}",
                title = "Cardio + Core",
                emphasisLine = CardioPlanner.standaloneDetail(prefs),
                exercises = listOfNotNull(accessory(pickAccessory(CORE_ROTATION[k % CORE_ROTATION.size], answers.equipment))),
                cardioBlock = cardio,
                kind = ProgramDayKind.CARDIO,
            )
        }
    }

    private const val STANDALONE_CARDIO_DAYS = 2
}
