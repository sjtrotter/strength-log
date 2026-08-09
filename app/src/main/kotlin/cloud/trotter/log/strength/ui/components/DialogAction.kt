package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One action in a dialog's button row: M3's `TextButton`, kept behind a
 * wrapper because six dialogs share one action policy — per-action semantic
 * color, the app's pill shape, and compact padding — and pinning those at
 * every call site is how the pre-M3 version drifted in the first place.
 */
@Composable
fun DialogAction(label: String, color: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
