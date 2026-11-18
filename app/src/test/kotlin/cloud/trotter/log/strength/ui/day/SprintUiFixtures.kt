package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.SpacingReason
import cloud.trotter.log.strength.data.serialization.RunResult
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.progression.sprintPlannedBlock
import cloud.trotter.log.strength.ui.day.receipt.ReceiptBuilder
import cloud.trotter.log.strength.ui.day.receipt.ReceiptProgressState
import cloud.trotter.log.strength.ui.day.receipt.timeTargetFixture

internal fun sprintBlockFixture(intro: Boolean = false): CardioBlock = requireNotNull(sprintPlannedBlock(
    if (intro) CardioRule(null, RunSession.ACCELERATION, intro = 3)
    else CardioRule(null, RunSession.ACCELERATION, steps = 12), CardioActivity.TRACK_RUN))

internal fun sprintStartFixture(dose: SprintDose = SprintDose.NORMAL, gap: Long? = 1,
                               reason: SpacingReason = SpacingReason.STARTING_OUT,
                               spacing: Int = 3, nineTenths: Boolean = false): DayUiState {
    val base = workoutSetCardioFixture()
    val full = sprintBlockFixture()
    val block = when (dose) {
        SprintDose.SHORTENED -> full.copy(sets = full.sets.take(8))
        SprintDose.SUBSTITUTE, SprintDose.TEMPO -> requireNotNull(sprintPlannedBlock(
            CardioRule(null, RunSession.SPRINT_TEMPO), CardioActivity.TRACK_RUN))
        else -> if (nineTenths) sprintBlockFixture(intro = true) else full
    }
    return base.copy(cardio = requireNotNull(base.cardio).copy(block = block, hard = block.sets.any { it.hard },
        nextTime = null, sprint = SprintStartUi(dose, 4.takeIf { dose == SprintDose.SHORTENED }, gap,
            spacing, reason, nineTenths)))
}

internal fun sprintEditorFixture() = DayEditUiState(cardioBlock = sprintBlockFixture(), cardioSprint = true,
    cardioPlanned = sprintBlockFixture(), planEditing = true)

internal fun sprintReceiptProgressFixture(change: CardioChange = CardioChange.Step, dose: SprintDose = SprintDose.NORMAL,
                                         introLeft: Int? = null, credit: Boolean = false) =
    ReceiptProgressState(cardioSessionId = 2, cardioStored = sprintBlockFixture(introLeft != null),
        run = RunResult(change, null, null, credit, false, sprintBlockFixture(introLeft != null), dose = dose, introLeft = introLeft))

internal fun sprintReceiptFixture(intro: Boolean = false, dose: SprintDose = SprintDose.NORMAL) = sprintBlockFixture(intro).let { planned ->
    val block = when (dose) {
        SprintDose.SHORTENED -> planned.copy(sets = planned.sets.take(8))
        SprintDose.SUBSTITUTE, SprintDose.TEMPO -> requireNotNull(sprintPlannedBlock(
            CardioRule(null, RunSession.SPRINT_TEMPO), CardioActivity.TRACK_RUN))
        else -> planned
    }
    val results = block.sets.map { CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as? Measure.Time)?.seconds ?: 5) }
    timeTargetFixture().copy(dayId = "A", dayIndex = 0, dayTitle = RunSession.ACCELERATION.title, planName = "Sprint",
        activity = block.activity, minutes = (results.sumOf { it.elapsedSeconds } + 59) / 60, cardioRows = ReceiptBuilder.cardio(block.activity, results, emptyMap()))
}
