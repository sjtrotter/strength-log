package cloud.trotter.log.strength.ui.wizard

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
    val selected = SetupFlowStateBuilder.answers(state.draft).together()
    Column(Modifier.selectableGroup()) {
        ChoiceRow(stringResource(R.string.setup_flow_together_same), stringResource(R.string.setup_flow_together_same_body),
            selected == BothAnswers.Together.SAME_VISIT) { actions.onTogether(BothAnswers.Together.SAME_VISIT) }
        ChoiceRow(stringResource(R.string.setup_flow_together_separate), stringResource(R.string.setup_flow_together_separate_body),
            selected == BothAnswers.Together.SEPARATE_DAYS) { actions.onTogether(BothAnswers.Together.SEPARATE_DAYS) }
    }
}
