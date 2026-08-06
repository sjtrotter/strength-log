package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit

/**
 * The cascade's moment (docs/briefs/journal.md §2): the one time this app
 * celebrates anything. A main lift's ramp moves up when the lifter edits its TOP
 * set — but the number is only *earned* on the DONE that records it completed
 * above everything before it. That is the transition this event marks.
 *
 * It is a moment, not data: built once inside the DONE-advance call and held in
 * a plain ViewModel field, so process death loses it and nothing can replay it.
 * Deliberately outside [DayUiState] — a value derived from state would re-fire
 * every time the state re-emits.
 */
data class CascadeCeremony(
    val dayIndex: Int,
    val lifts: List<CascadeLift>,
)

/** One lift's line in the scrim: the weight it had been standing at, struck
 *  through, and the weight it just moved to. */
data class CascadeLift(
    val name: String,
    val metDisplay: String,
    val newDisplay: String,
)

object CascadeCeremonyBuilder {

    /** Each lift's all-time heaviest completed top set, from the history rows
     *  read *before* an advance appends to them. */
    fun allTimeHighs(rows: List<TopSetRow>): Map<String, Double> =
        rows.groupBy { it.exerciseId }.mapValues { (_, forLift) -> forLift.maxOf { it.topWeightLb } }

    /**
     * The ceremony for a just-recorded session, or null when nothing cascaded.
     *
     * A lift qualifies only when it already had a high ([previousHighs]) and the
     * session's completed top set beat it — stricter than the journal's
     * new-high marker, which also marks a lift's very first session. A first-ever
     * top set is a starting point, not a progression, and gets no scrim.
     *
     * Several lifts can qualify on one DONE (a deload edit across a day); they
     * stack as lines in the single scrim, in the order they were performed.
     */
    fun from(
        previousHighs: Map<String, Double>,
        sessionSets: List<SessionSetEntity>,
        dayIndex: Int,
        unit: WeightUnit,
    ): CascadeCeremony? {
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
        return if (lifts.isEmpty()) null else CascadeCeremony(dayIndex, lifts)
    }
}
