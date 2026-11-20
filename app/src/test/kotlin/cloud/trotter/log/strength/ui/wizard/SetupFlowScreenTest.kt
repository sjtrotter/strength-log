package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.units.ClockText
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import cloud.trotter.log.strength.ui.TouchTargets.assertNoOverlappingTouchTargets
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp")
class SetupFlowScreenTest {
    @get:Rule val rule = createComposeRule()
    private var shown by mutableStateOf(SetupFlowFixtures.state())
    private var fontScale by mutableStateOf(1f)
    private var db: StrengthDatabase? = null
    private var scope: CoroutineScope? = null
    private val vms = mutableListOf<SetupFlowViewModel>()
    private var current by mutableStateOf<SetupFlowViewModel?>(null)

    private fun show(state: SetupFlowUiState, actions: SetupFlowActions = SetupFlowActions()) {
        shown = state
        rule.setContent { AppTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SetupFlowScreen(shown, actions)
            }
        } }
    }
    private fun state(step: SetupStep, draft: SetupDraft = SetupDraft()) = SetupFlowFixtures.state(draft.copy(step = step))
    private fun touchTargets() {
        for (delta in listOf(10000f, -10000f)) {
            if (rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
                rule.onAllNodes(hasScrollAction()).onLast().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            }
            rule.assertEveryTouchTargetIsAtLeast48dp()
            rule.assertNoOverlappingTouchTargets()
        }
    }
    @After fun closeFields() { vms.forEach { it.viewModelScope.cancel() }; scope?.cancel(); db?.close() }

    @Test fun `loading hides inputs and disables Next`() {
        show(SetupFlowUiState())
        rule.onNodeWithText("Lift").assertDoesNotExist(); rule.onNodeWithText("Next").assertIsNotEnabled(); touchTargets()
    }
    @Test fun `goal shows four goals and restore on a first run`() {
        var restores = 0
        show(SetupFlowFixtures.state(firstRun = true), SetupFlowActions(onRestoreFromBackup = { restores++ }))
        rule.onNodeWithText("Lift").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        // PR 7f: Sprint absent → visible fourth goal.
        listOf("Run", "Both").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Sprint").assertExists()
        rule.onNodeWithText("Have a backup? Restore it").performClick(); assertEquals(1, restores); touchTargets()
        rule.runOnIdle { shown = SetupFlowFixtures.state() }
        rule.onNodeWithText("Have a backup? Restore it").assertDoesNotExist(); touchTargets()
    }
    @Test fun `sub-goal rows are radio buttons`() {
        var goal: LiftGoal? = null
        show(state(SetupStep.SUB_GOAL), SetupFlowActions(onSubGoal = { goal = it }))
        listOf("Strength", "Muscle", "General fitness").forEach {
            rule.onNodeWithText(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        rule.onNodeWithText("General fitness").assertIsSelected(); rule.onNodeWithText("Strength").performClick()
        assertEquals(LiftGoal.STRENGTH, goal); touchTargets()
    }
    @Test fun `your week offers six day chips and five minute chips`() {
        var days = 0
        show(state(SetupStep.WEEK), SetupFlowActions(onDays = { days = it }))
        ((1..6).toList() + SetupFlowStateBuilder.minutesOptions).forEach { rule.onNodeWithText(it.toString()).assertExists() }
        rule.onNodeWithText("3").assertIsSelected(); rule.onNodeWithText("60").assertIsSelected()
        rule.onNodeWithText("5").performClick(); assertEquals(5, days)
        listOf("Days you can keep", "Minutes a session").forEach {
            rule.onNodeWithText(it).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
        touchTargets()
    }
    @Test fun `choose exactly opens eleven tagged boxes`() {
        var chosen = false
        show(state(SetupStep.EQUIPMENT), SetupFlowActions(onChooseExactly = { chosen = true }))
        rule.onNodeWithText("Full gym").assertIsSelected()
        rule.onNodeWithTag("setup-equipment-BARBELL").assertDoesNotExist()
        rule.onNodeWithText("Choose exactly").performClick(); assertTrue(chosen)
        rule.runOnIdle { shown = state(SetupStep.EQUIPMENT, SetupDraft(chooseExactly = true)) }
        Equipment.entries.forEach {
            rule.onNodeWithTag("setup-equipment-${it.name}").assertExists().assertIsOn()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        }
        val bounds = rule.onNodeWithTag("setup-equipment-KETTLEBELL").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.isEmpty || bounds.top >= with(rule.density) { 720 * density })
        touchTargets()
    }
    @Test fun `step rows follow the gear`() {
        show(state(SetupStep.EQUIPMENT)); rule.onNodeWithText("Bar").assertExists(); rule.onNodeWithText("Stack").assertExists(); touchTargets()
        rule.runOnIdle { shown = state(SetupStep.EQUIPMENT, SetupDraft(equipment = EquipmentPreset.DUMBBELLS.equipment, unit = WeightUnit.KG)) }
        rule.onNodeWithText("Bar").assertDoesNotExist(); rule.onNodeWithText("Stack").assertDoesNotExist()
        rule.onNodeWithText("2 kg").assertExists(); touchTargets()
        rule.runOnIdle { shown = state(SetupStep.EQUIPMENT, SetupDraft(unit = WeightUnit.KG)) }
        rule.onAllNodesWithText("2.5 kg").onFirst().assertExists(); touchTargets()
        rule.runOnIdle { shown = state(SetupStep.EQUIPMENT, SetupDraft(equipment = EquipmentPreset.BODYWEIGHT.equipment)) }
        rule.onNodeWithText("Smallest step").assertDoesNotExist(); touchTargets()
    }
    @Test fun `about asks experience and 65 or older only`() {
        var older = false
        show(state(SetupStep.ABOUT), SetupFlowActions(onOlderAdult = { older = it }))
        listOf("New", "Under a year", "A year or more").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("65 or older").assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        assertTrue(older)
        listOf("Pain now", "Injury", "Bone", "Falls").forEach { rule.onNodeWithText(it, substring = true).assertDoesNotExist() }; touchTargets()
    }
    @Test fun `sprint age choices write both existing age flags`() {
        show(state(SetupStep.ABOUT, SetupFlowFixtures.sprint), SetupFlowActions(
            onOlderAdult = { shown = state(SetupStep.ABOUT, shown.draft.copy(olderAdult = it)) },
            onFortyOrOlder = { shown = state(SetupStep.ABOUT, shown.draft.copy(fortyOrOlder = it)) }))
        for (draft in listOf(SetupFlowFixtures.sprint, SetupFlowFixtures.bothSprint)) {
            rule.runOnIdle { shown = state(SetupStep.ABOUT, draft) }
            rule.onNodeWithText("Age").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Under 40").assertIsSelected()
            rule.onNodeWithText("40 or older").assertDoesNotExist()
            rule.onNodeWithText("65 or older").performScrollTo().performClick().assertIsSelected()
            rule.runOnIdle { assertTrue(shown.draft.olderAdult); assertTrue(shown.draft.fortyOrOlder) }
            rule.onNodeWithText("40 to 64").performClick().assertIsSelected()
            rule.runOnIdle { assertFalse(shown.draft.olderAdult); assertTrue(shown.draft.fortyOrOlder) }
            rule.onNodeWithText("Under 40").performClick().assertIsSelected()
            rule.runOnIdle { assertFalse(shown.draft.olderAdult); assertFalse(shown.draft.fortyOrOlder) }
        }
        touchTargets()
    }

    @Test fun `Run keeps its older adult checkbox`() {
        show(state(SetupStep.ABOUT, SetupFlowFixtures.run))
        rule.onNodeWithText("65 or older").performScrollTo().assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        rule.onNodeWithText("Age").assertDoesNotExist()
    }

    @Test fun `a shortened sprint plan at ninety minutes has no Longer sessions button`() {
        val base = state(SetupStep.PLAN, SetupFlowFixtures.sprint.copy(minutes = 90))
        val plan = base.plan as PlanPreviewUi.Ready
        show(base.copy(plan = plan.copy(sprint = requireNotNull(plan.sprint).copy(shortened = true, fullDoseMinutes = null))))
        rule.onNodeWithTag("setup-over-budget").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Longer sessions").assertDoesNotExist()
    }

    @Test fun `your lifts rows drop down their choices`() {
        var picked: Pair<Int, String>? = null
        val s = state(SetupStep.LIFTS, SetupFlowFixtures.reference)
        show(s, SetupFlowActions(onMain = { row, id -> picked = row to id }))
        s.lifts.forEach { rule.onNodeWithTag("setup-lift-row-${it.row}").assertExists() }
        rule.onNodeWithTag("setup-lift-row-2").performScrollTo().performClick()
        rule.onNodeWithText("DB Bench Press").performClick(); assertEquals(2 to "db_bench", picked); touchTargets()
        rule.runOnIdle { shown = s.copy(lifts = s.lifts.map { if (it.row == 2) it.copy(selectedId = null) else it }) }
        rule.onNodeWithText("Not in the plan").assertExists(); touchTargets()
    }

    private fun fields(handle: SavedStateHandle): Pair<SetupFlowViewModel, (SavedStateHandle) -> SetupFlowViewModel> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        db = database
        val stores = CoroutineScope(Dispatchers.IO + SupervisorJob()); scope = stores
        val cardioRunPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = stores) {
            File.createTempFile("SetupFlowScreenTest-cardio", ".preferences_pb")
        })
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = stores) { File.createTempFile("setup-fields", ".preferences_pb") })
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = stores) {
            File.createTempFile("setup-fields-journal", ".preferences_pb")
        }, settings, cardioRunPrefs = cardioRunPrefs)
        val repo = TrackerRepository(database, database.programDao(), database.sessionDao(), database.customExerciseDao(), settings)
        val service = BackupService(BackupRepository(database, settings, repo.plans, repo.history,
            cardioRunPrefs = cardioRunPrefs), journal)
        val factory = { h: SavedStateHandle -> SetupFlowViewModel(repo.plans, h, context, service, stores,
            DeviceWeightUnitProvider { WeightUnit.LB }, settings).also { vms += it } }
        lateinit var vm: SetupFlowViewModel
        rule.runOnUiThread { vm = factory(handle); current = vm }
        return vm to factory
    }
    private fun recentHandle() = SavedStateHandle(mapOf("setup_initialized" to true, "setup_first_run" to true,
        "setup_draft" to SetupDraftJson.encode(SetupFlowFixtures.reference.copy(step = SetupStep.RECENT, recent = emptyMap()))))

    @Test fun `recent numbers survive recreation without done or focus loss`() {
        val handle = recentHandle(); val (vm, factory) = fields(handle)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { AppTheme { val displayed = requireNotNull(current); key(displayed) {
            val s by displayed.uiState.collectAsState()
            SetupFlowScreen(s, SetupFlowActions(onRecentWeight = displayed::setRecentWeight, onRecentReps = displayed::setRecentReps))
        } } }
        rule.waitUntil(5000) { rule.runOnIdle { vm.uiState.value.initialized } }
        rule.onNodeWithTag("setup-recent-weight-bb_back_squat").requestFocus().performTextReplacement("275")
        rule.onNodeWithTag("setup-recent-reps-bb_back_squat").requestFocus().performTextReplacement("5")
        rule.onNodeWithTag("setup-recent-reps-bb_back_squat").assertIsFocused()
        rule.runOnIdle {
            val revived = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
            vm.viewModelScope.cancel(); current = factory(revived)
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(5000) { rule.runOnIdle { current!!.uiState.value.step == SetupStep.RECENT } }
        assertEquals("5", rule.onNodeWithTag("setup-recent-reps-bb_back_squat").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.runOnIdle { assertEquals(RecentDraft(275.0, 5), current!!.uiState.value.draft.recent["bb_back_squat"]) }; touchTargets()
    }
    @Test fun `a zero weight is discarded`() {
        val handle = recentHandle(); val (vm, _) = fields(handle)
        rule.setContent { AppTheme { val s by vm.uiState.collectAsState(); SetupFlowScreen(s,
            SetupFlowActions(onRecentWeight = vm::setRecentWeight, onRecentReps = vm::setRecentReps)) } }
        rule.waitUntil(5000) { rule.runOnIdle { vm.uiState.value.initialized } }
        for (done in listOf(true, false)) {
            rule.onNodeWithTag("setup-recent-weight-bb_back_squat").requestFocus().performTextReplacement("225")
            rule.onNodeWithTag("setup-recent-weight-bb_back_squat").performTextReplacement("0")
            rule.runOnIdle { assertNull(SetupDraftJson.decode(handle["setup_draft"])!!.recent["bb_back_squat"]!!.weightLb) }
            if (done) rule.onNodeWithTag("setup-recent-weight-bb_back_squat").performImeAction()
            else rule.onNodeWithTag("setup-recent-reps-bb_back_squat").requestFocus()
            rule.runOnIdle { assertTrue(SetupFlowStateBuilder.answers(vm.uiState.value.draft).recent.isEmpty()) }
        }
        Effort.entries.forEach { rule.onNodeWithTag("setup-recent-effort-bb_back_squat-${it.name}").assertExists() }
        rule.onNodeWithTag("setup-recent-effort-bb_back_squat-GOOD").assertIsSelected()
        listOf("Easy", "Good", "Hard", "Max").forEach { rule.onAllNodesWithText(it).onFirst().assertExists() }; touchTargets()
    }
    @Test fun `your plan rows read as one line each`() {
        val s = state(SetupStep.PLAN, SetupFlowFixtures.reference); show(s)
        rule.onNodeWithText("Strength, 4 days").assertExists()
        val text = rule.onNodeWithTag("setup-plan-day-A").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(text.contains("Squat 275 lb") && text.contains("min") && text.contains("lifts"))
        val rest = (s.plan as PlanPreviewUi.Ready).rows.single { it.rest }
        val restText = rule.onNodeWithTag("setup-plan-day-${rest.dayId}").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(!restText.contains("min")); touchTargets()
    }
    @Test fun `over budget offers both choices`() {
        var dropped: String? = null; var longer = 0
        val s = state(SetupStep.PLAN, SetupFlowFixtures.overBudget)
        show(s, SetupFlowActions(onDropLift = { dropped = it }, onLongerSessions = { longer++ }))
        val p = s.plan as PlanPreviewUi.Ready
        rule.onNodeWithText("Over by ${p.rows.single().overMinutes} min").assertExists()
        rule.onNodeWithText("Start").assertIsEnabled()
        rule.onNodeWithText("Drop a lift").performScrollTo().performClick()
        p.droppable.forEach { rule.onNodeWithText(ExerciseLibrary.get(it).name).assertExists() }
        rule.onNodeWithText(ExerciseLibrary.get(p.droppable.first()).name).performClick(); assertEquals(p.droppable.first(), dropped)
        rule.onNodeWithText("Longer sessions").performScrollTo().performClick(); assertEquals(1, longer); touchTargets()
    }
    @Test fun `the balance offer reads its count`() {
        var balance: Boolean? = null
        val d = SetupDraft(step = SetupStep.PLAN, olderAdult = true, days = 1)
        show(SetupFlowFixtures.state(d), SetupFlowActions(onBalanceDays = { balance = it }))
        rule.onNodeWithText("Add 2 short balance days?").assertExists()
        rule.onNodeWithText("Add").performScrollTo().performClick(); assertEquals(true, balance); touchTargets()
        rule.runOnIdle { shown = SetupFlowFixtures.state(d.copy(balanceDays = true)) }
        rule.onNodeWithText("2 short balance days added").assertExists()
        rule.onNodeWithText("Remove").performScrollTo().performClick(); assertEquals(false, balance); touchTargets()
    }
    @Test fun `why this plan lists the draft's lines`() {
        show(state(SetupStep.PLAN, SetupFlowFixtures.reference))
        rule.onNodeWithText("Why this plan").performClick()
        rule.onNodeWithText("Minutes are estimates:", substring = true).assertExists()
        rule.onNodeWithText("Every set stops with reps left.", substring = true).assertExists(); touchTargets()
    }
    @Test fun `why we ask on Your week shows its copy`() {
        show(state(SetupStep.WEEK)); rule.onNodeWithText("Why we ask").performClick()
        rule.onNodeWithText("Two strength days covering the major muscles", substring = true).assertExists(); touchTargets()
    }
    @Test fun `preview loading failed and empty states`() {
        var retry = 0; var review = 0
        show(state(SetupStep.PLAN).copy(plan = PlanPreviewUi.Loading), SetupFlowActions(onRetryPreview = { retry++ }, onReviewAnswers = { review++ }))
        rule.onNodeWithText("Start").assertIsNotEnabled(); touchTargets()
        rule.runOnIdle { shown = shown.copy(plan = PlanPreviewUi.Failed) }
        rule.onNodeWithText("Try again").performClick(); assertEquals(1, retry); touchTargets()
        rule.runOnIdle { shown = shown.copy(plan = PlanPreviewUi.Empty) }
        rule.onNodeWithText("Start").assertDoesNotExist(); rule.onNodeWithText("Review answers").performScrollTo().performClick()
        assertEquals(1, review); touchTargets()
    }
    @Test fun `creating and a failed create name the operation`() {
        show(state(SetupStep.PLAN).copy(finishInFlight = true)); rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Creating your plan…").assertExists(); rule.onNodeWithText("Start").assertIsNotEnabled(); touchTargets()
        rule.runOnIdle { shown = shown.copy(finishInFlight = false, finishError = UiText.FileAccessFailure("Disk full")) }
        rule.onNodeWithText("Couldn't create your plan.").assertExists()
        rule.onNodeWithText("Couldn't access that file: Disk full").assertExists(); rule.onNodeWithText("Try again").assertIsEnabled(); touchTargets()
    }
    @Test fun `restore names the operation and shows its error`() {
        show(SetupFlowFixtures.state(firstRun = true).copy(restore = SetupRestoreState(offered = true, inFlight = true)))
        rule.mainClock.advanceTimeBy(400); rule.onNodeWithText("Restoring backup. Keep this screen open.").assertExists()
        rule.onNodeWithText("Next").assertIsNotEnabled(); touchTargets()
        rule.runOnIdle { shown = shown.copy(restore = SetupRestoreState(offered = true, error = UiText.FilePermissionLost)) }
        rule.onNodeWithText("No permission to access that file anymore.").assertExists()
        rule.onNodeWithText("Have a backup? Restore it").assertIsEnabled(); touchTargets()
    }
    @Test fun `the back arrow follows the entry`() {
        var exits = 0; var backs = 0
        show(SetupFlowFixtures.state(firstRun = true), SetupFlowActions(onExit = { exits++ }, onBack = { backs++ }))
        rule.onNodeWithContentDescription("Back").assertDoesNotExist(); touchTargets()
        rule.runOnIdle { shown = SetupFlowFixtures.state() }; rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, exits); touchTargets()
        rule.runOnIdle { shown = state(SetupStep.WEEK) }; rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs); touchTargets()
    }
    @Test fun `large font keeps every step usable`() {
        fontScale = 2f; show(SetupFlowFixtures.state(firstRun = true))
        SetupStep.entries.forEach { step ->
            // PR 7c: new step entries need a Both draft that offers them
            rule.runOnIdle { shown = state(step, if (step in listOf(SetupStep.ORDER, SetupStep.RUN_GOAL,
                SetupStep.EVENT, SetupStep.WHERE, SetupStep.TOGETHER)) SetupFlowFixtures.reference.copy(
                    branch = cloud.trotter.log.strength.domain.planner.PlanBranch.BOTH,
                    runGoal = cloud.trotter.log.strength.domain.planner.RunGoal.HALF_MARATHON) else SetupFlowFixtures.reference) }
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).onFirst().assertIsDisplayed()
            rule.onNodeWithText(if (step == SetupStep.PLAN) "Start" else "Next").assertIsDisplayed(); touchTargets()
        }
    }
    private fun largeScreen(step: SetupStep, draft: SetupDraft, title: String) {
        fontScale = 2f
        show(state(step, draft))
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(if (step == SetupStep.PLAN) "Start" else "Next")
            .assertIsDisplayed().assertHeightIsAtLeast(52.dp)
    }

    @Test @Config(fontScale = 2f) fun largeFontGoalShowsThreeGoals() {
        largeScreen(SetupStep.GOAL, SetupFlowFixtures.run, "What are you training for?")
        listOf("Lift", "Run", "Both").forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }
    @Test @Config(fontScale = 2f) fun largeFontWhichFirst() =
        largeScreen(SetupStep.ORDER, SetupFlowFixtures.both, "Which comes first?")
    @Test @Config(fontScale = 2f) fun largeFontRunningGoal() =
        largeScreen(SetupStep.RUN_GOAL, SetupFlowFixtures.run, "What kind of running?")
    @Test @Config(fontScale = 2f) fun largeFontAboutRun() =
        largeScreen(SetupStep.ABOUT, SetupFlowFixtures.run, "About your training")
    @Test @Config(fontScale = 2f) fun largeFontEventHalfWithDateTimeAndRecent() =
        largeScreen(SetupStep.EVENT, SetupFlowFixtures.half, "Your event")
    @Test @Config(fontScale = 2f) fun largeFontEventTest() =
        largeScreen(SetupStep.EVENT, SetupFlowFixtures.test, "Your event")
    @Test @Config(fontScale = 2f) fun largeFontWhereYouAre() =
        largeScreen(SetupStep.WHERE, SetupFlowFixtures.run, "Where you are")
    @Test @Config(fontScale = 2f) fun largeFontTogether() =
        largeScreen(SetupStep.TOGETHER, SetupFlowFixtures.both, "Lifting and running")
    @Test @Config(fontScale = 2f) fun largeFontRunPlanWithStrengthBlocks() =
        largeScreen(SetupStep.PLAN, SetupFlowFixtures.half, "Your plan")
    @Test @Config(fontScale = 2f) fun largeFontNoRunCard() {
        largeScreen(SetupStep.PLAN, SetupFlowFixtures.noRun, "Your plan")
        rule.onNodeWithText("Longer sessions").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Separate days").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
    @Test fun `the no run card offers only real choices`() {
        var longer = 0
        var together: cloud.trotter.log.strength.domain.planner.BothAnswers.Together? = null
        show(state(SetupStep.PLAN, SetupFlowFixtures.noRun), SetupFlowActions(
            onLongerSessions = { longer++ }, onTogether = { together = it }))
        rule.onNodeWithText("Start").assertIsEnabled()
        rule.onNodeWithText("No run fits in 30 minutes.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Longer sessions").performScrollTo().performClick()
        rule.onNodeWithText("Separate days").performScrollTo().performClick()
        assertEquals(1, longer)
        assertEquals(cloud.trotter.log.strength.domain.planner.BothAnswers.Together.SEPARATE_DAYS, together)
        rule.runOnIdle { shown = shown.copy(draft = shown.draft.copy(together = BothAnswers.Together.SEPARATE_DAYS)) }
        rule.onNodeWithText("No run fits in 30 minutes.").assertExists()
        rule.onNodeWithText("Separate days").assertDoesNotExist()
        rule.runOnIdle { shown = state(SetupStep.PLAN, SetupFlowFixtures.noRun.copy(days = 1)) }
        rule.onNodeWithText("Separate days").assertDoesNotExist()
    }

    @Test fun `A time needs a valid target before Next`() {
        show(state(SetupStep.EVENT, SetupFlowFixtures.run.copy(runGoal = RunGoal.FIRST_5K)),
            SetupFlowActions(onGoalSeconds = { shown = shown.copy(draft = shown.draft.copy(goalSeconds = it)) }))
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.onNodeWithText("A time").performClick()
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(INVALID_GOAL_TIME, shown.draft.goalSeconds) }
        for (text in listOf("", "bad", "0:00")) {
            rule.onNodeWithText("Target time").performTextReplacement(text)
            rule.onNodeWithText("Target time").performImeAction()
            rule.onNodeWithText("Next").assertIsNotEnabled()
            rule.runOnIdle {
                assertEquals(INVALID_GOAL_TIME, shown.draft.goalSeconds)
                assertNull(SetupFlowStateBuilder.answers(shown.draft).run.goalSeconds)
            }
        }
        rule.onNodeWithText("Target time").performTextReplacement("30:00")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertEquals(1800, shown.draft.goalSeconds) }
        rule.onNodeWithText("Target time").performTextReplacement("0:00")
        rule.onNodeWithText("Just finish").performScrollTo().performClick()
        rule.onNodeWithText("Target time").assertDoesNotExist()
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertNull(shown.draft.goalSeconds) }
    }

    @Test fun `a marathon target rejects minutes and accepts hours`() {
        show(state(SetupStep.EVENT, SetupFlowFixtures.run.copy(runGoal = RunGoal.MARATHON)),
            SetupFlowActions(onGoalSeconds = { shown = shown.copy(draft = shown.draft.copy(goalSeconds = it)) }))
        rule.onNodeWithText("A time").performClick()
        rule.onNodeWithText("Target time").performTextReplacement("3:30")
        rule.onNodeWithText("Target time").performImeAction()
        rule.onNodeWithText("Enter a time between 1:10:19 and 24:00:00").assertExists()
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(INVALID_GOAL_TIME, shown.draft.goalSeconds) }
        rule.onNodeWithText("Target time").performTextReplacement("3:30:00")
        rule.onNodeWithText("Target time").performImeAction()
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertEquals(12600, shown.draft.goalSeconds) }
    }

    @Test fun `an 800 m target accepts a fast result`() {
        show(state(SetupStep.EVENT, SetupFlowFixtures.run.copy(runGoal = RunGoal.MIDDLE_DISTANCE, runEvent = RunEvent.M800)),
            SetupFlowActions(onGoalSeconds = { shown = shown.copy(draft = shown.draft.copy(goalSeconds = it)) }))
        rule.onNodeWithText("A time").performClick()
        rule.onNodeWithText("Target time").performTextReplacement("1:41")
        rule.onNodeWithText("Target time").performImeAction()
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertEquals(101, shown.draft.goalSeconds) }
    }

    @Test fun `a valid 800 m time clears when switching to 1500 m`() {
        eventTimeFields(RunEvent.M800)
        rule.onNodeWithText("Target time").requestFocus().performTextReplacement("1:41")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.onNodeWithText("1500 m").performScrollTo().performClick()
        assertBlankEventTime()
        rule.onNodeWithText("Target time").performTextReplacement("3:30")
        rule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test fun `an invalid 1500 m time stays blank after switching to 800 m`() {
        eventTimeFields(RunEvent.M1500)
        rule.onNodeWithText("Target time").requestFocus().performTextReplacement("1:41")
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNodeWithText("800 m").performScrollTo().performClick()
        assertBlankEventTime()
        rule.onNodeWithText("Target time").performTextReplacement("1:41")
        rule.onNodeWithText("Next").assertIsEnabled()
    }

    private fun eventTimeFields(event: RunEvent) {
        val draft = SetupFlowFixtures.run.copy(step = SetupStep.EVENT, runGoal = RunGoal.MIDDLE_DISTANCE,
            runEvent = event, goalSeconds = INVALID_GOAL_TIME)
        val (vm, _) = fields(SavedStateHandle(mapOf("setup_initialized" to true,
            "setup_draft" to SetupDraftJson.encode(draft))))
        rule.setContent { AppTheme {
            val s by vm.uiState.collectAsState()
            SetupFlowScreen(s, SetupFlowActions(onRunEvent = vm::setRunEvent, onGoalSeconds = vm::setGoalSeconds))
        } }
        rule.waitUntil(5000) { rule.runOnIdle { vm.uiState.value.initialized } }
    }

    private fun assertBlankEventTime() {
        val field = rule.onNodeWithText("Target time").fetchSemanticsNode()
        assertEquals("", field.config[SemanticsProperties.EditableText].text)
        // PR 7f: blank invalid target without error → range error after a floor change.
        assertTrue(field.config.contains(SemanticsProperties.Error))
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(INVALID_GOAL_TIME, current!!.uiState.value.draft.goalSeconds) }
    }

    @Test fun `the marathon best rejects minutes and accepts hours`() {
        show(state(SetupStep.EVENT, SetupFlowFixtures.ultra),
            SetupFlowActions(onMarathonBest = { shown = shown.copy(draft = shown.draft.copy(marathonBestSeconds = it)) }))
        rule.onNodeWithText("Marathon best").performTextReplacement("3:30")
        rule.onNodeWithText("Marathon best").performImeAction()
        rule.onNodeWithText("Enter a time between 1:10:19 and 24:00:00").assertExists()
        rule.runOnIdle { assertNull(shown.draft.marathonBestSeconds) }
        rule.onNodeWithText("Marathon best").performTextReplacement("3:30:00")
        rule.onNodeWithText("Marathon best").performImeAction()
        rule.runOnIdle { assertEquals(12600, shown.draft.marathonBestSeconds) }
    }

    @Test fun `test Why mentions interval pace only with an aim`() {
        show(state(SetupStep.EVENT, SetupFlowFixtures.test))
        rule.onNodeWithText("Why we ask").performClick()
        val interval = "Interval reps aim slightly faster than the target pace. A date tapers the last ten days."
        val taper = "A date tapers the last ten days."
        for (test in FitnessTest.entries) for (target in listOf(false, true)) {
            rule.runOnIdle { shown = state(SetupStep.EVENT, SetupFlowFixtures.test.copy(test = test,
                goalSeconds = if (target) 960 else null, testScore = if (target) 50 else null)) }
            val hasAim = target && test !in listOf(FitnessTest.PACER, FitnessTest.HAMR)
            rule.onNodeWithText(if (hasAim) interval else taper).assertExists()
            rule.onNodeWithText(if (hasAim) taper else interval).assertDoesNotExist()
        }
    }

    @Test fun `running experience Why describes the ladder gate`() {
        show(state(SetupStep.ABOUT, SetupFlowFixtures.run))
        rule.onNodeWithText("Why we ask").performClick()
        // ladder-date: the Why sheet states the continuous short gate and keeps the New gate.
        rule.onNodeWithText("Experience sets where lifting and running start. A runner whose longest recent run is under 30 minutes and under 5 km starts on a run/walk ladder, as does a New runner who leaves it blank; Racing runners skip it. For a fitness test or a race up to 3000 m, a Some runner skips it after 20 minutes or 2 miles without walk breaks; a New runner still needs 30 minutes or 5 km. A past running injury starts it one level lower. Nothing here is stored as a profile.").assertExists()
    }

    @Test fun `Where Why describes the cap after the ladder and seed expiry`() {
        show(state(SetupStep.WHERE, SetupFlowFixtures.run))
        rule.onNodeWithText("Why we ask").performClick()
        rule.onNodeWithText("Runs past the run/walk ladder stay within 10% of your longest session in the last 30 days, a limit from runners (Frandsen 2025). This answer stands in for 30 days, or until there's a log.").assertExists()
    }

    @Test fun `Together describes visits and days`() {
        show(state(SetupStep.TOGETHER, SetupFlowFixtures.both))
        rule.onNodeWithText("Lifting and running in the same visits").assertExists()
        rule.onNodeWithText("Lifting days and running days").assertExists()
    }

    @Test fun `a cardio row shows minutes without zero lifts`() {
        val s = state(SetupStep.PLAN, SetupFlowFixtures.run)
        show(s)
        val cardio = (s.plan as PlanPreviewUi.Ready).rows.first { it.cardio != null && it.lifts == 0 }
        val text = rule.onNodeWithTag("setup-plan-day-${cardio.dayId}").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(text.contains("${cardio.minutes} min"))
        assertTrue(!text.contains("lifts"))
    }
    @Test fun `a fitness test target that is not a time waits`() {
        show(state(SetupStep.EVENT, SetupFlowFixtures.test.copy(test = FitnessTest.TWO_MILE, goalSeconds = null)),
            SetupFlowActions(onGoalSeconds = { shown = state(SetupStep.EVENT, shown.draft.copy(goalSeconds = it)) }))
        rule.onNodeWithText("Target time").performScrollTo().performTextReplacement("16")
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(INVALID_GOAL_TIME, shown.draft.goalSeconds) }
        rule.onNodeWithText("Target time").performTextReplacement("1:00")
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(INVALID_GOAL_TIME, shown.draft.goalSeconds) }
        rule.onNodeWithText("Target time").performTextReplacement("")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertNull(shown.draft.goalSeconds) }
        rule.onNodeWithText("Target time").performTextReplacement("16:00")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertEquals(960, shown.draft.goalSeconds) }
    }

    @Test fun `a time field marked invalid shows the range`() {
        val draft = SetupFlowFixtures.half.copy(step = SetupStep.EVENT, goalSeconds = INVALID_GOAL_TIME)
        val (vm, _) = fields(SavedStateHandle(mapOf("setup_initialized" to true,
            "setup_draft" to SetupDraftJson.encode(draft))))
        rule.setContent { AppTheme {
            val state by vm.uiState.collectAsState()
            SetupFlowScreen(state, SetupFlowActions(onRunGoal = vm::setRunGoal, onGoalSeconds = vm::setGoalSeconds))
        } }
        rule.waitUntil(5000) { rule.runOnIdle { vm.uiState.value.initialized } }
        rule.onNodeWithText("Target time").performScrollTo().performTextReplacement("1:30:00")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertEquals(5400, vm.uiState.value.draft.goalSeconds) }
        rule.runOnIdle { vm.setRunGoal(RunGoal.MARATHON) }
        rule.waitUntil(5000) { rule.runOnIdle { vm.uiState.value.draft.runGoal == RunGoal.MARATHON } }
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertEquals(5400, vm.uiState.value.draft.goalSeconds) }
        rule.runOnIdle { vm.setRunGoal(RunGoal.HALF_MARATHON) }
        rule.waitUntil(5000) { rule.runOnIdle { vm.uiState.value.draft.runGoal == RunGoal.HALF_MARATHON } }
        rule.onNodeWithText("Target time").requestFocus().performTextReplacement("1:00:00")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.onNodeWithText("Target time").assertIsFocused()
        rule.runOnIdle { assertEquals(3600, vm.uiState.value.draft.goalSeconds) }
        rule.runOnIdle { vm.setRunGoal(RunGoal.MARATHON) }
        rule.waitUntil(5000) { rule.runOnIdle { vm.uiState.value.draft.goalSeconds == INVALID_GOAL_TIME } }
        val floor = SetupFlowStateBuilder.goalTimeFloor(vm.uiState.value.draft)
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.onNodeWithText(context.getString(R.string.form_time_range, ClockText.duration(floor), "24:00:00"))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test @Config(fontScale = 2f) fun largeFontGoalShowsSprint() {
        largeScreen(SetupStep.GOAL, SetupFlowFixtures.sprint, "What are you training for?")
        rule.onNodeWithText("Sprint").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(fontScale = 2f) fun largeFontWhichSprint() {
        largeScreen(SetupStep.RUN_GOAL, SetupFlowFixtures.sprint, "Which sprint?")
        rule.onNodeWithText("A match or hard practice counts as a sprint day.").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(fontScale = 2f) fun largeFontAboutSprint() {
        largeScreen(SetupStep.ABOUT, SetupFlowFixtures.sprint, "About your training")
        for (label in listOf("Age", "Under 40", "40 to 64", "65 or older")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Under 40").assertIsSelected()
        rule.onNodeWithText("Running").assertDoesNotExist()
        touchTargets()
    }
    @Test @Config(fontScale = 2f) fun largeFontAboutBothSprint() {
        largeScreen(SetupStep.ABOUT, SetupFlowFixtures.bothSprint.copy(olderAdult = true, fortyOrOlder = true),
            "About your training")
        for (label in listOf("Age", "Under 40", "40 to 64", "65 or older")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("65 or older").assertIsSelected()
        touchTargets()
    }
    @Test @Config(fontScale = 2f) fun largeFontBothCardioGoalWithSprint() {
        largeScreen(SetupStep.RUN_GOAL, SetupFlowFixtures.bothSprint, "What kind of running?")
        rule.onNodeWithText("400 m").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(fontScale = 2f) fun largeFontLiftsSprintFloor() {
        largeScreen(SetupStep.LIFTS, SetupFlowFixtures.sprintFloor, "Your lifts")
        rule.onNodeWithText("A sprint plan needs one leg lift. Choose a squat or a hinge.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }
    @Test @Config(fontScale = 2f) fun largeFontSprintPlan() {
        largeScreen(SetupStep.PLAN, SetupFlowFixtures.sprint.copy(minutes = 30), "Your plan")
        listOf("Suggested week: Mon sprint, Tue upper, Thu sprint.",
            "Keep sprint days three days apart, like Monday and Thursday.",
            "Sprints first, then jumps, then lifts. Nordic curls last.", "Longer sessions").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }
    @Test @Config(fontScale = 2f) fun largeFontSprintNeedsLowerMain() {
        largeScreen(SetupStep.PLAN, SetupFlowFixtures.sprintFloor, "Your plan")
        rule.onNodeWithText("A sprint plan needs one leg lift. Choose a squat or a hinge.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsNotEnabled()
        rule.onNodeWithText("Review answers").performScrollTo().assertIsDisplayed()
    }

    @Test fun `ladder rows read their weeks`() {
        show(state(SetupStep.PLAN, SetupFlowFixtures.run))
        rule.onNodeWithText("Why this plan").performClick()
        rule.onNodeWithText("Run/walk ladder first: at least 8 weeks. Two Easy runs finish each level, and up to three a week count.")
            .performScrollTo().assertExists()
    }

    @Test fun `a Some tester is asked for a run without walk breaks`() {
        show(state(SetupStep.WHERE, SetupFlowFixtures.test.copy(runExperience = RunExperience.SOME)))
        rule.onNodeWithText("Longest run without walk breaks in the past 30 days").assertExists()
        rule.runOnIdle { shown = state(SetupStep.WHERE, SetupFlowFixtures.test.copy(runExperience = RunExperience.NEW)) }
        rule.onNodeWithText("Longest run in the past 30 days").assertExists()
        rule.onNodeWithText("Longest run without walk breaks in the past 30 days").assertDoesNotExist()
    }

}
