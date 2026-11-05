package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import kotlinx.coroutines.delay

/** Loads that resolve within this interval draw no status content. */
const val LOADING_REVEAL_DELAY_MS = 260L

/** A themed primary action; only starting or resuming a day uses that day's accent. */
data class ScreenStateAction(val label: String, val onClick: () -> Unit, val dayIndex: Int? = null)

/**
 * Screen messages use M3 typography and a filled Button in the bottom action area.
 * M3 has no screen-level empty layout; this composition keeps the message readable
 * while the action stays reachable. In a lazy-list item it sizes to its content.
 */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    action: ScreenStateAction? = null,
    modifier: Modifier = Modifier,
    messageContent: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bounded = constraints.hasBoundedHeight
        Column(Modifier.fillMaxSize()) {
            val messageModifier = if (bounded) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier
            Column(messageModifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                body?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                messageContent()
            }
            action?.let {
                ScreenStateButton(it, Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp))
            }
        }
    }
}

@Composable
fun ScreenStateButton(action: ScreenStateAction, modifier: Modifier = Modifier) {
    Button(
        onClick = action.onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = action.dayIndex?.let {
            ButtonDefaults.buttonColors(containerColor = dayAccent(it), contentColor = onDayAccent(it))
        } ?: ButtonDefaults.buttonColors(),
    ) {
        Text(action.label)
    }
}

/**
 * Delayed M3 indeterminate progress and a polite status announcement. Nothing is
 * composed before the reveal delay, so a fast read does not flash a loading state.
 */
@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_REVEAL_DELAY_MS)
        revealed = true
    }
    if (revealed) {
        Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(label, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NoProgramState(onSetUpProgram: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.screen_no_plan_title),
        body = stringResource(R.string.screen_no_plan_body),
        action = ScreenStateAction(stringResource(R.string.screen_set_up_plan), onSetUpProgram),
        modifier = modifier,
    )
}

/** Keeps the journal's existing callbacks; callers with a known day can name its action. */
@Composable
fun EmptyJournalState(
    hasProgram: Boolean,
    onStartSession: () -> Unit,
    onSetUpProgram: () -> Unit,
    modifier: Modifier = Modifier,
    dayId: String? = null,
) {
    if (!hasProgram) {
        NoProgramState(onSetUpProgram, modifier)
        return
    }
    EmptyState(
        title = stringResource(R.string.screen_no_sessions_title),
        body = stringResource(R.string.screen_no_sessions_body),
        action = ScreenStateAction(
            label = dayId?.let { stringResource(R.string.screen_start_day, it) }
                ?: stringResource(R.string.screen_start_session),
            onClick = onStartSession,
            dayIndex = dayId?.let { Program.accentIndex(it) },
        ),
        modifier = modifier,
    )
}
