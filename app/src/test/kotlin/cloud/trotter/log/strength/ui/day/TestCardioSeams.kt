package cloud.trotter.log.strength.ui.day

/** Frozen clock for tests that don't exercise cardio execution. */
class FixedCardioClock(
    private val wall: Long = 0L,
    private val elapsed: Long = 0L,
) : CardioClock {
    override fun wallMillis(): Long = wall
    override fun elapsedRealtimeMillis(): Long = elapsed
}

object InertCardioAlarm : CardioAlarm {
    override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = Unit
    override fun cancel() = Unit
}
