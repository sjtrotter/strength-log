package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.Done
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary

/** A "−" / value / "+" trio — the ± input the wire protocol and spec §9 call for. */
@Composable
fun PlusMinusValue(value: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    CompactButton(onClick = onDecrement) { Text("−") }
    Text(
        text = value,
        modifier = Modifier.width(40.dp),
        color = TextPrimary,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
    )
    CompactButton(onClick = onIncrement) { Text("+") }
}

/** The one-tick-per-round done control (spec §8.2/§9) — a filled square when done. */
@Composable
fun DoneTick(done: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .background(if (done) Done else Background, RoundedCornerShape(6.dp))
            .border(1.dp, if (done) Done else TextSecondary, RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Text("✓", color = TextPrimary, fontSize = 14.sp)
    }
}
