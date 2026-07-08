package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary

/**
 * Ambient-mode content (A8): burn-in-safe per Wear guidance — no accent
 * color, no animation, static text only. Shown in place of the normal nav
 * host while the system has the app in ambient rather than interactive mode.
 */
@Composable
fun AmbientScreen(snapshot: WatchSnapshot) {
    val state = snapshot.toDayListUiState()
    val done = state.rows.sumOf { it.doneCount }
    val total = state.rows.sumOf { it.totalCount }

    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = state.dayTitle, color = TextSecondary)
            Text(text = "$done/$total sets", color = TextSecondary)
        }
    }
}
