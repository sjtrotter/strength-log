package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary

/**
 * The standard app card: flat surface + hairline border, no Material tonal
 * elevation or shadow. One delta from the pre-M3 Box implementation, accepted
 * with the migration: OutlinedCard's Surface clips children to the card shape
 * (the old Column did not). Caller-side draw modifiers — the done edge — sit
 * outside that clip and are unaffected; content must stay in bounds.
 *
 * The look is the prototype's utilitarian near-black, not a floating M3 card.
 */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
    ) {
        AppCardContent(content)
    }
}

/** A card whose entire surface is one action. Do not use for nested actions. */
@Composable
fun AppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
    ) {
        AppCardContent(content)
    }
}

@Composable
private fun AppCardContent(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.padding(16.dp), content = content)

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun AppCardPreview() {
    AppTheme {
        AppCard {
            Text("Back Squat", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text("3x8-12 · main lift", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
