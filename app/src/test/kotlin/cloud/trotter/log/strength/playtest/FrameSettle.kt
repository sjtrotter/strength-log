package cloud.trotter.log.strength.playtest

internal fun <T> settleFrames(
    frameTimeMillis: () -> Long,
    awaitPendingWork: () -> Unit,
    nextFrame: () -> T,
): Int {
    val started = frameTimeMillis()
    var previous: T? = null
    var stable = 0
    var frames = 0
    var waited = false
    while (frameTimeMillis() - started < 2_000L) {
        val current = nextFrame()
        frames++
        stable = if (frames > 1 && current == previous) stable + 1 else 1
        if (stable >= 3) {
            if (waited) return frames
            awaitPendingWork()
            waited = true
            stable = 0
        }
        previous = current
    }
    return frames
}
