package cloud.trotter.log.strength.ui.day

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.cardio.CardioIncrease
import cloud.trotter.log.strength.domain.cardio.CardioNextTime
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.ui.components.distanceUnitFor

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
