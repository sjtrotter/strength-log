package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.mapSaver
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchCardioBlock
import cloud.trotter.log.strength.domain.sync.WatchCardioSetResult
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

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
    val ownsExecution: Boolean get() = !complete || entries.isNotEmpty()

    fun reconcile(snapshot: WatchSnapshot): CardioSetState? {
        // A completed receipt stays visible through the phone's rotation advance.
        if (complete) return takeIf { planId == snapshot.planId }
        if (planId != snapshot.planId || dayId != snapshot.day.dayId) return null
        if (startedAt == null && block != snapshot.cardioBlock) return null
        val logged = snapshot.cardio?.loggedStartedAt
        return takeIf { logged == null || logged == startedAt }
    }

    fun elapsed(nowElapsed: Long, nowWall: Long, boot: Int): Int {
        val anchor = active ?: return 0
        val millis = if (anchor.bootCount == boot) nowElapsed - anchor.startedAtElapsedMillis
            else nowWall - anchor.startedAtWallMillis
        return (millis.coerceIn(0L, Int.MAX_VALUE * 1_000L) / 1_000L).toInt()
    }

    fun deadline(nowElapsed: Long, nowWall: Long, boot: Int): Long? {
        val anchor = active ?: return null
        val spec = block.sets.getOrNull(position) ?: return null
        if (spec.endKind != "TIME") return null
        return if (anchor.bootCount == boot) anchor.startedAtElapsedMillis + spec.endValue * 1_000L
            else nowElapsed + anchor.startedAtWallMillis + spec.endValue * 1_000L - nowWall
    }

    fun start(nowWall: Long, nowElapsed: Long, boot: Int, finishesDay: Boolean = this.finishesDay): CardioSetState {
        if (complete || active != null || entries.isNotEmpty() || options || confirmingEnd) return this
        return copy(startedAt = startedAt ?: nowWall,
            finishesDay = if (startedAt == null) finishesDay else this.finishesDay,
            active = CardioAnchors(nowWall, nowElapsed, boot, planId), peekIndex = null)
    }

    /** Reconcile missed automatic boundaries silently, at their deadlines. */
    fun resume(nowWall: Long, nowElapsed: Long, boot: Int): CardioSetState {
        var run = this
        while (run.active != null) {
            val spec = run.block.sets[run.position]
            val deadline = run.deadline(nowElapsed, nowWall, boot) ?: break
            if (nowElapsed < deadline) break
            val boundaryWall = requireNotNull(run.active).startedAtWallMillis + spec.endValue * 1_000L
            run = run.finish("FINISHED", spec.endValue, boundaryWall, deadline, boot, autoStart = true)
        }
        return run
    }

    fun reached(nowWall: Long, nowElapsed: Long, boot: Int): CardioSetState {
        val current = resume(nowWall, nowElapsed, boot)
        val spec = current.block.sets.getOrNull(current.position) ?: return current
        if (current.active == null || spec.endKind != "DISTANCE" || current.entries.isNotEmpty()) return current
        return current.finish("FINISHED", current.elapsed(nowElapsed, nowWall, boot),
            nowWall, nowElapsed, boot, autoStart = true, metres = spec.endValue)
    }

    fun stop(nowWall: Long, nowElapsed: Long, boot: Int): CardioSetState {
        // A hold retained across a boundary must never stop the next set.
        val current = resume(nowWall, nowElapsed, boot)
        if (current.position != position || current.active == null) return current
        return current.finish("SHORTENED", current.elapsed(nowElapsed, nowWall, boot),
            nowWall, nowElapsed, boot, autoStart = false)
    }

    private fun finish(
        status: String, seconds: Int, wall: Long, elapsed: Long, boot: Int,
        autoStart: Boolean, metres: Int? = null,
    ): CardioSetState {
        val spec = block.sets[position]
        val entry = status == "SHORTENED" || (status == "FINISHED" && spec.aimKind == "DISTANCE")
        val next = copy(results = results + WatchCardioSetResult(spec, status, seconds, metres,
            metres?.let { "MANUAL" }), active = null,
            entries = if (entry) entries + position else entries, peekIndex = null,
            options = false, confirmingEnd = false)
        if (next.complete) return next.copy(completedAt = wall)
        val role = block.sets[next.position].role
        return if (autoStart && (role == "RECOVERY" || role == "COOLDOWN")) {
            next.copy(active = CardioAnchors(wall, elapsed, boot, planId))
        } else next
    }

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
            val index = entries.first()
            copy(results = results.mapIndexed { i, result ->
                if (i == index) result.copy(metres = draftMetres, distanceSource = draftMetres?.let { "MANUAL" }) else result
            }, entries = entries.drop(1), draftMetres = null)
        }
        confirmingEnd -> if (selection == 0) copy(confirmingEnd = false) else end(nowWall, nowElapsed, boot)
        options -> when (selection) {
            0 -> finish("SKIPPED", 0, nowWall, nowElapsed, boot, autoStart = false)
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
        while (!next.complete) next = next.finish("SKIPPED", 0, nowWall, nowElapsed, boot, autoStart = false)
        return next.copy(options = false, confirmingEnd = false)
    }

    fun delta(): CardioDelta? {
        if (!complete || entries.isNotEmpty()) return null
        val start = startedAt ?: return null // Skipping everything never logs or advances.
        val mode = CardioActivity.entries.find { it.name == block.activity }
            ?.let { CardioBlocks.legacyModeOf(it).name } ?: "LOW_IMPACT"
        val end = requireNotNull(completedAt).coerceAtLeast(start + 1L)
        return CardioDelta(dayId = dayId, mode = mode, hard = block.sets.any { it.role == "WORK" && it.hard },
            label = cardioActivityLabel(block.activity), startedAt = start, completedAt = end,
            seconds = results.sumOf { it.elapsedSeconds }, stepsCompleted = results.count { it.status == "FINISHED" },
            stamp = end, planId = planId, activity = block.activity, sets = results, finishesDay = finishesDay)
    }

    fun receipt(ackStamp: Long): String = when {
        queuedStamp != null && ackStamp >= queuedStamp -> "Logged ${cardioSetTime(results.sumOf { it.elapsedSeconds })}"
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

internal fun cardioActivityLabel(activity: String): String = activity.lowercase(java.util.Locale.ROOT)
    .replace('_', ' ').replaceFirstChar { it.titlecase() }

internal fun cardioSetTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

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
