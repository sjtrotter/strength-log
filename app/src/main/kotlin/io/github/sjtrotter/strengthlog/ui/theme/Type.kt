package io.github.sjtrotter.strengthlog.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.R

/**
 * Condensed display face for numerals and labels (spec §8.5: "Oswald-class").
 * Bundled as the OFL-licensed Oswald variable font — see
 * app/src/main/font-licenses/oswald/OFL.txt — so the app never needs network
 * access to render it. Two weight instances are pulled from the single
 * variable file via [FontVariation]; API 26 (this app's minSdk) is the
 * platform floor for variable-font axis support.
 */
@OptIn(ExperimentalTextApi::class)
val Condensed = FontFamily(
    Font(
        R.font.oswald_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.oswald_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/** Body copy stays the platform default sans — spec only opinionates on display type. */
private val Sans = FontFamily.Default

/**
 * The type scale M3 screens (#9-14) actually need: a big condensed numeral
 * for steppers/goals, a condensed label for badges/chips, a condensed title
 * for day/screen headers, and two plain-sans body sizes. Every other
 * [Typography] role is left at the M3 default and is unused by this app.
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
