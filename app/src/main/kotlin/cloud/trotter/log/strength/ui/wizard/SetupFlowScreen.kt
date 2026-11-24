package cloud.trotter.log.strength.ui.wizard

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.setupStepTransition
import cloud.trotter.log.strength.ui.text.resolve
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.readableWidth

/** Stateless setup screens; every answer is forwarded to the saved draft through [SetupFlowActions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupFlowScreen(state: SetupFlowUiState, actions: SetupFlowActions) {
    var whyOpen by rememberSaveable(state.step) { mutableStateOf(false) }
    val whyTitle = stringResource(if (state.step == SetupStep.PLAN) R.string.setup_flow_why_plan else R.string.setup_flow_why)
    val busy = state.restore.inFlight || state.finishInFlight
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.setup_flow_title)) }, navigationIcon = {
            if (!state.isFirstStep || !state.firstRun) IconButton(
                onClick = if (state.isFirstStep) actions.onExit else actions.onBack,
                enabled = state.initialized && !busy, modifier = Modifier.size(48.dp),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.setup_back_description)) }
        }, actions = { Text(stringResource(R.string.setup_flow_progress, state.stepNumber, state.stepCount), Modifier.padding(end = 16.dp)) })
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.finishInFlight) LoadingState(stringResource(R.string.setup_flow_creating))
            state.finishError?.let {
                Text(stringResource(R.string.setup_flow_create_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { whyOpen = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(whyTitle) }
                if (state.step != SetupStep.PLAN || state.plan != PlanPreviewUi.Empty) {
                    Button(onClick = actions.onNext,
                        enabled = state.initialized && !busy && SetupFlowStateBuilder.canAdvance(state.draft) &&
                            (state.step != SetupStep.PLAN || state.canStart),
                        modifier = Modifier.heightIn(min = 52.dp)) {
                        Text(stringResource(when {
                            state.step != SetupStep.PLAN -> R.string.setup_flow_next
                            state.finishError != null -> R.string.receipt_try_again
                            else -> R.string.setup_flow_start
                        }))
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LinearProgressIndicator(progress = { state.stepNumber.toFloat() / state.stepCount }, modifier = Modifier.fillMaxWidth())
            if (!state.initialized) LoadingState(stringResource(R.string.screen_loading)) else {
                AnimatedContent(targetState = state, contentKey = { it.step }, transitionSpec = { setupStepTransition() }, label = "setup step") { screen ->
                    Box(Modifier.fillMaxSize()) {
                        Column(readableWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(if (screen.step == SetupStep.RUN_GOAL && screen.draft.branch == PlanBranch.SPRINT)
                                R.string.setup_flow_sprint_goal_title else stepTitle(screen.step)), style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.semantics { heading() })
                            when (screen.step) {
                                SetupStep.GOAL -> GoalStep(screen, actions)
                                SetupStep.ORDER -> OrderStep(screen, actions)
                                SetupStep.RUN_GOAL -> if (screen.draft.branch == PlanBranch.SPRINT) SprintGoalStep(screen, actions) else RunGoalStep(screen, actions)
                                SetupStep.EVENT -> EventStep(screen, actions)
                                SetupStep.WHERE -> WhereStep(screen, actions)
                                SetupStep.TOGETHER -> TogetherStep(screen, actions)
                                SetupStep.TEST -> TestStep(screen, actions)
                                SetupStep.SUB_GOAL -> SubGoalStep(screen, actions)
                                SetupStep.WEEK -> WeekStep(screen, actions)
                                SetupStep.EQUIPMENT -> EquipmentStep(screen, actions)
                                SetupStep.ABOUT -> AboutStep(screen, actions)
                                SetupStep.LIFTS -> LiftsStep(screen, actions)
                                SetupStep.RECENT -> RecentStep(screen, actions)
                                SetupStep.PLAN -> PlanStep(screen, actions)
                            }
                        }
                    }
                }
            }
        }
    }
    if (whyOpen) AppModalBottomSheet(onDismissRequest = { whyOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = { Text(whyTitle, style = MaterialTheme.typography.titleLarge) }, body = {
            if (state.step == SetupStep.PLAN) PlanWhyLines((state.plan as? PlanPreviewUi.Ready)?.why.orEmpty())
            else Text(stringResource(whyCopy(state)), style = MaterialTheme.typography.bodyMedium)
        })
}

private fun stepTitle(step: SetupStep): Int = when (step) {
    SetupStep.GOAL -> R.string.setup_flow_goal_title
    SetupStep.ORDER -> R.string.setup_flow_order_title
    SetupStep.RUN_GOAL -> R.string.setup_flow_run_goal_title
    SetupStep.EVENT -> R.string.setup_flow_event_title
    SetupStep.WHERE -> R.string.setup_flow_where_title
    SetupStep.TOGETHER -> R.string.setup_flow_together_title
    SetupStep.TEST -> R.string.setup_flow_tests_title
    SetupStep.SUB_GOAL -> R.string.setup_flow_sub_goal_title
    SetupStep.WEEK -> R.string.setup_flow_week_title
    SetupStep.EQUIPMENT -> R.string.setup_flow_equipment_title
    SetupStep.ABOUT -> R.string.setup_flow_about_title
    SetupStep.LIFTS -> R.string.setup_flow_lifts_title
    SetupStep.RECENT -> R.string.setup_flow_recent_title
    SetupStep.PLAN -> R.string.setup_flow_plan_title
}

private fun whyCopy(state: SetupFlowUiState): Int = when (state.step) {
    SetupStep.GOAL -> R.string.setup_flow_why_goal
    SetupStep.ORDER -> R.string.setup_flow_why_order
    SetupStep.RUN_GOAL -> if (state.draft.branch == PlanBranch.SPRINT) R.string.setup_flow_why_sprint_goal else R.string.setup_flow_why_run_goal
    SetupStep.EVENT -> if (state.draft.runGoal == RunGoal.FITNESS_TEST) {
        val run = SetupFlowStateBuilder.answers(state.draft).run
        if (run.hasTestAim()) R.string.setup_flow_why_test else R.string.setup_flow_why_test_taper
    } else R.string.setup_flow_why_event
    SetupStep.WHERE -> R.string.setup_flow_why_where
    SetupStep.TOGETHER -> R.string.setup_flow_why_together
    SetupStep.TEST -> R.string.setup_flow_why_tests
    SetupStep.SUB_GOAL -> R.string.setup_flow_why_sub_goal
    SetupStep.WEEK -> R.string.setup_flow_why_week
    SetupStep.EQUIPMENT -> R.string.setup_flow_why_equipment
    SetupStep.ABOUT -> if (state.draft.isSprint) R.string.setup_flow_why_about_sprint else if (state.draft.branch == PlanBranch.LIFT) R.string.setup_flow_why_about else R.string.setup_flow_why_about_run
    SetupStep.LIFTS -> R.string.setup_flow_why_lifts
    SetupStep.RECENT -> R.string.setup_flow_why_recent
    SetupStep.PLAN -> R.string.setup_flow_why_minutes
}

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SetupFlowScreenPreview() {
    AppTheme {
        SetupFlowScreen(SetupFlowStateBuilder.build(SetupDraft(), true, true, PlanPreviewUi.Loading,
            SetupRestoreState(offered = true), false, null, false), SetupFlowActions())
    }
}
