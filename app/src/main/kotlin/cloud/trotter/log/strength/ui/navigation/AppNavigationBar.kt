package cloud.trotter.log.strength.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
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
            NavigationBarItem(
                selected = isSelected(destination),
                onClick = { onSelect(destination) },
                icon = {
                    Icon(imageVector = destination.icon, contentDescription = null)
                },
                label = { Text(stringResource(destination.label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colorScheme.onSurface,
                    selectedTextColor = colorScheme.onSurface,
                    indicatorColor = colorScheme.surfaceContainerHigh,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
