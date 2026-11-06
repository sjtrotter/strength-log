package cloud.trotter.log.strength.ui.day

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun NextTimeOfferChip(offer: NextTime.Offer, slot: String, onWeight: (Double) -> Unit) {
    FilterChip(
        selected = offer.applied,
        onClick = { onWeight(if (offer.applied) offer.openingLb else offer.offerLb) },
        label = { Text(offer.label) },
        modifier = Modifier.testTag("next-time-offer-$slot"),
    )
}
