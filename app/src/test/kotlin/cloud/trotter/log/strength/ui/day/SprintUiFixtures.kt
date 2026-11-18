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

internal fun sprintBlockFixture(intro: Boolean = false, steps: Int = 12): CardioBlock = requireNotNull(sprintPlannedBlock(
    if (intro) CardioRule(null, RunSession.ACCELERATION, intro = 3)
    else CardioRule(null, RunSession.ACCELERATION, steps = steps), CardioActivity.TRACK_RUN))

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
                                         introLeft: Int? = null, credit: Boolean = false): ReceiptProgressState {
    val block = sprintBlockFixture(introLeft != null, steps = if (change == CardioChange.Step) 13 else 12)
    return ReceiptProgressState(cardioSessionId = 2, cardioStored = block,
        run = RunResult(change, null, null, credit, false, block, dose = dose, introLeft = introLeft))
}

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

internal fun sprintRepFixture(beepElapsedMillis: Long? = null, recovery: Boolean = false, metres: Int = 60): DayUiState {
    val base = workoutSetCardioRunningFixture()
    val cardio = requireNotNull(base.cardio)
    val sourceRun = requireNotNull(cardio.run)
    val work = cloud.trotter.log.strength.domain.model.CardioSetSpec(
        cloud.trotter.log.strength.domain.model.CardioRole.WORK, Measure.Distance(metres), hard = true)
    val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(work,
        cloud.trotter.log.strength.domain.model.CardioSetSpec(
            cloud.trotter.log.strength.domain.model.CardioRole.RECOVERY, Measure.Time(120)), work))
    val json = cloud.trotter.log.strength.data.serialization.CardioBlockJson.encode(block)
    val run = sourceRun.copy(sourceBlockJson = json, blockJson = json,
        results = if (recovery) listOf(SavedCardioResult("FINISHED", 10, metres)) else emptyList())
    return base.copy(cardio = cardio.copy(block = block, run = run, stepSecondsLeft = 117, elapsedSeconds = 3,
        sprintRep = SprintRepUi(beepElapsedMillis != null, beepElapsedMillis,
            cloud.trotter.log.strength.domain.model.StartProtocol.THREE_POINT,
            if (recovery) cloud.trotter.log.strength.domain.model.SprintTime(452,
                cloud.trotter.log.strength.domain.model.TimingMethod.PHONE,
                cloud.trotter.log.strength.domain.model.StartProtocol.THREE_POINT) else null)))
}

internal fun sprintFortyYardReceiptFixture(): Pair<cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt, ReceiptProgressState> {
    val work = cloud.trotter.log.strength.domain.model.CardioSetSpec(
        cloud.trotter.log.strength.domain.model.CardioRole.WORK, Measure.Distance(37), hard = true)
    val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(work))
    val results = listOf(CardioSetResult(work, CardioSetStatus.FINISHED, 5))
    val receipt = sprintReceiptFixture().copy(cardioRows = ReceiptBuilder.cardio(block.activity, results, emptyMap()))
    val progress = sprintReceiptProgressFixture().let { state ->
        state.copy(cardioStored = block, cardioSets = listOf(results), run = requireNotNull(state.run).copy(block = block))
    }
    return receipt to progress
}
