package cloud.trotter.log.strength.playtest

internal fun <T> settleFrames(frameTimeMillis: () -> Long, nextFrame: () -> T): Int {
    val started = frameTimeMillis()
    var previous: T? = null
    var stable = 0
    var frames = 0
    while (frameTimeMillis() - started < 2_000L) {
        val current = nextFrame()
        frames++
        stable = if (frames > 1 && current == previous) stable + 1 else 1
        if (stable >= 3) return frames
        previous = current
    }
    return frames
}
