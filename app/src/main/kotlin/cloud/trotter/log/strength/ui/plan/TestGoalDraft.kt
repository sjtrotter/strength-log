package cloud.trotter.log.strength.ui.plan

import android.os.Parcelable
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.units.ClockText
import kotlinx.parcelize.Parcelize

@Parcelize
data class TestGoalDraft(val required: Map<String, String> = emptyMap(), val byEpochDay: Long? = null) : Parcelable {
    internal fun requiredValue(eventId: String, timed: Boolean): Int? {
        val text = required[eventId].orEmpty()
        val value = if (timed) ClockText.parse(text) else text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
        return value?.takeIf { it in 1..3600 }
    }

    internal fun goal(stored: TestGoal, rows: List<PlanTestEventUi>): TestGoal? {
        if (!PlanGoalDate.isValid(byEpochDay)) return null
        val events = stored.events.map {
            val row = rows.firstOrNull { row -> row.event.ref == it.ref } ?: return null
            val value = requiredValue(it.ref.eventId, row.timed) ?: return null
            it.copy(required = value)
        }
        return TestGoal(events, byEpochDay)
    }
}
