package cloud.trotter.log.strength.domain.theme

import kotlin.math.roundToLong

/**
 * SSOT for the per-day earth-tone accent colors, their on-accent contrast text
 * color and their bright/deep foreground variants, in day order A-G, as 0xAARRGGBB
 * values. `:app` (via `androidx.compose.ui.graphics.Color`) and `:wear` both
 * read the same hexes from here instead of duplicating the literals — and, since
 * #150, the same *derivations* too.
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
    private const val LIGHT_TEXT_PRIMARY = 0xFF1B1B1EL // ink used to deepen light-theme accents

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

    /**
     * The day accent as a *foreground* color on the near-black background: the
     * watch's band labels and tile day line, the phone's chart lines and the
     * cascade scrim's new number. The raw accents are identity fills, chosen for
     * on-accent contrast, and six of the seven fall short of AA on [BACKGROUND]:
     * D 2.14:1, E 2.36, B 2.44, F 2.81, G 3.44, A 3.79 — only C, the goldenrod,
     * clears 4.5:1 unlifted. None of the six reads as a hairline mark or a small
     * cap. Each is lifted [BRIGHT_LIFT] of the way toward [TEXT_PRIMARY], which
     * keeps the hue and carries all seven past 4.5:1.
     *
     * This lives here, next to the accents, because it is the same fact as the
     * color it derives from. It used to live twice: the phone lifted by contrast
     * measurement (#106) while the watch invented a flat HSL lift that left day D
     * at 3.68:1 — so the same day rendered differently on the wrist and in the
     * pocket, and only one of the two actually met AA (#150). Derived, never a
     * second hex table: a brightened table would be a second thing to keep in
     * step with the accents. [DayAccentColorsTest] pins the results and the floor.
     *
     * The blend is plain sRGB. The phone's old lift was Compose's `lerp`, which
     * interpolates in Oklab, and reproducing that here would mean porting a UI
     * toolkit's colour pipeline into pure Kotlin. Moving to this one nudged the
     * phone's rendered brights: C by 21/255 on blue, A by 6/4/11 on R/G/B, and
     * the remaining five by 5/255 or less on any channel. Every one of them
     * gained contrast headroom rather than losing it.
     */
    fun brightHex(dayIndex: Int): Long = lift(hex(dayIndex), TEXT_PRIMARY, BRIGHT_LIFT)

    /**
     * Foreground form of the day accent on the light theme's warm paper. The
     * identity fill remains [hex]; only accent text and hairline chart strokes
     * use this 30% move toward ink. One rule keeps all seven hues distinct while
     * the light-background contrast pin in `DayAccentColorsTest` guards AA.
     */
    fun deepHex(dayIndex: Int): Long = lift(hex(dayIndex), LIGHT_TEXT_PRIMARY, DEEPEN)

    /** How far [brightHex] moves an accent toward [TEXT_PRIMARY]. The worst day (D,
     *  the deep teal) first clears 4.5:1 at 0.30; the phone's shipped 0.35 keeps a
     *  margin, so a hairline chart stroke or a 9sp cap on the watch isn't sitting
     *  exactly on the line. One value for all seven — a lift tuned per day would be
     *  seven rules wearing one name. */
    private const val BRIGHT_LIFT = 0.35f
    private const val DEEPEN = 0.30f

    /** Per-channel blend of two opaque 0xAARRGGBB values; [from] keeps its alpha. */
    private fun lift(from: Long, to: Long, fraction: Float): Long {
        fun channel(shift: Int): Long {
            val start = (from shr shift) and 0xFFL
            val end = (to shr shift) and 0xFFL
            return (start + (end - start) * fraction).roundToLong().coerceIn(0L, 0xFFL)
        }
        return (from and 0xFF000000L) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
