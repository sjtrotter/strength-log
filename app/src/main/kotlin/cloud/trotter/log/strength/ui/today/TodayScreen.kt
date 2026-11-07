package cloud.trotter.log.strength.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.EmptyState
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.ScreenStateAction
import cloud.trotter.log.strength.ui.components.ScreenStateButton
import cloud.trotter.log.strength.ui.theme.ReadableWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, actions: TodayActions) {
    val primaryDay = state.resume ?: state.upNext.firstOrNull()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_today)) }, actions = {
            IconButton(onClick = actions.onOpenSettings) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.today_settings_description))
            }
        }) },
        bottomBar = {
            if (!state.loading && primaryDay != null && (state.hasProgram || state.resume != null)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ScreenStateButton(
                        ScreenStateAction(
                            label = stringResource(
                                when {
                                    state.resume != null -> R.string.screen_resume_day
                                    primaryDay.rest -> R.string.today_rest_taken
                                    else -> R.string.screen_start_day
                                },
                                primaryDay.dayId,
                            ),
                            onClick = {
                                if (state.resume != null) actions.onResume(primaryDay.selection)
                                else if (primaryDay.rest) actions.onRestTaken(primaryDay.selection)
                                else actions.onStart(primaryDay.selection)
                            },
                            dayIndex = primaryDay.dayIndex,
                        ),
                        Modifier.widthIn(max = ReadableWidth).padding(16.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> LoadingState(stringResource(R.string.screen_loading))
                state.nothingOnToday && state.resume == null -> TodayUnavailable(state, actions)
                !state.hasProgram -> NoProgramState(actions.onSetUpProgram)
                else -> LazyColumn(Modifier.widthIn(max = ReadableWidth).fillMaxWidth()) {
                    state.resume?.let { day -> item(key = "resume") {
                        TodayDayItem(day, onClick = { actions.onOpenPreview(day.selection) })
                    } }
                    if (state.upNext.isNotEmpty()) item { Text(stringResource(R.string.today_up_next), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                    state.upNext.forEachIndexed { index, day -> item(key = "next:${day.planId}:${day.dayId}") {
                        if (day.rest) {
                            TodayRestItem(day, actions, showRestTaken = state.resume != null || index != 0)
                        } else {
                            TodayDayItem(day, onClick = { actions.onOpenPreview(day.selection) },
                                start = if (index == 0 && state.resume != null) ({ actions.onStart(day.selection) }) else null)
                        }
                    } }
                    if (state.otherDays.isNotEmpty()) {
                        item {
                            val expansionState = stringResource(
                                if (state.otherDaysExpanded) R.string.day_expanded_state else R.string.day_collapsed_state,
                            )
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.today_other_days)) },
                                trailingContent = { Icon(
                                    if (state.otherDaysExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                ) },
                                modifier = Modifier.clickable(onClick = actions.onToggleOtherDays)
                                    .semantics { stateDescription = expansionState },
                            )
                        }
                        if (state.otherDaysExpanded) state.otherDays.forEach { day -> item(key = "other:${day.planId}:${day.dayId}") {
                            TodayDayItem(day, onClick = {
                                if (day.cardioOnly) actions.onOpenCardio(day.selection) else actions.onOpenPreview(day.selection)
                            })
                        } }
                    }
                    if (state.nothingOnToday) item { TodayUnavailable(state, actions) }
                    else state.fact?.let { fact -> item { Text(stringResource(R.string.today_last_trained, fact), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
                }
            }
        }
    }
}

@Composable
private fun TodayUnavailable(state: TodayUiState, actions: TodayActions) {
    val plansActionInSlot = state.plansPaused && state.resume == null
    EmptyState(
        title = stringResource(if (state.plansPaused) R.string.screen_plans_paused_title else R.string.screen_done_today_title),
        body = stringResource(if (state.plansPaused) R.string.screen_plans_paused_body else R.string.screen_done_today_body),
        action = if (plansActionInSlot) ScreenStateAction(stringResource(R.string.screen_open_plans), actions.onOpenPlans) else null,
    ) {
        state.fact?.let { Text(stringResource(R.string.today_last_trained, it), Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!plansActionInSlot) {
            TextButton(onClick = actions.onOpenPlans) { Text(stringResource(R.string.screen_open_plans)) }
        }
    }
}

@Composable
private fun TodayRestItem(day: TodayDay, actions: TodayActions, showRestTaken: Boolean) {
    ListItem(
        leadingContent = { DayIndex(day.dayIndex, day.dayId, DayIndexSize.Small) },
        headlineContent = { Text(stringResource(R.string.today_rest_day, day.dayId)) },
        supportingContent = { Text(day.planName) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                day.trainDayId?.let { trainDayId ->
                    TextButton(onClick = { actions.onSkipRest(day.selection.copy(dayId = trainDayId)) }) {
                        Text(stringResource(R.string.today_skip_rest))
                    }
                }
                if (showRestTaken) {
                    FilledTonalButton(onClick = { actions.onRestTaken(day.selection) }) {
                        Text(stringResource(R.string.today_rest_taken))
                    }
                }
            }
        },
    )
}

@Composable
private fun TodayDayItem(day: TodayDay, onClick: () -> Unit, start: (() -> Unit)? = null) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { DayIndex(day.dayIndex, day.dayId, DayIndexSize.Small) },
        headlineContent = { Text(stringResource(R.string.today_plan_day, day.planName, day.dayId)) },
        supportingContent = { Text(day.title) },
        trailingContent = start?.let { onStart -> {
            FilledTonalButton(onClick = onStart) { Text(stringResource(R.string.workout_start)) }
        } },
    )
}
