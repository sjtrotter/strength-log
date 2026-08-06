package cloud.trotter.log.strength.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
     * No two independent controls may claim the same pixel.
     *
     * Nesting is exempt: a control inside another control's clickable — SHARE
     * inside a session card, the swap pill inside a card header — legitimately
     * sits on top of it, and Compose resolves that by depth. What this catches
     * is the failure from PR #134: a *sibling* whose 48dp target was never given
     * 48dp of layout and so quietly annexed the control next to it.
     *
     * [knownOverlaps] names pairs that already collided before this check
     * existed. Listing one is a statement that it is someone else's bug, not
     * that it is acceptable — see the call site for which issue owns it.
     */
    fun ComposeContentTestRule.assertNoOverlappingTouchTargets(
        knownOverlaps: Set<Set<String>> = emptySet(),
    ) {
        val nodes = interactiveNodes()
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                if (a.isWithin(b) || b.isWithin(a)) continue
                if (setOf(a.describe(), b.describe()) in knownOverlaps) continue
                assertTrue(
                    "touch targets overlap: '${a.describe()}' touch=${a.touchBoundsInRoot} " +
                        "layout=${a.boundsInRoot} vs '${b.describe()}' touch=${b.touchBoundsInRoot} " +
                        "layout=${b.boundsInRoot}",
                    !a.touchBoundsInRoot.overlaps(b.touchBoundsInRoot),
                )
            }
        }
    }

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
