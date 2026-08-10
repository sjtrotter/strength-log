package cloud.trotter.log.strength.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Shared checks for issue #123's half of the contract that a screenshot cannot
 * see: where a finger actually lands.
 *
 * Both walk the merged semantics tree — the same tree an accessibility service
 * reads — and use [SemanticsNode.touchBoundsInRoot], not `boundsInRoot`. The
 * difference is the point of the exercise: `boundsInRoot` is what a control
 * draws, `touchBoundsInRoot` is what it answers to, and Compose grows the
 * latter to the 48dp minimum whether or not the layout left room. A control
 * that looks fine and steals from its neighbour differs from one that was given
 * room only in the second measurement.
 */
object TouchTargets {

    private const val MIN_DP = 48

    /**
     * Every node a tap, toggle or selection can reach *and see*. Nodes clipped
     * out of a scrolling viewport keep their semantics but report empty bounds;
     * they are skipped, because a target the user cannot reach cannot collide
     * with one they can.
     */
    private fun ComposeContentTestRule.interactiveNodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            if (node.config.contains(SemanticsActions.OnClick) && !node.boundsInRoot.isEmpty) all += node
            node.children.forEach(::walk)
        }
        walk(onRoot().fetchSemanticsNode())
        return all
    }

    private fun SemanticsNode.describe(): String =
        config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: config.getOrNull(SemanticsActions.OnClick)?.label
            ?: "node $id"

    private fun SemanticsNode.isWithin(other: SemanticsNode): Boolean {
        var ancestor = parent
        while (ancestor != null) {
            if (ancestor.id == other.id) return true
            ancestor = ancestor.parent
        }
        return false
    }

    /**
     * The part of this node's minimum touch target that is actually reachable.
     *
     * [SemanticsNode.touchBoundsInRoot] retains the node's full target when a
     * scrolling ancestor clips the node. Rebuild that target around the
     * clip-aware [SemanticsNode.boundsInRoot]: preserve only the padding by
     * which the touch target extends past the node's unclipped layout bounds,
     * then intersect it with the reported touch target. For a fully visible
     * node, `boundsInRoot` equals its unclipped layout bounds, so this returns
     * the original touch target unchanged; only viewport-clipped targets are
     * reduced.
     */
    private fun SemanticsNode.visibleTouchBoundsInRoot(): Rect {
        val touch = touchBoundsInRoot
        val visible = boundsInRoot
        if (visible.isEmpty) return Rect.Zero

        val position = positionInRoot
        val unclipped = Rect(
            left = position.x,
            top = position.y,
            right = position.x + size.width,
            bottom = position.y + size.height,
        )
        val paddedVisible = Rect(
            left = visible.left - (unclipped.left - touch.left).coerceAtLeast(0f),
            top = visible.top - (unclipped.top - touch.top).coerceAtLeast(0f),
            right = visible.right + (touch.right - unclipped.right).coerceAtLeast(0f),
            bottom = visible.bottom + (touch.bottom - unclipped.bottom).coerceAtLeast(0f),
        )
        return touch.intersect(paddedVisible)
    }

    /**
     * No two independent controls may claim the same pixel.
     *
     * Nesting is exempt: a control inside another control's clickable — the
     * swap pill inside a card header's collapse target — legitimately sits on
     * top of it, and Compose resolves that by depth. What this catches
     * is the failure from PR #134: a *sibling* whose 48dp target was never given
     * 48dp of layout and so quietly annexed the control next to it.
     */
    private fun ComposeContentTestRule.overlappingTouchTargets(): Map<Set<String>, Int> {
        val nodes = interactiveNodes()
        val found = mutableMapOf<Set<String>, Int>()
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                if (a.isWithin(b) || b.isWithin(a)) continue
                if (!a.visibleTouchBoundsInRoot().overlaps(b.visibleTouchBoundsInRoot())) continue
                val pair = setOf(a.describe(), b.describe())
                found[pair] = (found[pair] ?: 0) + 1
            }
        }
        return found
    }

    /**
     * The exact set of collisions on screen, by name and by count.
     *
     * Not a skip-list: an expected pair tolerates only the geometry that is
     * already known and documented, and any *other* overlapping pair — or one
     * more occurrence of a known one — fails. A collision that gets fixed fails
     * too, which is the point; the expectation is a record of the screen, not a
     * licence for a shape of bug.
     */
    fun ComposeContentTestRule.assertOverlappingTouchTargetsAreExactly(expected: Map<Set<String>, Int>) {
        val actual = overlappingTouchTargets()
        assertEquals(
            "overlapping touch targets changed — expected ${render(expected)}, found ${render(actual)}",
            expected,
            actual,
        )
    }

    /** No control may sit on another's target. */
    fun ComposeContentTestRule.assertNoOverlappingTouchTargets() =
        assertOverlappingTouchTargetsAreExactly(emptyMap())

    private fun render(pairs: Map<Set<String>, Int>): String =
        if (pairs.isEmpty()) "none" else pairs.entries.joinToString { "${it.key} x${it.value}" }

    /** Every reachable control answers to at least 48dp in both axes. */
    fun ComposeContentTestRule.assertEveryTouchTargetIsAtLeast48dp(allowUnder48: Set<String> = emptySet()) {
        val min = with(density) { MIN_DP.dp.toPx() }
        interactiveNodes().forEach { node ->
            val name = node.describe()
            if (name in allowUnder48) return@forEach
            val bounds = node.touchBoundsInRoot
            assertTrue(
                "'$name' has a ${bounds.width}x${bounds.height}px touch target, below ${min}px",
                bounds.width >= min && bounds.height >= min,
            )
        }
    }
}
