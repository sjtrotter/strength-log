package io.github.sjtrotter.strengthlog.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.transfer.csv.CsvImportPreview
import io.github.sjtrotter.strengthlog.transfer.csv.PreviewSession
import io.github.sjtrotter.strengthlog.transfer.csv.PreviewSet
import io.github.sjtrotter.strengthlog.transfer.csv.UnmatchedExerciseName
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.SelectionCard
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Done
import io.github.sjtrotter.strengthlog.ui.theme.DoneButtonLabel
import io.github.sjtrotter.strengthlog.ui.theme.Error
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TabLetter
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent

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

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            BackupHeader(actions.onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                item {
                    SectionCard(
                        title = "Full backup",
                        body = "Everything you own — program, logs, custom exercises, and history — as one JSON file.",
                        accent = accent,
                        busy = state.isBusy,
                        primaryLabel = "EXPORT BACKUP",
                        onPrimaryClick = actions.onExportBackupClick,
                        secondaryLabel = "RESTORE FROM BACKUP",
                        onSecondaryClick = actions.onImportBackupClick,
                    )
                }
                item {
                    SectionCard(
                        title = "CSV history",
                        body = "Strong-compatible export/import for your workout history — spreadsheets, Hevy, FitNotes.",
                        accent = accent,
                        busy = state.isBusy,
                        primaryLabel = "EXPORT CSV",
                        onPrimaryClick = actions.onExportCsvClick,
                        secondaryLabel = "IMPORT CSV",
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
private fun BackupHeader(onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackChevron(onBack)
            Text("DATA / BACKUP", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun BackChevron(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Back", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = TextSecondary, style = TabLetter.copy(fontSize = 20.sp), modifier = Modifier.clearAndSetSemantics {})
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
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn(min), not height (A7 font-scale): "RESTORE FROM BACKUP"
            // wraps to two lines at large fontScale instead of overflowing.
            .heightIn(min = 48.dp)
            .then(
                if (outlined) {
                    Modifier.border(1.dp, accent, shape)
                } else {
                    Modifier.background(accent, shape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (outlined) accent else Background,
            style = DoneButtonLabel.copy(fontSize = 14.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun MessageBanner(message: StatusMessage, onDismiss: () -> Unit) {
    val color = if (message.isError) Error else Done
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .clickable(onClick = onDismiss)
            .padding(14.dp),
    ) {
        Text(message.text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

// --- restore confirm (destructive: replaces all device data) -----------------

@Composable
private fun RestoreConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.padding(24.dp).clickable(enabled = false) {}) {
            AppCard {
                Text("Restore this backup?", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Text(
                    "This replaces everything on this device — program, logs, custom exercises, and history — " +
                        "with what's in the file. This can't be undone.",
                    color = TextFaint,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogButton("CANCEL", Surface2, TextPrimary, Modifier.weight(1f), onDismiss)
                    DialogButton("REPLACE ALL DATA", Error, TextPrimary, Modifier.weight(1f), onConfirm)
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    fill: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(fill, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = DoneButtonLabel.copy(fontSize = 13.sp), textAlign = TextAlign.Center, maxLines = 2)
    }
}

// --- CSV import preview/confirm screen (PLAN.md A2: "never silent guessing") -

@Composable
private fun CsvImportPreviewOverlay(state: CsvImportUiState, actions: BackupActions) {
    var editingName by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
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
                            "UNMATCHED EXERCISES — CONFIRM A PATTERN",
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
            PatternPickerOverlay(
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

@Composable
private fun CsvPreviewSummary(state: CsvImportUiState) {
    AppCard {
        Text("PREVIEW", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Text(
            "${state.sessionCount} session(s), ${state.matchedSetCount} set(s) will be added to your history.",
            color = TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.preview.unmatchedNames.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Text(
                "${state.preview.unmatchedNames.size} exercise name(s) below don't match your library — " +
                    "confirm (or change) the movement pattern for each so a custom exercise can be created.",
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
    )
}

@Composable
private fun PatternPickerOverlay(current: MovementPattern, onPick: (MovementPattern) -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.9f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.padding(24.dp).clickable(enabled = false) {}) {
            AppCard {
                Text("Movement pattern", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(10.dp))
                LazyColumn(
                    modifier = Modifier.height(360.dp),
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
}

@Composable
private fun CsvImportFooter(canCommit: Boolean, actions: BackupActions) {
    Column(Modifier.fillMaxWidth().background(Background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialogButton("CANCEL", Surface2, TextPrimary, Modifier.weight(1f), actions.onCancelCsvImport)
            Box(
                modifier = Modifier
                    .weight(2f)
                    .height(48.dp)
                    .background(if (canCommit) Done else Surface2, RoundedCornerShape(12.dp))
                    .clickable(enabled = canCommit, onClick = actions.onConfirmCsvImport),
                contentAlignment = Alignment.Center,
            ) {
                Text("IMPORT", color = if (canCommit) Background else TextFaint, style = DoneButtonLabel.copy(fontSize = 13.sp))
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
private fun patternLabel(pattern: MovementPattern): String = when (pattern) {
    MovementPattern.SQUAT_BILATERAL -> "Squat (bilateral)"
    MovementPattern.SINGLE_LEG -> "Single-leg"
    MovementPattern.HINGE -> "Hinge"
    MovementPattern.KNEE_FLEXION -> "Knee flexion (leg curl)"
    MovementPattern.KNEE_EXTENSION -> "Knee extension (leg extension)"
    MovementPattern.H_PUSH -> "Horizontal push"
    MovementPattern.V_PUSH -> "Vertical push"
    MovementPattern.H_PULL -> "Horizontal pull"
    MovementPattern.V_PULL -> "Vertical pull"
    MovementPattern.SIDE_DELT -> "Side delt"
    MovementPattern.REAR_DELT -> "Rear delt"
    MovementPattern.BICEPS -> "Biceps"
    MovementPattern.TRICEPS -> "Triceps"
    MovementPattern.CALF_GASTROC -> "Calf (gastroc)"
    MovementPattern.CALF_SOLEUS -> "Calf (soleus)"
    MovementPattern.CORE_ANTI_EXT -> "Core (anti-extension)"
    MovementPattern.CORE_ANTI_ROT -> "Core (anti-rotation)"
    MovementPattern.CORE_FLEX -> "Core (flexion)"
    MovementPattern.CARDIO -> "Cardio"
}

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
    onExportBackupClick = {}, onImportBackupClick = {}, onExportCsvClick = {}, onImportCsvClick = {},
    onConfirmRestore = {}, onCancelRestore = {}, onUnmatchedPatternChange = { _, _ -> },
    onConfirmCsvImport = {}, onCancelCsvImport = {}, onDismissMessage = {}, onBack = {},
)
