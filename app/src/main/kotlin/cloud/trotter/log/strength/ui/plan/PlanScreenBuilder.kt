package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.ui.day.DayScreenBuilder

object PlanScreenBuilder {
    fun days(
        program: Program,
        nextDayId: String?,
        config: LifterConfig,
        catalog: ExerciseCatalog,
    ): List<PlanDay> =
        program.strengthDays.mapIndexed { index, day ->
            PlanDay(
                dayId = day.id,
                dayIndex = index,
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
            )
        }
}
