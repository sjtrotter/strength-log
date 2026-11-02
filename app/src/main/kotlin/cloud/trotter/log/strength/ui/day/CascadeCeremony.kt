package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.parcelize.Parcelize

/** One new top set on the receipt: the previous high and its replacement. */
@Parcelize
data class CascadeLift(
    val name: String,
    val metDisplay: String,
    val newDisplay: String,
) : Parcelable

object CascadeCeremonyBuilder {

    /** Each lift's all-time heaviest completed top set, from the history rows
     *  read *before* an advance appends to them. */
    fun allTimeHighs(rows: List<TopSetRow>): Map<String, Double> =
        rows.groupBy { it.exerciseId }.mapValues { (_, forLift) -> forLift.maxOf { it.topWeightLb } }

    /**
     * The new top sets for a just-recorded session, empty when nothing moved.
     *
     * A lift qualifies only when it already had a high ([previousHighs]) and the
     * session's completed top set beat it — stricter than the journal's
     * new-high marker, which also marks a lift's very first session. A first-ever
     * top set is a starting point, not a progression, and gets no ceremony.
     *
     * Several lifts can qualify on one DONE (a deload edit across a day); they
     * stack as rows on the receipt, in the order they were performed.
     */
    fun from(
        previousHighs: Map<String, Double>,
        sessionSets: List<SessionSetEntity>,
        unit: WeightUnit,
    ): List<CascadeLift> {
        val tops = LinkedHashMap<String, SessionSetEntity>()
        for (set in sessionSets) {
            if (!set.done || set.kind != SetKind.TOP.name) continue
            val best = tops[set.exerciseId]
            if (best == null || set.weightLb > best.weightLb) tops[set.exerciseId] = set
        }
        val lifts = tops.values.mapNotNull { set ->
            val previous = previousHighs[set.exerciseId] ?: return@mapNotNull null
            if (set.weightLb <= previous) return@mapNotNull null
            CascadeLift(
                name = set.exerciseName,
                metDisplay = WeightStepper.format(unit.fromLb(previous)),
                newDisplay = WeightStepper.format(unit.fromLb(set.weightLb)),
            )
        }
        return lifts
    }
}
