package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.wear.theme.AmbientBackground
import io.github.sjtrotter.strengthlog.wear.theme.AmbientClock
import io.github.sjtrotter.strengthlog.wear.theme.AmbientDim
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Ambient-mode content (A8, design digest §3): OLED burn-in safe — true black
 * `#000000` (not the normal near-black [io.github.sjtrotter.strengthlog.wear.theme.Background]),
 * no accent color anywhere, no motion. The clock digit only repaints when
 * [ambientTick] changes, which [io.github.sjtrotter.strengthlog.wear.MainActivity]
 * bumps from `AmbientLifecycleCallback.onUpdateAmbient()` — the system's own
 * once-a-minute ambient refresh signal — rather than a free-running coroutine
 * that wouldn't reliably fire while the CPU is suspended in ambient.
 *
 * [restLabel] is non-null while a rest countdown is pending (redesign §2.3): a
 * single dim, static "REST · NEXT …" line — no live numeral, no motion (ambient
 * repaints once a minute and forbids animation; the buzz is the real signal).
 */
@Composable
fun AmbientScreen(snapshot: WatchSnapshot, ambientTick: Int, restLabel: String? = null) {
    val state = snapshot.toDayListUiState()
    val done = state.rows.sumOf { it.doneCount }
    val total = state.rows.sumOf { it.totalCount }
    val time = remember(ambientTick) {
        LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }

    Box(
        modifier = Modifier.fillMaxSize().background(AmbientBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = time,
                color = AmbientClock,
                fontWeight = FontWeight.Medium,
                fontSize = 40.sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Text(
                text = "day ${state.dayId} · $done/$total sets".uppercase(),
                color = AmbientDim,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!restLabel.isNullOrBlank()) {
                Text(
                    text = "rest · next $restLabel".uppercase(),
                    color = AmbientDim,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
