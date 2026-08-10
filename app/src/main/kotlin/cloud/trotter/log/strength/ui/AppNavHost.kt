package cloud.trotter.log.strength.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.backup.BackupActions
import cloud.trotter.log.strength.ui.backup.BackupScreen
import cloud.trotter.log.strength.ui.backup.BackupViewModel
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseActions
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseScreen
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseViewModel
import cloud.trotter.log.strength.ui.day.DayActions
import cloud.trotter.log.strength.ui.day.DayEditActions
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.licenses.LicenseEntry
import cloud.trotter.log.strength.ui.licenses.LicensesScreen
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogViewModel
import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupViewModel
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayViewModel
import cloud.trotter.log.strength.ui.wizard.WizardActions
import cloud.trotter.log.strength.ui.wizard.WizardScreen
import cloud.trotter.log.strength.ui.wizard.WizardViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext

/**
 * Single-activity nav graph (spec §8.1, brief D1): `wizard` (first run / re-run),
 * `today` (home, #121), `day` (the workout screen, pushed from Today), `setup`
 * (the gear, #12), `customExercise` (creation, #13), and `log` (history, #14, D2).
 */
object Routes {
    const val TODAY = "today"
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
 * Resolves whether the app opens on [Routes.WIZARD] or [Routes.TODAY] from
 * [TrackerRepository.wizardCompleteFlow] (D1). `null` means "not resolved
 * yet" — [AppNavHost] draws
 * [cloud.trotter.log.strength.ui.components.ProgramLoadingState] until then, so
 * the graph is never built with the wrong start destination and then
 * re-navigated (no flicker-navigation after compose).
 *
 * Every way into the app — launcher, a day-specific launcher alias, the
 * home-screen widget's tap target — is a plain ACTION_MAIN launch of
 * [cloud.trotter.log.strength.MainActivity] with no route extra, so they all
 * land wherever this resolves. #121 moved that landing from the workout screen
 * to Today: orientation first, the editor a tap away.
 *
 * [take] latches the *first* resolved value: this is only the initial
 * screen, and after that explicit navigation drives every transition. Without
 * the latch, first-run finish() flipping wizardComplete false→true would
 * rebuild the NavHost with a new start destination (resetting the back stack)
 * at the same instant [WizardRoute] explicitly navigates away — the
 * double-navigation D1 warns against.
 */
@HiltViewModel
class StartDestinationViewModel @Inject constructor(repo: TrackerRepository) : ViewModel() {
    val startDestination: StateFlow<String?> = repo.wizardCompleteFlow
        .map { complete -> if (complete) Routes.TODAY else Routes.WIZARD }
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun AppNavHost(startViewModel: StartDestinationViewModel = hiltViewModel()) {
    val destination by startViewModel.startDestination.collectAsStateWithLifecycle()
    val start = destination
    if (start == null) {
        // Authored rather than blank (#127) — and it stays blank anyway on the
        // ~100ms resolve this normally takes, because the treatment holds itself
        // back for longer than that (see ProgramLoadingState).
        ProgramLoadingState()
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.TODAY) {
            TodayRoute(
                // launchSingleTop: a double-tap on START is one workout, not two
                // stacked copies of it to back out of.
                onStart = { navController.navigate(Routes.DAY) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETUP) },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onSetUpProgram = { navController.navigate(Routes.WIZARD) },
            )
        }
        // Pushed from Today, never the start destination (#121), so system back
        // out of the workout screen is a plain pop to Today — no BackHandler,
        // no popUpTo, just the stack doing its job.
        composable(Routes.DAY) {
            DayRoute(
                onCreateExercise = { pattern -> navController.navigate(Routes.customExercise(pattern)) },
                onSetUpProgram = { navController.navigate(Routes.WIZARD) },
                // Dismissing the session receipt (#126) is the same pop system
                // back already performs — a finished workout leaves the workout
                // screen, and Today re-derives what the rotation now says.
                onFinishSession = { navController.popBackStack() },
            )
        }
        composable(Routes.WIZARD) {
            WizardRoute(
                onFinished = {
                    // graph.startDestinationId only clears back to WIZARD on a
                    // first-run finish (stack [wizard] -> [today]). It latches
                    // for the process lifetime (see StartDestinationViewModel),
                    // so a Setup re-run reaches here with stack
                    // [today, setup, wizard] and startDestinationId is still
                    // WIZARD, not TODAY — popping to it would leave
                    // [today, setup, today]. Popping the whole back stack (id 0)
                    // is correct for both paths: first-run [wizard] -> [today],
                    // re-run [today, setup, wizard] -> [today]. A re-run also
                    // regenerates the program, so landing on Today rather than
                    // straight in the editor is the honest result: the wizard
                    // changed what "next" means, and Today is what says so.
                    navController.navigate(Routes.TODAY) {
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
        // The empty journal's START (#127) leaves nothing behind it: popping LOG
        // before pushing DAY means backing out of the workout lands on Today,
        // not on the empty list the lifter just left.
        composable(Routes.LOG) {
            LogRoute(
                onBack = { navController.popBackStack() },
                onStartSession = {
                    navController.navigate(Routes.DAY) { popUpTo(Routes.TODAY) }
                },
                onSetUpProgram = { navController.navigate(Routes.WIZARD) },
            )
        }
        composable(Routes.BACKUP) { BackupRoute(onBack = { navController.popBackStack() }) }
        composable(Routes.LICENSES) { LicensesRoute(onBack = { navController.popBackStack() }) }
    }
}

/**
 * The orientation screen (#121) and the app's home: it says what day is next and
 * what is in it, then hands off. It owns no mutations — [TodayViewModel] reads,
 * [DayViewModel] writes — so the only thing that leaves here is a navigation.
 */
@Composable
private fun TodayRoute(
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onSetUpProgram: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TodayScreen(
        state = state,
        actions = TodayActions(
            onStart = onStart,
            onOpenSettings = onOpenSettings,
            onOpenLog = onOpenLog,
            onSetUpProgram = onSetUpProgram,
        ),
    )
}

@Composable
private fun DayRoute(
    onCreateExercise: (MovementPattern) -> Unit,
    onSetUpProgram: () -> Unit,
    onFinishSession: () -> Unit,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dayEditState by viewModel.dayEditState.collectAsStateWithLifecycle()
    val cascadeCeremony by viewModel.cascadeCeremony.collectAsStateWithLifecycle()
    val sessionReceipt by viewModel.sessionReceipt.collectAsStateWithLifecycle()
    val removedSets by viewModel.removedSets.collectAsStateWithLifecycle()
    // The receipt's SHARE, launched exactly the way the Log screen's is
    // (session-share brief §3): the ViewModel only ever builds the Intent, and
    // this is the call site that hands it to the chooser.
    val context = LocalContext.current
    val pendingShare by viewModel.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        pendingShare?.let { intent ->
            context.startActivity(intent)
            viewModel.shareHandled()
        }
    }
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
            onStartCardio = viewModel::startCardio,
            onStopCardio = viewModel::stopCardio,
            onCardioScreenLive = viewModel::setCardioScreenLive,
            onCreateExercise = onCreateExercise,
            onSetUpProgram = onSetUpProgram,
        ),
        dayEditState = dayEditState,
        dayEditActions = DayEditActions(
            onSwap = viewModel::swapDaySlot,
            onAdd = viewModel::addDaySlot,
            onRemove = viewModel::removeDaySlot,
            onSetSuperset = viewModel::setDaySlotSuperset,
            onRemoveSuperset = viewModel::removeDaySlotSuperset,
            onResetToTemplate = viewModel::resetDayToTemplate,
        ),
        cascadeCeremony = cascadeCeremony,
        onDismissCascade = viewModel::dismissCascadeCeremony,
        sessionReceipt = sessionReceipt,
        onShareSession = viewModel::shareSession,
        onFinishSession = {
            viewModel.dismissSessionReceipt()
            onFinishSession()
        },
        removedSets = removedSets,
        onUndoRemoveSet = viewModel::undoRemoveSet,
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
            onThemePreferenceChange = viewModel::setThemePreference,
            onRestTimerEnabledChange = viewModel::setRestTimerEnabled,
            onRestOverrideChange = viewModel::setRestOverride,
            onRestOverridesReset = viewModel::clearRestOverrides,
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
    val automaticBackupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::enableAutomaticBackup)
    }

    BackupScreen(
        state = state,
        actions = BackupActions(
            onAutomaticBackupChange = { enabled ->
                if (enabled) automaticBackupFolderLauncher.launch(null) else viewModel.disableAutomaticBackup()
            },
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
 * repo-only. They load once per visit off the main thread; there's no state to
 * survive process death because there's nothing to edit.
 */
@Composable
private fun LicensesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by produceState<List<LicenseEntry>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
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
    }
    entries?.let { LicensesScreen(entries = it, onBack = onBack) }
}

/**
 * Owns the Health Connect permission launcher (#17). The request contract and
 * the permission set come from [LogViewModel] (which delegates to the reader),
 * so `:app` drives the lazy, user-initiated request without importing any
 * androidx.health type. On any result — granted or denied — the ViewModel
 * re-reads Health Connect; a denial simply leaves the section empty (A3).
 */
@Composable
private fun LogRoute(
    onBack: () -> Unit,
    onStartSession: () -> Unit,
    onSetUpProgram: () -> Unit,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Two facts this screen can't observe change while it is away: the Health
    // Connect grant, which lives in another app and can be granted, revoked or
    // reset there (#158), and the device's civil day, which turns on its own
    // (#176). Re-reading both on every resume — rather than once when the
    // ViewModel was built — is what makes the section's status and the
    // journal's "today" honest on the next visit.
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose {}
    }
    val permissionLauncher = rememberLauncherForActivityResult(remember { viewModel.permissionContract() }) {
        viewModel.refreshHealth()
    }
    // The SHARE tap's payoff (session-share brief §3): LogViewModel only ever
    // builds the Intent, never launches it — this is the one call site that
    // hands a rendered share card to the system chooser, and it fires exactly
    // once per render (shareHandled clears pendingShare right after).
    val context = LocalContext.current
    val pendingShare by viewModel.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        pendingShare?.let { intent ->
            context.startActivity(intent)
            viewModel.shareHandled()
        }
    }
    LogScreen(
        state = state,
        actions = LogActions(
            onBack = onBack,
            onToggleExpanded = viewModel::toggleExpanded,
            onPageCalendar = viewModel::pageCalendar,
            onConnectHealth = { permissionLauncher.launch(viewModel.requestedPermissions) },
            onPublishPastWorkouts = viewModel::publishPastWorkouts,
            onApplyBodyweight = viewModel::applyBodyweightPrompt,
            onDismissBodyweight = viewModel::dismissBodyweightPrompt,
            onShare = viewModel::shareSession,
            onStartSession = onStartSession,
            onSetUpProgram = onSetUpProgram,
        ),
    )
}

@Composable
private fun WizardRoute(onFinished: () -> Unit, viewModel: WizardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A first-run restore leaves the wizard the same way finishing it does: the
    // ViewModel flips isComplete once the import lands on a backup that was
    // itself past the wizard. A backup taken mid-first-run leaves it false and
    // we stay here, now showing the restored answers.
    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onFinished()
    }
    // Same picker and mime type the Backup screen uses; a null Uri (the user
    // backed out) is a silent no-op, not an error.
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restoreFromBackup)
    }
    // System back steps the wizard backward, and the current step recedes under
    // the gesture. On the first step the handler is disabled precisely so the
    // system runs its own back-to-home preview (exiting a fresh install is fine
    // — the draft is in SavedStateHandle either way).
    val backProgress = rememberBackGestureProgress(enabled = !state.isFirstStep) {
        viewModel.onBack()
    }
    // Registered after the gesture handler so it takes priority while enabled.
    // Back on the first step means leaving the app, and a restore in flight is
    // writing two stores that share no transaction (#172) — it stays put.
    BackHandler(enabled = state.restore.inFlight) {}
    Box(Modifier.backGesturePreview { backProgress.value }) {
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
                onRestoreFromBackup = { restoreLauncher.launch(arrayOf("application/json")) },
            ),
        )
    }
}

/**
 * On save, returns to the caller (the picker or Setup) with the new exercise
 * already visible there: it's in [cloud.trotter.log.strength.data.catalog.ExerciseCatalog]
 * the moment [CustomExerciseViewModel.save] returns, and [cloud.trotter.log.strength.data.TrackerRepository.catalogFlow]
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
