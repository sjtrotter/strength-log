package cloud.trotter.log.strength.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.theme.ThemePreference
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backfill done-flag (#159) is deliberately NOT carried by restore: a
 * restored device holds history Health Connect has never seen, so the offer
 * must stand again. This pins that restore's clear() keeps dropping the flag —
 * a future restore refactor that preserves preferences would silently
 * suppress publishing restored history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRestoreClearsBackfillFlagTest {

    private lateinit var settings: SettingsStore
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("restore-backfill-flag", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `restore drops the backfill done flag`() = runTest {
        settings.setHealthBackfillDone()
        assertTrue(settings.healthBackfillDoneFlow.first())

        settings.restore(
            answers = WizardAnswers(
                daysPerWeek = 5,
                split = SplitTemplate.UPPER_LOWER,
                anchorScheme = AnchorScheme.BIG_4,
                deadliftVariant = DeadliftVariant.CONVENTIONAL,
                cardio = CardioPrefs(
                    mode = CardioMode.TREADMILL,
                    placement = CardioPlacement.SEPARATE_DAYS,
                    fiveKGoal = false,
                ),
                config = LifterConfig(
                    bodyweightLb = 210,
                    age = 33,
                    level = ExperienceLevel.ADVANCED,
                    emphasis = GoalEmphasis.STRENGTH,
                ),
                equipment = setOf(Equipment.BARBELL),
            ),
            unit = WeightUnit.LB,
            wizardComplete = true,
            suggestedDay = null,
            restSettings = RestSettings(),
            keepScreenOn = false,
        )

        assertFalse(settings.healthBackfillDoneFlow.first())
    }

    @Test
    fun `theme defaults to system and restore carries an explicit choice`() = runTest {
        assertTrue(settings.themePreferenceFlow.first() == ThemePreference.SYSTEM)
        settings.setThemePreference(ThemePreference.DARK)
        assertTrue(settings.themePreferenceFlow.first() == ThemePreference.DARK)

        settings.restore(
            answers = WizardAnswers(),
            unit = WeightUnit.LB,
            wizardComplete = true,
            suggestedDay = null,
            restSettings = RestSettings(),
            keepScreenOn = false,
            themePreference = ThemePreference.LIGHT,
        )

        assertTrue(settings.themePreferenceFlow.first() == ThemePreference.LIGHT)
    }
}
