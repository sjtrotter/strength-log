package cloud.trotter.log.strength.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.theme.FocusRing
import cloud.trotter.log.strength.ui.theme.PressVeil
import kotlinx.coroutines.launch

/**
 * How long the pressed veil takes to arrive and to leave — the design tokens'
 * `--dur-fast`, i.e. the timing the stepper's segment flash already ran at.
 */
private const val PRESS_FADE_MS = 120

/** Focus ring weight. Thick enough to read at arm's length, thin enough that a
 *  focused control is still the same control. */
private val FocusRingWidth = 2.dp

/** What a disabled control fades to — the value the two hand-rolled disabled
 *  treatments already used (day-edit sheet buttons, custom-exercise footer). */
private const val DISABLED_ALPHA = 0.4f

/**
 * The app's press language, in one place.
 *
 * Every custom clickable in this app used to do one of two things: hand-roll a
 * `MutableInteractionSource` and swap a background, or pass nothing and inherit
 * Material's ripple — which spills over squared, unclipped bounds and looks
 * nothing like the rest of the app. [pressable] replaces both. It draws
 * [PressVeil] over the control's own [shape] while held, fading over
 * [PRESS_FADE_MS], and a [FocusRing] outline while the control has keyboard or
 * d-pad focus, so a hardware-keyboard user never loses their place.
 *
 * Because the veil is drawn *over* the control's content rather than under it,
 * it is order-independent: put `.pressable(...)` wherever `.clickable(...)`
 * used to sit and the fill lands on top of the background, the border and the
 * label alike. What is *not* order-independent is size: a control whose touch
 * target is grown with `minimumInteractiveComponentSize()` must apply that
 * *before* `.pressable(...)`, or the veil paints the 48dp target instead of the
 * visible chip.
 *
 * Touch-target sizing is deliberately not folded in here. Growing a target can
 * push it onto a neighbour's (see #123/#134), so it stays an explicit decision
 * at each call site rather than a side effect of asking for press feedback.
 *
 * Controls with their own designed press motion — the DONE/START scale spring —
 * keep it by passing their own [interactionSource] and reading it alongside;
 * the veil is the shared floor, not a competing treatment.
 *
 * A control that can be disabled pairs this with [disabledAlpha] at the head of
 * its chain. That is deliberately not applied here: fading has to wrap the
 * control's own background and border, which sit *before* this call, so folding
 * it in would leave a disabled control with a full-strength fill and a ghost
 * label.
 */
fun Modifier.pressable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = PressFeedback(shape),
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick,
)

/** [pressable] for one-of-many choices (day tabs, picker rows): same feedback,
 *  `selectable` semantics so TalkBack announces the selection state. */
fun Modifier.pressableSelectable(
    selected: Boolean,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = selectable(
    selected = selected,
    interactionSource = interactionSource,
    indication = PressFeedback(shape),
    enabled = enabled,
    role = role,
    onClick = onClick,
)

/** [pressable] for on/off controls (the ✓ tick, the switches). */
fun Modifier.pressableToggleable(
    value: Boolean,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier = toggleable(
    value = value,
    interactionSource = interactionSource,
    indication = PressFeedback(shape),
    enabled = enabled,
    role = role,
    onValueChange = onValueChange,
)

/**
 * The one disabled treatment. Apply it at the *head* of a control's modifier
 * chain — before any `background`/`border` — so the whole control fades, and
 * exactly once: [pressable] deliberately does not apply it for you.
 */
fun Modifier.disabledAlpha(enabled: Boolean): Modifier =
    if (enabled) this else this.alpha(DISABLED_ALPHA)

/**
 * The [Indication] behind [pressable]. A data class so Compose can compare two
 * instances structurally and skip re-creating the node on every recomposition.
 */
private data class PressFeedback(private val shape: Shape) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressFeedbackNode(interactionSource, shape)
}

private class PressFeedbackNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
) : Modifier.Node(), DrawModifierNode {

    private val veil = Animatable(0f)
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            // Presses can nest (a second finger down before the first lifts), so
            // count them rather than tracking a boolean that the first release
            // would clear early.
            val presses = mutableListOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    is FocusInteraction.Focus -> {
                        focused = true
                        invalidateDraw()
                    }
                    is FocusInteraction.Unfocus -> {
                        focused = false
                        invalidateDraw()
                    }
                }
                val target = if (presses.isEmpty()) 0f else 1f
                if (veil.targetValue != target) {
                    launch { veil.animateTo(target, tween(PRESS_FADE_MS)) { invalidateDraw() } }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val pressed = veil.value
        if (pressed <= 0f && !focused) return
        if (pressed > 0f) {
            drawOutline(shape.createOutline(size, layoutDirection, this), PressVeil, alpha = pressed)
        }
        if (focused) {
            // A stroke straddles the path it follows, so drawing the ring on the
            // node's own outline would spill half its width past the bounds and
            // lose it to the first ancestor that clips. Inset by half and the
            // whole ring lands inside.
            val width = FocusRingWidth.toPx()
            inset(width / 2f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), FocusRing, style = Stroke(width))
            }
        }
    }
}
