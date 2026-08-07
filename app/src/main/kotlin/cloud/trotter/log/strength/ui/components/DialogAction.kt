package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Pill-shaped, like every other action in this app. */
private val DialogActionShape = RoundedCornerShape(50)

/**
 * One action in a dialog's button row.
 *
 * M3's own `TextButton` was what every dialog here reached for, and it brings
 * Material's ripple — the one press treatment #123 took out of the rest of the
 * app. Dialog *content* was simply never swept, because it lives in another
 * window. Same rhythm and label style as `TextButton`, the app's ripple and
 * focus ring instead, and a reserved 48dp target rather than M3's 40dp.
 *
 * The label is real text, so it is also the accessible name — no separate
 * content description.
 */
@Composable
fun DialogAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .pressable(role = Role.Button, shape = DialogActionShape, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}
