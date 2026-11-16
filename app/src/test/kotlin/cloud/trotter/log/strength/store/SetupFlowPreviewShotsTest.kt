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
