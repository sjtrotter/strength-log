package io.github.sjtrotter.strengthlog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.sjtrotter.strengthlog.ui.day.DayActions
import io.github.sjtrotter.strengthlog.ui.day.DayScreen
import io.github.sjtrotter.strengthlog.ui.day.DayViewModel

/**
 * Single-activity nav graph. Only the day screen exists today; the wizard (#9),
 * setup (#12), history (#14) and day-edit sheet (#11) add their own destinations
 * as they land, so the graph is deliberately one route for now.
 */
object Routes {
    const val DAY = "day"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.DAY) {
        composable(Routes.DAY) { DayRoute() }
    }
}

@Composable
private fun DayRoute(viewModel: DayViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DayScreen(
        state = state,
        actions = DayActions(
            onSelectDay = viewModel::selectDay,
            onWeightChange = viewModel::changeWeight,
            onRepsChange = viewModel::changeReps,
            onToggleDone = viewModel::toggleDone,
            onAddSet = viewModel::addSet,
            onRemoveSet = viewModel::removeSet,
            onToggleCollapse = viewModel::toggleCollapse,
            onKeepScreenOnChange = viewModel::setKeepScreenOn,
            onClearChecks = viewModel::clearChecks,
            onDone = viewModel::completeDay,
            // Setup screen lands in #12; the gear is inert until then.
            onOpenSettings = {},
        ),
    )
}
