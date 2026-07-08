package io.github.sjtrotter.strengthlog.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import io.github.sjtrotter.strengthlog.domain.theme.DayAccentColors

// Spec §8.5 — near-black, dark theme only; same character as the phone,
// its own (tiny) surface set rather than reusing app/ui/theme directly
// (that module isn't shared and pulls in Material3/phone-only tokens).
val Background = Color(0xFF0D0D0F)
val Surface = Color(0xFF16161A)
val TextPrimary = Color(0xFFF2F2F0)
val TextSecondary = Color(0xFF9A9AA2)
val Done = Color(0xFF3E8E5A)

/** Day accent for [accentIndex] — reads the pinned hexes from `:domain` (SSOT with `:app`). */
fun dayAccent(accentIndex: Int): Color = Color(DayAccentColors.hex(accentIndex))

@Composable
fun WearTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = dayAccent(0),
            surface = Surface,
            background = Background,
            onPrimary = TextPrimary,
            onSurface = TextPrimary,
            onBackground = TextPrimary,
        ),
        content = content,
    )
}
