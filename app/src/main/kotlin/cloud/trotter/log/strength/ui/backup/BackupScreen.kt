package cloud.trotter.log.strength.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.transfer.csv.CsvImportPreview
import cloud.trotter.log.strength.transfer.csv.PreviewSession
import cloud.trotter.log.strength.transfer.csv.PreviewSet
import cloud.trotter.log.strength.transfer.csv.UnmatchedExerciseName
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.BackAction
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.components.SelectionCard
import cloud.trotter.log.strength.ui.text.resolve
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.readableWidth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Data/Backup screen (PLAN.md A2, D9): full
 * JSON backup export/restore and Strong-compatible CSV history export/import,
 * reachable from Setup. Stateless like the rest of the app's screens — the
 * route (`AppNavHost`) owns the SAF [androidx.activity.result.ActivityResultLauncher]s
 * and forwards the resulting `Uri` straight to [BackupViewModel]; this
 * composable only renders [state] and forwards intent through [actions].
 */
@Composable
fun BackupScreen(state: BackupUiState, actions: BackupActions) {
    // A restore writes Room and then DataStore with no transaction spanning the
    // two, so walking out mid-write is how they end up disagreeing. The write
    // itself is app-scoped and survives, but the screen still refuses to leave
    // while it runs rather than reporting to nobody. Restore only: an export or
    // a CSV import has nothing to tear, and gating back on every operation
    // would just make the screen feel stuck.
    BackHandler(enabled = state.restoreInFlight) {}

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            BackupHeader(actions.onBack, enabled = !state.restoreInFlight)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                item { AutomaticBackupCard(state.automatic, enabled = state.operation != BackupOperation.Restore && !state.automaticChangeInFlight,
                    onCheckedChange = actions.onAutomaticBackupChange) }
                item {
                    SectionCard(
                        title = stringResource(R.string.backup_full_title),
                        body = stringResource(R.string.backup_full_body),
                    ) {
                        Button(
                            onClick = actions.onExportBackupClick,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.backup_export_button))
                        }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(
                            onClick = actions.onImportBackupClick,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.backup_restore_button))
                        }
                        when (state.operation) {
                            BackupOperation.ExportBackup -> LoadingState(stringResource(R.string.operation_export_backup))
                            BackupOperation.Restore -> LoadingState(stringResource(R.string.operation_restore_backup))
                            else -> Unit
                        }
                    }
                }
                item {
                    SectionCard(
                        title = stringResource(R.string.backup_csv_title),
                        body = stringResource(R.string.backup_csv_body),
                    ) {
                        OutlinedButton(
                            onClick = actions.onExportCsvClick,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.backup_csv_export_button))
                        }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(
                            onClick = actions.onImportCsvClick,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.backup_csv_import_button))
                        }
                        when (state.operation) {
                            BackupOperation.ExportCsv -> LoadingState(stringResource(R.string.operation_export_csv))
                            BackupOperation.ImportCsv -> LoadingState(stringResource(R.string.operation_import_csv))
                            else -> Unit
                        }
                    }
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
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    AppCard {
        Text(stringResource(R.string.backup_automatic_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.backup_automatic_body), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(10.dp))
        val toggleLabel = stringResource(R.string.backup_automatic_toggle)
        ListItem(
            headlineContent = { Text(toggleLabel) },
            trailingContent = { Switch(checked = state.enabled, onCheckedChange = null, enabled = enabled) },
            modifier = Modifier.fillMaxWidth().toggleable(value = state.enabled, enabled = enabled,
                role = Role.Switch, onValueChange = onCheckedChange),
        )

        state.detailLine?.let {
            Spacer(Modifier.size(6.dp))
            Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.lastAttemptFailed) {
            Text(stringResource(R.string.operation_automatic_backup_failed), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        }
        if (state.permissionLost) {
            Text(stringResource(R.string.operation_backup_folder_unavailable), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onCheckedChange(true) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.operation_choose_folder))
            }
        }
        if (state.enabled || state.lastAttemptFailed || state.permissionLost || state.lastSuccessAtMillis != null) {
            val date = state.lastSuccessAtMillis?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US))
            }
            Text(if (date == null) stringResource(R.string.operation_backup_no_success)
                else stringResource(R.string.operation_backup_last_success, date),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(14.dp))
        content()
    }
}

@Composable
private fun MessageBanner(message: StatusMessage, onDismiss: () -> Unit) {
    TextButton(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
    ) {
        if (!message.isError) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Done)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            message.text.resolve(),
            color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
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
                                confirmed = unmatched.name in state.approvedPatterns,
                                onClick = { editingName = unmatched.name },
                                onConfirm = { actions.onUnmatchedPatternChange(unmatched.name, unmatched.suggestedPattern) },
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
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UnmatchedNameRow(
    unmatched: UnmatchedExerciseName,
    selected: MovementPattern,
    confirmed: Boolean,
    onClick: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column {
        ListItem(
            headlineContent = { Text(unmatched.name) },
            supportingContent = { Text(patternLabel(selected)) },
            trailingContent = {
                if (confirmed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Text(stringResource(R.string.operation_csv_confirmed))
                } else TextButton(onClick = onConfirm) { Text(stringResource(R.string.operation_csv_confirm)) }
            },
        )
        TextButton(onClick = onClick) { Text(stringResource(R.string.operation_csv_change_movement)) }
    }
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
            OutlinedButton(
                onClick = actions.onCancelCsvImport,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Text(stringResource(R.string.backup_csv_cancel_button), style = DoneButtonLabel.copy(fontSize = 13.sp), textAlign = TextAlign.Center, maxLines = 2)
            }
            Button(
                onClick = actions.onConfirmCsvImport,
                enabled = canCommit,
                modifier = Modifier
                    .weight(2f)
                    .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Text(stringResource(R.string.backup_csv_confirm_button), style = DoneButtonLabel.copy(fontSize = 13.sp))
            }
        }
        if (!canCommit) Text(stringResource(R.string.operation_csv_confirmation_required),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
