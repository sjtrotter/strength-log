package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** A running rep window is an explicit active effort (#275); count entry releases it. */
@Composable
internal fun TimedRepScreenAwake(running: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
}
