package cloud.trotter.log.strength.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, actions: TodayActions) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = {}, actions = {
            IconButton(onClick = actions.onOpenSettings) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.today_settings_description))
            }
        }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> ProgramLoadingState()
                state.nothingOnToday && state.resume == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.nothing_on_today), style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = actions.onOpenPlans) { Text(stringResource(R.string.plans_title)) }
                    }
                }
                !state.hasProgram -> NoProgramState(actions.onSetUpProgram)
                else -> LazyColumn(Modifier.widthIn(max = ReadableWidth).fillMaxWidth()) {
                    state.resume?.let { day -> item {
                        Button(
                            onClick = { actions.onResume(day.selection) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = dayAccent(day.dayIndex), contentColor = onDayAccent(day.dayIndex)),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.workout_resume))
                                Text(stringResource(R.string.workout_identity, day.planName, day.dayId, day.title))
                            }
                        }
                    } }
                    item { Text(stringResource(R.string.today_up_next), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                    state.upNext.forEach { day -> item(key = "next:${day.planId}:${day.dayId}") {
                        TodayDayItem(day, onClick = { actions.onOpenPreview(day.selection) },
                            start = if (day.preferred) ({ actions.onStart(day.selection) }) else null)
                    } }
                    if (state.otherDays.isNotEmpty()) {
                        item { TextButton(onClick = actions.onToggleOtherDays) { Text(stringResource(R.string.today_other_days)) } }
                        if (state.otherDaysExpanded) state.otherDays.forEach { day -> item(key = "other:${day.planId}:${day.dayId}") {
                            TodayDayItem(day, onClick = {
                                if (day.cardioOnly) actions.onOpenCardio(day.selection) else actions.onOpenPreview(day.selection)
                            })
                        } }
                    }
                    state.fact?.let { fact -> item { Text(fact, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
                }
            }
        }
    }
}

@Composable
private fun TodayDayItem(day: TodayDay, onClick: () -> Unit, start: (() -> Unit)? = null) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { DayIndex(day.dayIndex, day.dayId, DayIndexSize.Small) },
        headlineContent = { Text(stringResource(R.string.today_plan_day, day.planName, day.dayId)) },
        supportingContent = { Text(day.title) },
        trailingContent = start?.let { onStart -> { Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = dayAccent(day.dayIndex), contentColor = onDayAccent(day.dayIndex)),
        ) { Text(stringResource(R.string.workout_start)) } } },
    )
}
