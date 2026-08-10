package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.CardTitleSmall
import cloud.trotter.log.strength.ui.theme.Surface
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

enum class SelectionMode { Radio, Check, Action }

/**
 * A tappable choice row for single-select questions (wizard, and later Setup
 * #12): filled/bordered like [AppCard], but tints to the accent and shows a
 * checkmark when [selected]. Screens that aren't day-scoped (the wizard has no
 * day of its own yet) use day index 0's accent as the app's one "primary"
 * highlight color — this reuses the existing per-day palette (SSOT) rather
 * than introducing a new brand color.
 *
 * [mode] controls the interaction semantics: [SelectionMode.Radio] is one of
 * many persistent choices in a `selectableGroup()`; [SelectionMode.Check] is
 * several of many; [SelectionMode.Action] means the card looks like a choice
 * but performs navigation or an action, so it uses plain button semantics and
 * has no selection state.
 * The ✓ glyph is silenced via [clearAndSetSemantics] to avoid reading it raw.
 * Material 3 has no single card API for all three semantic modes plus this
 * authored checkmark/subtitle interior, so only the container is M3.
 */
@Composable
fun SelectionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentDayIndex: Int = 0,
    mode: SelectionMode = SelectionMode.Radio,
) {
    val accent = dayAccent(accentDayIndex)
    val onAccent = onDayAccent(accentDayIndex)
    val background = if (selected) accent else Surface
    val border = if (selected) accent else Border
    val shape = MaterialTheme.shapes.large
    val interaction = when (mode) {
        SelectionMode.Radio -> Modifier.pressableSelectable(
            selected = selected,
            role = Role.RadioButton,
            shape = shape,
            onClick = onClick,
        )
        SelectionMode.Check -> Modifier.pressableToggleable(
            value = selected,
            role = Role.Checkbox,
            shape = shape,
            onValueChange = { onClick() },
        )
        SelectionMode.Action -> Modifier.pressable(
            role = Role.Button,
            shape = shape,
            onClick = onClick,
        )
    }
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            // A row shorter than the 48dp minimum gets its TOUCH bounds grown
            // past its layout — at a list viewport's bottom edge that growth
            // bleeds into whatever sits below (#210). A layout floor keeps the
            // target on the row's own ground; on-device text already renders
            // rows at or above it, so nothing visible changes.
            .heightIn(min = 48.dp)
            .then(interaction),
        shape = shape,
        colors = CardDefaults.outlinedCardColors(containerColor = background),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    color = if (selected) onAccent else TextPrimary,
                    style = CardTitleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text(
                        "✓",
                        color = onAccent,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
            subtitle?.let {
                Text(
                    it,
                    color = if (selected) onAccent else TextSecondary,
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
