package io.github.sjtrotter.strengthlog.domain.theme

/**
 * SSOT for the four per-day earth-tone accent colors (spec §8.5), in day
 * order A-D, as 0xAARRGGBB values. `:app` (`androidx.compose.ui.graphics.Color`)
 * and `:wear` both read the same hexes from here instead of duplicating the
 * literals — contrast/on-accent choices are UI concerns and stay in each
 * module's own theme, but the pinned colors themselves live in exactly one
 * place.
 */
object DayAccentColors {
    private val HEX = listOf(
        0xFFC1440EL, // A
        0xFF2D5A3DL, // B
        0xFFB8860BL, // C
        0xFF1F4E5FL, // D
    )

    /** 0-based day index (matches the generator's A-Z ids); cycles past 4. */
    fun hex(dayIndex: Int): Long = HEX[Math.floorMod(dayIndex, HEX.size)]
}
