package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.mapSaver
import cloud.trotter.log.strength.domain.cardio.BootAnchor
import cloud.trotter.log.strength.domain.cardio.CardioExecution
import cloud.trotter.log.strength.domain.cardio.Clocks
import cloud.trotter.log.strength.domain.cardio.cardioActivityLabel as activityLabel
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchCardioBlock
import cloud.trotter.log.strength.domain.sync.WatchCardioSetResult
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.acknowledgesCardio
import cloud.trotter.log.strength.domain.sync.toWatch
import cloud.trotter.log.strength.domain.units.ClockText

/** Frozen prescription and device-local anchors. A snapshot never starts execution. */
data class CardioSetState(
    val planId: Long?,
    val dayId: String,
    val block: WatchCardioBlock,
    val results: List<WatchCardioSetResult> = emptyList(),
    val startedAt: Long? = null,
    val active: CardioAnchors? = null,
    val completedAt: Long? = null,
    val entries: List<Int> = emptyList(),
    val draftMetres: Int? = null,
    val options: Boolean = false,
    val confirmingEnd: Boolean = false,
    val selection: Int = 0,
    val peekIndex: Int? = null,
    /** The actual durable queue stamp, which may exceed the wall-clock stamp. */
    val queuedStamp: Long? = null,
    /** Eligibility at the first local start; later snapshots cannot change it. */
    val finishesDay: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val position: Int get() = results.size
    val complete: Boolean get() = position == block.sets.size
    val ownsExecution: Boolean get() = !complete || entries.isNotEmpty() || (startedAt != null && queuedStamp == null)

    fun reconcile(snapshot: WatchSnapshot): CardioSetState? {
        // Started work belongs to its frozen plan/day until it is durably queued.
        if (startedAt != null && complete && (entries.isNotEmpty() || queuedStamp == null)) return this
        // A completed receipt stays visible through the phone's rotation advance.
        if (complete) return takeIf { planId == snapshot.planId }
        if (planId != snapshot.planId || dayId != snapshot.day.dayId) return takeIf { startedAt != null }
        if (startedAt == null && block != snapshot.cardioBlock) return null
        val logged = snapshot.cardio?.loggedStartedAt
        return takeIf { logged == null || logged == startedAt }
    }

    private val execution: CardioExecution get() = CardioExecution(
        block = block.toDomain(),
        results = results.map { it.toDomain() },
        startedAt = startedAt,
        anchor = active?.let { BootAnchor(it.startedAtWallMillis, it.startedAtElapsedMillis, it.bootCount) },
        completedAt = completedAt,
        distanceEntries = entries,
    )

    private fun withExecution(next: CardioExecution): CardioSetState = copy(
        results = next.results.map { result ->
            WatchCardioSetResult(result.spec.toWatch(), result.status.name, result.elapsedSeconds,
                result.metres, result.distanceSource?.name)
        },
        startedAt = next.startedAt,
        active = next.anchor?.let {
            CardioAnchors(it.wallMillis, requireNotNull(it.elapsedMillis), requireNotNull(it.bootCount), planId)
        },
        completedAt = next.completedAt,
        entries = next.distanceEntries,
        peekIndex = if (next.position != position) null else peekIndex,
        options = if (next.position != position) false else options,
        confirmingEnd = if (next.position != position) false else confirmingEnd,
    )

    fun elapsed(nowElapsed: Long, nowWall: Long, boot: Int): Int =
        execution.elapsedSeconds(Clocks(nowWall, nowElapsed, boot))

    fun deadline(nowElapsed: Long, nowWall: Long, boot: Int): Long? {
        val run = execution
        val end = run.block.sets.getOrNull(run.position)?.end as? Measure.Time ?: return null
        return run.anchor?.deadlineElapsedMillis(end.seconds * 1_000L, Clocks(nowWall, nowElapsed, boot))
    }

    fun start(nowWall: Long, nowElapsed: Long, boot: Int, finishesDay: Boolean = this.finishesDay): CardioSetState {
        if (complete || active != null || entries.isNotEmpty() || options || confirmingEnd) return this
        return withExecution(execution.start(Clocks(nowWall, nowElapsed, boot))).copy(
            finishesDay = if (startedAt == null) finishesDay else this.finishesDay, peekIndex = null)
    }

    fun resume(nowWall: Long, nowElapsed: Long, boot: Int): CardioSetState {
        val now = Clocks(nowWall, nowElapsed, boot)
        val next = execution.reconcile(now)
        if (next.position == position) return this
        // Watch saved anchors adopt the current boot only after crossing a set boundary.
        return withExecution(next.copy(anchor = next.anchor?.rebase(now)))
    }

    fun reached(nowWall: Long, nowElapsed: Long, boot: Int): CardioSetState {
        val current = resume(nowWall, nowElapsed, boot)
        if (current.entries.isNotEmpty()) return current
        return current.withExecution(current.execution.reachTarget(Clocks(nowWall, nowElapsed, boot)))
    }

    fun stop(nowWall: Long, nowElapsed: Long, boot: Int): CardioSetState {
        // A hold retained across a boundary must never stop the next set.
        val current = resume(nowWall, nowElapsed, boot)
        if (current.position != position || current.active == null) return current
        return current.finish(CardioSetStatus.SHORTENED, current.elapsed(nowElapsed, nowWall, boot),
            nowWall, nowElapsed, boot)
    }

    private fun finish(
        status: CardioSetStatus, seconds: Int, wall: Long, elapsed: Long, boot: Int,
    ): CardioSetState = withExecution(execution.finishStep(status, seconds, Clocks(wall, elapsed, boot)))

    fun openOptions(): CardioSetState = if (!complete && active == null && entries.isEmpty()) {
        copy(options = true, selection = 0, peekIndex = null)
    } else this

    fun crownPosition(): Int = when {
        entries.isNotEmpty() -> draftMetres ?: 0
        options || confirmingEnd -> selection
        else -> peekIndex ?: results.lastIndex
    }

    fun turn(detents: Int): CardioSetState = when {
        entries.isNotEmpty() -> copy(draftMetres = ((draftMetres ?: 0).toLong() + detents)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt().takeIf { it > 0 })
        options -> copy(selection = (selection + detents).coerceIn(0, 2))
        confirmingEnd -> copy(selection = (selection + detents).coerceIn(0, 1))
        active == null && results.isNotEmpty() -> copy(peekIndex =
            ((peekIndex ?: results.lastIndex) + detents).coerceIn(results.indices))
        else -> this
    }

    fun tap(nowWall: Long, nowElapsed: Long, boot: Int, finishesDay: Boolean = this.finishesDay): CardioSetState = when {
        complete && queuedStamp == null && saveFailed -> copy(saveFailed = false)
        entries.isNotEmpty() -> {
            withExecution(execution.enterDistance(entries.first(), draftMetres)).copy(draftMetres = null)
        }
        confirmingEnd -> if (selection == 0) copy(confirmingEnd = false) else end(nowWall, nowElapsed, boot)
        options -> when (selection) {
            0 -> finish(CardioSetStatus.SKIPPED, 0, nowWall, nowElapsed, boot)
            1 -> copy(options = false, confirmingEnd = true, selection = 0)
            else -> copy(options = false)
        }
        peekIndex != null -> copy(peekIndex = null)
        active != null -> reached(nowWall, nowElapsed, boot)
        else -> start(nowWall, nowElapsed, boot, finishesDay)
    }

    fun end(nowWall: Long, nowElapsed: Long, boot: Int): CardioSetState {
        var next = resume(nowWall, nowElapsed, boot)
        if (next.active != null) next = next.stop(nowWall, nowElapsed, boot)
        while (!next.complete) next = next.finish(CardioSetStatus.SKIPPED, 0, nowWall, nowElapsed, boot)
        return next.copy(options = false, confirmingEnd = false)
    }

    fun delta(): CardioDelta? {
        if (!complete || entries.isNotEmpty()) return null
        val start = startedAt ?: return null // Skipping everything never logs or advances.
        val summary = execution.summary()
        val end = requireNotNull(completedAt).coerceAtLeast(start + 1L)
        return CardioDelta(dayId = dayId, mode = summary.mode.name, hard = summary.hard,
            label = summary.label, startedAt = start, completedAt = end,
            seconds = summary.seconds, stepsCompleted = summary.stepsCompleted,
            stamp = end, planId = planId, activity = block.activity, sets = results, finishesDay = finishesDay)
    }

    fun receipt(snapshot: WatchSnapshot): String = when {
        queuedStamp != null && snapshot.acknowledgesCardio(queuedStamp) -> "Logged ${cardioSetTime(execution.summary().seconds)}"
        queuedStamp != null -> "Saved on watch"
        saveFailed -> "Couldn't save"
        else -> "Saving…"
    }
}

/** The starting device creates the execution; a phone snapshot only supplies a prescription. */
internal fun newCardioSetState(
    snapshot: WatchSnapshot,
    timedRep: TimedRepState?,
    legacyCardioRunning: Boolean,
): CardioSetState? {
    if (timedRep != null || legacyCardioRunning || snapshot.day.dayId.isBlank() ||
        snapshot.cardio?.loggedStartedAt != null || snapshot.day.exercises.any { ex -> ex.sets.any { !it.done } }) return null
    val block = snapshot.cardioBlock ?: return null
    if (runCatching { block.toDomain() }.isFailure) return null
    return CardioSetState(snapshot.planId, snapshot.day.dayId, block)
}

internal fun cardioActivityLabel(activity: String): String = activityLabel(CardioActivity.valueOf(activity))

internal fun cardioSetTime(seconds: Int): String = ClockText.minutesSeconds(seconds)

internal val cardioSetStateSaver = mapSaver<CardioSetState?>(
    save = { run ->
        if (run == null) emptyMap() else mapOf(
            "plan" to run.planId, "day" to run.dayId,
            "block" to SyncCodec.encodeCardioBlock(run.block),
            "results" to SyncCodec.encodeCardioSetResults(run.results),
            "start" to run.startedAt, "wall" to run.active?.startedAtWallMillis,
            "elapsed" to run.active?.startedAtElapsedMillis, "boot" to run.active?.bootCount,
            "end" to run.completedAt, "entries" to ArrayList(run.entries), "draft" to run.draftMetres,
            "options" to run.options, "confirm" to run.confirmingEnd, "selection" to run.selection,
            "queued" to run.queuedStamp, "finishesDay" to run.finishesDay, "saveFailed" to run.saveFailed,
        )
    },
    restore = { saved ->
        runCatching {
            val plan = saved["plan"] as Long?
            CardioSetState(plan, saved["day"] as String, SyncCodec.decodeCardioBlock(saved["block"] as String),
                results = SyncCodec.decodeCardioSetResults(saved["results"] as String),
                startedAt = saved["start"] as Long?,
                active = (saved["wall"] as Long?)?.let { CardioAnchors(it, saved["elapsed"] as Long, saved["boot"] as Int, plan) },
                completedAt = saved["end"] as Long?, entries = (saved["entries"] as List<*>).map { it as Int },
                draftMetres = saved["draft"] as Int?, options = saved["options"] as Boolean,
                confirmingEnd = saved["confirm"] as Boolean, selection = saved["selection"] as Int,
                queuedStamp = saved["queued"] as Long?,
                finishesDay = saved["finishesDay"] as? Boolean ?: false,
                saveFailed = saved["saveFailed"] as? Boolean ?: false,
            ).also { run ->
                run.block.toDomain()
                require(run.results.size <= run.block.sets.size)
                run.results.forEachIndexed { index, result ->
                    result.toDomain()
                    require(result.spec == run.block.sets[index])
                }
                require(run.entries.distinct() == run.entries && run.entries.all { it in run.results.indices })
                require(run.active == null || (!run.complete && run.startedAt != null))
                require(!run.complete || run.completedAt != null)
                require(run.draftMetres == null || run.draftMetres > 0)
            }
        }.getOrNull()
    },
)
