package cloud.trotter.log.strength.domain.model

import java.time.LocalDate

object PlanGoalDate {
    val years: IntRange = 2000..2100
    val epochDays: LongRange = LocalDate.of(years.first, 1, 1).toEpochDay()..
        LocalDate.of(years.last, 12, 31).toEpochDay()

    fun isValid(epochDay: Long?): Boolean = epochDay == null || epochDay in epochDays
}
