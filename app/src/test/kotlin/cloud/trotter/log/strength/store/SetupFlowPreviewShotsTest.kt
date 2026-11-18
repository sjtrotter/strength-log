package cloud.trotter.log.strength.store

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.wizard.PlanPreviewUi
import cloud.trotter.log.strength.ui.wizard.SetupDraft
import cloud.trotter.log.strength.ui.wizard.SetupFlowActions
import cloud.trotter.log.strength.ui.wizard.SetupFlowFixtures
import cloud.trotter.log.strength.ui.wizard.SetupFlowScreen
import cloud.trotter.log.strength.ui.wizard.SetupFlowUiState
import cloud.trotter.log.strength.ui.wizard.SetupStep
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupFlowPreviewShotsTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone
    private var originalFontScale = 1f

    @Before fun fixLocaleAndTimeZone() {
        originalLocale = Locale.getDefault(); originalTimeZone = TimeZone.getDefault()
        originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale); TimeZone.setDefault(originalTimeZone); RuntimeEnvironment.setFontScale(originalFontScale)
    }
    private fun state(step: SetupStep, draft: SetupDraft = SetupDraft()) = SetupFlowFixtures.state(draft.copy(step = step), firstRun = true)
    private fun plan() = state(SetupStep.PLAN, SetupFlowFixtures.reference)
    private fun shot(name: String, state: SetupFlowUiState, scale: Float = 1f, light: Boolean = false) {
        render(scale, light) { SetupFlowScreen(state, SetupFlowActions()) }; capture(name)
    }

    @Test fun goal() = shot("s01-setup-goal.png", state(SetupStep.GOAL))
    @Test fun subGoal() = shot("s02-setup-sub-goal.png", state(SetupStep.SUB_GOAL))
    @Test fun week() = shot("s03-setup-week.png", state(SetupStep.WEEK))
    @Test fun equipment() = shot("s04-setup-equipment.png", state(SetupStep.EQUIPMENT))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun equipmentExact() = shot("s05-setup-equipment-exact.png", state(SetupStep.EQUIPMENT,
        SetupDraft(equipment = EquipmentPreset.DUMBBELLS.equipment, chooseExactly = true)))
    @Test fun about() = shot("s06-setup-about.png", state(SetupStep.ABOUT))
    @Test fun lifts() = shot("s07-setup-lifts.png", state(SetupStep.LIFTS, SetupFlowFixtures.reference))
    @Test fun recent() = shot("s08-setup-recent.png", state(SetupStep.RECENT, SetupFlowFixtures.reference))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planReference() = shot("s09-setup-plan.png", plan())
    @Test fun planLight() = shot("s10-setup-plan-light.png", plan(), light = true)
    @Test fun overBudget() {
        render { SetupFlowScreen(state(SetupStep.PLAN, SetupFlowFixtures.overBudget), SetupFlowActions()) }
        rule.onNodeWithText("Drop a lift").performScrollTo().performClick(); capture("s11-setup-plan-over-budget.png", true)
    }
    @Test fun balanceOffer() = shot("s12-setup-plan-balance-offer.png", state(SetupStep.PLAN, SetupDraft(olderAdult = true, days = 1)))
    @Test fun balanceAdded() = shot("s13-setup-plan-balance-added.png", state(SetupStep.PLAN, SetupDraft(olderAdult = true, days = 1, balanceDays = true)))
    @Test fun whyWeek() {
        render { SetupFlowScreen(state(SetupStep.WEEK), SetupFlowActions()) }
        rule.onNodeWithText("Why we ask").performClick(); capture("s14-setup-why-week.png", true)
    }
    @Test fun whyPlan() {
        render { SetupFlowScreen(plan(), SetupFlowActions()) }
        rule.onNodeWithText("Why this plan").performClick(); capture("s15-setup-why-plan.png", true)
    }
    @Test fun loading() = shot("s16-setup-plan-loading.png", plan().copy(plan = PlanPreviewUi.Loading))
    @Test fun failed() = shot("s17-setup-plan-failed.png", plan().copy(plan = PlanPreviewUi.Failed))
    @Test fun empty() = shot("s18-setup-plan-empty.png", plan().copy(plan = PlanPreviewUi.Empty))
    @Test fun creating() = shot("s19-setup-creating.png", plan().copy(finishInFlight = true))
    @Test fun createFailed() = shot("s20-setup-create-failed.png", plan().copy(finishError = UiText.FileAccessFailure("Disk full")))
    @Test fun goalLargeFont() = shot("s21-setup-goal-2x.png", state(SetupStep.GOAL), scale = 2f)
    @Test fun planLargeFont() = shot("s22-setup-plan-2x.png", plan(), scale = 2f)

    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun goalRun() = shot("s23-setup-goal-run.png",
        state(SetupStep.GOAL, SetupFlowFixtures.run))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun goalRunLargeFont() = shot("s24-setup-goal-run-2x.png",
        state(SetupStep.GOAL, SetupFlowFixtures.run), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun order() = shot("s25-setup-order.png",
        state(SetupStep.ORDER, SetupFlowFixtures.both))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun orderLargeFont() = shot("s26-setup-order-2x.png",
        state(SetupStep.ORDER, SetupFlowFixtures.both), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun runGoal() = shot("s27-setup-run-goal.png",
        state(SetupStep.RUN_GOAL, SetupFlowFixtures.run))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun runGoalLargeFont() = shot("s28-setup-run-goal-2x.png",
        state(SetupStep.RUN_GOAL, SetupFlowFixtures.run), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun aboutRun() = shot("s29-setup-about-run.png",
        state(SetupStep.ABOUT, SetupFlowFixtures.run))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun aboutRunLargeFont() = shot("s30-setup-about-run-2x.png",
        state(SetupStep.ABOUT, SetupFlowFixtures.run), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun eventHalf() = shot("s31-setup-event-half.png",
        state(SetupStep.EVENT, SetupFlowFixtures.half))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun eventHalfLargeFont() = shot("s32-setup-event-half-2x.png",
        state(SetupStep.EVENT, SetupFlowFixtures.half), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun eventTest() = shot("s33-setup-event-test.png",
        state(SetupStep.EVENT, SetupFlowFixtures.test))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun eventTestLargeFont() = shot("s34-setup-event-test-2x.png",
        state(SetupStep.EVENT, SetupFlowFixtures.test), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun eventUltra() = shot("s35-setup-event-ultra.png",
        state(SetupStep.EVENT, SetupFlowFixtures.ultra))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun eventUltraLargeFont() = shot("s36-setup-event-ultra-2x.png",
        state(SetupStep.EVENT, SetupFlowFixtures.ultra), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun where() = shot("s37-setup-where.png",
        state(SetupStep.WHERE, SetupFlowFixtures.half))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun whereLargeFont() = shot("s38-setup-where-2x.png",
        state(SetupStep.WHERE, SetupFlowFixtures.half), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun whereCardio() = shot("s39-setup-where-cardio.png",
        state(SetupStep.WHERE, SetupFlowFixtures.run))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun whereCardioLargeFont() = shot("s40-setup-where-cardio-2x.png",
        state(SetupStep.WHERE, SetupFlowFixtures.run), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun together() = shot("s41-setup-together.png",
        state(SetupStep.TOGETHER, SetupFlowFixtures.both))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun togetherLargeFont() = shot("s42-setup-together-2x.png",
        state(SetupStep.TOGETHER, SetupFlowFixtures.both), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planLadder() = shot("s43-setup-plan-ladder.png",
        state(SetupStep.PLAN, SetupFlowFixtures.run))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planLadderLargeFont() = shot("s44-setup-plan-ladder-2x.png",
        state(SetupStep.PLAN, SetupFlowFixtures.run), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planHalf() = shot("s45-setup-plan-half.png",
        state(SetupStep.PLAN, SetupFlowFixtures.half))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planHalfLargeFont() = shot("s46-setup-plan-half-2x.png",
        state(SetupStep.PLAN, SetupFlowFixtures.half), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planBoth() = shot("s47-setup-plan-both.png",
        state(SetupStep.PLAN, SetupFlowFixtures.both))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planBothLargeFont() = shot("s48-setup-plan-both-2x.png",
        state(SetupStep.PLAN, SetupFlowFixtures.both), scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planNoRun() = shot("s49-setup-plan-no-run.png",
        state(SetupStep.PLAN, SetupFlowFixtures.noRun))
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun planNoRunLargeFont() = shot("s50-setup-plan-no-run-2x.png",
        state(SetupStep.PLAN, SetupFlowFixtures.noRun), scale = 2f)
    @Test fun whyEvent() {
        render { SetupFlowScreen(state(SetupStep.EVENT, SetupFlowFixtures.half), SetupFlowActions()) }
        rule.onNodeWithText("Why we ask").performClick()
        capture("s51-setup-why-event.png", true)
    }

    private fun render(fontScale: Float = 1f, light: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent { AppTheme(preference = if (light) ThemePreference.LIGHT else ThemePreference.DARK, content = content) }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
    }
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, sheet: Boolean = false) {
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        val root = if (sheet) {
            val dialogs = rule.onAllNodes(isDialog())
            if (dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs.onLast() else rule.onAllNodes(isPopup()).onLast()
        } else rule.onAllNodes(isRoot()).onLast()
        root.assertExists()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("previewShots.dir", "app/build/outputs/preview"), name)
            file.parentFile.mkdirs()
            if (sheet) captureScreenRoboImage(file.absolutePath) else root.captureRoboImage(file)
        }
    }
}
