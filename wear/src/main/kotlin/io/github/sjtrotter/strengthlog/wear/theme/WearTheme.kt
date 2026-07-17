package io.github.sjtrotter.strengthlog.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import io.github.sjtrotter.strengthlog.domain.theme.DayAccentColors

// Design digest §0 palette — near-black, dark theme only (spec §8.5); its own
// (tiny) surface set rather than reusing app/ui/theme directly (that module
// isn't shared and pulls in Material3/phone-only tokens).
val Background = Color(0xFF0D0D0F)
val Surface = Color(0xFF16161A)
val Border = Color(0xFF2A2A30)
val TextPrimary = Color(0xFFF2F2F0)
val TextSecondary = Color(0xFF9A9AA2)

// Help copy, back button, uppercase micro-labels — dimmer than TextSecondary,
// brighter than AmbientDim (digest §0).
val TextTertiary = Color(0xFF6B6B73)

// Ambient-only secondary text, dimmer still than TextTertiary — burn-in safety
// keeps ambient content the least visually loud thing the watch ever shows.
val AmbientDim = Color(0xFF4E4E55)

// True black: ambient-mode background only (OLED burn-in rule), distinct from
// the normal near-black [Background].
val AmbientBackground = Color(0xFF000000)
// Ambient clock digit — dim, no accent (digest §3).
val AmbientClock = Color(0xFF6B6B73)

val Done = Color(0xFF3E8E5A)

// "Phone away" queued-edit pill (digest §0/§3).
val QueuedPillBg = Color(0xFF1D1D22)
val QueuedPillBorder = Color(0xFF3A3A42)

/** Day accent for [accentIndex] — reads the pinned hexes from `:domain` (SSOT with `:app`). */
fun dayAccent(accentIndex: Int): Color = Color(DayAccentColors.hex(accentIndex))

/** Contrast text color for [dayAccent] at the same index — SSOT with `:domain`. */
fun onDayAccent(accentIndex: Int): Color = Color(DayAccentColors.onAccentHex(accentIndex))

/**
 * The day accent at 14% alpha (digest §0 `accentSoft`) — the "up next" row fill
 * and the "updated from phone" pill fill. Derived here, not stored: alpha is a
 * UI presentation concern, the hex itself stays SSOT in `:domain`.
 */
fun accentSoft(accentIndex: Int): Color = dayAccent(accentIndex).copy(alpha = 0.14f)

@Composable
fun WearTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = dayAccent(0),
            surface = Surface,
            background = Background,
            onPrimary = onDayAccent(0),
            onSurface = TextPrimary,
            onBackground = TextPrimary,
        ),
        content = content,
    )
}
