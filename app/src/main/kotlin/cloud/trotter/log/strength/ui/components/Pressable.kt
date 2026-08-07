package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.theme.FocusRing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val PressableFocusRingWidth = 2.dp
internal const val PressableDisabledAlpha = 0.4f

/**
 * Foundation interaction wrappers for controls without a faithful Material
 * component. Press feedback comes from the single [LocalIndication] installed
 * by `AppTheme`; this layer supplies semantics, shape clipping, and the app's
 * keyboard/d-pad focus ring. Authored motion may share [interactionSource].
 */
fun Modifier.pressable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    pressableBase(shape, source).clickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}

/** [pressable] for one-of-many choices (day tabs and picker rows). */
fun Modifier.pressableSelectable(
    selected: Boolean,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    pressableBase(shape, source).selectable(
        selected = selected,
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/** [pressable] for on/off controls (the completion tick and switches). */
fun Modifier.pressableToggleable(
    value: Boolean,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    pressableBase(shape, source).toggleable(
        value = value,
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onValueChange = onValueChange,
    )
}

private fun Modifier.pressableBase(shape: Shape, source: InteractionSource): Modifier =
    clip(shape).then(FocusRingElement(source, shape))

/** The shared whole-control disabled treatment; apply before background/border. */
fun Modifier.disabledAlpha(enabled: Boolean): Modifier =
    if (enabled) this else this.alpha(PressableDisabledAlpha)

private data class FocusRingElement(
    val interactionSource: InteractionSource,
    val shape: Shape,
) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(interactionSource, shape)
    override fun update(node: FocusRingNode) = node.update(interactionSource, shape)
    override fun InspectorInfo.inspectableProperties() { name = "pressableFocusRing" }
}

private class FocusRingNode(
    private var interactionSource: InteractionSource,
    private var shape: Shape,
) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var observation: Job? = null

    override fun onAttach() = observe(interactionSource)

    fun update(interactionSource: InteractionSource, shape: Shape) {
        this.shape = shape
        if (this.interactionSource != interactionSource) {
            this.interactionSource = interactionSource
            focused = false
            observation?.cancel()
            observe(interactionSource)
        }
        invalidateDraw()
    }

    private fun observe(source: InteractionSource) {
        observation = coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val width = PressableFocusRingWidth.toPx()
        inset(width / 2f) {
            drawOutline(shape.createOutline(size, layoutDirection, this), FocusRing, style = Stroke(width))
        }
    }
}
