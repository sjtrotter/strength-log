package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.standards.GoalTarget

data class PerformedTrack(
    val performed: PerformedExercise,
    val goal: GoalTarget.Weight?,
    val stored: List<LoggedSet>?,
    val seed: List<LoggedSet>?,
    val note: String = "",
    /** The rows Start would open this track with, when it opens differently (spec W2b-3c decision 4); null in a run. */
    val opening: List<LoggedSet>? = null,
) {
    val tracking: TrackingType get() = performed.tracking
    /** The stored rows (the run's own during a run), else the seed: what a next-time offer describes. */
    val baseRows: List<LoggedSet>? get() = stored ?: seed
    val sets: List<LoggedSet> get() = opening ?: baseRows.orEmpty()
    val loadLb: Double
        get() = when (val prescribedTarget = performed.prescription?.target) {
            is PrescribedTarget.LoadReps -> prescribedTarget.lb
            is PrescribedTarget.Hold -> prescribedTarget.addedLb
            is PrescribedTarget.Reps, is PrescribedTarget.RepsInTime -> 0.0
            null -> if (tracking == TrackingType.TIMED)
                WorkingRows.workingIndices(sets).firstOrNull()?.let { sets[it].weightLb } ?: 0.0
            else goal?.lb ?: 0.0
        }
    /** Whether this hold takes an added load: the prescription's, else the catalog's, never the current rows. */
    val showsLoad: Boolean get() = tracking == TrackingType.TIMED && if (performed.prescription == null)
        ((performed.entry?.goal as? GoalSource.Time)?.addedWeightLb ?: 0.0) > 0.0 else loadLb > 0.0
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
            seeds: Map<Pair<Long, String>, List<LoggedSet>>,
            openings: Map<Pair<Long, String>, List<LoggedSet>> = emptyMap(),
        ): PerformedDay {
            val workoutSlots = slots.forRun(tracks.droppedPartners, tracks.skippedLifts).sortedBy { it.position }
            val stored = tracks.tracks.associateBy { it.programExerciseId to it.slot }
            fun track(slot: ProgramSlot, trackSlot: String): PerformedTrack {
                val key = slot.programExerciseId to trackSlot
                val performed = performedExercise(slot, stored[key]?.exerciseId, catalog, trackSlot)
                return PerformedTrack(performed, performed.goal(answers.config), stored[key]?.sets, seeds[key],
                    note = stored[key]?.note.orEmpty(), opening = openings[key])
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
