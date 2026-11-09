package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.seeding.SlotSeeds
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalTarget
import kotlinx.parcelize.Parcelize

enum class PrescriptionKind { LOAD_REPS, REPS, HOLD, REPS_IN_TIME }

/** Canonical pounds stay stable if the display unit changes while this page is open. */
@Parcelize
data class PrescriptionDraft(
    val slotId: Long,
    val sets: Int = 3,
    val reps: Int = 10,
    val seconds: Int = 30,
    val loadLb: Double = 0.0,
    val repAim: Int = 10,
    val hasRepAim: Boolean = false,
    val eachSide: Boolean = false,
    val resistance: String = "",
    val kind: PrescriptionKind = PrescriptionKind.LOAD_REPS,
) : Parcelable {
    fun prescription(): Prescription = Prescription(
        target = when (kind) {
            PrescriptionKind.LOAD_REPS -> PrescribedTarget.LoadReps(loadLb, reps)
            PrescriptionKind.REPS -> PrescribedTarget.Reps(reps)
            PrescriptionKind.HOLD -> PrescribedTarget.Hold(seconds, loadLb)
            PrescriptionKind.REPS_IN_TIME -> PrescribedTarget.RepsInTime(seconds, repAim.takeIf { hasRepAim })
        },
        eachSide = eachSide,
        resistance = resistance,
    )

    companion object {
        private const val STRAIGHT_SETS = 3

        /** Straight sets from a ramped slot: three sets at its current working weight and reps (#500). */
        fun straightFrom(
            slot: DayEditSlotState,
            working: List<LoggedSet>?,
            catalog: ExerciseCatalog,
            config: LifterConfig,
        ): PrescriptionDraft {
            val base = from(slot, catalog, config)
            val set = working?.let { rows -> WorkingRows.workingIndices(rows).firstOrNull()?.let(rows::get) }
            return base.copy(
                kind = PrescriptionKind.LOAD_REPS,
                sets = STRAIGHT_SETS,
                loadLb = set?.weightLb ?: base.loadLb,
                reps = set?.reps?.takeIf { it >= 1 } ?: base.reps,
            )
        }

        fun from(slot: DayEditSlotState, catalog: ExerciseCatalog, config: LifterConfig): PrescriptionDraft {
            val prescription = slot.prescription
            val seeded = SlotSeeds.main(
                ProgramExercise(slot.exerciseId, slot.structure, slot.targetSets), catalog.find(slot.exerciseId), config,
            )
            val working = seeded?.let { rows -> WorkingRows.workingIndices(rows).firstOrNull()?.let(rows::get) }
            val draft = PrescriptionDraft(
                slotId = slot.programExerciseId, sets = slot.targetSets.coerceIn(1, 20),
                reps = working?.reps?.takeIf { it >= 1 } ?: 10,
                eachSide = prescription?.eachSide ?: false, resistance = prescription?.resistance.orEmpty(),
            )
            val target = prescription?.target ?: catalog.find(slot.exerciseId)?.let { entry ->
                when (val goal = GoalCalculator.targetFor(entry, config)) {
                    is GoalTarget.Weight -> PrescribedTarget.LoadReps(goal.lb, draft.reps)
                    is GoalTarget.Reps -> PrescribedTarget.Reps(goal.reps)
                    is GoalTarget.Time -> PrescribedTarget.Hold(goal.seconds, goal.addedLb)
                }
            }
            return when (target) {
                is PrescribedTarget.LoadReps -> draft.copy(loadLb = target.lb, reps = target.reps.coerceAtLeast(1))
                is PrescribedTarget.Reps -> draft.copy(kind = PrescriptionKind.REPS, reps = target.reps.coerceAtLeast(1))
                is PrescribedTarget.Hold -> draft.copy(kind = PrescriptionKind.HOLD, seconds = target.seconds.coerceAtLeast(5), loadLb = target.addedLb)
                is PrescribedTarget.RepsInTime -> draft.copy(
                    kind = PrescriptionKind.REPS_IN_TIME, seconds = target.seconds.coerceAtLeast(5),
                    hasRepAim = target.repAim != null, repAim = target.repAim?.coerceAtLeast(1) ?: 10,
                )
                null -> draft
            }
        }
    }
}
