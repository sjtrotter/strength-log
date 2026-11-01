package cloud.trotter.log.strength.ui.navigation

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.DayRoute
import cloud.trotter.log.strength.ui.PreviewRoute
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.TodayRoute
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.plan.PlanActions
import cloud.trotter.log.strength.ui.plan.PlanScreen
import cloud.trotter.log.strength.ui.plan.PlanViewModel
import cloud.trotter.log.strength.ui.plan.PlansActions
import cloud.trotter.log.strength.ui.plan.PlansScreen
import cloud.trotter.log.strength.ui.plan.PlansViewModel
import cloud.trotter.log.strength.ui.preview.PreviewViewModel
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.today.TodayViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class OverviewNavigationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var nav: NavHostController
    private lateinit var dayVm: DayViewModel
    private val vms = mutableListOf<ViewModel>()
    private var preferred = 0L
    private var other = 0L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("overview-nav", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        runBlocking {
            repo.replaceProgram(Program(listOf(
                ProgramDay("A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true))),
                ProgramDay("B", "Arms", "", listOf(ProgramExercise("ez_curl"))),
            )))
            preferred = repo.selectedPlanIdFlow.first()!!
            repo.renamePlan(preferred, "Strength")
            other = repo.duplicatePlan(preferred, "Other")
            repo.renameDay(other, "A", "Rehab")
            repo.useAlongside(other)
        }
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
    }

    private fun <T : ViewModel> keep(vm: T): T = vm.also { vms += it }

    private fun render(expectedRow: String = "Strength · Day A") {
        rule.setContent { AppTheme {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = {
                if (TopLevelDestination.showsBarOn(entry?.destination?.route)) AppNavigationBar(nav)
            }) { padding ->
                NavHost(nav, Routes.TODAY, Modifier.padding(padding)) {
                    composable(Routes.TODAY) {
                        val vm = remember { keep(TodayViewModel(repo, object : CivilTimeSource {
                            override val civilTime = flowOf(CivilTime.now())
                            override fun refresh() = Unit
                        }, SavedStateHandle())) }
                        TodayRoute(
                            onStart = { nav.navigate(Routes.day(it.dayId, it.planId)) { launchSingleTop = true } },
                            onOpenCardio = { nav.navigate(Routes.day(it.dayId, it.planId)) },
                            onOpenPreview = { nav.navigate(Routes.preview(it)) },
                            onOpenSettings = {}, onSetUpProgram = {},
                            onOpenPlans = { nav.navigateToTopLevel(TopLevelDestination.PLANS) }, viewModel = vm,
                        )
                    }
                    composable(Routes.LOG) { Text("Log contents") }
                    composable(Routes.PLANS) {
                        val vm = remember { keep(PlansViewModel(repo, SavedStateHandle(), context)) }
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        PlansScreen(state, PlansActions(
                            onNewPlan = {}, onSelect = vm::select, onMenu = vm::openMenu,
                            onRename = vm::rename, onRenameDraft = vm::setRenameDraft, onSaveRename = vm::saveRename,
                            onDismissRename = vm::dismissRename, onDuplicate = vm::duplicate, onArchive = vm::archive,
                            onRestore = vm::restore, onOpenPlan = { nav.navigate(Routes.plan(it)) },
                        ))
                    }
                    composable(Routes.PLAN_IN_PLANS, arguments = listOf(navArgument(Routes.PLAN_ID_ARG) { type = NavType.StringType })) { planEntry ->
                        val vm = remember(planEntry) { keep(PlanViewModel(repo, SavedStateHandle(mapOf(
                            Routes.PLAN_ID_ARG to planEntry.arguments?.getString(Routes.PLAN_ID_ARG),
                        )), context)) }
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        PlanScreen(state, PlanActions(
                            onOpenDay = { nav.navigate(Routes.preview(it)) }, onOpenPlans = { nav.popBackStack() }, onSetUpProgram = {},
                        ))
                    }
                    composable(Routes.PREVIEW, arguments = listOf(
                        navArgument(Routes.PLAN_ID_ARG) { type = NavType.StringType },
                        navArgument(Routes.DAY_ID_ARG) { type = NavType.StringType },
                    )) { previewEntry ->
                        val vm = remember(previewEntry) { keep(PreviewViewModel(repo, SavedStateHandle(mapOf(
                            Routes.PLAN_ID_ARG to previewEntry.arguments?.getString(Routes.PLAN_ID_ARG),
                            Routes.DAY_ID_ARG to previewEntry.arguments?.getString(Routes.DAY_ID_ARG),
                        )))) }
                        PreviewRoute(onBack = { nav.popBackStack() }, onOpenDay = { selection, edit ->
                            nav.navigate(Routes.day(selection.dayId, selection.planId, edit))
                        }, viewModel = vm)
                    }
                    listOf(Routes.DAY_IN_PLAN, Routes.DAY_EDIT_IN_PLAN).forEach { route ->
                        composable(route, arguments = listOf(
                            navArgument(Routes.DAY_PLAN_ID_ARG) { type = NavType.StringType },
                            navArgument(Routes.DAY_ID_ARG) { type = NavType.StringType },
                            navArgument(Routes.DAY_EDITOR_ARG) { type = NavType.BoolType; defaultValue = route == Routes.DAY_EDIT_IN_PLAN },
                        )) { dayEntry ->
                            dayVm = remember(dayEntry) { keep(DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo),
                                SavedStateHandle(mapOf(
                                    Routes.DAY_PLAN_ID_ARG to dayEntry.arguments?.getString(Routes.DAY_PLAN_ID_ARG),
                                    Routes.DAY_ID_ARG to dayEntry.arguments?.getString(Routes.DAY_ID_ARG),
                                    Routes.DAY_EDITOR_ARG to dayEntry.arguments?.getBoolean(Routes.DAY_EDITOR_ARG),
                                )), FixedCardioClock(), InertCardioAlarm)) }
                            DayRoute(onCreateExercise = {}, onSetUpProgram = {}, onFinishSession = { nav.finishWorkout() },
                                onBack = { nav.popBackStack() },
                                onOpenRestTimer = {}, viewModel = dayVm)
                        }
                    }
                }
            }
        } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(expectedRow)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openOtherPreview() {
        rule.onNodeWithText("Plans").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Other")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Other").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Rehab")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Rehab").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Start day A")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun tabsShowTodayLogPlansAndPlanDayOpensThatPlansPreview() {
        render()
        rule.onNodeWithText("Today").assertExists()
        rule.onNodeWithText("Log").assertExists()
        rule.onNodeWithText("Plans").assertExists()
        rule.onNodeWithText("Plan").assertDoesNotExist()
        assertNotEquals(TopLevelDestination.LOG.icon, TopLevelDestination.PLANS.icon)
        openOtherPreview()
        rule.runOnIdle {
            assertEquals(Routes.PREVIEW, nav.currentDestination?.route)
            assertEquals(other.toString(), nav.currentBackStackEntry?.arguments?.getString(Routes.PLAN_ID_ARG))
        }
        rule.onNodeWithText("Rehab").assertExists()
        rule.onNodeWithText("Other").assertExists()
        assertEquals(preferred, runBlocking { repo.selectedPlanIdFlow.first() })
    }

    @Test fun todayStartCreatesRunThenNavigatesToDay() {
        render()
        rule.onNodeWithText("Start").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { ::dayVm.isInitialized && dayVm.uiState.value.runOpen } }
        rule.runOnIdle { assertEquals(Routes.DAY_IN_PLAN, nav.currentDestination?.route) }
        assertEquals(preferred, runBlocking { repo.openRunFlow.first()!!.planId })
    }

    @Test fun todayConflictingStartRequiresConfirmationThenReachesRequestedDay() {
        runBlocking { repo.startRun(other, "B", emptyList()) }
        val oldId = runBlocking { repo.openRunFlow.first()!!.id }
        render()
        rule.onNodeWithText("Start").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("Workout open") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(isDialog()).assertExists()
        rule.onNode(hasText("Other · Day B Arms") and hasAnyAncestor(isDialog())).assertExists()
        rule.onNodeWithText("Discard and start").performClick()
        assertEquals(oldId, runBlocking { repo.openRunFlow.first()!!.id })
        rule.onNodeWithText("Discard workout?").assertExists()
        rule.onNodeWithText("Discard and start").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { ::dayVm.isInitialized && dayVm.uiState.value.runOpen } }
        assertEquals(preferred, runBlocking { repo.openRunFlow.first()!!.planId })
        assertNotEquals(oldId, runBlocking { repo.openRunFlow.first()!!.id })
    }

    @Test fun previewStartCallsStartRunAndNavigatesToItsDay() {
        render()
        openOtherPreview()
        rule.onNodeWithText("Start day A").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { ::dayVm.isInitialized && dayVm.uiState.value.runOpen } }
        assertEquals(other, runBlocking { repo.openRunFlow.first()!!.planId })
        rule.runOnIdle { assertEquals(other, dayVm.uiState.value.planId) }
    }

    @Test fun previewEditReachesTheTappedPlansDayWithEditorOpen() {
        render()
        openOtherPreview()
        rule.onNodeWithText("Edit").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { nav.currentDestination?.route == Routes.DAY_EDIT_IN_PLAN && ::dayVm.isInitialized &&
                !dayVm.uiState.value.loading && dayVm.uiState.value.planId == other && dayVm.dayEditState.value.editorOpen } }
        rule.runOnIdle {
            assertEquals(Routes.DAY_EDIT_IN_PLAN, nav.currentDestination?.route)
            assertEquals(other.toString(), nav.currentBackStackEntry?.arguments?.getString(Routes.DAY_PLAN_ID_ARG))
            assertEquals(other, dayVm.uiState.value.planId)
            assertEquals("A", dayVm.uiState.value.viewDayId)
            assertTrue(dayVm.dayEditState.value.editorOpen)
        }
    }

    private fun finishAndReturnToToday() {
        rule.waitUntil(5_000) { rule.runOnIdle {
            ::dayVm.isInitialized && dayVm.uiState.value.runOpen && dayVm.uiState.value.exercises.isNotEmpty()
        } }
        rule.runOnIdle {
            val slot = dayVm.uiState.value.exercises.first()
            dayVm.toggleDone(slot.programExerciseId, 0, true, Slot.MAIN)
        }
        rule.waitUntil(5_000) { rule.runOnIdle { dayVm.uiState.value.doneSets > 0 } }
        rule.runOnIdle { dayVm.completeDay() }
        rule.waitUntil(5_000) { rule.runOnIdle { dayVm.sessionReceipt.value != null } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("BACK TO TODAY")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("BACK TO TODAY").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { nav.currentDestination?.route == Routes.TODAY } }
        rule.runOnIdle {
            assertEquals(Routes.TODAY, nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
        }
    }

    @Test fun pausedPreferredPlanStartStartsTheFirstPlanOnToday() {
        runBlocking { repo.pause(preferred) }
        render(expectedRow = "Other · Day A")
        rule.onNodeWithText("Start").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { ::dayVm.isInitialized && dayVm.uiState.value.runOpen } }
        assertEquals(other, runBlocking { repo.openRunFlow.first()!!.planId })
        assertEquals(preferred, runBlocking { repo.selectedPlanIdFlow.first() })
    }

    @Test fun todayStartReceiptReturnsToToday() {
        render()
        rule.onNodeWithText("Start").performClick()
        finishAndReturnToToday()
    }

    @Test fun todayPreviewStartReceiptClearsPreviewAndReturnsToToday() {
        render()
        rule.onNodeWithText("Strength · Day A").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Start day A")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Start day A").performClick()
        finishAndReturnToToday()
    }

    @Test fun plansPreviewStartReceiptClearsPlanAndPreviewAndReturnsToToday() {
        render()
        openOtherPreview()
        rule.onNodeWithText("Start day A").performClick()
        finishAndReturnToToday()
    }

    @Test fun backFromPreviewReturnsToThePlansDayList() {
        render()
        openOtherPreview()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { nav.currentDestination?.route == Routes.PLAN_IN_PLANS } }
        rule.runOnIdle { assertEquals(other.toString(), nav.currentBackStackEntry?.arguments?.getString(Routes.PLAN_ID_ARG)) }
    }

    @Test fun backFromEditorClosesEditorThenReturnsToPreview() {
        render()
        openOtherPreview()
        rule.onNodeWithText("Edit").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { ::dayVm.isInitialized &&
            !dayVm.uiState.value.loading && dayVm.uiState.value.planId == other && dayVm.dayEditState.value.editorOpen } }
        // The sheet owns the first Back; leaving the workout then returns to its preview.
        rule.onNode(isDialog()).assertExists()
        rule.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { rule.runOnIdle { !dayVm.dayEditState.value.editorOpen } }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { nav.currentDestination?.route == Routes.PREVIEW } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Start day A")).fetchSemanticsNodes().isNotEmpty() }
    }

}
