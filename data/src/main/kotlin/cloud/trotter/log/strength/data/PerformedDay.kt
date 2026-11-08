package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.standards.GoalTarget

data class PerformedTrack(
    val performed: PerformedExercise,
    val goal: GoalTarget?,
    val stored: List<LoggedSet>?,
    val seed: List<LoggedSet>?,
    val note: String = "",
) {
    val tracking: TrackingType get() = performed.tracking
    val sets: List<LoggedSet> get() = stored ?: seed.orEmpty()
    val loadLb: Double
        get() = when (val prescribedTarget = performed.prescription?.target) {
            is PrescribedTarget.LoadReps -> prescribedTarget.lb
            is PrescribedTarget.Hold -> prescribedTarget.addedLb
            is PrescribedTarget.Reps, is PrescribedTarget.RepsInTime -> 0.0
            null -> when (val target = goal) {
                is GoalTarget.Weight -> target.lb
                is GoalTarget.Time -> target.addedLb
                is GoalTarget.Reps, null -> 0.0
            }
        }
    val showsLoad: Boolean get() = tracking == TrackingType.TIMED && loadLb > 0.0
}

data class PerformedSlot(val slot: ProgramSlot, val main: PerformedTrack, val partner: PerformedTrack?)

data class PerformedDay(
    val planId: Long,
    val dayId: String,
    val program: Program,
    val day: ProgramDay?,
    val config: LifterConfig,
    val equipment: Set<Equipment>,
    val catalog: ExerciseCatalog,
    val tracks: DayTracks,
    val slots: List<PerformedSlot>,
    /** Every planned slot's performed exercises, skipped lifts and dropped partners included:
     *  the day reads history for the whole plan, not only what is left to do. */
    val historyExerciseIds: List<String>,
) {

    companion object {
        fun of(
            planId: Long,
            dayId: String,
            program: Program,
            slots: List<ProgramSlot>,
            tracks: DayTracks,
            answers: WizardAnswers,
            catalog: ExerciseCatalog,
        ): PerformedDay {
            val workoutSlots = slots.forRun(tracks.droppedPartners, tracks.skippedLifts).sortedBy { it.position }
            val stored = tracks.tracks.associateBy { it.programExerciseId to it.slot }
            val seeds = DaySeeds.plan(workoutSlots, stored.mapValues { it.value.sets.size }, answers.config, catalog)
            fun track(slot: ProgramSlot, trackSlot: String): PerformedTrack {
                val key = slot.programExerciseId to trackSlot
                val performed = performedExercise(slot, stored[key]?.exerciseId, catalog, trackSlot)
                return PerformedTrack(performed, performed.goal(answers.config), stored[key]?.sets, seeds[key],
                    note = stored[key]?.note.orEmpty())
            }
            return PerformedDay(
                planId, dayId, program, program.days.firstOrNull { it.id == dayId },
                answers.config, answers.equipment, catalog, tracks,
                workoutSlots.map { slot ->
                    PerformedSlot(slot, track(slot, Slot.MAIN), slot.exercise.superset?.let { track(slot, Slot.SS) })
                },
                slots.sortedBy { it.position }.flatMap { slot ->
                    listOfNotNull(
                        performedExercise(slot, stored[slot.programExerciseId to Slot.MAIN]?.exerciseId, catalog).exerciseId,
                        slot.exercise.superset?.let {
                            performedExercise(slot, stored[slot.programExerciseId to Slot.SS]?.exerciseId, catalog, Slot.SS).exerciseId
                        },
                    )
                }.distinct(),
            )
        }
    }
}
