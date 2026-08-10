package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.generator.CardioPlan
import cloud.trotter.log.strength.domain.sync.CardioDelta

/** Minimal, saveable device-local session state. Everything visible derives
 *  from these anchors; the boot count arbitrates reboot exactly, the phone's
 *  C1b discipline on the wrist. */
data class CardioAnchors(
    val startedAtWallMillis: Long,
    val startedAtElapsedMillis: Long,
    val bootCount: Int = 0,
)

data class CardioProgress(
    val elapsedSeconds: Int,
    val stepIndex: Int,
    val stepsCompleted: Int,
    val stepRemainingSeconds: Int,
    val stepRemainingFraction: Float,
    val overrun: Boolean,
    val nextBoundaryElapsedMillis: Long?,
)

fun cardioProgress(
    plan: CardioPlan,
    anchors: CardioAnchors,
    nowElapsedMillis: Long,
    nowWallMillis: Long? = null,
    nowBootCount: Int = anchors.bootCount,
): CardioProgress {
    // The boot counter decides exactly (a longer post-reboot uptime can fake a
    // plausible monotonic delta); across a reboot the wall clock is the only
    // witness left.
    val elapsedDelta = if (nowBootCount == anchors.bootCount) {
        (nowElapsedMillis - anchors.startedAtElapsedMillis).coerceAtLeast(0L)
    } else {
        (nowWallMillis ?: anchors.startedAtWallMillis) - anchors.startedAtWallMillis
    }
    val elapsed = (elapsedDelta.coerceAtLeast(0L) / 1_000L).toInt()
    var before = 0
    val index = plan.steps.indexOfFirst { step ->
        val contains = elapsed < before + step.seconds
        if (!contains) before += step.seconds
        contains
    }
    if (index < 0) return CardioProgress(elapsed, plan.steps.lastIndex.coerceAtLeast(0), plan.steps.size, 0, 0f, true, null)
    val step = plan.steps[index]
    val into = elapsed - before
    val remaining = (step.seconds - into).coerceAtLeast(0)
    return CardioProgress(
        elapsed, index, index, remaining,
        remaining.toFloat() / step.seconds,
        false,
        // Now-relative, never anchor-relative: after a reboot the anchor's
        // elapsed epoch no longer exists, but "time until the boundary" does.
        nowElapsedMillis + ((before + step.seconds) * 1_000L - elapsedDelta.coerceAtLeast(0L)),
    )
}

fun buildCardioDelta(
    dayId: String,
    mode: String,
    hard: Boolean,
    label: String,
    anchors: CardioAnchors,
    progress: CardioProgress,
    completedAtWallMillis: Long,
): CardioDelta? {
    if (progress.elapsedSeconds < 60) return null
    return CardioDelta(
        dayId = dayId, mode = mode, hard = hard, label = label,
        startedAt = anchors.startedAtWallMillis,
        completedAt = completedAtWallMillis,
        seconds = progress.elapsedSeconds,
        stepsCompleted = progress.stepsCompleted,
        stamp = completedAtWallMillis,
    )
}
