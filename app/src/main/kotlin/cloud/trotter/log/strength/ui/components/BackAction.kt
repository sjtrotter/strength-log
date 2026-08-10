package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** M3 navigation leaf for the app's authored-height header rows. */
@Composable
fun BackAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualSize: Dp = 40.dp,
    outlined: Boolean = true,
) {
    // Keep the authored 40/32dp painted box inside M3's separate 48dp target.
    // IconButton itself has a 40dp token, so the inner exact size is required
    // for the picker's existing 32dp visual.
    val sizedModifier = modifier.minimumInteractiveComponentSize().size(visualSize)
    if (outlined) {
        OutlinedIconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    }
}

/** Borderless M3 close leaf; its icon owns the single TalkBack description. */
@Composable
fun CloseAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close")
    }
}
