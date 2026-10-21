package cloud.trotter.log.strength.ui.day

/** Frozen clock for tests that don't exercise cardio execution. */
class FixedCardioClock(
    private val wall: Long = 0L,
    private val elapsed: Long = 0L,
) : CardioClock {
    override fun wallMillis(): Long = wall
    override fun elapsedRealtimeMillis(): Long = elapsed
    override fun bootCount(): Int = 0
}

object InertCardioAlarm : CardioAlarm {
    override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = Unit
    override fun cancel() = Unit
}

/** Structured prescription for screen fixtures; these pages use the same block as the runner. */
fun cardioFixture(): CardioCardState {
    val block = cloud.trotter.log.strength.domain.model.CardioBlock(
        cloud.trotter.log.strength.domain.model.CardioActivity.ROAD_RUN,
        listOf(cloud.trotter.log.strength.domain.model.CardioSetSpec(
            cloud.trotter.log.strength.domain.model.CardioRole.WORK,
            cloud.trotter.log.strength.domain.model.Measure.Time(300),
        )),
    )
    return CardioCardState("Road run", "", false, block = block)
}

fun runningCardioFixture(): CardioCardState {
    val ready = cardioFixture()
    val json = cloud.trotter.log.strength.data.serialization.CardioBlockJson.encode(requireNotNull(ready.block))
    return ready.copy(phase = CardioPhase.EXECUTING, stepSecondsLeft = 60, elapsedSeconds = 240,
        run = CardioRun(1L, "Plan", "D", 0, json, json, false, null,
            startedAt = 1_000L, wallStart = 1_000L, elapsedStart = 1_000L, bootCount = 1))
}
