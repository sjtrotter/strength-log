package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.progression.WorkingRows
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

        /** Straight sets from a ramped slot: three sets at the live track's working weight and reps (#500), else the
         *  slot's own through [from]. */
        fun straightFrom(
            slot: DayEditSlotState,
            working: List<LoggedSet>?,
            catalog: ExerciseCatalog,
        ): PrescriptionDraft {
            val base = from(slot, catalog)
            val set = working?.let { rows -> WorkingRows.workingIndices(rows).firstOrNull()?.let(rows::get) }
            return base.copy(
                kind = PrescriptionKind.LOAD_REPS,
                sets = STRAIGHT_SETS,
                loadLb = set?.weightLb ?: base.loadLb,
                reps = set?.reps?.takeIf { it >= 1 } ?: base.reps,
            )
        }

        /** The slot's prescription, else its first working row read by the catalog's tracking. */
        fun from(slot: DayEditSlotState, catalog: ExerciseCatalog): PrescriptionDraft {
            val prescription = slot.prescription
            val working = slot.rows?.let { rows -> WorkingRows.workingIndices(rows).firstOrNull()?.let(rows::get) }
            val draft = PrescriptionDraft(
                slotId = slot.programExerciseId, sets = slot.targetSets.coerceIn(1, 20),
                reps = working?.reps?.takeIf { it >= 1 } ?: 10,
                eachSide = prescription?.eachSide ?: false, resistance = prescription?.resistance.orEmpty(),
            )
            val target = prescription?.target
                ?: catalog.find(slot.exerciseId)?.let { unprescribedTarget(it, working, draft.reps) }
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

        /** Without a working row (an empty stored track), a REPS or TIMED entry takes its catalog target; a weighted
         *  one keeps the class defaults, because a load needs the slot's rule. */
        private fun unprescribedTarget(entry: ExerciseEntry, working: LoggedSet?, reps: Int): PrescribedTarget? =
            when (entry.tracking) {
                TrackingType.WEIGHTED -> working?.let { PrescribedTarget.LoadReps(it.weightLb, reps) }
                TrackingType.TIMED -> working?.let { PrescribedTarget.Hold(it.seconds, it.weightLb) }
                    ?: (entry.goal as GoalSource.Time).let { PrescribedTarget.Hold(it.targetSeconds, it.addedWeightLb) }
                TrackingType.REPS -> PrescribedTarget.Reps(
                    working?.reps?.takeIf { it >= 1 } ?: (entry.goal as GoalSource.Reps).targetReps)
            }
    }
}
