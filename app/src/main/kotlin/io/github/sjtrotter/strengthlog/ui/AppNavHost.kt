package io.github.sjtrotter.strengthlog.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.ui.backup.BackupActions
import io.github.sjtrotter.strengthlog.ui.backup.BackupScreen
import io.github.sjtrotter.strengthlog.ui.backup.BackupViewModel
import io.github.sjtrotter.strengthlog.ui.customexercise.CustomExerciseActions
import io.github.sjtrotter.strengthlog.ui.customexercise.CustomExerciseScreen
import io.github.sjtrotter.strengthlog.ui.customexercise.CustomExerciseViewModel
import io.github.sjtrotter.strengthlog.ui.day.DayActions
import io.github.sjtrotter.strengthlog.ui.day.DayEditActions
import io.github.sjtrotter.strengthlog.ui.day.DayScreen
import io.github.sjtrotter.strengthlog.ui.day.DayViewModel
import io.github.sjtrotter.strengthlog.ui.licenses.LicenseEntry
import io.github.sjtrotter.strengthlog.ui.licenses.LicensesScreen
import io.github.sjtrotter.strengthlog.ui.log.LogActions
import io.github.sjtrotter.strengthlog.ui.log.LogScreen
import io.github.sjtrotter.strengthlog.ui.log.LogViewModel
import io.github.sjtrotter.strengthlog.ui.setup.SetupActions
import io.github.sjtrotter.strengthlog.ui.setup.SetupScreen
import io.github.sjtrotter.strengthlog.ui.setup.SetupViewModel
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.wizard.WizardActions
import io.github.sjtrotter.strengthlog.ui.wizard.WizardScreen
import io.github.sjtrotter.strengthlog.ui.wizard.WizardViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take

/**
 * Single-activity nav graph (spec §8.1, brief D1): `wizard` (first run / re-run),
 * `day` (home), `setup` (the gear, #12), `customExercise` (creation, #13), and
 * `log` (history, #14, D2 — reached from a trailing tab once the header wires
 * it up).
 */
object Routes {
    const val DAY = "day"
    const val WIZARD = "wizard"
    const val SETUP = "setup"
    const val LOG = "log"
    const val BACKUP = "backup"
    const val LICENSES = "licenses"

    const val CUSTOM_EXERCISE = "customExercise"
    const val CUSTOM_EXERCISE_PATTERN_ARG = "pattern"
    const val CUSTOM_EXERCISE_ROUTE = "$CUSTOM_EXERCISE?$CUSTOM_EXERCISE_PATTERN_ARG={$CUSTOM_EXERCISE_PATTERN_ARG}"

    /**
     * Public entry point for #13 (D1: a route, not a sheet, since it's reachable
     * from two places). [pattern] pre-selects the form's movement pattern for
     * the #11 picker's "＋ Create exercise" context; Setup (#12) calls this with
     * `null`. Both callers land with their own PRs — this is the stable surface
     * they navigate through (`navController.navigate(Routes.customExercise(...))`).
     */
    fun customExercise(pattern: MovementPattern? = null): String =
        if (pattern == null) CUSTOM_EXERCISE else "$CUSTOM_EXERCISE?$CUSTOM_EXERCISE_PATTERN_ARG=${pattern.name}"
}

/**
 * Resolves whether the app opens on [Routes.WIZARD] or [Routes.DAY] from
 * [TrackerRepository.wizardCompleteFlow] (D1). `null` means "not resolved
 * yet" — [AppNavHost] renders nothing but the app background until then, so
 * the graph is never built with the wrong start destination and then
 * re-navigated (no flicker-navigation after compose).
 *
 * [take] latches the *first* resolved value: this is only the initial
 * screen, and after that explicit navigation drives every transition. Without
 * the latch, first-run finish() flipping wizardComplete false→true would
 * rebuild the NavHost with a new start destination (resetting the back stack)
 * at the same instant [WizardRoute] explicitly navigates to the day screen —
 * the double-navigation D1 warns against.
 */
@HiltViewModel
class StartDestinationViewModel @Inject constructor(repo: TrackerRepository) : ViewModel() {
    val startDestination: StateFlow<String?> = repo.wizardCompleteFlow
        .map { complete -> if (complete) Routes.DAY else Routes.WIZARD }
        .take(1)
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
        composable(Routes.DAY) {
            DayRoute(
                onOpenSettings = { navController.navigate(Routes.SETUP) },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onCreateExercise = { pattern -> navController.navigate(Routes.customExercise(pattern)) },
            )
        }
        composable(Routes.WIZARD) {
            WizardRoute(
                onFinished = {
                    // graph.startDestinationId only clears back to WIZARD on a
                    // first-run finish (stack [wizard] -> [day]). It latches for
                    // the process lifetime (see StartDestinationViewModel), so a
                    // Setup re-run reaches here with stack [day, setup, wizard]
                    // and startDestinationId is still WIZARD, not DAY — popping
                    // to it would leave [day, setup, day]. Popping the whole
                    // back stack (id 0) is correct for both paths: first-run
                    // [wizard] -> [day], re-run [day, setup, wizard] -> [day].
                    navController.navigate(Routes.DAY) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.CUSTOM_EXERCISE_ROUTE,
            arguments = listOf(
                navArgument(Routes.CUSTOM_EXERCISE_PATTERN_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            CustomExerciseRoute(onDone = { navController.popBackStack() })
        }
        composable(Routes.SETUP) {
            SetupRoute(
                onBack = { navController.popBackStack() },
                onRerunWizard = { navController.navigate(Routes.WIZARD) },
                onCreateCustomExercise = { navController.navigate(Routes.customExercise(null)) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenLicenses = { navController.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.LOG) { LogRoute(onBack = { navController.popBackStack() }) }
        composable(Routes.BACKUP) { BackupRoute(onBack = { navController.popBackStack() }) }
        composable(Routes.LICENSES) { LicensesRoute(onBack = { navController.popBackStack() }) }
    }
}

@Composable
private fun DayRoute(
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dayEditState by viewModel.dayEditState.collectAsStateWithLifecycle()
    DayScreen(
        state = state,
        actions = DayActions(
            onSelectDay = viewModel::selectDay,
            onWeightChange = viewModel::changeWeight,
            onRepsChange = viewModel::changeReps,
            onSecondsChange = viewModel::changeSeconds,
            onToggleDone = viewModel::toggleDone,
            onAddSet = viewModel::addSet,
            onRemoveSet = viewModel::removeSet,
            onToggleCollapse = viewModel::toggleCollapse,
            onKeepScreenOnChange = viewModel::setKeepScreenOn,
            onClearChecks = viewModel::clearChecks,
            onDone = viewModel::completeDay,
            onOpenSettings = onOpenSettings,
            onOpenLog = onOpenLog,
            onCreateExercise = onCreateExercise,
        ),
        dayEditState = dayEditState,
        dayEditActions = DayEditActions(
            onSwap = viewModel::swapDaySlot,
            onAdd = viewModel::addDaySlot,
            onRemove = viewModel::removeDaySlot,
            onResetToTemplate = viewModel::resetDayToTemplate,
        ),
    )
}

@Composable
private fun SetupRoute(
    onBack: () -> Unit,
    onRerunWizard: () -> Unit,
    onCreateCustomExercise: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SetupScreen(
        state = state,
        actions = SetupActions(
            onBodyweightChange = viewModel::setBodyweight,
            onAgeChange = viewModel::setAge,
            onLevelChange = viewModel::setLevel,
            onEmphasisChange = viewModel::setEmphasis,
            onCardioModeChange = viewModel::setCardioMode,
            onCardioPlacementChange = viewModel::setCardioPlacement,
            onFiveKChange = viewModel::setFiveK,
            onUnitToggle = viewModel::setUnit,
            onRerunWizard = onRerunWizard,
            onCreateCustomExercise = onCreateCustomExercise,
            onOpenBackup = onOpenBackup,
            onOpenLicenses = onOpenLicenses,
            onBack = onBack,
        ),
    )
}

/**
 * Owns the SAF launchers (brief D9: `:transfer` stays Uri-free, so the Uri
 * itself only ever exists here and in [BackupViewModel]). Each launcher hands
 * its result `Uri` straight to the matching view-model call; a `null` result
 * (the user backed out of the picker) is a no-op, not an error.
 */
@Composable
private fun BackupRoute(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now().toString() }

    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::beginImportBackup)
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(viewModel::exportCsv)
    }
    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::beginImportCsv)
    }

    BackupScreen(
        state = state,
        actions = BackupActions(
            onExportBackupClick = { exportBackupLauncher.launch("strength-log-backup-$today.json") },
            onImportBackupClick = { importBackupLauncher.launch(arrayOf("application/json")) },
            onExportCsvClick = { exportCsvLauncher.launch("strength-log-history-$today.csv") },
            onImportCsvClick = { importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
            onConfirmRestore = viewModel::confirmRestore,
            onCancelRestore = viewModel::cancelRestore,
            onUnmatchedPatternChange = viewModel::setUnmatchedPattern,
            onConfirmCsvImport = viewModel::confirmCsvImport,
            onCancelCsvImport = viewModel::cancelCsvImport,
            onDismissMessage = viewModel::dismissMessage,
            onBack = onBack,
        ),
    )
}

/**
 * Static OSS-licenses screen (M6 #23). No view-model: the two license texts
 * ship as APK assets (`app/src/main/assets/licenses/`) rather than live
 * repo-only, so `remember` just reads them off [LocalContext] once per visit
 * — there's no state to survive process death because there's nothing to
 * edit.
 */
@Composable
private fun LicensesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember {
        listOf(
            LicenseEntry(
                "Barlow Condensed — SIL Open Font License 1.1",
                context.assets.open("licenses/barlow-condensed-OFL.txt").bufferedReader().use { it.readText() },
            ),
            LicenseEntry(
                "Third-party libraries — Apache License 2.0",
                context.assets.open("licenses/apache-2.0-notices.txt").bufferedReader().use { it.readText() },
            ),
        )
    }
    LicensesScreen(entries = entries, onBack = onBack)
}

/**
 * Owns the Health Connect permission launcher (#17). The request contract and
 * the permission set come from [LogViewModel] (which delegates to the reader),
 * so `:app` drives the lazy, user-initiated request without importing any
 * androidx.health type. On any result — granted or denied — the ViewModel
 * re-reads Health Connect; a denial simply leaves the section empty (A3).
 */
@Composable
private fun LogRoute(onBack: () -> Unit, viewModel: LogViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(remember { viewModel.permissionContract() }) {
        viewModel.refreshHealth()
    }
    LogScreen(
        state = state,
        actions = LogActions(
            onBack = onBack,
            onToggleExpanded = viewModel::toggleExpanded,
            onConnectHealth = { permissionLauncher.launch(viewModel.requestedPermissions) },
            onApplyBodyweight = viewModel::applyBodyweightPrompt,
            onDismissBodyweight = viewModel::dismissBodyweightPrompt,
        ),
    )
}

@Composable
private fun WizardRoute(onFinished: () -> Unit, viewModel: WizardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onFinished()
    }
    // System back steps the wizard backward; on the first step it's disabled so
    // back falls through (exiting a fresh install is fine — the draft is in
    // SavedStateHandle either way).
    BackHandler(enabled = !state.isFirstStep) { viewModel.onBack() }
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

/**
 * On save, returns to the caller (the picker or Setup) with the new exercise
 * already visible there: it's in [io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog]
 * the moment [CustomExerciseViewModel.save] returns, and [io.github.sjtrotter.strengthlog.data.TrackerRepository.catalogFlow]
 * is live, so the #11 picker wiring landing later only needs to observe it.
 * System back before saving cancels the same way.
 */
@Composable
private fun CustomExerciseRoute(onDone: () -> Unit, viewModel: CustomExerciseViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }
    CustomExerciseScreen(
        state = state,
        actions = CustomExerciseActions(
            onNameChange = viewModel::setName,
            onPatternChange = viewModel::setPattern,
            onEquipmentToggle = viewModel::toggleEquipment,
            onPerHandChange = viewModel::setPerHand,
            onTrackingChange = viewModel::setTracking,
            onWeightChange = viewModel::setWeightDisplay,
            onTargetRepsChange = viewModel::setTargetReps,
            onTargetSecondsChange = viewModel::setTargetSeconds,
            onAddedWeightChange = viewModel::setAddedWeightDisplay,
            onSave = viewModel::save,
            onCancel = onDone,
        ),
    )
}
