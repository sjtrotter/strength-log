package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.ui.test.testRunTrainingText
import cloud.trotter.log.strength.ui.test.testTrainingText
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.planner.EVENT_WEEKS_AFTER_LADDER
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import cloud.trotter.log.strength.ui.day.receipt.blockValue
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.EmptyState
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.components.ScreenStateAction

@Composable
internal fun PlanStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    when (val plan = state.plan) {
        PlanPreviewUi.Loading -> LoadingState(stringResource(R.string.setup_flow_plan_loading))
        PlanPreviewUi.Failed -> {
            Text(stringResource(R.string.setup_flow_plan_failed), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = actions.onRetryPreview, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.receipt_try_again)) }
        }
        PlanPreviewUi.Empty -> EmptyState(title = stringResource(R.string.setup_flow_plan_empty),
            action = ScreenStateAction(stringResource(R.string.setup_flow_review_answers), actions.onReviewAnswers))
        is PlanPreviewUi.NeedsLowerMain -> {
            SprintFloorLine(plan.floor)
            TextButton(onClick = actions.onReviewAnswers, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.setup_flow_review_answers))
            }
        }
        is PlanPreviewUi.Ready -> {
            Text(plan.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            plan.sprint?.let { sprint ->
                val week = sprint.week.map { (day, kind) ->
                    stringResource(when (kind) {
                        SprintWeekKind.SPRINT -> R.string.setup_flow_week_sprint
                        SprintWeekKind.UPPER -> R.string.setup_flow_week_upper
                        SprintWeekKind.TEMPO -> R.string.setup_flow_week_tempo
                    }, day.getDisplayName(TextStyle.SHORT, Locale.US))
                }.joinToString(", ")
                Text(stringResource(R.string.setup_flow_suggested_week, week), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (sprint.threeDayTier) R.string.setup_flow_sprint_spacing_three
                    else R.string.setup_flow_sprint_spacing_two), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(sprintHighDayLabel(sprint.jumps, sprint.nordic)), style = MaterialTheme.typography.bodyMedium)
            }
            plan.testTraining.forEach { Text(testTrainingText(it), style = MaterialTheme.typography.bodyMedium) }
            plan.testRun?.let { (test, program) -> Text(testRunTrainingText(test, program)) }
            plan.rows.forEach { row -> PlanRow(row, state.draft.unit, state.draft.isSprint) }
            if (SetupFlowStateBuilder.branch(state.draft) == PlanBranch.RUN) {
                SetupLabel(stringResource(R.string.setup_flow_strength_blocks))
                SetupSegments((0..2).map(Int::toString), state.draft.strengthBlocks, actions.onStrengthBlocks)
            }
            if (plan.why.any { it is PlanWhy.NoRun }) {
                AppCard {
                    Text(stringResource(R.string.setup_flow_no_run, state.draft.minutes), style = MaterialTheme.typography.bodyMedium)
                    if (plan.longerAvailable) OutlinedButton(onClick = actions.onLongerSessions, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.setup_flow_longer_sessions))
                    }
                    if (SetupFlowStateBuilder.offersTogether(state.draft) &&
                        SetupFlowStateBuilder.answers(state.draft).together() != BothAnswers.Together.SEPARATE_DAYS) OutlinedButton(
                        onClick = { actions.onTogether(BothAnswers.Together.SEPARATE_DAYS) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.setup_flow_together_separate))
                    }
                }
            }
            if (plan.sprint?.shortened == true || plan.sprint == null && plan.over) OverBudgetCard(plan, actions)
            plan.balanceOffer?.let { count ->
                AppCard(Modifier.testTag("setup-balance-offer")) {
                    Text(pluralStringResource(R.plurals.setup_flow_balance_offer, count, count), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.setup_flow_balance_offer_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { actions.onBalanceDays(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.setup_flow_balance_add))
                    }
                }
            }
            plan.balanceAdded?.let { count ->
                AppCard(Modifier.testTag("setup-balance-offer")) {
                    Text(pluralStringResource(R.plurals.setup_flow_balance_added, count, count), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { actions.onBalanceDays(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.setup_flow_balance_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRow(row: PlanDayRow, unit: WeightUnit, sprint: Boolean) {
    ListItem(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("setup-plan-day-${row.dayId}"),
        leadingContent = { DayIndex(Program.accentIndex(row.dayId), row.dayId, DayIndexSize.Small) },
        headlineContent = { Text(if (row.restBeforeSprint) stringResource(R.string.today_rest_before_sprint) else row.title) },
        supportingContent = if (row.mainName != null || row.overMinutes != null || row.cardio != null) { {
            Column {
                row.mainName?.let { name ->
                    Text(row.mainLoadLb?.let { stringResource(R.string.setup_flow_plan_main, name, SetFormatter.weight(it, unit)) } ?: name)
                }
                row.cardio?.let { Text(blockValue(it, distanceUnitFor(it.activity), sprint)) }
                row.overMinutes?.let { Text(stringResource(R.string.setup_flow_over_by, it), color = MaterialTheme.colorScheme.error) }
            }
        } } else null,
        trailingContent = if (!row.rest) { {
            Column {
                if (row.lifts > 0) Text(pluralStringResource(R.plurals.setup_flow_plan_lifts, row.lifts, row.lifts))
                Text(stringResource(R.string.setup_flow_plan_minutes, row.minutes))
            }
        } } else null)
}

@Composable
private fun OverBudgetCard(plan: PlanPreviewUi.Ready, actions: SetupFlowActions) {
    AppCard(Modifier.testTag("setup-over-budget")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            plan.sprint?.fullDoseMinutes?.let { minutes ->
                Text(stringResource(R.string.setup_flow_full_plan_minutes, minutes), style = MaterialTheme.typography.bodyMedium)
            }
            if (plan.droppable.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.setup_flow_drop_lift))
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        plan.droppable.forEach { id ->
                            DropdownMenuItem(text = { Text(ExerciseLibrary.get(id).name) }, onClick = {
                                expanded = false
                                actions.onDropLift(id)
                            })
                        }
                    }
                }
            }
            if (plan.longerAvailable) OutlinedButton(onClick = actions.onLongerSessions, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.setup_flow_longer_sessions))
            }
        }
    }
}

@Composable
internal fun PlanWhyLines(why: List<PlanWhy>) {
    why.forEach { Text(whyLine(it), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun whyLine(why: PlanWhy): String = when (why) {
    is PlanWhy.Floor -> stringResource(R.string.setup_flow_why_floor, why.dayId, why.minutes)
    is PlanWhy.Trimmed -> stringResource(R.string.setup_flow_why_trimmed, why.dayId, why.minutes)
    is PlanWhy.OneSet -> stringResource(R.string.setup_flow_why_one_set, why.dayId, why.minutes)
    is PlanWhy.Dropped -> {
        val names = why.names + if (why.extraSets) listOf(stringResource(R.string.setup_flow_extra_sets)) else emptyList<String>()
        val list = when (names.size) {
            0 -> ""
            1 -> names.single()
            else -> stringResource(R.string.setup_flow_list_and, names.dropLast(1).joinToString(", "), names.last())
        }
        stringResource(R.string.setup_flow_why_dropped, why.dayId, list, why.minutes)
    }
    PlanWhy.ShortTwoDaySprint -> stringResource(R.string.setup_flow_why_short_two_day)
    PlanWhy.SplitDay -> stringResource(R.string.setup_flow_why_split_day)
    is PlanWhy.Over -> stringResource(R.string.setup_flow_why_over, why.dayId, why.minutes)
    PlanWhy.BalanceDays -> stringResource(R.string.setup_flow_why_balance_days)
    is PlanWhy.BalanceOffered -> pluralStringResource(R.plurals.setup_flow_why_balance_offer, why.liftingDays, why.liftingDays)
    PlanWhy.RepsLeft -> stringResource(R.string.setup_flow_why_reps_left)
    PlanWhy.Calibrating -> stringResource(R.string.setup_flow_why_calibrating)
    is PlanWhy.LightWeek -> stringResource(R.string.setup_flow_why_light_week, why.everyCycles)
    is PlanWhy.RunCapped -> stringResource(R.string.setup_flow_why_run_capped, why.dayId)
    PlanWhy.DateTooClose -> stringResource(R.string.setup_flow_why_date_too_close)
    is PlanWhy.GoalCheck -> stringResource(R.string.setup_flow_why_goal_check, ClockText.duration(why.predictedSeconds))
    is PlanWhy.Ladder -> pluralStringResource(R.plurals.setup_flow_why_ladder_weeks, why.weeks, why.weeks)
    is PlanWhy.LadderDateTooClose -> pluralStringResource(
        if (why.fitnessTest) R.plurals.setup_flow_why_ladder_date_test else R.plurals.setup_flow_why_ladder_date_race,
        EVENT_WEEKS_AFTER_LADDER, EVENT_WEEKS_AFTER_LADDER)
    is PlanWhy.NoRun -> stringResource(R.string.setup_flow_no_run, why.minutes)
    is PlanWhy.TestPrep -> pluralStringResource(R.plurals.setup_flow_why_test_prep, why.events, why.events)
    PlanWhy.Minutes -> stringResource(R.string.setup_flow_why_minutes)
}
