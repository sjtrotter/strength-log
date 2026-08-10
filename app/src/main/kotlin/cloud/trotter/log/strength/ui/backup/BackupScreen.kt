package cloud.trotter.log.strength.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.transfer.csv.CsvImportPreview
import cloud.trotter.log.strength.transfer.csv.PreviewSession
import cloud.trotter.log.strength.transfer.csv.PreviewSet
import cloud.trotter.log.strength.transfer.csv.UnmatchedExerciseName
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.BackAction
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.SelectionCard
import cloud.trotter.log.strength.ui.components.SelectionMode
import cloud.trotter.log.strength.ui.components.SwitchToggle
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.readableWidth
import cloud.trotter.log.strength.ui.text.resolve

/**
 * The Data/Backup screen (PLAN.md A2, brief D9's `:app`-side UI PR): full
 * JSON backup export/restore and Strong-compatible CSV history export/import,
 * reachable from Setup. Stateless like the rest of the app's screens — the
 * route (`AppNavHost`) owns the SAF [androidx.activity.result.ActivityResultLauncher]s
 * and forwards the resulting `Uri` straight to [BackupViewModel]; this
 * composable only renders [state] and forwards intent through [actions].
 */
@Composable
fun BackupScreen(state: BackupUiState, actions: BackupActions) {
    val accent = dayAccent(0)

    // A restore writes Room and then DataStore with no transaction spanning the
    // two, so walking out mid-write is how they end up disagreeing (#172). The
    // write itself is app-scoped now and survives, but the screen still refuses
    // to leave while it runs rather than reporting to nobody. Restore only:
    // an export or a CSV import has nothing to tear, and gating back on every
    // operation would just make the screen feel stuck.
    BackHandler(enabled = state.restoreInFlight) {}

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            BackupHeader(actions.onBack, enabled = !state.restoreInFlight)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                item { AutomaticBackupCard(state.automatic, actions.onAutomaticBackupChange) }
                item {
                    SectionCard(
                        title = stringResource(R.string.backup_full_title),
                        body = stringResource(R.string.backup_full_body),
                        accent = accent,
                        busy = state.isBusy,
                        primaryLabel = stringResource(R.string.backup_export_button),
                        onPrimaryClick = actions.onExportBackupClick,
                        secondaryLabel = stringResource(R.string.backup_restore_button),
                        onSecondaryClick = actions.onImportBackupClick,
                    )
                }
                item {
                    SectionCard(
                        title = stringResource(R.string.backup_csv_title),
                        body = stringResource(R.string.backup_csv_body),
                        accent = accent,
                        busy = state.isBusy,
                        primaryLabel = stringResource(R.string.backup_csv_export_button),
                        onPrimaryClick = actions.onExportCsvClick,
                        secondaryLabel = stringResource(R.string.backup_csv_import_button),
                        onSecondaryClick = actions.onImportCsvClick,
                    )
                }
                state.message?.let { message ->
                    item { MessageBanner(message, actions.onDismissMessage) }
                }
                item { Spacer(Modifier.size(8.dp)) }
            }
        }
        if (state.pendingRestoreConfirm) {
            RestoreConfirmDialog(onConfirm = actions.onConfirmRestore, onDismiss = actions.onCancelRestore)
        }
        state.csvImport?.let { csvImport ->
            CsvImportPreviewOverlay(csvImport, actions)
        }
    }
}

@Composable
private fun AutomaticBackupCard(
    state: AutomaticBackupUiState,
    onCheckedChange: (Boolean) -> Unit,
) {
    AppCard {
        Text(stringResource(R.string.backup_automatic_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.backup_automatic_body), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(10.dp))
        SwitchToggle(
            label = stringResource(R.string.backup_automatic_toggle),
            checked = state.enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.fillMaxWidth(),
        )
        state.detailLine?.let {
            Spacer(Modifier.size(6.dp))
            Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        state.resultLine?.let {
            Spacer(Modifier.size(4.dp))
            Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BackupHeader(onBack: () -> Unit, enabled: Boolean = true) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackAction(onBack, enabled = enabled)
            Text(stringResource(R.string.backup_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(thickness = 1.dp, color = Border)
    }
}

@Composable
private fun SectionCard(
    title: String,
    body: String,
    accent: androidx.compose.ui.graphics.Color,
    busy: Boolean,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String,
    onSecondaryClick: () -> Unit,
) {
    AppCard {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(14.dp))
        SectionButton(primaryLabel, accent, enabled = !busy, onClick = onPrimaryClick)
        Spacer(Modifier.size(8.dp))
        SectionButton(secondaryLabel, accent, enabled = !busy, outlined = true, onClick = onSecondaryClick)
    }
}

@Composable
private fun SectionButton(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    outlined: Boolean = false,
) {
    val modifier = Modifier
        .fillMaxWidth()
            // heightIn(min), not height (A7 font-scale): "RESTORE FROM BACKUP"
            // wraps to two lines at large fontScale instead of overflowing.
        .heightIn(min = 48.dp)
    val content: @Composable () -> Unit = {
        Text(
            label,
            style = DoneButtonLabel.copy(fontSize = 14.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
    if (outlined) {
        OutlinedButton(onClick, modifier, enabled, shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, accent), colors = ButtonDefaults.outlinedButtonColors(
                contentColor = accent, disabledContentColor = accent),
            contentPadding = PaddingValues(vertical = 6.dp), content = { content() })
    } else {
        Button(onClick, modifier, enabled, shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent, contentColor = Background,
                disabledContainerColor = accent, disabledContentColor = Background),
            contentPadding = PaddingValues(vertical = 6.dp), content = { content() })
    }
}

@Composable
private fun MessageBanner(message: StatusMessage, onDismiss: () -> Unit) {
    val color = if (message.isError) Error else Done
    FilledTonalButton(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, color),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = color.copy(alpha = 0.12f), contentColor = color),
        contentPadding = PaddingValues(14.dp),
    ) {
        Text(message.text.resolve(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

// --- restore confirm (destructive: replaces all device data) -----------------

@Composable
private fun RestoreConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
        text = {
            Text(stringResource(R.string.backup_restore_confirm_body))
        },
        confirmButton = { DialogAction(stringResource(R.string.backup_restore_confirm_button), Error, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.backup_restore_cancel_button), TextSecondary, onDismiss) },
    )
}

@Composable
private fun DialogButton(
    label: String,
    fill: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = textColor),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        Text(label, style = DoneButtonLabel.copy(fontSize = 13.sp), textAlign = TextAlign.Center, maxLines = 2)
    }
}

// --- CSV import preview/confirm screen (PLAN.md A2: "never silent guessing") -

@Composable
private fun CsvImportPreviewOverlay(state: CsvImportUiState, actions: BackupActions) {
    var editingName by rememberSaveable { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = actions.onCancelCsvImport,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Background)) {
            Column(readableWidth()) {
                BackupHeader(actions.onCancelCsvImport)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.size(4.dp)) }
                    item { CsvPreviewSummary(state) }
                    if (state.preview.unmatchedNames.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.backup_csv_unmatched_header),
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        items(state.preview.unmatchedNames, key = { it.name }) { unmatched ->
                            UnmatchedNameRow(
                                unmatched = unmatched,
                                selected = state.approvedPatterns[unmatched.name] ?: unmatched.suggestedPattern,
                                onClick = { editingName = unmatched.name },
                            )
                        }
                    }
                    item { Spacer(Modifier.size(8.dp)) }
                }
                CsvImportFooter(canCommit = state.canCommit, actions = actions)
            }
            editingName?.let { name ->
                val current = state.approvedPatterns[name]
                    ?: state.preview.unmatchedNames.first { it.name == name }.suggestedPattern
                PatternPickerSheet(
                    current = current,
                    onPick = { pattern ->
                        actions.onUnmatchedPatternChange(name, pattern)
                        editingName = null
                    },
                    onDismiss = { editingName = null },
                )
            }
        }
    }
}

@Composable
private fun CsvPreviewSummary(state: CsvImportUiState) {
    AppCard {
        Text(stringResource(R.string.backup_csv_preview_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(
                R.string.backup_csv_preview_summary,
                pluralStringResource(R.plurals.backup_csv_preview_sessions, state.sessionCount, state.sessionCount),
                pluralStringResource(R.plurals.backup_csv_preview_sets, state.matchedSetCount, state.matchedSetCount),
            ),
            color = TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.preview.unmatchedNames.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Text(
                pluralStringResource(
                    R.plurals.backup_csv_unmatched_body,
                    state.preview.unmatchedNames.size,
                    state.preview.unmatchedNames.size,
                ),
                color = TextFaint,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UnmatchedNameRow(unmatched: UnmatchedExerciseName, selected: MovementPattern, onClick: () -> Unit) {
    SelectionCard(
        title = unmatched.name,
        subtitle = patternLabel(selected),
        selected = false,
        onClick = onClick,
        mode = SelectionMode.Action,
    )
}

/**
 * The 19 movement patterns, as a sheet rather than a dialog: it is a list to
 * scroll, and the day-edit sheet's pattern picker (`DayEditSheet`) is the same
 * gesture on the same content — down to the 420dp list cap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternPickerSheet(current: MovementPattern, onPick: (MovementPattern) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.backup_csv_pattern_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(10.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(MovementPattern.entries) { pattern ->
                    SelectionCard(
                        title = patternLabel(pattern),
                        selected = pattern == current,
                        onClick = { onPick(pattern) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CsvImportFooter(canCommit: Boolean, actions: BackupActions) {
    Column(Modifier.fillMaxWidth().background(Background)) {
        HorizontalDivider(thickness = 1.dp, color = Border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialogButton(stringResource(R.string.backup_csv_cancel_button), Surface2, TextPrimary, Modifier.weight(1f), actions.onCancelCsvImport)
            Button(
                onClick = actions.onConfirmCsvImport,
                enabled = canCommit,
                modifier = Modifier
                    .weight(2f)
                    .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = Done, contentColor = Background,
                    disabledContainerColor = Surface2, disabledContentColor = TextFaint),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Text(stringResource(R.string.backup_csv_confirm_button), style = DoneButtonLabel.copy(fontSize = 13.sp))
            }
        }
    }
}

/**
 * A friendlier label than the generic enum-name formatting used elsewhere in
 * the app: here the user is guessing a pattern for a name they may not
 * recognize (an imported CSV's exercise name), not picking from a familiar
 * list, so the extra context in each label earns its keep.
 */
@Composable
private fun patternLabel(pattern: MovementPattern): String = stringResource(
    when (pattern) {
        MovementPattern.SQUAT_BILATERAL -> R.string.backup_pattern_squat_bilateral
        MovementPattern.SINGLE_LEG -> R.string.backup_pattern_single_leg
        MovementPattern.HINGE -> R.string.backup_pattern_hinge
        MovementPattern.KNEE_FLEXION -> R.string.backup_pattern_knee_flexion
        MovementPattern.KNEE_EXTENSION -> R.string.backup_pattern_knee_extension
        MovementPattern.H_PUSH -> R.string.backup_pattern_horizontal_push
        MovementPattern.V_PUSH -> R.string.backup_pattern_vertical_push
        MovementPattern.H_PULL -> R.string.backup_pattern_horizontal_pull
        MovementPattern.V_PULL -> R.string.backup_pattern_vertical_pull
        MovementPattern.SIDE_DELT -> R.string.backup_pattern_side_delt
        MovementPattern.REAR_DELT -> R.string.backup_pattern_rear_delt
        MovementPattern.BICEPS -> R.string.backup_pattern_biceps
        MovementPattern.TRICEPS -> R.string.backup_pattern_triceps
        MovementPattern.CALF_GASTROC -> R.string.backup_pattern_calf_gastroc
        MovementPattern.CALF_SOLEUS -> R.string.backup_pattern_calf_soleus
        MovementPattern.CORE_ANTI_EXT -> R.string.backup_pattern_core_anti_extension
        MovementPattern.CORE_ANTI_ROT -> R.string.backup_pattern_core_anti_rotation
        MovementPattern.CORE_FLEX -> R.string.backup_pattern_core_flexion
        MovementPattern.CARDIO -> R.string.backup_pattern_cardio
    },
)

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun BackupScreenPreview() {
    AppTheme {
        BackupScreen(
            state = BackupUiState(),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, heightDp = 1400, backgroundColor = 0xFF0D0D0F)
@Composable
private fun BackupScreenCsvPreviewPreview() {
    val preview = CsvImportPreview(
        sessions = listOf(
            PreviewSession(
                dayTitle = "Push Day",
                completedAt = 0L,
                sets = listOf(
                    PreviewSet("Bench Press", "bench_press", 0, 185.0, 8),
                ),
            ),
        ),
        unmatchedNames = listOf(
            UnmatchedExerciseName("Cable Hack Squat", MovementPattern.SQUAT_BILATERAL),
            UnmatchedExerciseName("Reverse Nordic", MovementPattern.KNEE_EXTENSION),
        ),
    )
    AppTheme {
        BackupScreen(
            state = BackupUiState(csvImport = CsvImportUiState.of(preview)),
            actions = previewActions(),
        )
    }
}

private fun previewActions() = BackupActions(
    onAutomaticBackupChange = {},
    onExportBackupClick = {}, onImportBackupClick = {}, onExportCsvClick = {}, onImportCsvClick = {},
    onConfirmRestore = {}, onCancelRestore = {}, onUnmatchedPatternChange = { _, _ -> },
    onConfirmCsvImport = {}, onCancelCsvImport = {}, onDismissMessage = {}, onBack = {},
)
