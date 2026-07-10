package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent

/**
 * Shown while [io.github.sjtrotter.strengthlog.wear.data.WatchTrackerClient.snapshotFlow]
 * hasn't produced anything yet — either the very first prime, or a paired
 * phone the watch hasn't heard from (design digest §3). There is no day/accent
 * to key off yet, so the wordmark's period uses day A's accent as a neutral
 * brand color, not a per-day one.
 */
@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark()
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                PulseDot(delayMillis = 0)
                PulseDot(delayMillis = 200)
                PulseDot(delayMillis = 400)
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Row {
        Text("strength", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(".", color = dayAccent(0), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text("log", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

/** One dot of the 3-dot pulse loader — staggered by [delayMillis], 1.4s cycle, opacity .2<->1. */
@Composable
private fun PulseDot(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "loadingDot")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, delayMillis = delayMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingDotAlpha",
    )
    Box(Modifier.size(4.dp).background(TextSecondary.copy(alpha = alpha), CircleShape))
}
