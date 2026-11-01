package cloud.trotter.log.strength.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import cloud.trotter.log.strength.ui.preview.PreviewActions
import cloud.trotter.log.strength.ui.preview.PreviewScreen
import cloud.trotter.log.strength.ui.preview.PreviewViewModel
import cloud.trotter.log.strength.ui.day.WorkoutConflictDialog
import cloud.trotter.log.strength.ui.day.DayActions
import cloud.trotter.log.strength.ui.day.DayEditActions
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.WorkoutSetScreen
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.licenses.LicenseEntry
import cloud.trotter.log.strength.ui.licenses.LicensesScreen
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogViewModel
import cloud.trotter.log.strength.ui.navigation.AppNavigationBar
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.navigation.finishWorkout
import cloud.trotter.log.strength.ui.navigation.legacyPlanDestination
import cloud.trotter.log.strength.ui.navigation.navigateToTopLevel
import cloud.trotter.log.strength.ui.plan.PlanActions
import cloud.trotter.log.strength.ui.plan.PlanScreen
import cloud.trotter.log.strength.ui.plan.PlansActions
import cloud.trotter.log.strength.ui.plan.PlansScreen
import cloud.trotter.log.strength.ui.plan.PlansViewModel
import cloud.trotter.log.strength.ui.plan.PlanViewModel
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
 * Single-activity nav graph (spec §8.1, brief D1): `wizard` (first run / new plan),
 * `today` (overview), `plans` (plans list), `plan/{planId}` (one plan's days),
 * `preview/{planId}/{dayId}` (day preview), and `day` / `dayplan` (workout).
 * The plan-scoped workout's `/edit` route opens its editor once. `setup` is
 * the gear, `customExercise` creates exercises, and `log` shows history.
 * Today, Log, and Plans share the navigation bar; all other destinations hide it.
 * The legacy plain `plan` destination redirects restored back stacks to Plans.
 */
object Routes {
    const val TODAY = "today"
    const val DAY = "day"
    const val DAY_ID_ARG = "dayId"
    const val DAY_PLAN_ID_ARG = "dayPlanId"
    // Kept byte-for-byte: a back stack saved by an older version names this
    // destination by its route, and restoring it after an update must still
    // find it. A day that carries its plan uses DAY_IN_PLAN instead.
    const val DAY_WITH_ID = "$DAY/{$DAY_ID_ARG}"
    const val DAY_IN_PLAN = "dayplan/{$DAY_PLAN_ID_ARG}/{$DAY_ID_ARG}"
    const val DAY_EDITOR_ARG = "openEditor"
    const val DAY_EDIT_IN_PLAN = "dayplan/{$DAY_PLAN_ID_ARG}/{$DAY_ID_ARG}/edit"
    fun day(dayId: String, planId: Long? = null, openEditor: Boolean = false) =
        if (planId == null) "$DAY/$dayId" else "dayplan/$planId/$dayId" + if (openEditor) "/edit" else ""
    const val WIZARD = "wizard"
    const val SETUP = "setup"
    const val SETUP_REST = "setup?rest=true"
    const val LOG = "log"
    const val PLAN = "plan" // Compatibility for back stacks saved before the Plans tab.
    const val PLAN_ID_ARG = "planId"
    const val PLAN_IN_PLANS = "plan/{$PLAN_ID_ARG}"
    const val PREVIEW = "preview/{$PLAN_ID_ARG}/{$DAY_ID_ARG}"
    fun plan(planId: Long) = "plan/$planId"
    fun preview(selection: DaySelection) = "preview/${requireNotNull(selection.planId)}/${selection.dayId}"
    const val PLANS = "plans"
    const val BACKUP = "backup"
    const val LICENSES = "licenses"

    const val CUSTOM_EXERCISE = "customExercise"
    const val CUSTOM_EXERCISE_PATTERN_ARG = "pattern"
    const val CUSTOM_EXERCISE_ROUTE = "$CUSTOM_EXERCISE?$CUSTOM_EXERCISE_PATTERN_ARG={$CUSTOM_EXERCISE_PATTERN_ARG}"

    /**
     * Public entry point for custom exercise creation (D1: a route, not a
     * sheet, since it's reachable from two places). [pattern] pre-selects the
     * form's movement pattern for the day edit picker's "＋ Create exercise"
     * context; Setup calls this with `null`.
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
 * land wherever this resolves. After setup, that landing is Today: orientation
 * first, the editor a tap away.
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

/**
 * Hosts the app graph and its three top-level bar destinations; the workout
 * and every other destination hide the bar.
 */
@Composable
fun AppNavHost(startViewModel: StartDestinationViewModel = hiltViewModel()) {
    val destination by startViewModel.startDestination.collectAsStateWithLifecycle()
    val start = destination
    if (start == null) {
        // Authored rather than blank — and it stays blank anyway on the ~100ms
        // resolve this normally takes, because the treatment holds itself back
        // for longer than that (see ProgramLoadingState).
        ProgramLoadingState()
        return
    }

    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (TopLevelDestination.showsBarOn(currentRoute)) {
                AppNavigationBar(navController)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            enterTransition = { routeEnterTransition },
            exitTransition = { routeExitTransition },
            popEnterTransition = { routePopEnterTransition },
            popExitTransition = { routePopExitTransition },
        ) {
        composable(Routes.TODAY) {
            TodayRoute(
                // launchSingleTop: a double-tap on START is one workout, not two
                // stacked copies of it to back out of.
                onStart = { navController.navigate(Routes.day(it.dayId, it.planId)) { launchSingleTop = true } },
                onOpenCardio = { navController.navigate(Routes.day(it.dayId, it.planId)) },
                onOpenPreview = { navController.navigate(Routes.preview(it)) },
                onOpenSettings = { navController.navigate(Routes.SETUP) },
                onOpenPlans = { navController.navigateToTopLevel(TopLevelDestination.PLANS) },
                onSetUpProgram = { navController.navigate(Routes.WIZARD) },
            )
        }
        // Pushed from Today, never the start destination, so leaving an idle
        // workout screen is a plain pop to Today.
        composable(
            route = Routes.DAY,
            enterTransition = { dayFadeThroughEnterTransition },
            exitTransition = { dayFadeThroughExitTransition },
            popEnterTransition = { dayFadeThroughEnterTransition },
            popExitTransition = { dayFadeThroughExitTransition },
        ) {
            DayRoute(
                onCreateExercise = { pattern -> navController.navigate(Routes.customExercise(pattern)) },
                onSetUpProgram = { navController.navigate(Routes.WIZARD) },
                onFinishSession = { navController.finishWorkout() },
                onBack = { navController.popBackStack() },
                onOpenRestTimer = { navController.navigate(Routes.SETUP_REST) },
            )
        }
        listOf(
            Routes.DAY_WITH_ID to listOf(navArgument(Routes.DAY_ID_ARG) { type = NavType.StringType }),
            Routes.DAY_EDIT_IN_PLAN to listOf(
                navArgument(Routes.DAY_PLAN_ID_ARG) { type = NavType.StringType },
                navArgument(Routes.DAY_ID_ARG) { type = NavType.StringType },
                navArgument(Routes.DAY_EDITOR_ARG) { type = NavType.BoolType; defaultValue = true },
            ),
            Routes.DAY_IN_PLAN to listOf(
                navArgument(Routes.DAY_PLAN_ID_ARG) { type = NavType.StringType },
                navArgument(Routes.DAY_ID_ARG) { type = NavType.StringType },
            ),
        ).forEach { (route, arguments) ->
            composable(route = route, arguments = arguments) { entry ->
                val dayId = entry.arguments?.getString(Routes.DAY_ID_ARG).orEmpty()
                DayRoute(
                    onCreateExercise = { pattern -> navController.navigate(Routes.customExercise(pattern)) },
                    onSetUpProgram = { navController.navigate(Routes.WIZARD) },
                    onFinishSession = { navController.finishWorkout() },
                    onBack = { navController.popBackStack() },
                    initialDayId = dayId,
                    onOpenRestTimer = { navController.navigate(Routes.SETUP_REST) },
                )
            }
        }
        composable(Routes.WIZARD) {
            WizardRoute(
                onFinished = {
                    // Clear both first-run and new-plan wizard stacks. The new
                    // plan is selected, and Today shows its next day.
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
        composable(
            route = "${Routes.SETUP}?rest={rest}",
            arguments = listOf(navArgument("rest") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            SetupRoute(
                openRestTimer = entry.arguments?.getBoolean("rest") == true,
                onBack = { navController.popBackStack() },
                onCreateCustomExercise = { navController.navigate(Routes.customExercise(null)) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenLicenses = { navController.navigate(Routes.LICENSES) },
            )
        }
        // The empty journal's START leaves nothing behind it: popping LOG before
        // pushing DAY means backing out of the workout lands on Today, not on
        // the empty list the lifter just left.
        composable(Routes.LOG) {
            LogRoute(
                onStartSession = {
                    navController.navigate(Routes.DAY) { popUpTo(Routes.TODAY) }
                },
                onSetUpProgram = { navController.navigate(Routes.WIZARD) },
            )
        }
        legacyPlanDestination(navController)
        composable(Routes.PLAN_IN_PLANS, arguments = listOf(
            navArgument(Routes.PLAN_ID_ARG) { type = NavType.StringType },
        )) {
            PlanRoute(
                onOpenDay = { navController.navigate(Routes.preview(it)) },
                onOpenPlans = { navController.popBackStack() },
                onSetUpProgram = { navController.navigate(Routes.WIZARD) },
            )
        }
        composable(Routes.PREVIEW, arguments = listOf(
            navArgument(Routes.PLAN_ID_ARG) { type = NavType.StringType },
            navArgument(Routes.DAY_ID_ARG) { type = NavType.StringType },
        )) {
            PreviewRoute(
                onBack = { navController.popBackStack() },
                onOpenDay = { selection, edit -> navController.navigate(Routes.day(selection.dayId, selection.planId, edit)) { launchSingleTop = true } },
            )
        }
        composable(Routes.PLANS) {
            PlansRoute(
                onOpenPlan = { navController.navigate(Routes.plan(it)) },
                onOpenSettings = { navController.navigate(Routes.SETUP) },
                onNewPlan = { navController.navigate(Routes.WIZARD) },
            )
        }
        composable(Routes.BACKUP) { BackupRoute(onBack = { navController.popBackStack() }) }
        composable(Routes.LICENSES) { LicensesRoute(onBack = { navController.popBackStack() }) }
        }
    }
}

/**
 * Home overview: resume the open run, preview any day, or explicitly start
 * the display plan. Shared Start handling resolves conflicts before navigation.
 */
@Composable
internal fun TodayRoute(
    onStart: (DaySelection) -> Unit,
    onOpenCardio: (DaySelection) -> Unit,
    onOpenPreview: (DaySelection) -> Unit,
    onOpenSettings: () -> Unit,
    onSetUpProgram: () -> Unit,
    onOpenPlans: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val start = viewModel.workoutStart
    val conflict by start.conflict.collectAsStateWithLifecycle()
    LaunchedEffect(start) { start.navigation.collect { onStart(it) } }
    TodayScreen(state, TodayActions(
        onStart = start::start, onResume = onStart,
        onOpenCardio = onOpenCardio, onOpenPreview = onOpenPreview,
        onOpenSettings = onOpenSettings, onSetUpProgram = onSetUpProgram,
        onOpenPlans = onOpenPlans, onToggleOtherDays = viewModel::toggleOtherDays,
    ))
    WorkoutConflictDialog(conflict, start::dismiss, start::resume, start::confirmDiscard, start::discardAndStart)
}

@Composable
internal fun PreviewRoute(
    onBack: () -> Unit,
    onOpenDay: (DaySelection, Boolean) -> Unit,
    viewModel: PreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val start = viewModel.workoutStart
    val conflict by start.conflict.collectAsStateWithLifecycle()
    LaunchedEffect(start) { start.navigation.collect { onOpenDay(it, false) } }
    PreviewScreen(state, PreviewActions(onBack, { onOpenDay(viewModel.selection, true) }, viewModel::start))
    WorkoutConflictDialog(conflict, start::dismiss, start::resume, start::confirmDiscard, start::discardAndStart)
}

@Composable
private fun PlansRoute(
    onOpenPlan: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onNewPlan: () -> Unit,
    viewModel: PlansViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.createdPlan.collect { onOpenPlan(it) } }
    PlansScreen(
        state,
        PlansActions(
            onOpenPlan = onOpenPlan, onOpenSettings = onOpenSettings,
            onNewPlan = onNewPlan,
            onSelect = viewModel::select,
            onMenu = viewModel::openMenu,
            onRename = viewModel::rename,
            onRenameDraft = viewModel::setRenameDraft,
            onSaveRename = viewModel::saveRename,
            onDismissRename = viewModel::dismissRename,
            onDuplicate = viewModel::duplicate,
            onArchive = viewModel::archive,
            onRestore = viewModel::restore,
            onUseAlongside = viewModel::useAlongside,
            onPause = viewModel::pause,
            onUseOnly = viewModel::useOnly,
            onShowNewPlan = viewModel::showNewPlan,
            onStartEmpty = viewModel::startEmpty,
            onNewPlanName = viewModel::setNewPlanName,
            onCreateEmpty = viewModel::createEmpty,
            onDismissNewPlan = viewModel::dismissNewPlan,
        ),
    )
}

@Composable
private fun PlanRoute(
    onOpenDay: (DaySelection) -> Unit,
    onOpenPlans: () -> Unit,
    onSetUpProgram: () -> Unit,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlanScreen(
        state = state,
        actions = PlanActions(
            onOpenDay = onOpenDay,
            onOpenPlans = onOpenPlans,
            onSetUpProgram = onSetUpProgram,
            onAddDay = viewModel::addDay,
            onDayMenu = viewModel::openDayMenu,
            onRenameDay = viewModel::renameDay,
            onRemoveDay = viewModel::removeDay,
            onDayTitleDraft = viewModel::setDayTitleDraft,
            onSaveDayDialog = viewModel::saveDayDialog,
            onDismissDayDialog = viewModel::dismissDayDialog,
        ),
    )
}

@Composable
internal fun DayRoute(
    onCreateExercise: (MovementPattern) -> Unit,
    onSetUpProgram: () -> Unit,
    onFinishSession: () -> Unit,
    onBack: () -> Unit = onFinishSession,
    initialDayId: String? = null,
    onOpenRestTimer: () -> Unit,
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
    val notificationAsked by viewModel.notificationPermissionAsked.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.rest, notificationAsked) {
        if (state.rest != null && !notificationAsked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            viewModel.markNotificationPermissionAsked()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(pendingShare) {
        pendingShare?.let { intent ->
            context.startActivity(intent)
            viewModel.shareHandled()
        }
    }
    val actions = DayActions(
        onSelectDay = viewModel::selectDay,
        onWeightChange = viewModel::changeWeight,
        onRepsChange = viewModel::changeReps,
        onSecondsChange = viewModel::changeSeconds,
        onToggleDone = viewModel::toggleDone,
        onAddSet = viewModel::addSet,
        onRemoveSet = viewModel::removeSet,
        onSelectPage = viewModel::selectPage,
        onLogFocusedSet = viewModel::logFocusedSet,
        onUndoLastLog = viewModel::undoLastLog,
        onSelectWorkoutLift = viewModel::selectWorkoutLift,
        onNextWorkoutLift = viewModel::nextWorkoutLift,
        onOpenSetEditor = viewModel::openSetEditor,
        onCloseSetEditor = viewModel::closeSheet,
        onOpenAllSets = viewModel::openAllSets,
        onOpenLiftSheet = viewModel::openLiftSheet,
        onOpenDaySheet = viewModel::openDaySheet,
        onSkipLift = viewModel::skipLift,
        onRestoreSkipped = viewModel::restoreSkipped,
        onSkipNowSet = viewModel::skipNowSet,
        onOpenSwapSheet = viewModel::openSwapSheet,
        onUndoWorkoutSwap = viewModel::undoWorkoutSwap,
        onDismissSwapUndo = viewModel::dismissSwapUndo,
        onConfirmPlanSwap = viewModel::confirmPlanSwap,
        onDismissPlanSwap = viewModel::dismissPlanSwap,
        onCloseAllSets = viewModel::closeSheet,
        onKeepScreenOnChange = viewModel::setKeepScreenOn,
        onDiscardWorkout = viewModel::discardWorkout,
        onClearTodayChecks = viewModel::clearTodayChecks,
        onDone = viewModel::completeDay,
        onStartCardio = viewModel::startCardio,
        onStopCardio = viewModel::stopCardio,
        onReachCardioTarget = viewModel::reachCardioTarget,
        onSkipCardioSet = viewModel::skipCardioSet,
        onEndCardioBlock = viewModel::endCardioBlock,
        onAddCardioSet = viewModel::addCardioSet,
        onOpenCardioSets = viewModel::openCardioSets,
        onOpenCardioDistance = viewModel::openCardioDistance,
        onRequestCardioDistance = viewModel::requestCardioDistance,
        onCardioDistanceChange = viewModel::changeCardioDistance,
        onSaveCardioDistance = viewModel::saveCardioDistance,
        onRetryCardioSave = viewModel::retryCardioSave,
        onCardioScreenLive = viewModel::setCardioScreenLive,
        onStartTimedRep = viewModel::startTimedRep,
        onStopTimedRep = viewModel::stopTimedRep,
        onRequestTimedRepCount = viewModel::requestTimedRepCount,
        onOpenTimedRepCount = viewModel::openTimedRepCount,
        onTimedRepDraftChange = viewModel::changeTimedRepDraft,
        onLogTimedRepCount = viewModel::logTimedRepCount,
        onAdjustRest = viewModel::adjustRest,
        onSkipRest = viewModel::skipRest,
        onCreateExercise = onCreateExercise,
        onSetUpProgram = onSetUpProgram,
        onSetExerciseNote = viewModel::setExerciseNote,
        onSetSessionNote = viewModel::setReceiptNote,
        onOpenRestTimer = onOpenRestTimer,
    )
    val dayEditActions = DayEditActions(
        onSwap = viewModel::swapDaySlot,
        onWorkoutSwap = { position, id -> viewModel.swapWorkoutSlot(position, id) },
        onWorkoutSwapHalf = { position, id, slot -> viewModel.swapWorkoutSlot(position, id, slot) },
        onMessageShown = viewModel::dismissSwapMessage,
        onAdd = viewModel::addDaySlot,
        onRemove = viewModel::removeDaySlot,
        onSetSuperset = viewModel::setDaySlotSuperset,
        onRemoveSuperset = viewModel::removeDaySlotSuperset,
        onSetKeepToPlan = viewModel::setDaySlotKeepToPlan,
        onResetSlotToPlan = viewModel::resetDaySlotToPlan,
        onResetToTemplate = viewModel::resetDayToTemplate,
        onMove = viewModel::moveDaySlot,
        onOpenPrescription = viewModel::openPrescription,
        onPrescriptionDraft = viewModel::changePrescriptionDraft,
        onSavePrescription = viewModel::savePrescription,
        onDiscardPrescription = viewModel::discardPrescription,
        onEditorOpen = viewModel::setDayEditorOpen,
        onOpenCardio = viewModel::openCardioBlock,
        onCardioDraft = viewModel::changeCardioDraft,
        onSaveCardio = viewModel::saveCardioBlock,
        onBackCardio = viewModel::backCardioBlock,
        onRemoveCardio = viewModel::removeCardioBlock,
    )
    DayScreen(
        state = state,
        actions = actions,
        dayEditState = dayEditState,
        dayEditActions = dayEditActions,
        cascadeCeremony = cascadeCeremony,
        onDismissCascade = viewModel::dismissCascadeCeremony,
        sessionReceipt = sessionReceipt,
        onShareSession = viewModel::shareSession,
        onFinishSession = {
            if (viewModel.dismissSessionReceipt()) onFinishSession()
        },
        onBack = onBack,
        removedSets = removedSets,
        onUndoRemoveSet = viewModel::undoRemoveSet,
        standaloneCardio = initialDayId != null && !state.isProgramDay,
        setScreenContent = if (state.usesWorkoutSetScreen) {
            { onExercises, onFinish, snackbarHost, onEditDay ->
                WorkoutSetScreen(state, actions, dayEditState.catalog, onExercises, onFinish, snackbarHost, onEditDay, onBack)
            }
        } else null,
    )
}

@Composable
private fun SetupRoute(
    openRestTimer: Boolean = false,
    onBack: () -> Unit,
    onCreateCustomExercise: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SetupScreen(
        state = state,
        openRestTimer = openRestTimer,
        actions = SetupActions(
            onBodyweightChange = viewModel::setBodyweight,
            onAgeChange = viewModel::setAge,
            onLevelChange = viewModel::setLevel,
            onEmphasisChange = viewModel::setEmphasis,
            onUnitToggle = viewModel::setUnit,
            onThemePreferenceChange = viewModel::setThemePreference,
            onRestTimerEnabledChange = viewModel::setRestTimerEnabled,
            onPhoneRestTimerEnabledChange = viewModel::setPhoneRestTimerEnabled,
            onKeepScreenOnChange = viewModel::setKeepScreenOn,
            onRestOverrideChange = viewModel::setRestOverride,
            onRestOverridesReset = viewModel::clearRestOverrides,
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
 * Static OSS-licenses screen. No view-model: the two license texts ship as APK
 * assets (`app/src/main/assets/licenses/`) rather than live repo-only. They
 * load once per visit off the main thread; there's no state to survive process
 * death because there's nothing to edit.
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
 * Owns the Health Connect permission launcher. The request contract and the
 * permission set come from [LogViewModel] (which delegates to the reader), so
 * `:app` drives the lazy, user-initiated request without importing any
 * androidx.health type. On any result — granted or denied — the ViewModel
 * re-reads Health Connect; a denial simply leaves the section empty (A3).
 */
@Composable
private fun LogRoute(
    onStartSession: () -> Unit,
    onSetUpProgram: () -> Unit,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Two facts this screen can't observe change while it is away: the Health
    // Connect grant, which lives in another app and can be granted, revoked or
    // reset there, and the device's civil day, which turns on its own.
    // Re-reading both on every resume — rather than once when the ViewModel
    // was built — is what makes the section's status and the journal's "today"
    // honest on the next visit.
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
            onToggleExpanded = viewModel::toggleExpanded,
            onPageCalendar = viewModel::pageCalendar,
            onConnectHealth = { permissionLauncher.launch(viewModel.requestedPermissions) },
            onPublishPastWorkouts = viewModel::publishPastWorkouts,
            onApplyBodyweight = viewModel::applyBodyweightPrompt,
            onDismissBodyweight = viewModel::dismissBodyweightPrompt,
            onShare = viewModel::shareSession,
            onToggleEdit = viewModel::toggleEdit,
            onWeightChange = viewModel::updateSessionSetWeight,
            onRepsChange = viewModel::updateSessionSetReps,
            onSecondsChange = viewModel::updateSessionSetSeconds,
            onDoneChange = viewModel::updateSessionSetDone,
            onDeleteSession = viewModel::deleteSession,
            onUndoDeleteSession = viewModel::undoDeleteSession,
            onSetSessionNote = viewModel::setSessionNote,
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
    // writing two stores that share no transaction — it stays put.
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
                onUnitChange = viewModel::setUnit,
                onAgeChange = viewModel::setAge,
                onLevelChange = viewModel::setLevel,
                onEquipmentToggle = viewModel::toggleEquipment,
                onRestoreFromBackup = { restoreLauncher.launch(arrayOf("application/json")) },
            ),
        )
    }
}

/**
 * On save, returns to the caller (the picker or Setup) with the new exercise already visible there: it's in
 * [cloud.trotter.log.strength.data.catalog.ExerciseCatalog] the moment [CustomExerciseViewModel.save] returns, and
 * [cloud.trotter.log.strength.data.TrackerRepository.catalogFlow] is live, so the picker only needs to observe it.
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
