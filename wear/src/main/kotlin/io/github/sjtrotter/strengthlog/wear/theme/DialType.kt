package io.github.sjtrotter.strengthlog.wear.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
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
 * nothing escapes its zone (§2, acceptance 1), and a 1.3× font scale on a 176px
 * disc breaks that guarantee outright. Body text elsewhere in the app is
 * unaffected — the watch's only screen is this dial.
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
        numeralLarge = numeral(58f),
        numeral = numeral(44f),
        // 3px tracking is the START label's, and START is what this step is for (§5.2).
        discLabel = numeral(25f, tracking = 3f),
        discLabelSmall = numeral(21f),
        band = band(13f, tracking = 2f),
        bandSecondary = band(11f, tracking = 1.5f),
    )
}

private const val TABULAR = "tnum"
