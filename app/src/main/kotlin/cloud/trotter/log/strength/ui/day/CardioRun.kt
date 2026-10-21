package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlinx.parcelize.Parcelize

@Parcelize
data class SavedCardioResult(
    val status: String,
    val seconds: Int,
    val metres: Int? = null,
) : Parcelable

@Parcelize
data class CardioDistanceDraft(
    val position: Int,
    val stopped: Boolean,
    val text: String = "",
    val requested: Boolean = false,
    val dismissed: Boolean = false,
    val saved: Boolean = false,
    val amended: Boolean = false,
) : Parcelable {
    val metres: Int? get() = text.toIntOrNull()?.takeIf { it > 0 }
    val valid: Boolean get() = text.isBlank() || (text.all(Char::isDigit) && metres != null)
}

/** One saved value commits each transition, including the sheet delivery and logging acknowledgement. */
@Parcelize
data class CardioRun(
    val planId: Long,
    val planName: String,
    val dayId: String,
    val dayIndex: Int,
    val sourceBlockJson: String,
    val blockJson: String,
    val finishesDay: Boolean,
    val nextDayTitle: String?,
    val results: List<SavedCardioResult> = emptyList(),
    val startedAt: Long? = null,
    val wallStart: Long? = null,
    val elapsedStart: Long? = null,
    val bootCount: Int? = null,
    val drafts: List<CardioDistanceDraft> = emptyList(),
    val completedAt: Long? = null,
    val sessionId: Long? = null,
    val saveFailed: Boolean = false,
) : Parcelable {
    val block: CardioBlock get() = requireNotNull(CardioBlockJson.decode(blockJson))
    val position: Int get() = results.size
    val complete: Boolean get() = position == block.sets.size
    val running: Boolean get() = wallStart != null
    val resultSets: List<CardioSetResult> get() {
        val prescriptions = block.sets
        return results.mapIndexed { index, result ->
            CardioSetResult(prescriptions[index], CardioSetStatus.valueOf(result.status), result.seconds,
                result.metres, result.metres?.let { DistanceSource.MANUAL })
        }
    }

    fun elapsedMillis(clock: CardioClock): Long {
        val wall = wallStart ?: return 0L
        return if (bootCount == clock.bootCount()) {
            (clock.elapsedRealtimeMillis() - requireNotNull(elapsedStart)).coerceAtLeast(0L)
        } else (clock.wallMillis() - wall).coerceAtLeast(0L)
    }

    fun start(clock: CardioClock): CardioRun {
        val wall = clock.wallMillis()
        return copy(startedAt = startedAt ?: wall, wallStart = wall,
            elapsedStart = clock.elapsedRealtimeMillis(), bootCount = clock.bootCount())
    }

    fun finish(
        status: CardioSetStatus,
        seconds: Int,
        clock: CardioClock,
        metres: Int? = null,
        requestDistance: Boolean = true,
    ): CardioRun {
        val spec = block.sets[position]
        val needsEntry = requestDistance && (status == CardioSetStatus.SHORTENED ||
            (status == CardioSetStatus.FINISHED && spec.end is Measure.Time && spec.aim is Measure.Distance))
        val nextResults = results + SavedCardioResult(status.name, seconds, metres)
        val end = nextResults.size == block.sets.size
        return copy(
            results = nextResults, wallStart = null, elapsedStart = null, bootCount = null,
            drafts = if (needsEntry) drafts + CardioDistanceDraft(position, status == CardioSetStatus.SHORTENED) else drafts,
            completedAt = if (end) clock.wallMillis() else null,
        )
    }

    /** Expired automatic sets keep their exact deadlines; work and distance never start by inference. */
    fun reconcile(clock: CardioClock): CardioRun {
        var run = this
        while (run.running && !run.complete) {
            val seconds = (run.block.sets[run.position].end as? Measure.Time)?.seconds ?: break
            val elapsed = run.elapsedMillis(clock)
            if (elapsed < seconds * 1_000L) break
            val deadlineWall = requireNotNull(run.wallStart) + seconds * 1_000L
            val deadlineElapsed = requireNotNull(run.elapsedStart) + seconds * 1_000L
            val boot = run.bootCount
            run = run.finish(CardioSetStatus.FINISHED, seconds, clock)
            if (run.complete) run = run.copy(completedAt = deadlineWall)
            else if (run.block.sets[run.position].role in listOf(CardioRole.RECOVERY, CardioRole.COOLDOWN)) {
                run = run.copy(wallStart = deadlineWall, elapsedStart = deadlineElapsed, bootCount = boot)
            }
        }
        return run
    }
}
