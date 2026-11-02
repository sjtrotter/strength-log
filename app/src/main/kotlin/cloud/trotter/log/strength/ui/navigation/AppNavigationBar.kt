package cloud.trotter.log.strength.ui.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
                        contentDescription = stringResource(destination.label),
                        modifier = Modifier.size(30.dp),
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colorScheme.onSurface,
                    // The pill cannot hold a 30 dp icon; the filled glyph marks the selected tab.
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
