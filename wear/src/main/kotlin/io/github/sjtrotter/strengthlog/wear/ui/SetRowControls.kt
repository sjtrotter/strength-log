package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.wear.theme.Done
import io.github.sjtrotter.strengthlog.wear.theme.QueuedPillBg
import io.github.sjtrotter.strengthlog.wear.theme.QueuedPillBorder
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary

/** The floating 56dp tick button (redesign §1.3) — accent when undone, [Done] green when done.
 *  Grown back to 56dp (A7's ≥48dp target) now that [ExerciseStreamScreen]'s reserved bottom
 *  slot makes overlap with the round content structurally impossible, rather than shrunk to
 *  dodge it. */
@Composable
fun TickButton(done: Boolean, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(if (done) Done else accent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** The 18dp green check badge that replaces a list row's `{done}/{total}` counter (digest §0). */
@Composable
fun CheckBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .background(Done, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The centered day pill (digest §1.1/§1.4) — accent bg, on-accent text, uppercase. */
@Composable
fun DayPill(dayId: String, accentColor: Color, onAccentColor: Color, modifier: Modifier = Modifier) {
    Text(
        text = "day $dayId".uppercase(),
        color = onAccentColor,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = modifier
            .background(accentColor, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

/** Persistent "● N queued" pill (digest §3) — phone unreachable, edits still local. */
@Composable
fun QueuedPill(count: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(QueuedPillBg, RoundedCornerShape(50))
            .border(1.dp, QueuedPillBorder, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        PulsingDot(color = TextSecondary)
        Text("$count queued", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** Transient "✓ synced" pill (digest §3) — the caller fades it after ~2s. */
@Composable
fun SyncedPill(modifier: Modifier = Modifier) {
    Text(
        text = "✓ synced",
        color = Done,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(Done.copy(alpha = 0.14f), RoundedCornerShape(50))
            .border(1.dp, Done, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Transient "updated from phone" pill (digest §1.1) — accent on accentSoft. Compact and
 *  top-anchored so it reads as a quiet indicator, not a screen-dominating banner. */
@Composable
fun UpdatedFromPhonePill(accentColor: Color, accentSoftColor: Color, modifier: Modifier = Modifier) {
    Text(
        text = "updated · phone".uppercase(),
        color = accentColor,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier
            .background(accentSoftColor, RoundedCornerShape(50))
            .border(1.dp, accentColor, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** A small pulsing dot — the queued pill's "phone away" indicator. */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "queuedDot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "queuedDotAlpha",
    )
    Box(
        modifier = modifier
            .size(5.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}
