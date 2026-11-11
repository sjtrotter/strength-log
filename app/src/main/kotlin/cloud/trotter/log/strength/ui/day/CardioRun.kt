package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.cardio.BootAnchor
import cloud.trotter.log.strength.domain.cardio.CardioExecution
import cloud.trotter.log.strength.domain.cardio.CardioSummary
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.units.DistanceUnit
import kotlinx.parcelize.Parcelize

@Parcelize
data class SavedCardioResult(
    val status: String,
    val seconds: Int,
    val metres: Int? = null,
) : Parcelable

/** Text under the original saved keys stays in meters, including restored drafts. */
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
    fun metres(): Int? = text.toIntOrNull()?.takeIf { it > 0 }
    fun valid(): Boolean = text.isBlank() || metres() != null
}

/** Text under the original saved key stays in meters, including restored drafts. */
@Parcelize
data class CardioRunDistance(val runId: String, val position: Int, val text: String) : Parcelable {
    fun metres(): Int? = text.toIntOrNull()?.takeIf { it > 0 }
}

/** Editable display text has its own saved key so the original keys keep their meaning. */
@Parcelize
data class CardioDistanceInput(
    val runId: String,
    val position: Int,
    val text: String,
    val unit: DistanceUnit,
) : Parcelable

/** The reading at a set's planned end as typed in the after-run entry, under its own saved key: a distance in
 *  [unit] for a time end, `m:ss` for a distance end. */
@Parcelize
data class CardioReadingInput(val runId: String, val position: Int, val text: String, val unit: DistanceUnit) : Parcelable

/** Frozen execution snapshot, retained separately after logging only while distance amendments remain. */
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
    val runId: String = java.util.UUID.randomUUID().toString(),
    /** Invalidated runs log normally without navigating away from the current page. */
    val showReceipt: Boolean = true,
) : Parcelable {
    fun action(setIndex: Int = position): CardioSetAction = CardioSetAction(dayId, runId, setIndex)

    val block: CardioBlock get() = requireNotNull(CardioBlockJson.decode(blockJson))
    val position: Int get() = results.size
    val complete: Boolean get() = position == block.sets.size
    val running: Boolean get() = wallStart != null
    val canAddSet: Boolean get() = !complete && !(running && block.openEnded)
    val resultSets: List<CardioSetResult> get() {
        val prescriptions = block.sets
        return results.mapIndexed { index, result ->
            CardioSetResult(prescriptions[index], CardioSetStatus.valueOf(result.status), result.seconds,
                result.metres, result.metres?.let { DistanceSource.MANUAL })
        }
    }

    private val execution: CardioExecution get() = CardioExecution(
        block = block,
        results = resultSets,
        startedAt = startedAt,
        anchor = wallStart?.let { BootAnchor(it, elapsedStart, bootCount) },
        completedAt = completedAt,
        distanceEntries = drafts.filterNot { it.saved }.map { it.position },
        allowOpenEnded = true,
    )

    private fun withExecution(next: CardioExecution): CardioRun = copy(
        results = next.results.map { SavedCardioResult(it.status.name, it.elapsedSeconds, it.metres) },
        startedAt = next.startedAt,
        wallStart = next.anchor?.wallMillis,
        elapsedStart = next.anchor?.elapsedMillis,
        bootCount = next.anchor?.bootCount,
        completedAt = next.completedAt,
        drafts = drafts + next.distanceEntries.filter { position -> drafts.none { it.position == position } }.map { position ->
            CardioDistanceDraft(position, next.results[position].status == CardioSetStatus.SHORTENED)
        },
    )

    fun elapsedMillis(clock: CardioClock): Long = execution.elapsedMillis(clock.clocks())

    fun start(clock: CardioClock): CardioRun = withExecution(execution.start(clock.clocks()))

    fun finish(
        status: CardioSetStatus,
        seconds: Int,
        clock: CardioClock,
        metres: Int? = null,
        requestDistance: Boolean = true,
    ): CardioRun = withExecution(execution.finishStep(status, seconds, clock.clocks(), metres, requestDistance))

    fun reconcile(clock: CardioClock): CardioRun = withExecution(execution.reconcile(clock.clocks()))

    fun reachTarget(clock: CardioClock): CardioRun = withExecution(execution.reachTarget(clock.clocks()))

    fun enterDistance(position: Int, metres: Int?): CardioRun = withExecution(execution.enterDistance(position, metres))

    fun summary(): CardioSummary = execution.summary()
}

/** Identity captured by the rendered control, including a ready block before its first action. */
data class CardioSetAction(val dayId: String, val runId: String?, val setIndex: Int)
