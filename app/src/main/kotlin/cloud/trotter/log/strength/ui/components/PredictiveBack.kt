package cloud.trotter.log.strength.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Exposes the active back gesture as progress a visible surface can draw with.
 * A cancelled gesture animates home instead of snapping because the user
 * changed their mind, and the surface should look as though it never left.
 */
@Composable
fun rememberBackGestureProgress(enabled: Boolean = true, onBack: () -> Unit): State<Float> {
    val progress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { progress.snapTo(it.progress) }
            // Reset before the commit: a handler may only pop one layer, leaving
            // the same surface instance to appear whole when it returns.
            progress.snapTo(0f)
            onBack()
        } catch (cancellation: CancellationException) {
            progress.animateTo(0f)
            throw cancellation
        }
    }
    return progress.asState()
}

/**
 * Recedes a surface under an active back gesture without fighting the
 * platform's own edge animation. [progress] is a lambda so it is read in the
 * draw phase; a gesture must not recompose a whole screen on every frame.
 */
fun Modifier.backGesturePreview(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress()
    alpha = 1f - 0.45f * p
    scaleX = 1f - 0.08f * p
    scaleY = 1f - 0.08f * p
}
