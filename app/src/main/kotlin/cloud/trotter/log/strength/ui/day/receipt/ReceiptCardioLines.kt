package cloud.trotter.log.strength.ui.day.receipt

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
private fun blockValue(block: CardioBlock, unit: DistanceUnit): String {
    val work = block.sets.filter { it.role == CardioRole.WORK }
    val end = work.firstOrNull()?.end ?: return ""
    val value = when (end) {
        is Measure.Distance -> distance(end.metres, unit)
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
}

@Composable
internal fun cardioNextLine(state: ReceiptProgressState): String? {
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
    val offer = state.cardioNext as? CardioNextTime.Offer ?: return null
    val unit = distanceUnitFor(offer.offered.activity)
    return stringResource(if (state.cardioCapped) R.string.receipt_why_cardio_capped else R.string.receipt_why_cardio,
        name, blockValue(offer.offered, unit), cardioDelta(offer, unit, false), 0,
        stringResource(if (offer.applied) R.string.receipt_why_next_time else R.string.receipt_why_offered))
}

@Composable
internal fun spokenCardioNextLine(state: ReceiptProgressState): String? {
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
        is CardioIncrease.Faster -> return stringResource(R.string.receipt_say_next_time_same,
            stringResource(R.string.next_time_faster, change.seconds))
    }
    return stringResource(if (offer.applied) R.string.receipt_say_next_time_up else R.string.receipt_say_next_time_offered_up,
        value, delta)
}
