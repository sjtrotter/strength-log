package cloud.trotter.log.strength.ui.today

import androidx.compose.runtime.Immutable
import cloud.trotter.log.strength.data.PlanSummary
import cloud.trotter.log.strength.ui.text.UiText

/** Immutable render model for the Today screen (issue #121) — the ViewModel's
 *  single output. [Immutable] for the reason
 *  [cloud.trotter.log.strength.ui.day.DayUiState] carries it (#156): the
 *  `List` field otherwise leaves the whole screen comparing by identity. */
@Immutable
data class TodayUiState(
    val hasProgram: Boolean = false,
    /** True only while the program is still being read (#127) — see
     *  [cloud.trotter.log.strength.ui.day.DayUiState.loading] for why the two
     *  silences are worth telling apart. */
    val loading: Boolean = false,
    val dayId: String = "",
    /** 0-based rotation position — the key into `dayAccent`/`onDayAccent`. */
    val dayIndex: Int = 0,
    val dayTitle: String = "",
    val coachLine: UiText? = null,
    val lifts: List<TodayLift> = emptyList(),
    val cardio: TodayCardio? = null,
    val standaloneCardio: StandaloneCardio? = null,
    /** "Start day B" / "Continue day B" / "Finish day B". */
    val actionLabel: UiText.TodayAction = UiText.TodayAction(cloud.trotter.log.strength.ui.text.TodayActionKind.START, "", 0, 0),
    /** One quiet derived history fact; null on a fresh install. */
    val lifeLine: UiText? = null,
    /** Identity of the plan that produced the displayed day and history. */
    val planId: Long? = null,
    val planName: String? = null,
    val activePlans: List<PlanSummary> = emptyList(),
    val planPickerOpen: Boolean = false,
)

/** One slot of the previewed day: what it is called and how many rounds it has. */
data class TodayLift(val name: String, val setCount: Int)

/** The day's cardio finisher, if it has one. */
data class TodayCardio(
    val label: String,
    val detail: String,
    val activity: cloud.trotter.log.strength.domain.model.CardioActivity? = null,
    val loggedSeconds: Int? = null,
)
data class StandaloneCardio(val dayId: String, val line: String, val loggedSeconds: Int? = null)

/** Callbacks the screen forwards — one place, so previews stay trivial. */
data class TodayActions(
    val onStart: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenLog: () -> Unit,
    /** The recovery out of the no-program state (#127): back into the wizard. */
    val onSetUpProgram: () -> Unit,
    val onOpenCardio: (String) -> Unit = {},
    val onShowPlanPicker: () -> Unit = {},
    val onDismissPlanPicker: () -> Unit = {},
    val onSelectPlan: (Long) -> Unit = {},
)
