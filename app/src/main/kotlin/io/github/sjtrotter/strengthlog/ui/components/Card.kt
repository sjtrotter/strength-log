package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Surface
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary

// Design-pass: card radius 10 -> 12 (docs/design-handoff/tokens/spacing.css --r-card).
private val CardShape = RoundedCornerShape(12.dp)

/**
 * The standard app card: flat surface + hairline border, no Material tonal
 * elevation or shadow — the prototype's utilitarian near-black look, not a
 * floating M3 card.
 */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface, CardShape)
            .border(1.dp, Border, CardShape)
            .padding(16.dp),
        content = content,
    )
}

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
