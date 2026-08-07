package cloud.trotter.log.strength.ui.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The widest a column of this app's text stays readable on a large window. */
val ReadableWidth = 600.dp

/**
 * Caps and centres the app's single content column, and carries the system-bar
 * insets with it. Not the two-pane layout reserved for #29 — one column, capped.
 *
 * The order is the whole point, which is why it lives here and not at the nine
 * call sites that would each have to get it right: insets first, cap second, so
 * the 600dp band is measured *inside* the safe area. Applied the other way round
 * a display cutout or a foldable hinge eats its width out of the band instead of
 * out of the window, and the column silently narrows on exactly the devices this
 * cap exists for.
 *
 * The cap never binds on a 360-430dp portrait phone, so those windows measure
 * exactly as they did before it existed.
 */
@Composable
fun BoxScope.readableWidth(): Modifier =
    Modifier
        .align(Alignment.TopCenter)
        .systemBarsPadding()
        .widthIn(max = ReadableWidth)
        .fillMaxSize()

/**
 * Whether the window is too short to spend the usual vertical rhythm on fixed
 * chrome — a phone in landscape, at roughly 360dp.
 *
 * Reads the activity's own [android.content.res.Configuration], which is
 * window-scoped (multi-window included) and, unlike `WindowInfo.containerSize`,
 * carries a real value on the first composition. That matters: a size that
 * starts at zero would spend one frame on the tall-window rhythm and then visibly
 * re-space the chrome underneath the lifter's thumb.
 */
@Composable
fun isShortWindow(): Boolean = LocalConfiguration.current.screenHeightDp.dp < ShortWindowHeight

/** Vertical breathing room for fixed chrome, tightened only in a short window. */
@Composable
fun chromeVerticalPadding(): Dp = if (isShortWindow()) 4.dp else 10.dp

private val ShortWindowHeight = 480.dp
