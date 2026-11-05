package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import java.util.Locale

data class Clocks(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int?)

data class BootAnchor(val wallMillis: Long, val elapsedMillis: Long?, val bootCount: Int?) {
    fun elapsedSince(now: Clocks): Long =
        (if (usesElapsedClock(now)) {
            now.elapsedMillis - requireNotNull(elapsedMillis)
        } else now.wallMillis - wallMillis).coerceAtLeast(0L)

    fun deadlineElapsedMillis(durationMillis: Long, now: Clocks, clampAfterReboot: Boolean = false): Long =
        if (usesElapsedClock(now)) {
            requireNotNull(elapsedMillis) + durationMillis
        } else if (clampAfterReboot) {
            now.elapsedMillis + (durationMillis - elapsedSince(now)).coerceAtLeast(0L)
        } else now.elapsedMillis + wallMillis + durationMillis - now.wallMillis

    fun rebase(now: Clocks): BootAnchor =
        BootAnchor(wallMillis, deadlineElapsedMillis(0L, now), now.bootCount)

    private fun usesElapsedClock(now: Clocks): Boolean =
        bootCount != null && bootCount == now.bootCount && elapsedMillis != null
}

data class CardioSummary(
    val mode: CardioMode,
    val hard: Boolean,
    val label: String,
    val seconds: Int,
    val stepsCompleted: Int,
)

fun cardioActivityLabel(activity: CardioActivity): String =
    activity.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase() }

data class CardioExecution(
    val block: CardioBlock,
    val results: List<CardioSetResult> = emptyList(),
    val startedAt: Long? = null,
    val anchor: BootAnchor? = null,
    val completedAt: Long? = null,
    val distanceEntries: List<Int> = emptyList(),
    /** The phone opts in while the watch retains deadline execution. */
    val allowOpenEnded: Boolean = false,
) {
    val position: Int get() = results.size
    val complete: Boolean get() = position == block.sets.size
    val running: Boolean get() = anchor != null

    fun elapsedMillis(now: Clocks): Long = anchor?.elapsedSince(now) ?: 0L

    fun elapsedSeconds(now: Clocks): Int =
        (elapsedMillis(now) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun start(now: Clocks): CardioExecution = copy(
        startedAt = startedAt ?: now.wallMillis,
        anchor = BootAnchor(now.wallMillis, now.elapsedMillis, now.bootCount),
    )

    fun finishStep(
        status: CardioSetStatus,
        seconds: Int,
        now: Clocks,
        metres: Int? = null,
        requestDistance: Boolean = true,
        autoStart: Boolean = false,
    ): CardioExecution {
        val spec = block.sets[position]
        val needsEntry = requestDistance && (status == CardioSetStatus.SHORTENED ||
            (status == CardioSetStatus.FINISHED && spec.end is Measure.Time &&
                (spec.aim is Measure.Distance || (allowOpenEnded && block.openEnded))))
        val nextResults = results + CardioSetResult(spec, status, seconds, metres, metres?.let { DistanceSource.MANUAL })
        val next = copy(
            results = nextResults,
            anchor = null,
            distanceEntries = if (needsEntry) distanceEntries + position else distanceEntries,
            completedAt = if (nextResults.size == block.sets.size) now.wallMillis else null,
        )
        return if (autoStart && !next.complete &&
            block.sets[next.position].role in listOf(CardioRole.RECOVERY, CardioRole.COOLDOWN)
        ) next.copy(anchor = BootAnchor(now.wallMillis, now.elapsedMillis, now.bootCount)) else next
    }

    /** Expired automatic sets keep their exact deadlines; work and distance never finish by inference. */
    fun reconcile(now: Clocks): CardioExecution {
        var run = this
        while (run.running && !run.complete) {
            if (run.allowOpenEnded && run.block.openEnded) break
            val seconds = (run.block.sets[run.position].end as? Measure.Time)?.seconds ?: break
            if (run.elapsedMillis(now) < seconds * 1_000L) break
            val active = requireNotNull(run.anchor)
            val boundary = Clocks(active.wallMillis + seconds * 1_000L,
                requireNotNull(active.elapsedMillis) + seconds * 1_000L, active.bootCount)
            run = run.finishStep(CardioSetStatus.FINISHED, seconds, boundary, autoStart = true)
        }
        return run
    }

    fun reachTarget(now: Clocks): CardioExecution {
        val target = block.sets.getOrNull(position)?.end as? Measure.Distance ?: return this
        if (!running) return this
        return finishStep(CardioSetStatus.FINISHED, elapsedSeconds(now), now, target.metres, autoStart = true)
    }

    fun enterDistance(position: Int, metres: Int?): CardioExecution = copy(
        results = results.mapIndexed { index, result ->
            if (index == position) result.copy(metres = metres, distanceSource = metres?.let { DistanceSource.MANUAL })
            else result
        },
        distanceEntries = distanceEntries.filterNot { it == position },
    )

    fun summary(): CardioSummary = CardioSummary(
        mode = CardioBlocks.legacyModeOf(block.activity),
        hard = block.sets.any { it.role == CardioRole.WORK && it.hard },
        label = cardioActivityLabel(block.activity),
        seconds = results.sumOf { it.elapsedSeconds },
        stepsCompleted = results.count { it.status == CardioSetStatus.FINISHED },
    )
}
