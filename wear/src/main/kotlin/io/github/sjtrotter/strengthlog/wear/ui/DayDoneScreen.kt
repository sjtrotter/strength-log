package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent
import io.github.sjtrotter.strengthlog.wear.theme.onDayAccent

/**
 * Day done (design digest §1.4): full-bleed accent flood, not a card on black.
 * There is deliberately no "next: day X" pill — [WatchSnapshot] doesn't carry
 * the program's next day (only the currently-suggested one), and the digest is
 * explicit that a missing signal should never be invented; this shows only
 * what the snapshot actually gives us.
 */
@Composable
fun DayDoneScreen(state: DayDoneUiState) {
    val accent = dayAccent(state.accentIndex)
    val onAccent = onDayAccent(state.accentIndex)

    Box(
        modifier = Modifier.fillMaxSize().background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "day ${state.dayId}".uppercase(),
                color = onAccent.copy(alpha = 0.75f),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = "DONE",
                color = onAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 46.sp,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "${state.roundsLogged} rounds logged",
                color = onAccent.copy(alpha = 0.8f),
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
