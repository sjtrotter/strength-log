package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.planner.mainRow
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.planner.BothAnswers

@Composable
internal fun OrderStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    Column(Modifier.selectableGroup()) {
        ChoiceRow(stringResource(R.string.setup_flow_order_lift), stringResource(R.string.setup_flow_order_lift_body),
            !state.draft.cardioFirst) { actions.onCardioFirst(false) }
        ChoiceRow(stringResource(R.string.setup_flow_order_run), stringResource(R.string.setup_flow_order_run_body),
            state.draft.cardioFirst) { actions.onCardioFirst(true) }
    }
}

@Composable
internal fun TogetherStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    val draft = state.draft
    val selected = SetupFlowStateBuilder.answers(draft).together()
    val lift = draft.testLifts.firstOrNull { mainRow(
        ExerciseLibrary.get(it.exerciseId).pattern) >= 2 }
    val separateBody = if (draft.fitnessTest == true && draft.testRun && draft.days in 2..4 && lift != null)
        stringResource(R.string.setup_flow_test_tradeoff,
            ExerciseLibrary.get(lift.exerciseId).name,
            stringResource(testLabel(draft.test)), stringResource(when (draft.days) {
                2 -> R.string.setup_flow_test_two
                3 -> R.string.setup_flow_test_three
                else -> R.string.setup_flow_test_four
            })) else stringResource(R.string.setup_flow_together_separate_body)
    Column(Modifier.selectableGroup()) {
        ChoiceRow(stringResource(R.string.setup_flow_together_same), stringResource(R.string.setup_flow_together_same_body),
            selected == BothAnswers.Together.SAME_VISIT) { actions.onTogether(BothAnswers.Together.SAME_VISIT) }
        ChoiceRow(stringResource(R.string.setup_flow_together_separate), separateBody,
            selected == BothAnswers.Together.SEPARATE_DAYS) { actions.onTogether(BothAnswers.Together.SEPARATE_DAYS) }
    }
}
