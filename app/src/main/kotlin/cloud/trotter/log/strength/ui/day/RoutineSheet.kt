package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.ui.components.AppHaptics
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.SheetCommitButton
import cloud.trotter.log.strength.ui.text.cueResource
import cloud.trotter.log.strength.ui.text.doseSpoken
import cloud.trotter.log.strength.ui.text.doseText
import cloud.trotter.log.strength.ui.text.nameResource
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import kotlinx.coroutines.delay

/** Assembled from M3 parts with the app's sheet layout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutineSheet(
    kind: RoutineKind,
    sheet: RoutineSheetState,
    dayIndex: Int,
    nowMillis: () -> Long,
    onToggle: (Move) -> Unit,
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onClose: () -> Unit,
    onStartCardio: () -> Unit,
) {
    val title = stringResource(when (kind) {
        RoutineKind.WARM_UP -> R.string.routine_warm_up
        RoutineKind.COOL_DOWN -> R.string.routine_cool_down
    })
    AppModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
        },
        body = {
            RoutineTimer(sheet, nowMillis, onStartTimer, onStopTimer)
            Column {
                sheet.moves.forEach { move ->
                    val checked = move in sheet.checked
                    val spoken = doseSpoken(move.dose)
                    ListItem(
                        headlineContent = { Text(stringResource(move.nameResource())) },
                        supportingContent = { Column {
                            Text(stringResource(move.cueResource()))
                            Text(doseText(move.dose), modifier = Modifier.semantics { contentDescription = spoken })
                        } },
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = if (checked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(move) }),
                    )
                }
            }
        },
        footer = {
            SheetCommitButton(
                label = stringResource(if (sheet.startsCardio) R.string.workout_start else R.string.routine_done),
                onClick = if (sheet.startsCardio) onStartCardio else onClose,
                colors = ButtonDefaults.buttonColors(containerColor = dayAccent(dayIndex), contentColor = onDayAccent(dayIndex)),
                cancelLabel = stringResource(if (sheet.startsCardio) R.string.start_sheet_not_yet else R.string.routine_skip),
                onCancel = onClose,
            )
        },
    )
}

@Composable
private fun RoutineTimer(sheet: RoutineSheetState, nowMillis: () -> Long, onStart: () -> Unit, onStop: () -> Unit) {
    val startedAt = sheet.timerStartedAt
    val context = LocalContext.current
    var now by remember(sheet.owner, startedAt) { mutableLongStateOf(nowMillis()) }
    val remaining = if (startedAt == null) sheet.timerSeconds else
        (sheet.timerSeconds - (now - startedAt) / 1000).coerceIn(0L, sheet.timerSeconds.toLong()).toInt()
    LaunchedEffect(sheet.owner, startedAt) {
        if (startedAt == null) return@LaunchedEffect
        var sawRunning = remaining > 0
        while (true) {
            now = nowMillis()
            val left = (sheet.timerSeconds - (now - startedAt) / 1000).coerceIn(0L, sheet.timerSeconds.toLong())
            if (left == 0L) {
                if (sawRunning) AppHaptics.restComplete(context)
                break
            }
            sawRunning = true
            delay(1000)
        }
    }
    val time = formatCardioTime(remaining)
    val timerDescription = if (startedAt == null) stringResource(R.string.routine_timer_duration,
        formatDurationWords(context.resources, sheet.timerSeconds)) else
        stringResource(R.string.routine_timer_left, formatDurationWords(context.resources, remaining))
    val announcement = if (remaining == 0) stringResource(R.string.routine_timer_done) else
        stringResource(R.string.routine_timer_left, formatDurationWords(context.resources, ((remaining + 59) / 60) * 60))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2) {
        Text(time, style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag("routine-timer-$time").clearAndSetSemantics {
                contentDescription = timerDescription
            })
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (startedAt == null) {
                FilledTonalButton(onClick = onStart) { Text(stringResource(R.string.routine_timer_start)) }
            } else {
                LinearProgressIndicator(progress = { if (sheet.timerSeconds == 0) 0f else remaining.toFloat() / sheet.timerSeconds },
                    modifier = Modifier.weight(1f).clearAndSetSemantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = announcement
                    },
                    color = MaterialTheme.colorScheme.onSurface, trackColor = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = onStop) { Text(stringResource(R.string.routine_timer_stop)) }
            }
        }
    }
}
