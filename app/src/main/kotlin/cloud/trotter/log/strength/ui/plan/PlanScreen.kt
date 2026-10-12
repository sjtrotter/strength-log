package cloud.trotter.log.strength.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.ProgramLoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(state: PlanUiState, actions: PlanActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plan_title)) },
                actions = {
                    TextButton(onClick = actions.onSetUpAgain) {
                        Text(stringResource(R.string.plan_setup_again))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> ProgramLoadingState(Modifier.fillMaxSize().padding(innerPadding))
            !state.hasProgram -> NoProgramState(
                actions.onSetUpAgain,
                Modifier.fillMaxSize().padding(innerPadding),
            )
            else -> LazyColumn(contentPadding = innerPadding) {
                itemsIndexed(state.days, key = { _, day -> day.dayId }) { index, day ->
                    PlanDayItem(day, actions.onOpenDay)
                    if (index != state.days.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlanDayItem(day: PlanDay, onOpenDay: (String) -> Unit) {
    val lifts = pluralStringResource(R.plurals.plan_lifts, day.liftCount, day.liftCount)
    val sets = pluralStringResource(R.plurals.plan_sets, day.setCount, day.setCount)
    ListItem(
        modifier = Modifier.clickable { onOpenDay(day.dayId) },
        leadingContent = {
            DayIndex(day.dayIndex, day.dayId, DayIndexSize.Medium)
        },
        overlineContent = {
            Text(stringResource(R.string.today_day_label, day.dayId))
        },
        headlineContent = { Text(day.title) },
        supportingContent = { Text("$lifts, $sets") },
        trailingContent = {
            if (day.isNext) Text(stringResource(R.string.plan_next))
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
