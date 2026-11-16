package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import java.util.UUID

/** An old (v1) wizard plan as its build stored it: the recipe text, the program before rules, and the old
 *  generator's mains for it. Frozen at 6d; OldWizardPlansTest proved each against the generator. Never edit. */
data class OldWizardPlan(
    val recipeJson: String,
    val program: Program,
    val mains: List<String>,
    val equipment: Set<Equipment>,
    val goalsLb: Map<String, Double> = emptyMap(),
    val cardioFirst: Boolean = false,
)

/** The old wizard's recipe text, byte for byte as it wrote it (proved in OldWizardPlansTest). */
fun v1RecipeJson(
    daysPerWeek: Int = 4,
    split: String = "FULL_BODY",
    anchorScheme: String = "PROTOTYPE",
    deadliftVariant: String = "TRAP_BAR",
    emphasis: String = "BALANCED",
    cardioMode: String = "OUTDOOR_RUN",
    cardioPlacement: String = "FINISHERS",
    fiveKGoal: Boolean = true,
    equipment: Collection<Equipment> = Equipment.entries,
    goalsLb: Map<String, Double> = emptyMap(),
    cardioFirst: String? = null,
): String = buildString {
    append("{\"daysPerWeek\":$daysPerWeek,\"split\":\"$split\",\"anchorScheme\":\"$anchorScheme\",")
    append("\"deadliftVariant\":\"$deadliftVariant\",\"emphasis\":\"$emphasis\",\"cardioMode\":\"$cardioMode\",")
    append("\"cardioPlacement\":\"$cardioPlacement\",\"fiveKGoal\":$fiveKGoal,\"equipment\":[")
    append(equipment.joinToString(",") { "\"${it.name}\"" })
    append(']')
    if (goalsLb.isNotEmpty()) {
        append(",\"goalsLb\":{")
        append(goalsLb.entries.joinToString(",") { (id, goal) -> "\"$id\":$goal" })
        append('}')
    }
    if (cardioFirst != null) append(",\"cardioFirst\":$cardioFirst")
    append('}')
}

/** A literal old program day. */
fun oldDay(id: String, title: String, emphasisLine: String, vararg slots: ProgramExercise,
    cardio: CardioBlock? = null, kind: ProgramDayKind = ProgramDayKind.STRENGTH) =
    ProgramDay(id, title, emphasisLine, slots.toList(), kind, cardio)

/** An old ramped main. */
fun rampMain(id: String) = ProgramExercise(id, SetStructure.RAMP_TOP_BACKOFF, 6)

/** An old novice main with its stored load. */
fun noviceMain(id: String, lb: Double, reps: Int = 5) = ProgramExercise(id, SetStructure.STRAIGHT, 3,
    prescription = Prescription(PrescribedTarget.LoadReps(lb, reps)))

/** An old accessory slot. */
fun accessory(id: String) = ProgramExercise(id, targetSets = 3)

/** An old arms superset. */
fun armsPair(biceps: String, triceps: String) = ProgramExercise(biceps, targetSets = 3, superset = SupersetPartner(triceps))

/** The old road-run finisher. */
fun finisher(hard: Boolean) = CardioBlocks.fromLegacy(CardioSuggestion("", "", hard), fiveK = true, CardioActivity.ROAD_RUN)

/** The old cardio-first easy run. */
val easyRun = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800))))

/** The old cardio-first long run. */
val longRun = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(2700))))

/** The old cardio-first interval run. */
val intervalRun = CardioBlocks.fromLegacy(CardioSuggestion("", "", hard = true), fiveK = true, CardioActivity.ROAD_RUN)

/** Stored old wizard plans, frozen independently of the generator and the exercise catalog. */
object OldWizardPlans {
    val DEFAULT = OldWizardPlan(
        recipeJson = v1RecipeJson(),
        program = Program(listOf(
            oldDay("A", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                rampMain("bb_back_squat"), accessory("db_bench"), accessory("rdl"), accessory("cs_row"),
                accessory("cable_lateral"), accessory("plank"), cardio = finisher(false)),
            oldDay("B", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                rampMain("bb_bench"), accessory("bss"), accessory("seated_curl"), accessory("pullup"),
                armsPair("ez_curl", "rope_pushdown"), accessory("pallof"), cardio = finisher(true)),
            oldDay("C", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                rampMain("trap_dl"), accessory("db_bench"), accessory("hack_squat"), accessory("cs_row"),
                accessory("standing_calf"), accessory("cable_crunch"), cardio = finisher(false)),
            oldDay("D", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                rampMain("incline_db"), accessory("bss"), accessory("seated_curl"), accessory("pullup"),
                accessory("cable_lateral"), accessory("plank"), cardio = finisher(true)),
        )),
        mains = listOf("bb_back_squat", "bb_bench", "trap_dl", "incline_db"),
        equipment = Equipment.entries.toSet(),
    )

    val MACHINE_NOVICE = OldWizardPlan(
        recipeJson = v1RecipeJson(equipment = listOf(Equipment.MACHINE), goalsLb = mapOf("hack_squat" to 150.0)),
        program = Program(listOf(
            oldDay("A", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                noviceMain("hack_squat", 150.0), accessory("machine_chest"), accessory("machine_hip_thrust"),
                accessory("cs_row"), accessory("machine_lateral"), cardio = finisher(false)),
            oldDay("B", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                noviceMain("machine_chest", 100.0), accessory("smith_split_squat"), accessory("seated_curl"),
                accessory("assisted_pullup"), armsPair("machine_curl", "machine_tri_ext"), cardio = finisher(true)),
            oldDay("C", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                noviceMain("machine_hip_thrust", 230.0), accessory("machine_chest"), accessory("hack_squat"),
                accessory("cs_row"), accessory("standing_calf"), accessory("machine_crunch"), cardio = finisher(false)),
            oldDay("D", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                noviceMain("pec_deck", 100.0), accessory("smith_split_squat"), accessory("seated_curl"),
                accessory("assisted_pullup"), accessory("machine_lateral"), cardio = finisher(true)),
        )),
        mains = listOf("hack_squat", "machine_chest", "machine_hip_thrust", "pec_deck"),
        equipment = setOf(Equipment.MACHINE),
        goalsLb = mapOf("hack_squat" to 150.0),
    )

    val DUMBBELL_NOVICE = OldWizardPlan(
        recipeJson = v1RecipeJson(equipment = listOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
            goalsLb = mapOf("goblet_squat" to 80.0)),
        program = Program(listOf(
            oldDay("A", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                noviceMain("goblet_squat", 80.0), accessory("db_bench"), accessory("db_rdl"), accessory("db_row"),
                accessory("db_lateral"), accessory("plank"), cardio = finisher(false)),
            oldDay("B", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                noviceMain("db_bench", 60.0), accessory("bss"), accessory("ball_curl"), accessory("db_pullover"),
                armsPair("incline_curl", "db_oh_tri_ext"), accessory("suitcase_carry"), cardio = finisher(true)),
            oldDay("C", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                noviceMain("db_rdl", 55.0), accessory("db_bench"), accessory("goblet_squat"), accessory("db_row"),
                accessory("db_calf"), accessory("weighted_crunch"), cardio = finisher(false)),
            oldDay("D", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                noviceMain("incline_db", 60.0), accessory("bss"), accessory("ball_curl"), accessory("db_pullover"),
                accessory("db_lateral"), accessory("plank"), cardio = finisher(true)),
        )),
        mains = listOf("goblet_squat", "db_bench", "db_rdl", "incline_db"),
        equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
        goalsLb = mapOf("goblet_squat" to 80.0),
    )

    val DUMBBELL_INTERMEDIATE = OldWizardPlan(
        recipeJson = v1RecipeJson(equipment = listOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
            goalsLb = mapOf("goblet_squat" to 80.0)),
        program = Program(listOf(
            oldDay("A", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                rampMain("goblet_squat"), accessory("db_bench"), accessory("db_rdl"), accessory("db_row"),
                accessory("db_lateral"), accessory("plank"), cardio = finisher(false)),
            oldDay("B", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                rampMain("db_bench"), accessory("bss"), accessory("ball_curl"), accessory("db_pullover"),
                armsPair("incline_curl", "db_oh_tri_ext"), accessory("suitcase_carry"), cardio = finisher(true)),
            oldDay("C", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                rampMain("db_rdl"), accessory("db_bench"), accessory("goblet_squat"), accessory("db_row"),
                accessory("db_calf"), accessory("weighted_crunch"), cardio = finisher(false)),
            oldDay("D", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                rampMain("incline_db"), accessory("bss"), accessory("ball_curl"), accessory("db_pullover"),
                accessory("db_lateral"), accessory("plank"), cardio = finisher(true)),
        )),
        mains = listOf("goblet_squat", "db_bench", "db_rdl", "incline_db"),
        equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
        goalsLb = mapOf("goblet_squat" to 80.0),
    )

    val UPPER_LOWER_MACHINE_NOVICE = OldWizardPlan(
        recipeJson = v1RecipeJson(split = "UPPER_LOWER", equipment = listOf(Equipment.MACHINE)),
        program = Program(listOf(
            oldDay("A", "Upper", "horizontal + vertical push and pull · arms superset",
                noviceMain("machine_chest", 100.0), accessory("cs_row"), accessory("machine_shoulder"),
                accessory("assisted_pullup"), accessory("machine_lateral"), armsPair("machine_curl", "machine_tri_ext"),
                cardio = finisher(true)),
            oldDay("B", "Lower", "squat + hinge · single-leg · knee-flexion hamstrings",
                noviceMain("machine_hip_thrust", 230.0), accessory("hack_squat"), accessory("smith_split_squat"),
                accessory("seated_curl"), accessory("standing_calf"), cardio = finisher(false)),
            oldDay("C", "Upper", "horizontal + vertical push and pull · arms superset",
                noviceMain("machine_chest", 100.0), accessory("cs_row"), accessory("machine_shoulder"),
                accessory("assisted_pullup"), accessory("reverse_pec"), armsPair("machine_curl", "machine_tri_ext"),
                accessory("machine_crunch"), cardio = finisher(true)),
            oldDay("D", "Lower", "squat + hinge · single-leg · knee-flexion hamstrings",
                noviceMain("hack_squat", 180.0), accessory("machine_hip_thrust"), accessory("smith_split_squat"),
                accessory("seated_curl"), accessory("seated_calf"), cardio = finisher(false)),
        )),
        mains = listOf("machine_chest", "machine_hip_thrust", "hack_squat"),
        equipment = setOf(Equipment.MACHINE),
    )

    val PPL = OldWizardPlan(
        recipeJson = v1RecipeJson(daysPerWeek = 6, split = "PPL"),
        program = Program(listOf(
            oldDay("A", "Push", "flat + incline press · vertical press · side delts · triceps",
                rampMain("bb_bench"), accessory("incline_bb"), accessory("db_shoulder"), accessory("cable_lateral"),
                accessory("rope_pushdown"), accessory("plank"), cardio = finisher(true)),
            oldDay("B", "Pull", "horizontal row · vertical pull · rear delts · biceps",
                rampMain("bb_row"), accessory("pullup"), accessory("face_pull"), accessory("ez_curl"),
                accessory("pallof"), cardio = finisher(true)),
            oldDay("C", "Legs", "hinge main · single-leg · knee-flexion hamstrings",
                rampMain("trap_dl"), accessory("bss"), accessory("seated_curl"), accessory("standing_calf"),
                accessory("cable_crunch"), cardio = finisher(false)),
            oldDay("D", "Push", "flat + incline press · vertical press · side delts · triceps",
                rampMain("bb_bench"), accessory("incline_bb"), accessory("db_shoulder"), accessory("cable_lateral"),
                accessory("rope_pushdown"), accessory("plank"), cardio = finisher(true)),
            oldDay("E", "Pull", "horizontal row · vertical pull · rear delts · biceps",
                rampMain("bb_row"), accessory("pullup"), accessory("face_pull"), accessory("ez_curl"),
                accessory("pallof"), cardio = finisher(true)),
            oldDay("F", "Legs", "squat main · single-leg · knee-flexion hamstrings",
                rampMain("bb_back_squat"), accessory("bss"), accessory("seated_curl"), accessory("seated_calf"),
                accessory("cable_crunch"), cardio = finisher(false)),
        )),
        mains = listOf("bb_bench", "bb_row", "trap_dl", "bb_back_squat"),
        equipment = Equipment.entries.toSet(),
    )

    val CARDIO_FIRST = OldWizardPlan(
        recipeJson = v1RecipeJson(daysPerWeek = 3,
            cardioFirst = """{"activity":"ROAD_RUN","seconds":1800,"sessions":3,"liftDays":3}"""),
        program = Program(listOf(
            oldDay("A", "Easy", "", cardio = easyRun),
            oldDay("B", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                rampMain("bb_back_squat"), accessory("db_bench"), accessory("rdl"), accessory("cs_row"),
                accessory("cable_lateral"), accessory("plank")),
            oldDay("C", "Intervals", "", cardio = intervalRun),
            oldDay("D", "Full Body", "incline press · vertical pull · knee-flexion hamstrings · soleus calves · single-leg quads",
                rampMain("bb_bench"), accessory("bss"), accessory("seated_curl"), accessory("pullup"),
                armsPair("ez_curl", "rope_pushdown"), accessory("pallof")),
            oldDay("E", "Long", "", cardio = longRun),
            oldDay("F", "Full Body", "flat press · horizontal pull · hip-hinge hamstrings · gastroc calves · bilateral quads",
                rampMain("trap_dl"), accessory("db_bench"), accessory("hack_squat"), accessory("cs_row"),
                accessory("standing_calf"), accessory("cable_crunch")),
        )),
        mains = listOf("bb_back_squat", "bb_bench", "trap_dl"),
        equipment = Equipment.entries.toSet(),
        cardioFirst = true,
    )

    val ALL = listOf(DEFAULT, MACHINE_NOVICE, DUMBBELL_NOVICE, DUMBBELL_INTERMEDIATE,
        UPPER_LOWER_MACHINE_NOVICE, PPL, CARDIO_FIRST)
}

/** A GENERATED plan holding exactly [program], stored the way the old wizard's create stored it, without rules:
 *  the launch gate (runLaunchFixups, a fresh repository) gives every slot its rule and opening rows. */
suspend fun StrengthDatabase.insertGeneratedPlan(name: String, program: Program, recipeJson: String? = null,
    offerCardioIncreases: Boolean = false): Long {
    val id = planDao().insertPlan(PlanEntity(
        id = 0, name = name, origin = PlanOrigin.GENERATED.name,
        position = (planDao().maxPosition() ?: -1) + 1, createdAt = 0L, recipeJson = recipeJson,
        suggestedDayId = program.rotationDays.firstOrNull()?.let { Rotation.trainingDayFrom(program, it.id) },
        offerCardioIncreases = offerCardioIncreases,
    ))
    program.days.forEachIndexed { index, day ->
        programDao().upsertDay(day.toEntity(id).copy(position = index, nextRunId = UUID.randomUUID().toString()))
        day.exercises.forEachIndexed { position, exercise ->
            programDao().insertExercise(exercise.toEntity(id, day.id, position))
        }
    }
    return id
}

/** An old wizard plan as a build before 2a left it: v1 recipe, no rules. */
suspend fun StrengthDatabase.insertOldWizardPlan(plan: OldWizardPlan, name: String = "Old"): Long =
    insertGeneratedPlan(name, plan.program, plan.recipeJson, plan.cardioFirst)

/** An old wizard plan as a 2a to 6b build left it: ruled at creation, its v1 recipe not yet converted. */
suspend fun StrengthDatabase.insertRuledOldWizardPlan(plan: OldWizardPlan, settings: SettingsStore,
    name: String = "Old"): Long {
    val id = insertGeneratedPlan(name, plan.program, null, plan.cardioFirst)
    runLaunchFixups(settings)
    planDao().updateRecipe(id, plan.recipeJson)
    return id
}

/** What a process start's first gated read does: legacy fixups, v1 conversion and slot rules, in Room. */
suspend fun StrengthDatabase.runLaunchFixups(settings: SettingsStore) =
    PlanRepository(this, settings).runPlanSettingsFixupIfNeeded()
