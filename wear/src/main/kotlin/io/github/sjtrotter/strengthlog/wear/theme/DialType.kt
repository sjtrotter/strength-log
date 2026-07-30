package io.github.sjtrotter.strengthlog.wear.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.wear.compose.foundation.CurvedTextStyle
import io.github.sjtrotter.strengthlog.wear.ui.DialTextRole

/**
 * The dial's type scale (brief §3): three sizes over Barlow Condensed, two steps
 * each, tabular numerals throughout. Anything between these steps is a smell —
 * which is why the sizes are declared once, here, and every dial text asks for a
 * [DialTextRole] rather than an `sp` value.
 *
 * Sizes are logical px on the brief's 384px canvas and are converted against the
 * *measured* face, like every other dial measurement, so type keeps its
 * proportion on a 41mm and a 45mm watch. That deliberately pins the dial to
 * physical size instead of the system font scale: the layout's guarantee is that
 * nothing escapes its zone (§2, acceptance 1), and a 1.3× font scale on a 204px
 * disc breaks that guarantee outright. Body text elsewhere in the app is
 * unaffected — the watch's only screen is this dial.
 *
 * The reference px are twice what the v1 brief drew because the 384px canvas maps
 * 1:1 to *physical* pixels on a Pixel Watch — a density-2.0 face, where a 13px
 * reference landed at 6.5sp on the wrist. Every step below is chosen so that face
 * lands on the sp column, and the smallest of them is 12sp: nothing on the dial
 * may render below that (dial v2 §1).
 */
@Immutable
data class DialTypography(
    val numeralLarge: TextStyle,
    val numeral: TextStyle,
    val discLabel: TextStyle,
    val discLabelSmall: TextStyle,
    val band: TextStyle,
    val bandSecondary: TextStyle,
) {
    fun style(role: DialTextRole): TextStyle = when (role) {
        DialTextRole.NUMERAL_LARGE -> numeralLarge
        DialTextRole.NUMERAL -> numeral
        DialTextRole.DISC_LABEL -> discLabel
        DialTextRole.DISC_LABEL_SMALL -> discLabelSmall
        DialTextRole.BAND -> band
        DialTextRole.BAND_SECONDARY -> bandSecondary
    }

    /**
     * The same step, on an arc. There is no second scale: [CurvedTextStyle] is
     * built from the straight [TextStyle] this role already resolves to, so a
     * curved band and a straight one are the same type by construction — family,
     * size, weight and tracking all ride across.
     *
     * The one attribute that cannot is `fontFeatureSettings`: the curved renderer
     * paints glyphs itself and has no hook for it, so arced text gets Barlow's
     * proportional figures rather than the tabular ones. It costs the LIFTING
     * clock a pixel of re-centring per digit and buys the whole band its arc.
     */
    fun curved(role: DialTextRole, color: Color): CurvedTextStyle =
        CurvedTextStyle(style(role).copy(color = color))
}

/** Reference-canvas sizes; [scale] is measured diameter / 384. */
fun dialTypography(scale: Float, density: Density): DialTypography {
    fun size(referencePx: Float): TextUnit = with(density) { (referencePx * scale).toSp() }
    fun numeral(referencePx: Float, tracking: Float = 0f) = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = size(referencePx),
        letterSpacing = size(tracking),
        lineHeight = 1.02.em,
        fontFeatureSettings = TABULAR,
    )
    fun band(referencePx: Float, tracking: Float) = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = size(referencePx),
        letterSpacing = size(tracking),
        lineHeight = 1.2.em,
        fontFeatureSettings = TABULAR,
    )
    return DialTypography(
        numeralLarge = numeral(72f),
        numeral = numeral(60f),
        // The tracking is the START label's, and START is what this step is for (§5.2).
        discLabel = numeral(36f, tracking = 6f),
        discLabelSmall = numeral(30f),
        band = band(26f, tracking = 4f),
        bandSecondary = band(24f, tracking = 3f),
    )
}

private const val TABULAR = "tnum"
