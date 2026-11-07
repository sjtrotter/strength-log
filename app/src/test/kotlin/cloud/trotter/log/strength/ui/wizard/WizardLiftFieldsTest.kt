package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.os.Parcel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WizardLiftFieldsTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var backupService: BackupService
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<WizardViewModel>()
    private var current by mutableStateOf<WizardViewModel?>(null)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("wizard-fields-settings", ".preferences_pb")
        })
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("wizard-fields-journal", ".preferences_pb")
        }, settings)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        backupService = BackupService(BackupRepository(db, settings, repo.plans, repo.history), journal)
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        db.close()
    }

    private fun newViewModel(handle: SavedStateHandle) = WizardViewModel(
        repo.plans, handle, context, backupService, storeScope,
        DeviceWeightUnitProvider { WeightUnit.LB }, settings = settings,
    ).also { vms += it }

    private fun actions(vm: WizardViewModel, onWeightChange: (Double?) -> Unit) = WizardActions(
        onNext = vm::onNext, onBack = vm::onBack, onEmphasisChange = vm::setEmphasis,
        onDaysPerWeekChange = vm::setDaysPerWeek, onSplitChange = vm::setSplit,
        onAnchorSchemeChange = vm::setAnchorScheme, onDeadliftVariantChange = vm::setDeadliftVariant,
        onCardioModeChange = vm::setCardioMode, onCardioPlacementChange = vm::setCardioPlacement,
        onFiveKChange = vm::setFiveK, onBodyweightChange = vm::setBodyweight,
        onUnitChange = vm::setUnit, onAgeChange = vm::setAge, onLevelChange = vm::setLevel,
        onEquipmentToggle = vm::toggleEquipment, onRestoreFromBackup = {},
        onLiftWeightChange = { id, weight -> onWeightChange(weight); vm.setLiftWeight(id, weight) },
        onLiftRepsChange = vm::setLiftReps,
        onCardioStartSecondsChange = vm::setCardioStartSeconds,
    )

    private fun show(handle: SavedStateHandle, onWeightChange: (Double?) -> Unit = {}): WizardViewModel {
        lateinit var vm: WizardViewModel
        rule.runOnUiThread {
            vm = newViewModel(handle)
            current = vm
        }
        rule.setContent { AppTheme {
            val displayed = requireNotNull(current)
            key(displayed) {
                val state by displayed.uiState.collectAsState()
                WizardScreen(state, actions(displayed, onWeightChange))
            }
        } }
        rule.waitUntil(5_000) { rule.runOnIdle { vm.uiState.value.initialized } }
        rule.runOnIdle {
            vm.setBodyweight(235.0)
            vm.setAge(40)
            repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.LIFTS)) { vm.onNext() }
        }
        rule.waitUntil(5_000) { rule.runOnIdle { vm.uiState.value.step == WizardStep.LIFTS } }
        return vm
    }

    @Test fun typedRepsSurviveProcessDeathWithoutDoneOrFocusLoss() {
        val handle = SavedStateHandle()
        val initial = show(handle)
        rule.onAllNodesWithText("Weight (lb)").onFirst().requestFocus().performTextReplacement("225")
        rule.onAllNodesWithText("Reps").onFirst().requestFocus().performTextReplacement("8")
        rule.onAllNodesWithText("Reps").onFirst().assertIsFocused()

        rule.runOnIdle {
            val parcel = Parcel.obtain()
            val restored = try {
                parcel.writeBundle(handle.savedStateProvider().saveState())
                parcel.setDataPosition(0)
                SavedStateHandle.createHandle(parcel.readBundle(WizardLiftDraft::class.java.classLoader), null)
            } finally {
                parcel.recycle()
            }
            initial.viewModelScope.cancel()
            current = newViewModel(restored)
        }
        val revived = rule.runOnIdle { requireNotNull(current) }
        rule.waitUntil(5_000) { rule.runOnIdle { revived.uiState.value.step == WizardStep.LIFTS } }
        assertEquals("8", rule.onAllNodesWithText("Reps").onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.runOnIdle {
            val squat = revived.uiState.value.liftRows.first()
            assertEquals(225.0, squat.weightLb ?: Double.NaN, 0.0)
            assertEquals(8, squat.reps)
            revived.onNext()
        }
        rule.waitUntil(5_000) { rule.runOnIdle { revived.uiState.value.step == WizardStep.ROTATION } }
        rule.runOnIdle {
            assertEquals(PrescribedTarget.LoadReps(225.0, 8), revived.uiState.value.previewProgram!!
                .days.first().exercises.first().prescription?.target)
        }
    }

    @Test fun zeroRecentLiftWeightIsDiscardedOnDraftAndDone() {
        assertZeroWeightDiscarded { rule.onAllNodesWithText("Weight (lb)").onFirst().performImeAction() }
    }

    @Test fun zeroRecentLiftWeightIsDiscardedOnDraftAndFocusLoss() {
        assertZeroWeightDiscarded { rule.onAllNodesWithText("Reps").onFirst().requestFocus() }
    }

    @Test fun typedCardioMinutesSurviveProcessDeathWithoutDoneOrFocusLoss() {
        val handle = SavedStateHandle(mapOf(
            "wizard_initialized" to true,
            "wizard_focus" to true,
            "wizard_step_index_v2" to 2,
        ))
        lateinit var initial: WizardViewModel
        rule.runOnUiThread {
            initial = newViewModel(handle)
            current = initial
        }
        rule.setContent { AppTheme {
            val displayed = requireNotNull(current)
            key(displayed) {
                val state by displayed.uiState.collectAsState()
                WizardScreen(state, actions(displayed) {})
            }
        } }
        rule.waitUntil(5_000) { rule.runOnIdle { initial.uiState.value.step == WizardStep.CARDIO_START } }
        rule.onNodeWithText("Minutes").requestFocus().performTextReplacement("45")
        rule.onNodeWithText("Minutes").assertIsFocused()
        rule.runOnIdle {
            val parcel = Parcel.obtain()
            val restored = try {
                parcel.writeBundle(handle.savedStateProvider().saveState())
                parcel.setDataPosition(0)
                SavedStateHandle.createHandle(parcel.readBundle(WizardLiftDraft::class.java.classLoader), null)
            } finally {
                parcel.recycle()
            }
            initial.viewModelScope.cancel()
            current = newViewModel(restored)
        }
        val revived = rule.runOnIdle { requireNotNull(current) }
        rule.waitUntil(5_000) { rule.runOnIdle { revived.uiState.value.step == WizardStep.CARDIO_START } }
        assertEquals("45", rule.onNodeWithText("Minutes").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.runOnIdle { assertEquals(Measure.Time(2700), revived.uiState.value.answers.cardioFirst?.comfortable) }
    }

    private fun assertZeroWeightDiscarded(commit: () -> Unit) {
        val handle = SavedStateHandle()
        val writes = mutableListOf<Double?>()
        val vm = show(handle) { writes += it }
        rule.onAllNodesWithText("Weight (lb)").onFirst().requestFocus().performTextReplacement("225")
        rule.runOnIdle { writes.clear() }
        rule.onAllNodesWithText("Weight (lb)").onFirst().performTextReplacement("0")
        rule.onAllNodesWithText("Weight (lb)").onFirst().assertIsFocused()
        rule.runOnIdle {
            assertEquals(listOf<Double?>(null), writes)
            assertNull(handle.get<ArrayList<WizardLiftDraft>>("wizard_lifts")!!.single().weightLb)
        }
        commit()
        rule.runOnIdle {
            assertEquals(listOf<Double?>(null, null), writes)
            assertNull(handle.get<ArrayList<WizardLiftDraft>>("wizard_lifts")!!.single().weightLb)
            vm.onNext()
        }
        rule.waitUntil(5_000) { rule.runOnIdle { vm.uiState.value.step == WizardStep.ROTATION } }
        rule.runOnIdle {
            assertTrue(vm.uiState.value.answers.lifts.isEmpty())
            assertNull(vm.uiState.value.previewProgram!!.days.first().exercises.first().prescription)
        }
    }
}
