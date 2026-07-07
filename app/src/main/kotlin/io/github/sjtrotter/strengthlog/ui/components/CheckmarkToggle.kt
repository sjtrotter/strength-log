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
 * ticking pops the glyph in — scale 0.7 -> 1.0 on a spring, ~200ms (design
 * tokens: `--dur-med`/`--ease-spring`) — via a transient [Animatable], never
 * persisted state (consistent with A6/motion rule).
 */
@Composable
fun CheckmarkToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(if (checked) 1f else 0.7f) }
    LaunchedEffect(checked) {
        if (checked) {
            scale.snapTo(0.7f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        } else {
            scale.snapTo(1f)
        }
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(if (checked) Done else Surface2, ToggleShape)
                .border(1.dp, if (checked) Done else Border, ToggleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    color = Background,
                    style = TickGlyph,
                    modifier = Modifier.graphicsLayer(scaleX = scale.value, scaleY = scale.value),
                )
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
