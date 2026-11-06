package cloud.trotter.log.strength.playtest

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
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

internal fun ComposeTestRule.settleSemantics(
    deadline: StepDeadline,
    awaitPendingWork: () -> Unit,
    onFrame: () -> Unit,
): List<SemanticsShape> {
    var shapes = emptyList<SemanticsShape>()
    settleFrames({ mainClock.currentTime }, awaitPendingWork) {
        val paused = deadline.pause(this)
        onFrame()
        if (!paused) throw deadline.timeout()
        deadline.call { semanticsSnapshot(density.density) }.also { shapes = it }
    }
    return shapes
}
