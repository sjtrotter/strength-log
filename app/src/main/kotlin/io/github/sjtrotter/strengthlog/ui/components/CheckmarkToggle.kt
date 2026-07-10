package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Done
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TickGlyph

private val ToggleShape = RoundedCornerShape(6.dp)

/**
 * Per-set done tick (spec §8's "green ✓"). Visually 28dp, but the toggleable
 * area sits on a >= 48dp box so the touch target meets Material's minimum.
 * Design-pass restyle: unchecked fill is [Surface2] (was transparent), and
 * ticking pops the whole chip in — scale 0.7 -> 1.0 on a spring, ~200ms (design
 * tokens: `--dur-med`/`--ease-spring`, reference `@keyframes pop` scales the
 * tick box) — via a transient [Animatable], never persisted state (A6/motion).
 *
 * TalkBack (A7): [description] is the accessible name (defaults to "Set
 * done"); [stateDescription] announces "Done"/"Not done" on top of the
 * checkbox role `toggleable` already sets. The inner ✓ glyph is silenced via
 * [clearAndSetSemantics] — the state description already says it.
 */
@Composable
fun CheckmarkToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "Set done",
) {
    // Resting scale is always 1.0; 0.7 is only the transient start of the pop.
    val scale = remember { Animatable(1f) }
    // Only pop on an actual tick — not when a checked row scrolls back into the
    // LazyColumn (LaunchedEffect re-runs on every re-entry to composition, which
    // otherwise replays the spring per card during a scroll and stutters it).
    val mounted = remember { booleanArrayOf(false) }
    LaunchedEffect(checked) {
        if (!mounted[0]) {
            mounted[0] = true
        } else if (checked) {
            scale.snapTo(0.7f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        } else {
            scale.snapTo(1f)
        }
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox)
            .semantics {
                contentDescription = description
                stateDescription = if (checked) "Done" else "Not done"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer(scaleX = scale.value, scaleY = scale.value)
                .size(28.dp)
                .background(if (checked) Done else Surface2, ToggleShape)
                .border(1.dp, if (checked) Done else Border, ToggleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(text = "✓", color = Background, style = TickGlyph, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun CheckmarkTogglePreview() {
    AppTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            var checkedA by remember { mutableStateOf(false) }
            var checkedB by remember { mutableStateOf(true) }
            CheckmarkToggle(checked = checkedA, onCheckedChange = { checkedA = it })
            CheckmarkToggle(checked = checkedB, onCheckedChange = { checkedB = it })
        }
    }
}
