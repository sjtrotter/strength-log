package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.records.GoalProgress
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.SlotSeeding
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.setup.SetupStateBuilder

object PlanScreenBuilder {
    fun buildUiState(
        plan: PlanEntity?,
        program: Program,
        answers: WizardAnswers,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
        cardio: List<Pair<CardioActivity?, List<CardioSetResult>>> = emptyList(),
    ): PlanUiState {
        val days = days(program, plan?.suggestedDayId, unit, catalog)
        val generated = plan?.origin == PlanOrigin.GENERATED.name && days.any { it.liftCount > 0 }
        return PlanUiState(
            goal = PlanGoalJson.decode(plan?.goalJson)?.let { goal ->
                when (goal) {
                    is PlanGoal.Timed -> GoalProgress.bestTimed(goal, cardio).let { best ->
                        PlanGoalUi(goal, best, GoalProgress.met(goal, best))
                    }
                    is PlanGoal.Shuttle -> GoalProgress.bestShuttles(goal).let { best ->
                        PlanGoalUi(goal, best, GoalProgress.met(goal, best))
                    }
                }
            },
            unit = unit,
            name = plan?.name,
            planId = plan?.id,
            hasProgram = days.isNotEmpty(),
            offerStrengthIncreases = plan?.offerStrengthIncreases ?: true,
            offerCardioIncreases = plan?.offerCardioIncreases ?: false,
            days = days,
            goals = if (generated) SetupStateBuilder.goalPreview(answers.config, answers, unit) else emptyList(),
            emphasis = if (generated) answers.config.emphasis else null,
        )
    }

    fun days(
        program: Program,
        nextDayId: String?,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
    ): List<PlanDay> =
        program.days.map { day ->
            val trainingDay = day.takeUnless { it.kind == ProgramDayKind.REST }
            PlanDay(
                dayId = day.id,
                dayIndex = Program.accentIndex(day.id),
                title = day.title,
                liftCount = trainingDay?.exercises?.size ?: 0,
                setCount = trainingDay?.exercises.orEmpty().sumOf { openingRows(it, catalog.find(it.exerciseId), unit) },
                isNext = day.id == nextDayId,
                kind = day.kind,
                cardioActivity = trainingDay?.cardioBlock?.activity,
                cardioSetCount = trainingDay?.cardioBlock?.sets?.size ?: 0,
            )
        }

    /** Rows [exercise] opens with when nothing is stored, from [SlotSeeding] with no loads known; the count never
     *  depends on a load. An unruled slot (test fixtures only) counts under the defaults for its structure. */
    private fun openingRows(exercise: ProgramExercise, entry: ExerciseEntry?, unit: WeightUnit): Int {
        if (entry == null && exercise.prescription == null) return exercise.targetSets
        val rule = exercise.rule ?: SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, exercise.structure, null,
            entry?.pattern ?: MovementPattern.CORE_FLEX)
        return SlotSeeding.rows(exercise, rule, KnownLoads.NONE, LoadSteps.defaults(unit), unit, entry).rows.size
    }
}
