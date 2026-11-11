package cloud.trotter.log.strength.ui.day

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.ui.components.distanceUnitFor

/** The size of an offered change, read from the first WORK set; the app formats it. */
sealed interface CardioIncrease {
    data class Distance(val metres: Int) : CardioIncrease
    data class Time(val seconds: Int) : CardioIncrease
    data class Faster(val seconds: Int) : CardioIncrease
}

/** Next time for a cardio block: derived on every read, like strength's NextTime. */
sealed interface CardioNextTime {
    data object Repeat : CardioNextTime
    data class Offer(
        val offered: CardioBlock,
        val opening: CardioBlock,
        val applied: Boolean,
        val change: CardioIncrease,
    ) : CardioNextTime
}

@Composable
internal fun CardioNextTimeChip(offer: CardioNextTime.Offer, onApply: (CardioBlock) -> Unit) {
    val label = when (val change = offer.change) {
        is CardioIncrease.Distance -> "+" + distanceUnitFor(offer.offered.activity).format(change.metres)
        is CardioIncrease.Time -> "+" + ClockText.minutesSeconds(change.seconds)
        is CardioIncrease.Faster -> stringResource(R.string.next_time_faster, change.seconds)
    }
    FilterChip(
        selected = offer.applied,
        onClick = { onApply(if (offer.applied) offer.opening else offer.offered) },
        label = { Text(label) },
        modifier = Modifier.testTag("cardio-next-time-offer"),
    )
}
