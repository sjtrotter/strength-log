package cloud.trotter.log.strength.ui.day.receipt

import cloud.trotter.log.strength.ui.day.NextTimeGate
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.data.CardioJudge
import android.os.Parcelable
import cloud.trotter.log.strength.data.DayTracks
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.ui.day.CardioNextTime
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import java.time.Instant
import java.time.ZoneId
import kotlinx.parcelize.Parcelize

@Parcelize
data class ReceiptProgress(val sessionId: Long?, val cardioSessionId: Long?, val planId: Long?, val dayId: String,
                           val slots: List<ReceiptSlotRef>) : Parcelable

@Parcelize
data class ReceiptSlotRef(val groupIndex: Int, val rowIndex: Int, val programExerciseId: Long, val slot: String) : Parcelable

data class ReceiptLiftProgress(val ref: ReceiptSlotRef, val name: String, val line: ReceiptLiftLine,
                               val whyLine: ReceiptLiftLine?)

data class ReceiptProgressState(
    val lifts: List<ReceiptLiftProgress> = emptyList(),
    val undoVisible: Boolean = false,
    val rough: Boolean = false,
    val roughEnabled: Boolean = false,
    val lightWeekNext: Boolean = false,
    val lightRemaining: Int = 0,
    val lightEnded: Boolean = false,
    val roughStartedLight: Boolean = false,
    val cardioSessionId: Long? = null,
    val cardioEffort: CardioEffort? = null,
    val cardioNext: CardioNextTime? = null,
    val cardioStored: CardioBlock? = null,
    val cardioCapped: Boolean = false,
) {
    val whyVisible: Boolean get() = lifts.isNotEmpty() || lightRemaining > 0 || lightEnded || cardioNext is CardioNextTime.Offer
}

sealed interface ReceiptMutation {
    data object Undone : ReceiptMutation
    data class DayOpen(val dayId: String) : ReceiptMutation
    data object Failed : ReceiptMutation
    data class RoughDayOpen(val dayId: String) : ReceiptMutation
    data object RoughDayFailed : ReceiptMutation
    data object EffortFailed : ReceiptMutation
}

internal object ReceiptProgressBuilder {
    suspend fun build(identity: ReceiptProgress, receipt: WorkoutReceipt?, tracks: DayTracks, slots: List<ProgramSlot>,
                      plan: PlanEntity?, history: HistoryRepository, repo: TrackerRepository,
                      cardio: List<CardioSessionEntity>, sets: Map<Long, List<CardioSetResult>>, program: Program): ReceiptProgressState {
        if (plan == null || receipt == null) return ReceiptProgressState()
        val undo = repo.finishUndoState(identity.sessionId, identity.cardioSessionId)
        val sessions = history.slotSessionsById(plan.id, identity.dayId)
        val lifts = identity.slots.mapNotNull { ref ->
            val slot = slots.firstOrNull { it.programExerciseId == ref.programExerciseId } ?: return@mapNotNull null
            val row = receipt.groups.getOrNull(ref.groupIndex)?.rows?.getOrNull(ref.rowIndex) ?: return@mapNotNull null
            if (row.shape == ReceiptRow.Shape.SKIPPED) return@mapNotNull null
            val rule = (if (ref.slot == Slot.SS) slot.exercise.superset?.rule else slot.exercise.rule) ?: return@mapNotNull null
            val rows = tracks.tracks.firstOrNull { it.programExerciseId == ref.programExerciseId && it.slot == ref.slot }?.sets.orEmpty()
            val session = sessions[ref.programExerciseId to ref.slot] ?: return@mapNotNull null
            val line = ReceiptLines.strength(rows, session, slot.exercise.keepToPlan,
                rule, receipt.unit, NextTimeGate.strength(plan, rule.nextTime))
                ?: return@mapNotNull null
            ReceiptLiftProgress(ref, row.name, line, ReceiptLines.whyLine(rows, session, rule, receipt.unit))
        }
        val date = Instant.ofEpochMilli(receipt.completedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val entry = identity.cardioSessionId?.let { id -> cardio.firstOrNull { it.id == id } }
            ?: cardio.filter { it.planId == plan.id && it.dayId == identity.dayId &&
                Instant.ofEpochMilli(it.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == date }
                .maxWithOrNull(compareBy({ it.completedAt }, { it.id }))
        val day = program.days.firstOrNull { it.id == identity.dayId }
        val next = if (identity.cardioSessionId != null && day != null) {
            // Receipts retain applied changes and unchanged values when Ask offers are hidden.
            DayScreenBuilder.cardioNextTime(day, plan.copy(offerCardioIncreases = true), cardio, sets)?.takeIf {
                it !is CardioNextTime.Offer || it.applied || NextTimeGate.cardio(plan)
            }
        } else null
        val capped = if (entry != null && day != null && identity.cardioSessionId != null) {
            (CardioJudge.judge(day, entry, cardio, sets, plan.planSettings.runCap)?.outcome?.change
                as? CardioChange.Increase)?.capped == true
        } else false
        val counter = undo.roughLight ?: undo.strengthPlan ?: undo.cardioPlan
        val lightNext = counter != null && counter.beforeRemaining == 0 && counter.afterRemaining > 0 &&
            plan.lightCycleRemaining == counter.afterRemaining && plan.cyclesCompleted == counter.afterCycles
        return ReceiptProgressState(lifts, undo.restorable && !undo.rough, undo.rough, undo.hasStrengthRecord,
            lightNext, plan.lightCycleRemaining, counter?.beforeRemaining?.let { it > 0 && counter.afterRemaining == 0 } == true,
            undo.roughLight != null, entry?.id, CardioEffort.entries.firstOrNull { it.name == entry?.effort },
            next, day?.cardioToRun.takeIf { identity.cardioSessionId != null }, capped)
    }
}
