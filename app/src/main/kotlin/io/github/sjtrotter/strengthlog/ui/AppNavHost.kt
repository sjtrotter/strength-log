package io.github.sjtrotter.strengthlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.ui.day.DayActions
import io.github.sjtrotter.strengthlog.ui.day.DayScreen
import io.github.sjtrotter.strengthlog.ui.day.DayViewModel
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.wizard.WizardActions
import io.github.sjtrotter.strengthlog.ui.wizard.WizardScreen
import io.github.sjtrotter.strengthlog.ui.wizard.WizardViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Single-activity nav graph (spec §8.1, brief D1): `wizard` (first run / re-run)
 * and `day` (home). Setup (#12), history (#14) and the day-edit sheet (#11) add
 * their own destinations as they land.
 */
object Routes {
    const val DAY = "day"
    const val WIZARD = "wizard"
}

/**
 * Resolves whether the app opens on [Routes.WIZARD] or [Routes.DAY] from
 * [TrackerRepository.wizardCompleteFlow] (D1). `null` means "not resolved
 * yet" — [AppNavHost] renders nothing but the app background until then, so
 * the graph is never built with the wrong start destination and then
 * re-navigated (no flicker-navigation after compose).
 */
@HiltViewModel
class StartDestinationViewModel @Inject constructor(repo: TrackerRepository) : ViewModel() {
    val startDestination: StateFlow<String?> = repo.wizardCompleteFlow
        .map { complete -> if (complete) Routes.DAY else Routes.WIZARD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun AppNavHost(startViewModel: StartDestinationViewModel = hiltViewModel()) {
    val destination by startViewModel.startDestination.collectAsStateWithLifecycle()
    val start = destination
    if (start == null) {
        Box(Modifier.fillMaxSize().background(Background))
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.DAY) { DayRoute() }
        composable(Routes.WIZARD) {
            WizardRoute(
                onFinished = {
                    navController.navigate(Routes.DAY) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    }
                },
            )
        }
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

@Composable
private fun WizardRoute(onFinished: () -> Unit, viewModel: WizardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onFinished()
    }
    WizardScreen(
        state = state,
        actions = WizardActions(
            onNext = viewModel::onNext,
            onBack = viewModel::onBack,
            onEmphasisChange = viewModel::setEmphasis,
            onDaysPerWeekChange = viewModel::setDaysPerWeek,
            onSplitChange = viewModel::setSplit,
            onAnchorSchemeChange = viewModel::setAnchorScheme,
            onDeadliftVariantChange = viewModel::setDeadliftVariant,
            onCardioModeChange = viewModel::setCardioMode,
            onCardioPlacementChange = viewModel::setCardioPlacement,
            onFiveKChange = viewModel::setFiveK,
            onBodyweightChange = viewModel::setBodyweight,
            onAgeChange = viewModel::setAge,
            onLevelChange = viewModel::setLevel,
            onEquipmentToggle = viewModel::toggleEquipment,
        ),
    )
}
