package cloud.trotter.log.strength.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.EmptyState
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.NumberField
import cloud.trotter.log.strength.ui.components.ScreenStateAction
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.cardioActivityName
import cloud.trotter.log.strength.ui.setup.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(state: PlanUiState, actions: PlanActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(state.name ?: stringResource(R.string.plan_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onOpenPlans) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.setup_back_description))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> LoadingState(stringResource(R.string.screen_loading), Modifier.padding(innerPadding))
            !state.hasProgram && state.planId == null -> NoProgramState(
                actions.onSetUpProgram,
                Modifier.fillMaxSize().padding(innerPadding),
            )
            state.days.isEmpty() -> EmptyState(
                title = stringResource(R.string.screen_no_days_title),
                body = stringResource(R.string.screen_no_days_body),
                action = ScreenStateAction(stringResource(R.string.screen_add_day), actions.onAddDay),
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(contentPadding = innerPadding) {
                itemsIndexed(state.days, key = { _, day -> day.dayId }) { index, day ->
                    PlanDayItem(day, state, actions)
                    if (index != state.days.lastIndex) HorizontalDivider()
                }
                if (state.days.count { it.kind == ProgramDayKind.STRENGTH } < 7) {
                    item(key = "add_day") {
                        TextButton(onClick = actions.onAddDay) { Text(stringResource(R.string.plan_add_day)) }
                    }
                }
                if (state.emphasis != null) {
                    item(key = "goals_header") {
                        Box(Modifier.padding(horizontal = 16.dp)) { SectionHeader(stringResource(R.string.plan_goals)) }
                    }
                    itemsIndexed(state.goals, key = { _, goal -> "goal_${goal.name}" }) { _, goal ->
                        ListItem(
                            modifier = Modifier.clickable { actions.onOpenGoal(goal.exerciseId) },
                            headlineContent = { Text(goal.name) },
                            supportingContent = goal.calculated?.let { calculated ->
                                { Text(stringResource(R.string.plan_goal_calculated, calculated)) }
                            },
                            trailingContent = {
                                val suffix = if (goal.perHand) stringResource(R.string.setup_per_hand_suffix) else ""
                                Text(goal.display + suffix, style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                            },
                        )
                    }
                    item(key = "emphasis") {
                        EmphasisSection(state.emphasis, actions.onEmphasisChange)
                    }
                }
            }
        }
    }
    state.goalDialog?.let { dialog ->
        val unit = state.unit
        val unitLabel = unit.name.lowercase() + if (dialog.perHand) stringResource(R.string.setup_per_hand_suffix) else ""
        val onGoalDraft: (Double?) -> Unit = { value ->
            actions.onGoalDraft(value?.takeIf { it > 0.0 }?.let {
                unit.toLb(it).coerceAtMost(PlanRecipeJson.MAX_GOAL_LB)
            })
        }
        AppAlertDialog(
            onDismissRequest = actions.onDismissGoal,
            title = { Text(stringResource(R.string.plan_goal_title, dialog.name)) },
            text = {
                Column {
                    NumberField(
                        value = dialog.draftLb?.let(unit::fromLb),
                        onValueChange = onGoalDraft,
                        onDraftChange = onGoalDraft,
                        label = stringResource(R.string.plan_goal_weight, unitLabel),
                        minValue = 0.0,
                        maxValue = unit.fromLb(PlanRecipeJson.MAX_GOAL_LB),
                        format = WeightStepper::format,
                        round = { if (it == 0.0) 0.0 else WeightStepper.round(it, unit) },
                        decimalInput = true,
                        weightInput = true,
                    )
                    Text(stringResource(R.string.plan_goal_calculated, dialog.calculated))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = actions.onSaveGoal,
                    enabled = dialog.draftLb?.let { it > 0.0 && it <= PlanRecipeJson.MAX_GOAL_LB } == true,
                ) {
                    Text(stringResource(R.string.plans_save))
                }
            },
            dismissButton = {
                Row {
                    if (dialog.edited) {
                        TextButton(onClick = actions.onUseCalculatedGoal) { Text(stringResource(R.string.plan_goal_use_calculated)) }
                    }
                    TextButton(onClick = actions.onDismissGoal) { Text(stringResource(R.string.setup_cancel_button)) }
                }
            },
        )
    }
    if (state.dialog != null) {
        val renaming = state.dialog == "rename"
        AppAlertDialog(
            onDismissRequest = actions.onDismissDayDialog,
            title = { Text(stringResource(if (renaming) R.string.plan_rename_day else R.string.plan_remove_day)) },
            text = {
                if (renaming) {
                    OutlinedTextField(
                        value = state.dayTitleDraft,
                        onValueChange = actions.onDayTitleDraft,
                        label = { Text(stringResource(R.string.plans_name)) },
                        singleLine = true,
                    )
                } else {
                    Text(stringResource(R.string.plan_remove_day_confirm, state.dayTitleDraft))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = actions.onSaveDayDialog,
                    enabled = !renaming || state.dayTitleDraft.trim().length in 1..30,
                    colors = if (renaming) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(if (renaming) R.string.plans_save else R.string.day_edit_remove_button))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissDayDialog) { Text(stringResource(R.string.setup_cancel_button)) }
            },
        )
    }
}

@Composable
private fun PlanDayItem(day: PlanDay, state: PlanUiState, actions: PlanActions) {
    val lifts = pluralStringResource(R.plurals.plan_lifts, day.liftCount, day.liftCount)
    val sets = pluralStringResource(R.plurals.plan_sets, day.setCount, day.setCount)
    ListItem(
        modifier = Modifier.clickable { actions.onOpenDay(DaySelection(state.planId, day.dayId)) },
        leadingContent = {
            DayIndex(day.dayIndex, day.dayId, DayIndexSize.Medium)
        },
        overlineContent = {
            Text(stringResource(R.string.today_day_label, day.dayId))
        },
        headlineContent = { Text(day.title) },
        supportingContent = {
            val activity = day.cardioActivity
            Text(if (day.liftCount == 0 && activity != null) {
                stringResource(R.string.plan_cardio_summary, cardioActivityName(activity),
                    pluralStringResource(R.plurals.plan_sets, day.cardioSetCount, day.cardioSetCount))
            } else "$lifts, $sets")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (day.isNext) Text(stringResource(R.string.plan_next))
                Box {
                    IconButton(onClick = { actions.onDayMenu(day.dayId) }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.plan_day_menu))
                    }
                    DropdownMenu(expanded = state.menuDayId == day.dayId, onDismissRequest = { actions.onDayMenu(null) }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plans_rename)) }, onClick = { actions.onRenameDay(day.dayId) })
                        if (state.days.size > 1) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.day_edit_remove_button)) }, onClick = { actions.onRemoveDay(day.dayId) })
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            overlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun EmphasisSection(emphasis: GoalEmphasis, onChange: (GoalEmphasis) -> Unit) {
    val options = listOf(
        GoalEmphasis.STRENGTH to (R.string.setup_emphasis_strength_title to R.string.setup_emphasis_strength_description),
        GoalEmphasis.BALANCED to (R.string.setup_emphasis_balanced_title to R.string.setup_emphasis_balanced_description),
        GoalEmphasis.PHYSIQUE to (R.string.setup_emphasis_physique_title to R.string.setup_emphasis_physique_description),
    )
    Column(Modifier.selectableGroup()) {
        Box(Modifier.padding(horizontal = 16.dp)) { SectionHeader(stringResource(R.string.plan_emphasis)) }
        options.forEach { (value, copy) ->
            ListItem(
                headlineContent = { Text(stringResource(copy.first)) },
                supportingContent = { Text(stringResource(copy.second)) },
                leadingContent = { RadioButton(selected = emphasis == value, onClick = null) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = emphasis == value,
                    role = Role.RadioButton,
                    onClick = { onChange(value) },
                ),
            )
        }
    }
}
