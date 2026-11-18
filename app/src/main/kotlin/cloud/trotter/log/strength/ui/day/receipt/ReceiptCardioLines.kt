package cloud.trotter.log.strength.ui.day.receipt

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.LadderHold
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import cloud.trotter.log.strength.ui.day.CardioIncrease
import cloud.trotter.log.strength.ui.day.CardioNextTime
import java.util.Locale

private fun distance(meters: Int, unit: DistanceUnit): String = if (unit == DistanceUnit.M) unit.format(meters)
    else String.format(Locale.US, "%.2f %s", unit.fromMetres(meters), unit.symbol)

@Composable
internal fun blockValue(block: CardioBlock, unit: DistanceUnit, sprint: Boolean = false): String {
    val work = block.sets.filter { it.role == CardioRole.WORK }
    val end = work.firstOrNull()?.end ?: return ""
    val value = when (end) {
        is Measure.Distance -> if (sprint) SprintEvent.label(end.metres, unit) else distance(end.metres, unit)
        is Measure.Time -> ClockText.minutesSeconds(end.seconds)
    }
    return if (work.size > 1) stringResource(R.string.receipt_cardio_intervals, work.size, value) else value
}

@Composable
private fun cardioDelta(offer: CardioNextTime.Offer, unit: DistanceUnit, withUnit: Boolean): String = when (val change = offer.change) {
    is CardioIncrease.Distance -> "+" + if (unit == DistanceUnit.M || withUnit) distance(change.metres, unit)
        else String.format(Locale.US, "%.2f", unit.fromMetres(change.metres))
    is CardioIncrease.Time -> "+" + ClockText.minutesSeconds(change.seconds)
    is CardioIncrease.Faster -> stringResource(R.string.next_time_faster, change.seconds)
    is CardioIncrease.Level -> stringResource(R.string.next_time_level, change.level)
    CardioIncrease.LadderDone -> stringResource(R.string.next_time_ladder_done)
    is CardioIncrease.Rounds -> pluralStringResource(R.plurals.next_time_rounds, change.rounds, change.rounds)
}

@Composable
internal fun cardioNextLine(state: ReceiptProgressState): String? {
    state.run?.let { return stringResource(R.string.receipt_next_time, runNextValue(state)) }
    val block = state.cardioStored ?: return null
    val unit = distanceUnitFor(block.activity)
    val offer = state.cardioNext as? CardioNextTime.Offer
    val value = if (offer == null) blockValue(block, unit) else if (offer.change is CardioIncrease.Faster) {
        val faster = cardioDelta(offer, unit, false)
        if (offer.applied) faster else stringResource(R.string.next_time_offered_plain, faster)
    } else stringResource(if (offer.applied) R.string.next_time_applied else R.string.next_time_offered,
        blockValue(offer.offered, unit), cardioDelta(offer, unit, false))
    return stringResource(R.string.receipt_next_time, value)
}

@Composable
internal fun cardioWhyLine(state: ReceiptProgressState, name: String): String? {
    state.run?.let { return runWhyLine(state, name) }
    val offer = state.cardioNext as? CardioNextTime.Offer ?: return null
    val unit = distanceUnitFor(offer.offered.activity)
    return stringResource(if (state.cardioCapped) R.string.receipt_why_cardio_capped else R.string.receipt_why_cardio,
        name, blockValue(offer.offered, unit), cardioDelta(offer, unit, false), 0,
        stringResource(if (offer.applied) R.string.receipt_why_next_time else R.string.receipt_why_offered))
}

@Composable
internal fun spokenCardioNextLine(state: ReceiptProgressState): String? {
    state.run?.let { return stringResource(R.string.receipt_say_next_time_same, runNextValue(state)) }
    val block = state.cardioStored ?: return null
    val unit = distanceUnitFor(block.activity)
    val offer = state.cardioNext as? CardioNextTime.Offer
    val chosen = offer?.offered ?: block
    val work = chosen.sets.filter { it.role == CardioRole.WORK }
    val end = work.firstOrNull()?.end ?: return null
    val measure = when (end) {
        is Measure.Distance -> spokenDistance(end.metres, unit)
        is Measure.Time -> spokenDuration(end.seconds)
    }
    val value = if (work.size > 1) stringResource(R.string.receipt_say_cardio_by, work.size, measure) else measure
    if (offer == null) return stringResource(R.string.receipt_say_next_time_same, value)
    val delta = when (val change = offer.change) {
        is CardioIncrease.Distance -> spokenDistance(change.metres, unit)
        is CardioIncrease.Time -> spokenDuration(change.seconds)
        is CardioIncrease.Level -> stringResource(R.string.next_time_level, change.level)
        CardioIncrease.LadderDone -> stringResource(R.string.next_time_ladder_done)
        is CardioIncrease.Rounds -> pluralStringResource(R.plurals.next_time_rounds, change.rounds, change.rounds)
        is CardioIncrease.Faster -> return stringResource(R.string.receipt_say_next_time_same,
            stringResource(R.string.next_time_faster, change.seconds))
    }
    return stringResource(if (offer.applied) R.string.receipt_say_next_time_up else R.string.receipt_say_next_time_offered_up,
        value, delta)
}

@Composable
private fun runNextValue(state: ReceiptProgressState): String {
    val result = state.run ?: return ""
    val block = result.block ?: state.cardioStored ?: return ""
    if (result.dose != null) return blockValue(block, distanceUnitFor(block.activity), sprint = true)
    val change = result.change
    val level = (change as? CardioChange.LevelTo)?.level ?: result.from
    val value = when {
        change is CardioChange.Alternate -> stringResource(R.string.receipt_run_kind,
            change.to.title, blockValue(block, distanceUnitFor(block.activity)))
        state.runLadder && result.change != CardioChange.LadderDone && level != null ->
            stringResource(R.string.receipt_run_level, level, RunWalkLadder.TOP)
        else -> blockValue(block, distanceUnitFor(block.activity))
    }
    return if (result.held) stringResource(R.string.next_time_offered_plain, value) else value
}

@Composable
private fun runWhyLine(state: ReceiptProgressState, name: String): String? {
    val result = state.run ?: return null
    if (result.dose != null) {
        val introLeft = result.introLeft
        return when {
            result.change == CardioChange.Restart -> stringResource(R.string.receipt_why_sprint_restart)
            result.dose == SprintDose.TRIAL -> stringResource(R.string.receipt_why_sprint_trial)
            result.dose == SprintDose.SHORTENED -> stringResource(R.string.receipt_why_sprint_shortened)
            result.dose == SprintDose.SUBSTITUTE -> stringResource(R.string.receipt_why_sprint_substitute)
            result.dose == SprintDose.TEMPO -> stringResource(R.string.receipt_why_sprint_tempo)
            result.change == CardioChange.Step -> stringResource(R.string.receipt_why_sprint_step)
            introLeft == 0 -> stringResource(R.string.receipt_why_sprint_intro_done)
            introLeft != null && introLeft > 0 -> pluralStringResource(R.plurals.receipt_why_sprint_intro, introLeft, introLeft)
            result.credit -> stringResource(R.string.receipt_why_sprint_credit)
            else -> null
        }
    }
    if (result.credit) return stringResource(R.string.receipt_why_ladder_credit, result.from ?: return null)
    when (result.hold) {
        LadderHold.WEEK_CAP -> return stringResource(R.string.receipt_why_ladder_week_cap)
        LadderHold.BUSY -> return stringResource(R.string.receipt_why_ladder_busy)
        LadderHold.BACKDATED -> return stringResource(R.string.receipt_why_ladder_backdated)
        null -> Unit
    }
    return when (val change = result.change) {
        is CardioChange.LevelTo -> { val from = result.from
            if (from != null && change.level > from) stringResource(R.string.receipt_why_ladder_up, from)
            else stringResource(R.string.receipt_why_ladder_down, change.level) }
        CardioChange.LadderDone -> stringResource(R.string.receipt_why_ladder_done)
        CardioChange.Step -> {
            val block = result.block ?: return null
            stringResource(R.string.receipt_why_run_step, name, blockValue(block, distanceUnitFor(block.activity)),
                stringResource(if (result.held) R.string.receipt_why_offered else R.string.receipt_why_next_time))
        }
        is CardioChange.Alternate -> stringResource(R.string.receipt_why_run_alternate,
            if (change.to == RunSession.VO2MAX) RunSession.THRESHOLD.title else RunSession.VO2MAX.title, change.to.title)
        is CardioChange.Increase -> {
            val offer = state.cardioNext as? CardioNextTime.Offer ?: return null
            val unit = distanceUnitFor(offer.offered.activity)
            stringResource(if (change.capped) R.string.receipt_why_cardio_capped else R.string.receipt_why_cardio,
                name, blockValue(offer.offered, unit), cardioDelta(offer, unit, false), 0,
                stringResource(if (result.held) R.string.receipt_why_offered else R.string.receipt_why_next_time))
        }
        CardioChange.Repeat, CardioChange.Restart -> null
    }
}
