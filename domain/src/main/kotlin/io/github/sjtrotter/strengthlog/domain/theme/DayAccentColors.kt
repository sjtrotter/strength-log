package io.github.sjtrotter.strengthlog.domain.theme

/**
 * SSOT for the per-day earth-tone accent colors and their on-accent contrast
 * text color, in day order A-G, as 0xAARRGGBB values. `:app` (via
 * `androidx.compose.ui.graphics.Color`) and `:wear` both read the same hexes
 * from here instead of duplicating the literals.
 *
 * Spec §8.5 pins only A-D and says "cycle for >4 days"; this is a deliberate
 * amendment (wear-companion design digest §0/§4.2) expanding the rotation to
 * seven accents (E-G added) so a 5-7 day program doesn't repeat a color, and
 * adding an explicit on-accent contrast color per day — needed because a
 * mock that floods a whole screen with the accent (day-done, pills) requires
 * per-day text contrast, and gold (C) is too light for white text. Contrast
 * ratios are pinned in [DayAccentColorsTest].
 */
object DayAccentColors {
    private val HEX = listOf(
        0xFFC1440EL, // A
        0xFF2D5A3DL, // B
        0xFFB8860BL, // C
        0xFF1F4E5FL, // D
        0xFF3C4E78L, // E
        0xFF8B4356L, // F
        0xFF6B6A2CL, // G
    )

    private const val TEXT_PRIMARY = 0xFFF2F2F0L // light on-accent (the wear/app "TextPrimary" hex)
    private const val BACKGROUND = 0xFF0D0D0FL // dark on-accent (the wear/app "Background" hex)

    /** Per-day on-accent text color, A-G order — only C (goldenrod) needs the dark pairing. */
    private val ON_HEX = listOf(
        TEXT_PRIMARY, // A
        TEXT_PRIMARY, // B
        BACKGROUND, // C
        TEXT_PRIMARY, // D
        TEXT_PRIMARY, // E
        TEXT_PRIMARY, // F
        TEXT_PRIMARY, // G
    )

    /** Distinct accents before the rotation repeats — the cycle length both
     *  consumers mod by. Exposed so neither `:app` nor `:wear` hardcodes 7. */
    val count: Int get() = HEX.size

    /** 0-based day index (matches the generator's A-Z ids); cycles past 7. */
    fun hex(dayIndex: Int): Long = HEX[Math.floorMod(dayIndex, HEX.size)]

    /** The contrast text color to paint on top of [hex] for the same [dayIndex]. */
    fun onAccentHex(dayIndex: Int): Long = ON_HEX[Math.floorMod(dayIndex, ON_HEX.size)]
}
