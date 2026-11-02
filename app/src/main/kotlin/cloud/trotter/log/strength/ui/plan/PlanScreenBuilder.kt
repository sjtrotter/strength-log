package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.setup.SetupStateBuilder

object PlanScreenBuilder {
    fun buildUiState(
        plan: PlanEntity?,
        program: Program,
        answers: WizardAnswers,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
    ): PlanUiState {
        val days = days(program, plan?.suggestedDayId, answers.config, catalog)
        val generated = plan?.origin == PlanOrigin.GENERATED.name && days.isNotEmpty()
        return PlanUiState(
            name = plan?.name,
            planId = plan?.id,
            hasProgram = days.isNotEmpty(),
            days = days,
            goals = if (generated) SetupStateBuilder.goalPreview(answers.config, answers, unit) else emptyList(),
            emphasis = if (generated) answers.config.emphasis else null,
        )
    }

    fun days(
        program: Program,
        nextDayId: String?,
        config: LifterConfig,
        catalog: ExerciseCatalog,
    ): List<PlanDay> =
        program.days.map { day ->
            PlanDay(
                dayId = day.id,
                dayIndex = program.strengthDays.indexOf(day).coerceAtLeast(0),
                title = day.title,
                liftCount = day.exercises.size,
                setCount = day.exercises.mapIndexed { position, exercise ->
                    DayScreenBuilder.previewMainSets(
                        ProgramSlot(programExerciseId = position.toLong(), position = position, exercise = exercise),
                        config,
                        catalog,
                    ).size
                }.sum(),
                isNext = day.id == nextDayId,
                kind = day.kind,
                cardioActivity = day.cardioBlock?.activity,
                cardioSetCount = day.cardioBlock?.sets?.size ?: 0,
            )
        }
}
