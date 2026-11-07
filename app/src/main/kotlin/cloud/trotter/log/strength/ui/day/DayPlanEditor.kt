package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.theme.readableWidth

/** The day editor as a screen, opened from a day preview's Edit (#500). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPlanEditor(
    state: DayEditUiState,
    dayId: String?,
    loading: Boolean,
    accent: Color,
    actions: DayEditActions,
    onBack: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(
            title = { dayId?.let { Text(stringResource(R.string.day_plan_editor_title, it)) } },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.day_back))
            } },
        ) },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).fillMaxSize()) {
            Box(readableWidth()) {
                if (loading) LoadingState(stringResource(R.string.screen_loading))
                else DayEditPages(state, actions, accent, onDismiss = onBack,
                    onCreateExercise = onCreateExercise, onRootBack = onBack)
            }
        }
    }
}
