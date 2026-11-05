package cloud.trotter.log.strength.ui.customexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Custom-exercise creation form (PLAN.md A4). Every draft field lives in [SavedStateHandle] — same
 * reasoning as [cloud.trotter.log.strength.ui.wizard.WizardViewModel]: a route this reachable (from a
 * sheet or Setup) can lose focus/rotate before the user finishes typing a name, and that draft must
 * survive it.
 *
 * Movement pattern starts unselected; the explicit draft choice survives recreation.
 *
 * [save] is the one write path, and it is the existing `:data` surface
 * ([CatalogRepository.addCustomExercise]) — no new persistence semantics.
 */
@HiltViewModel
class CustomExerciseViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val savedState: SavedStateHandle,
    private val settings: SettingsStore,
) : ViewModel() {

    private object Keys {
        const val NAME = "custom_exercise_name"
        const val PATTERN = "custom_exercise_pattern_choice_v2"
        const val EQUIPMENT = "custom_exercise_equipment"
        const val PER_HAND = "custom_exercise_per_hand"
        const val TRACKING = "custom_exercise_tracking"
        const val WEIGHT_LB = "custom_exercise_weight_lb"
        const val TARGET_REPS = "custom_exercise_target_reps"
        const val TARGET_SECONDS = "custom_exercise_target_seconds"
        const val ADDED_WEIGHT_LB = "custom_exercise_added_weight_lb"
    }

    private val name: StateFlow<String> = savedState.getStateFlow(Keys.NAME, "")
    private val pattern: StateFlow<String?> = savedState.getStateFlow(Keys.PATTERN, null)
    private val equipment: StateFlow<List<String>> = savedState.getStateFlow(Keys.EQUIPMENT, emptyList())
    private val perHand: StateFlow<Boolean> = savedState.getStateFlow(Keys.PER_HAND, false)
    private val tracking: StateFlow<String> = savedState.getStateFlow(Keys.TRACKING, TrackingType.WEIGHTED.name)
    private val weightLb: StateFlow<Double> = savedState.getStateFlow(Keys.WEIGHT_LB, DEFAULT_WEIGHT_LB)
    private val targetReps: StateFlow<Int> = savedState.getStateFlow(Keys.TARGET_REPS, DEFAULT_TARGET_REPS)
    private val targetSeconds: StateFlow<Int> = savedState.getStateFlow(Keys.TARGET_SECONDS, DEFAULT_TARGET_SECONDS)
    private val addedWeightLb: StateFlow<Double> = savedState.getStateFlow(Keys.ADDED_WEIGHT_LB, 0.0)

    private val saved = MutableStateFlow(false)
    private val writeOrder = Mutex()

    private data class FormGroup(
        val name: String,
        val pattern: MovementPattern?,
        val perHand: Boolean,
        val tracking: TrackingType,
        val weightLb: Double,
        val targetReps: Int,
        val targetSeconds: Int,
        val addedWeightLb: Double,
    )
    private data class DisplayGroup(val equipment: Set<Equipment>, val unit: WeightUnit)

    // The draft fields split into two sub-groups (`combine` tops out at 5
    // flows) and joined below — same shape as [displayGroup].
    private data class IdentityGroup(val name: String, val pattern: MovementPattern?, val perHand: Boolean)
    private data class TargetGroup(
        val tracking: TrackingType,
        val weightLb: Double,
        val targetReps: Int,
        val targetSeconds: Int,
        val addedWeightLb: Double,
    )

    private val identityGroup: Flow<IdentityGroup> =
        combine(name, pattern, perHand) { n, p, ph -> IdentityGroup(n, MovementPattern.entries.firstOrNull { it.name == p }, ph) }
    private val targetGroup: Flow<TargetGroup> =
        combine(tracking, weightLb, targetReps, targetSeconds, addedWeightLb) { t, w, reps, secs, addedLb ->
            TargetGroup(enumOf(t, TrackingType.WEIGHTED), w, reps, secs, addedLb)
        }
    private val formGroup: Flow<FormGroup> =
        combine(identityGroup, targetGroup) { identity, target ->
            FormGroup(
                identity.name, identity.pattern, identity.perHand,
                target.tracking, target.weightLb, target.targetReps, target.targetSeconds, target.addedWeightLb,
            )
        }
    private val displayGroup: Flow<DisplayGroup> =
        combine(equipment, settings.unitFlow) { equip, unit ->
            DisplayGroup(equip.mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }.toSet(), unit)
        }

    val uiState: StateFlow<CustomExerciseUiState> =
        combine(formGroup, displayGroup, saved) { form, display, isSaved ->
            CustomExerciseUiState(
                name = form.name,
                pattern = form.pattern,
                equipment = display.equipment,
                perHand = form.perHand,
                tracking = form.tracking,
                weightDisplay = display.unit.fromLb(form.weightLb),
                targetReps = form.targetReps,
                targetSeconds = form.targetSeconds,
                addedWeightDisplay = display.unit.fromLb(form.addedWeightLb),
                unit = display.unit,
                saved = isSaved,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CustomExerciseUiState())

    fun setName(value: String) {
        savedState[Keys.NAME] = value
    }

    fun setPattern(value: MovementPattern) {
        savedState[Keys.PATTERN] = value.name
    }

    fun toggleEquipment(value: Equipment) {
        val current = equipment.value
        savedState[Keys.EQUIPMENT] = if (value.name in current) current - value.name else current + value.name
    }

    fun setPerHand(value: Boolean) {
        savedState[Keys.PER_HAND] = value
    }

    fun setTracking(value: TrackingType) {
        savedState[Keys.TRACKING] = value.name
    }

    /** [newDisplayWeight] is in the current [WeightUnit] — converted to canonical
     *  lb before storage, mirroring [cloud.trotter.log.strength.ui.day.DayViewModel.changeWeight]. */
    fun setWeightDisplay(newDisplayWeight: Double) {
        viewModelScope.launch {
            writeOrder.withLock {
                val unit = settings.unitFlow.first()
                savedState[Keys.WEIGHT_LB] = unit.toLb(newDisplayWeight)
            }
        }
    }

    fun setTargetReps(value: Int) {
        savedState[Keys.TARGET_REPS] = value
    }

    fun setTargetSeconds(value: Int) {
        savedState[Keys.TARGET_SECONDS] = value
    }

    /** TIMED's optional added load — same unit-aware storage as [setWeightDisplay]. */
    fun setAddedWeightDisplay(newDisplayWeight: Double) {
        viewModelScope.launch {
            writeOrder.withLock {
                val unit = settings.unitFlow.first()
                savedState[Keys.ADDED_WEIGHT_LB] = unit.toLb(newDisplayWeight)
            }
        }
    }

    /** No-ops on a blank name or missing pattern (mirrors [CustomExerciseUiState.canSave],
     *  which [CustomExerciseScreen] uses to disable the save action) so a bad
     *  name can never reach [CatalogRepository.addCustomExercise]. [tracking]
     *  selects which target field is the live GOAL — the other two are simply
     *  not sent, exactly as [CatalogRepository.addCustomExercise] expects. */
    fun save() {
        val trimmedName = name.value.trim()
        if (trimmedName.isEmpty()) return
        val selectedPattern = MovementPattern.entries.firstOrNull { it.name == pattern.value } ?: return
        viewModelScope.launch {
            writeOrder.withLock {
                val selectedTracking = enumOf(tracking.value, TrackingType.WEIGHTED)
                val goalStartLb = when (selectedTracking) {
                    TrackingType.WEIGHTED -> weightLb.value
                    TrackingType.TIMED -> addedWeightLb.value
                    TrackingType.REPS -> 0.0
                }
                repo.addCustomExercise(
                    name = trimmedName,
                    pattern = selectedPattern,
                    equipment = equipment.value.mapNotNull { n -> Equipment.entries.firstOrNull { it.name == n } },
                    perHand = perHand.value,
                    goalStartLb = goalStartLb,
                    tracking = selectedTracking,
                    targetReps = targetReps.value.takeIf { selectedTracking == TrackingType.REPS },
                    targetSeconds = targetSeconds.value.takeIf { selectedTracking == TrackingType.TIMED },
                )
                saved.value = true
            }
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** A friendly starting point (an empty barbell) — not a spec constant;
         *  the user changes it in the number field for a real exercise. */
        const val DEFAULT_WEIGHT_LB = 45.0
        const val DEFAULT_TARGET_REPS = 10
        const val DEFAULT_TARGET_SECONDS = 30
    }
}
