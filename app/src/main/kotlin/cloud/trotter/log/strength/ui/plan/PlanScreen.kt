package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.ui.day.DaySelection

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
import cloud.trotter.log.strength.ui.day.cardioActivityName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(state: PlanUiState, actions: PlanActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(state.name ?: stringResource(R.string.plan_title)) },
                actions = {
                    TextButton(onClick = actions.onOpenPlans) {
                        Text(stringResource(R.string.plans_title))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> ProgramLoadingState(Modifier.fillMaxSize().padding(innerPadding))
            !state.hasProgram -> NoProgramState(
                actions.onSetUpProgram,
                Modifier.fillMaxSize().padding(innerPadding),
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
            }
        }
    }
    if (state.dialog != null) {
        val renaming = state.dialog == "rename"
        AlertDialog(
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
