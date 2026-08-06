package cloud.trotter.log.strength.ui.today

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.log.LogScreenBuilder

/**
 * The pure decision logic behind the Today screen (#121): the statement's
 * overline, the one action's label, the rotation rail and the last-session
 * strip. Kept free of Android and coroutines so every rule is unit-testable on
 * the JVM; [TodayViewModel] only wires these to repository flows.
 *
 * Anything the glance surfaces already say lives in
 * [cloud.trotter.log.strength.domain.glance.GlanceLines], not here — Today
 * prints the same day and stat lines as the widget and the watch.
 */
object TodayScreenBuilder {

    /**
     * The statement's overline. Reads the same three phases the day itself has,
     * with the `>=` (not `==`) completion test GlanceLines documents: an
     * over-count is better read as a finished day than as an impossible ratio.
     */
    fun overline(doneSets: Int, totalSets: Int): String = when {
        totalSets > 0 && doneSets >= totalSets -> "READY TO FINISH"
        doneSets > 0 -> "IN PROGRESS"
        else -> "NEXT IN ROTATION"
    }

    /** The one dominant action's label, in the same three phases as [overline]. */
    fun actionLabel(dayId: String, doneSets: Int, totalSets: Int): String = when {
        totalSets > 0 && doneSets >= totalSets -> "FINISH DAY ${dayId.uppercase()}"
        doneSets > 0 -> "CONTINUE — $doneSets OF $totalSets SETS"
        else -> "START DAY ${dayId.uppercase()}"
    }

    /** The rotation rail: every day of the cycle in order, with [nextDayId] marked. */
    fun rotationMarks(days: List<String>, nextDayId: String): List<RotationMark> =
        days.mapIndexed { index, id -> RotationMark(id, index, isNext = id == nextDayId) }

    /**
     * The heaviest TOP set of one session, named — `null` when [rows] is empty.
     * The caller filters [rows] to a single session; ties keep the first row.
     */
    fun topLiftDisplay(rows: List<TopSetRow>, catalog: ExerciseCatalog, unit: WeightUnit): String? {
        val row = rows.maxByOrNull { it.topWeightLb } ?: return null
        val name = catalog.find(row.exerciseId)?.name ?: row.exerciseId
        return "$name ${WeightStepper.format(unit.fromLb(row.topWeightLb))}"
    }

    /**
     * The quiet last-session strip — "Jul 30, 2026 · 18 sets · Back Squat 245".
     * `null` with no history at all, and the strip is then *absent* from the
     * screen rather than showing placeholder copy. [sessions] arrives
     * newest-first ([cloud.trotter.log.strength.data.TrackerRepository.sessionSummariesFlow]'s
     * contract); the top-lift part drops out silently when that session logged
     * no TOP set.
     */
    fun lastSessionLine(
        sessions: List<SessionSummaryRow>,
        topSets: List<TopSetRow>,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
    ): String? {
        val row = sessions.firstOrNull() ?: return null
        return listOfNotNull(
            LogScreenBuilder.dateDisplay(row.session.completedAt),
            "${row.setCount} sets",
            topLiftDisplay(topSets.filter { it.sessionId == row.session.id }, catalog, unit),
        ).joinToString(" · ")
    }
}
