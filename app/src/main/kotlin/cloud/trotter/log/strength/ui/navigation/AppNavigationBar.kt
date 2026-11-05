package cloud.trotter.log.strength.ui.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import cloud.trotter.log.strength.ui.Routes

@Composable
fun AppNavigationBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    AppNavigationBar(
        isSelected = { destination ->
            currentDestination?.hierarchy?.any { it.route == destination.route } == true
        },
        onSelect = navController::navigateToTopLevel,
    )
}

/** Shared top-level navigation policy for both the bar and in-content links. */
internal fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(Routes.TODAY) {
            saveState = true
            inclusive = false
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
internal fun AppNavigationBar(
    selectedRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    AppNavigationBar(
        isSelected = { it.route == selectedRoute },
        onSelect = onSelect,
    )
}

@Composable
private fun AppNavigationBar(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    NavigationBar(containerColor = colorScheme.surfaceContainer) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = isSelected(destination)
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = { NavigationLabel(stringResource(destination.label)) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colorScheme.onSecondaryContainer,
                    selectedTextColor = colorScheme.onSurface,
                    indicatorColor = colorScheme.secondaryContainer,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * M3's [NavigationBar] has a fixed 80 dp height, so a label at font scale 2 would be clipped by
 * the bar's own bounds. The label follows the user's font size up to [MAX_LABEL_FONT_SCALE] and
 * stops there, where it still fits; the screens above the bar carry the full scale.
 */
@Composable
private fun NavigationLabel(text: String) {
    val density = LocalDensity.current
    val capped = Density(density.density, minOf(density.fontScale, MAX_LABEL_FONT_SCALE))
    CompositionLocalProvider(LocalDensity provides capped) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val MAX_LABEL_FONT_SCALE = 1.5f
