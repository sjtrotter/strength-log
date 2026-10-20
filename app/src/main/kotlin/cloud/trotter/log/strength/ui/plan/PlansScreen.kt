package cloud.trotter.log.strength.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.PlanSummary
import cloud.trotter.log.strength.ui.setup.SectionHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(state: PlansUiState, actions: PlansActions) {
    val active = state.plans.filterNot { it.archived }
    val archived = state.plans.filter { it.archived }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plans_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.setup_back_description))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = actions.onNewPlan) {
                Text(stringResource(R.string.plans_new))
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            items(active, key = { it.id }) { plan ->
                PlanRow(plan, state.menuPlanId == plan.id, active.size > 1, actions)
                HorizontalDivider()
            }
            if (archived.isNotEmpty()) {
                item(key = "archived") {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SectionHeader(stringResource(R.string.plans_archived))
                    }
                }
                items(archived, key = { it.id }) { plan ->
                    PlanRow(plan, state.menuPlanId == plan.id, false, actions)
                    HorizontalDivider()
                }
            }
            // Leave the last row and its menu clear of the floating action button.
            item(key = "bottom_space") { Box(Modifier.padding(bottom = 88.dp)) }
        }
    }
    if (state.renamePlanId != null) {
        AlertDialog(
            onDismissRequest = actions.onDismissRename,
            title = { Text(stringResource(R.string.plans_rename)) },
            text = {
                OutlinedTextField(
                    value = state.renameDraft,
                    onValueChange = actions.onRenameDraft,
                    label = { Text(stringResource(R.string.plans_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = actions.onSaveRename, enabled = state.renameDraft.trim().length in 1..40) {
                    Text(stringResource(R.string.plans_save))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissRename) { Text(stringResource(R.string.setup_cancel_button)) }
            },
        )
    }
}

@Composable
private fun PlanRow(plan: PlanSummary, menuOpen: Boolean, canArchive: Boolean, actions: PlansActions) {
    ListItem(
        modifier = if (plan.archived) Modifier else Modifier.clickable { actions.onSelect(plan.id) },
        leadingContent = if (plan.archived) null else {
            { RadioButton(selected = plan.selected, onClick = { actions.onSelect(plan.id) }) }
        },
        headlineContent = { Text(plan.name) },
        supportingContent = {
            val next = plan.nextDayTitle?.let { stringResource(R.string.plans_next_day, it) }
                ?: stringResource(R.string.plans_no_next_day)
            val last = plan.lastSessionAt?.let {
                val date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    .format(DateTimeFormatter.ofPattern("d MMM"))
                stringResource(R.string.plans_last_session, date)
            }
            Text(if (last == null) next else stringResource(R.string.plans_supporting, next, last))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { actions.onMenu(plan.id) }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.plans_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { actions.onMenu(null) }) {
                    if (plan.archived) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.plans_restore)) },
                            onClick = { actions.onRestore(plan.id) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.plans_rename)) },
                            onClick = { actions.onRename(plan.id) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.plans_duplicate)) },
                            onClick = { actions.onDuplicate(plan.id) },
                        )
                        if (!plan.selected && canArchive) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plans_archive)) },
                                onClick = { actions.onArchive(plan.id) },
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
