package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.HoldClock
import cloud.trotter.log.strength.domain.standards.TestEvent
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.domain.standards.TestSetType

/** Spacing is preference: every 2 to 4 weeks, with the last benchmark about a week before test day (#646). */
object Benchmark {
    const val MIN_DAYS_BETWEEN = 21
    const val QUIET_DAYS_BEFORE_DATE = 7

    fun due(newestEpochDay: Long?, todayEpochDay: Long, byEpochDay: Long?): Boolean {
        if (byEpochDay != null && byEpochDay - todayEpochDay < QUIET_DAYS_BEFORE_DATE) return false
        return newestEpochDay == null || todayEpochDay - newestEpochDay >= MIN_DAYS_BETWEEN
    }

    fun target(event: TestEvent, ref: TestRef): PrescribedTarget = when (event.setType) {
        TestSetType.REPS_IN_WINDOW -> PrescribedTarget.RepsInTime(requireNotNull(ref.windowSeconds))
        TestSetType.MAX_HOLD -> PrescribedTarget.Hold(event.ceilingSeconds ?: HoldClock.PAST_TARGET_LIMIT_SECONDS)
        TestSetType.REPS_CONTINUOUS, TestSetType.REPS_CADENCE -> error("No benchmark execution for ${event.setType} yet")
    }

    fun row(event: TestEvent, ref: TestRef): LoggedSet = when (val target = target(event, ref)) {
        is PrescribedTarget.RepsInTime -> LoggedSet(0.0, 0, SetKind.WORK)
        is PrescribedTarget.Hold -> LoggedSet(0.0, 0, SetKind.WORK, seconds = target.seconds)
        is PrescribedTarget.LoadReps, is PrescribedTarget.Reps -> error("unreachable")
    }

    fun runnable(event: TestEvent): Boolean =
        event.setType == TestSetType.REPS_IN_WINDOW || event.setType == TestSetType.MAX_HOLD
}
