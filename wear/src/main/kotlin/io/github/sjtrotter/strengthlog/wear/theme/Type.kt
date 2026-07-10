package io.github.sjtrotter.strengthlog.wear.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.sjtrotter.strengthlog.wear.R

/**
 * Condensed display face for every numeral/label (design digest §0), matching
 * the phone's `ui/theme/Type.kt` — same three OFL-licensed static-weight TTFs
 * (`app/src/main/font-licenses/barlow-condensed/OFL.txt` covers this reuse),
 * copied into this module's own `res/font/` because `:wear` is a separate APK
 * with its own resource set (`:domain`, the only shared module, is pure
 * Kotlin and can't carry Android resources).
 */
val Condensed = FontFamily(
    Font(R.font.barlow_condensed_medium, weight = FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, weight = FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, weight = FontWeight.Bold),
)
