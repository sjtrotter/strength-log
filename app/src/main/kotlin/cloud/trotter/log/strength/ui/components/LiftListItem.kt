package cloud.trotter.log.strength.ui.components

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import cloud.trotter.log.strength.R

/**
 * The shared numbered lift row. Material 3 supplies [ListItem], but not the
 * app-specific number/name/set-count field mapping used by Today and Wizard.
 */
@Composable
internal fun LiftListItem(number: Int, name: String, setCount: Int) {
    ListItem(
        leadingContent = {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(name) },
        trailingContent = {
            Text(pluralStringResource(R.plurals.today_lift_set_count, setCount, setCount))
        },
        colors = liftListItemColors(),
    )
}

@Composable
internal fun liftListItemColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.background,
    headlineColor = MaterialTheme.colorScheme.onSurface,
    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
