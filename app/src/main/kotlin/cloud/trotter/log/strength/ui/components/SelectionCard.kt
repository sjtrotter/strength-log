package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.CardTitleSmall
import cloud.trotter.log.strength.ui.theme.Surface
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.accentSoft
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

/**
 * A tappable choice row for single-select questions (wizard, and later Setup
 * #12): filled/bordered like [AppCard], but tints to the accent and shows a
 * checkmark when [selected]. Screens that aren't day-scoped (the wizard has no
 * day of its own yet) use day index 0's accent as the app's one "primary"
 * highlight color — this reuses the existing per-day palette (SSOT) rather
 * than introducing a new brand color.
 *
 * `.semantics { selected = ... }` (A7) exposes the choice state directly, so
 * TalkBack announces "selected"/"not selected" without depending on the ✓
 * glyph, which is silenced via [clearAndSetSemantics] to avoid reading it raw.
 */
@Composable
fun SelectionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentDayIndex: Int = 0,
) {
    val accent = dayAccent(accentDayIndex)
    val onAccent = onDayAccent(accentDayIndex)
    val background = if (selected) accentSoft(accentDayIndex) else Surface
    val border = if (selected) accent else Border
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(background, MaterialTheme.shapes.large)
            .border(1.dp, border, MaterialTheme.shapes.large)
            .pressable(onClick = onClick, shape = MaterialTheme.shapes.large)
            .semantics { this.selected = selected }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                color = TextPrimary,
                style = CardTitleSmall,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text("✓", color = onAccent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        subtitle?.let {
            Text(
                it,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SelectionCardPreview() {
    AppTheme {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionCard(
                title = "Balanced strength + muscle",
                subtitle = "The default — even mix of heavy work and volume.",
                selected = true,
                onClick = {},
            )
            SelectionCard(title = "Strength-leaning", selected = false, onClick = {})
        }
    }
}
