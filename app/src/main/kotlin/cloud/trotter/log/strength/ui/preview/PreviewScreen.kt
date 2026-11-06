package cloud.trotter.log.strength.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.EmptyState
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.components.ScreenStateAction
import cloud.trotter.log.strength.ui.day.NextTime
import cloud.trotter.log.strength.ui.day.NextTimeOfferChip
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(state: PreviewUiState, actions: PreviewActions) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(
            title = { Text(state.planName) },
            navigationIcon = { IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.setup_back_description))
            } },
        ) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (state.loading) LoadingState(stringResource(R.string.screen_loading))
            else if (state.lifts.isEmpty() && state.cardio == null) EmptyState(
                title = stringResource(R.string.screen_preview_no_lifts_title),
                action = ScreenStateAction(stringResource(R.string.screen_edit_day), actions.onEdit),
                modifier = Modifier.widthIn(max = ReadableWidth),
            ) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    DayIndex(state.dayIndex, state.selection.dayId, DayIndexSize.Large)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.title, style = MaterialTheme.typography.headlineLarge)
                        if (state.emphasis.isNotBlank()) Text(state.emphasis, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else Column(
                Modifier.widthIn(max = ReadableWidth).fillMaxSize(),
            ) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item { Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        DayIndex(state.dayIndex, state.selection.dayId, DayIndexSize.Large)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.title, style = MaterialTheme.typography.headlineLarge)
                            if (state.emphasis.isNotBlank()) Text(state.emphasis, style = MaterialTheme.typography.bodyLarge)
                        }
                    } }
                    items(state.lifts) { lift -> ListItem(
                        headlineContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(lift.name)
                                if (lift.note.isNotBlank()) Text(lift.note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                lift.working?.let { working ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.preview_working, working), modifier = Modifier.weight(1f, fill = false))
                                        when (val nextTime = lift.nextTime) {
                                            NextTime.Repeat -> Text(stringResource(R.string.next_time_repeat),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            is NextTime.Offer -> NextTimeOfferChip(nextTime, Slot.MAIN) {
                                                actions.onNextTime(lift.programExerciseId, it)
                                            }
                                            null -> Unit
                                        }
                                    }
                                }
                                lift.previous.forEach { previous ->
                                    val source = previous.source?.let { day ->
                                        previous.sourcePlanName?.let { stringResource(R.string.today_plan_day, it, day) }
                                            ?: stringResource(R.string.preview_last_day, day)
                                    }
                                    val result = when {
                                        !previous.comparable -> stringResource(R.string.preview_no_comparable_result)
                                        previous.last == null -> null
                                        source != null -> stringResource(R.string.preview_last_elsewhere, previous.last, source)
                                        else -> stringResource(R.string.preview_last, previous.last)
                                    }
                                    if (result != null) Text(if (lift.previous.size > 1) stringResource(R.string.form_named_previous_result,
                                        previous.exerciseName, result) else result)
                                }
                            }
                        },
                    ) }
                    state.cardio?.let { cardio -> item { ListItem(
                        headlineContent = { Text(cardio.activity) },
                        supportingContent = { Text(cardio.time) },
                    ) } }
                }
                TextButton(onClick = actions.onEdit, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.preview_edit))
                }
                Button(
                    onClick = actions.onStart,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = dayAccent(state.dayIndex), contentColor = onDayAccent(state.dayIndex)),
                ) {
                    Text(stringResource(R.string.preview_start_day, state.selection.dayId))
                }
            }
        }
    }
}
