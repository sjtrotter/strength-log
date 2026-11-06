package cloud.trotter.log.strength.playtest

import android.view.View
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.fetchRobolectricWindowRoots
import kotlin.math.roundToInt

internal data class SemanticsShape(
    val texts: List<String>,
    val tag: String?,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val inDialog: Boolean,
    val children: Int,
)

internal fun SemanticsNodeInteractionsProvider.semanticsSnapshot(density: Float): List<SemanticsShape> =
    onAllNodes(SemanticsMatcher("all nodes") { true })
        .fetchSemanticsNodes(atLeastOneRootRequired = false).map { node ->
            val bounds = node.boundsInWindow
            SemanticsShape(
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text),
                node.config.getOrNull(SemanticsProperties.TestTag),
                (bounds.left / density).roundToInt(),
                (bounds.top / density).roundToInt(),
                (bounds.width / density).roundToInt(),
                (bounds.height / density).roundToInt(),
                generateSequence(node) { it.parent }.any { isDialog().matches(it) },
                node.children.size,
            )
        }

internal fun List<SemanticsShape>.captureTexts(): List<CaptureText> =
    sortedWith(compareBy({ it.top }, { it.left }))
        .flatMap { node -> node.texts.map { CaptureText(it, node.inDialog) } }

private data class WindowShape(
    val decor: View,
    val visibility: Int,
    val windowVisibility: Int,
    val shown: Boolean,
    val attached: Boolean,
    val focused: Boolean,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

@OptIn(InternalRoborazziApi::class)
internal fun ComposeTestRule.settleSemantics(
    deadline: StepDeadline,
    awaitPendingWork: () -> Unit,
    onFrame: () -> Unit,
): List<SemanticsShape> {
    var shapes = emptyList<SemanticsShape>()
    var idle = false
    settleFrames({ mainClock.currentTime }, awaitPendingWork, isIdle = { idle }) {
        val paused = deadline.pause(this)
        onFrame()
        if (!paused) throw deadline.timeout()
        shapes = deadline.call { semanticsSnapshot(density.density) }
        val windows = deadline.call {
            runOnUiThread {
                val roots = fetchRobolectricWindowRoots().map { it.decorView }
                // A sheet can stop moving in semantics while its scrim or dismissal still awaits frames.
                idle = Recomposer.runningRecomposers.value.none { it.hasPendingWork } && roots.none {
                    it.isLayoutRequested || it.animation?.let { animation -> !animation.hasEnded() } == true
                }
                roots.map { view ->
                    WindowShape(view, view.visibility, view.windowVisibility, view.isShown,
                        view.isAttachedToWindow, view.hasWindowFocus(), view.left, view.top, view.width, view.height)
                }.toSet()
            }
        }
        shapes to windows
    }
    return shapes
}
