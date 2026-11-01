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
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
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
            if (state.loading) ProgramLoadingState() else Column(
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
                        headlineContent = { Text(lift.name) },
                        supportingContent = lift.working?.let { working -> { Text(stringResource(R.string.preview_working, working)) } },
                        trailingContent = lift.last?.let { last -> { Text(stringResource(R.string.preview_last, last)) } },
                    ) }
                    state.cardio?.let { cardio -> item { ListItem(
                        headlineContent = { Text(cardio.activity) },
                        supportingContent = { Text(cardio.time) },
                    ) } }
                    if (state.lifts.isEmpty() && state.cardio == null) item {
                        Text(stringResource(R.string.preview_no_lifts), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                val empty = state.lifts.isEmpty() && state.cardio == null
                if (!empty) TextButton(onClick = actions.onEdit, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.preview_edit))
                }
                Button(
                    onClick = if (empty) actions.onEdit else actions.onStart,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = dayAccent(state.dayIndex), contentColor = onDayAccent(state.dayIndex)),
                ) {
                    Text(if (empty) stringResource(R.string.preview_edit) else stringResource(R.string.preview_start_day, state.selection.dayId))
                }
            }
        }
    }
}
