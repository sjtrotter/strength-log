package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent
import io.github.sjtrotter.strengthlog.ui.theme.onDayAccent

/**
 * A labeled on/off switch (wizard's "keep me 5k-ready" toggle, spec §6.1, and
 * later Setup's unit/prefs toggles). Same track-and-thumb look the day screen
 * already uses for keep-screen-on, pulled out here so a second screen doesn't
 * have to re-draw it.
 *
 * `toggleable(role = Role.Switch)` (A7) gives TalkBack the switch role and
 * on/off state for free; [label] is real [Text] so it merges into the
 * accessible name without any extra `contentDescription`.
 */
@Composable
fun SwitchToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accentDayIndex: Int = 0,
) {
    val accent = dayAccent(accentDayIndex)
    val onAccent = onDayAccent(accentDayIndex)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
    ) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        val trackColor by animateColorAsState(if (checked) accent else Surface2, tween(200), label = "switchTrack")
        val thumbOffset by animateFloatAsState(if (checked) 18f else 2f, tween(200), label = "switchThumb")
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(24.dp)
                .background(trackColor, RoundedCornerShape(50))
                .border(1.dp, if (checked) accent else Border, RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset.dp, top = 2.dp)
                    .size(18.dp)
                    .background(if (checked) onAccent else TextSecondary, CircleShape),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SwitchTogglePreview() {
    AppTheme {
        var on by remember { mutableStateOf(true) }
        SwitchToggle(label = "Keep me 5k-ready", checked = on, onCheckedChange = { on = it })
    }
}
