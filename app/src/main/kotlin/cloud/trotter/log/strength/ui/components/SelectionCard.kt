package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
 * [selectable] exposes this as a radio-button choice, including its selection
 * state, without depending on the ✓ glyph. The glyph is silenced via
 * [clearAndSetSemantics] to avoid reading it raw. Callers place exclusive
 * choice sets in `selectableGroup()`. A multi-select list (equipment) passes
 * [multiChoice] and gets checkbox semantics via [toggleable] instead — one of
 * several is not one of many, and TalkBack must not promise exclusivity.
 */
@Composable
fun SelectionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentDayIndex: Int = 0,
    multiChoice: Boolean = false,
) {
    val accent = dayAccent(accentDayIndex)
    val onAccent = onDayAccent(accentDayIndex)
    val background = if (selected) accentSoft(accentDayIndex) else Surface
    val border = if (selected) accent else Border
    val interaction = if (multiChoice) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .then(interaction),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = background),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(14.dp)) {
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
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SelectionCardPreview() {
    AppTheme {
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
