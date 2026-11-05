package cloud.trotter.log.strength.ui.day

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.day.receipt.CardioReceiptRowItem
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.StrengthReceiptRow
import cloud.trotter.log.strength.ui.day.receipt.SupersetGroup
import cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt
import cloud.trotter.log.strength.ui.day.receipt.receiptMeta
import cloud.trotter.log.strength.ui.day.receipt.hasPlannedSetCount
import cloud.trotter.log.strength.ui.day.receipt.isPartial
import cloud.trotter.log.strength.ui.day.receipt.loggedSetCount
import cloud.trotter.log.strength.ui.day.receipt.plannedSetCount
import cloud.trotter.log.strength.ui.emphasizedDecelerate
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.accentEmphasis
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import kotlinx.coroutines.delay

@Composable
internal fun SessionReceiptScreen(
    receipt: WorkoutReceipt,
    onShare: () -> Unit,
    onFinishSession: () -> Unit,
    onSetNote: (String) -> Unit = {},
    noteSave: NoteSave? = null,
    onNoteSaveHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var noteOpen by rememberSaveable(receipt.sessionId) { mutableStateOf(false) }
    var played by rememberSaveable(receipt.sessionId) { mutableStateOf(false) }
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val animate = remember(receipt.sessionId) { !played && !reducedMotion }
    val roll = remember(receipt.sessionId) { Animatable(if (animate) 1f else 0f) }
    val hasRecords = receipt.recordsChecked && (receipt.groups.any { group -> group.rows.any { it.record != null } } ||
        receipt.cardioRows.any { it.record != null })
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(receipt.sessionId) {
        if (!played) {
            played = true
            if (hasRecords) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        if (animate && hasRecords) {
            delay(300)
            roll.animateTo(0f, tween(400, easing = emphasizedDecelerate))
        }
    }
    val backProgress = rememberBackGestureProgress(enabled = !noteOpen, onBack = onFinishSession)
    ReceiptScaffold(
        title = stringResource(if (receipt.isPartial) R.string.operation_receipt_saved else R.string.receipt_title_done, receipt.dayTitle),
        planLine = receipt.planName,
        metaLine = receiptMeta(receipt.completedAt, receipt.minutes),
        showShare = !receipt.cardio,
        shareEnabled = true,
        onShare = onShare,
        modifier = modifier.backGesturePreview { backProgress.value },
        bottomBar = {
            if (receipt.allPlansDone && !receipt.isPartial) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp).heightIn(min = 40.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Done)
                    Text(stringResource(R.string.receipt_all_plans_done), style = MaterialTheme.typography.bodyLarge)
                }
            }
            ReceiptDoneButton(receipt.dayIndex, onFinishSession)
        },
    ) {
        if (receipt.isPartial) Text(if (receipt.hasPlannedSetCount) {
                stringResource(R.string.operation_receipt_partial_count, receipt.loggedSetCount, receipt.plannedSetCount)
            } else stringResource(R.string.operation_receipt_plan_count_missing, receipt.loggedSetCount),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        if (!receipt.recordsChecked) Text(stringResource(R.string.receipt_records_not_checked),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        if (receipt.cardio) {
            receipt.activity?.let {
                Text(cardioActivityName(it), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
            }
            receipt.cardioRows.forEachIndexed { index, row ->
                key(receipt.sessionId, index) {
                    CardioReceiptRowItem(if (receipt.recordsChecked) row else row.copy(record = null), receipt.dayIndex,
                        "${receipt.sessionId}:cardio:$index", { roll.value })
                }
            }
        } else {
            receipt.groups.forEachIndexed { groupIndex, group ->
                val rows: @Composable ColumnScope.() -> Unit = {
                    group.rows.forEachIndexed { rowIndex, row ->
                        key(receipt.sessionId, groupIndex, rowIndex) {
                            StrengthReceiptRow(if (receipt.recordsChecked) row else row.copy(record = null), receipt.unit, receipt.dayIndex,
                                "${receipt.sessionId}:$groupIndex:$rowIndex", { roll.value },
                                if (group.rounds != null) stringResource(R.string.receipt_superset_gutter, rowIndex + 1) else null)
                        }
                    }
                }
                if (group.rounds == null) rows() else SupersetGroup(group.rounds, rows)
            }
            if (receipt.note.isNotBlank()) Text(receipt.note, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { noteOpen = true }, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp)) {
                Text(stringResource(if (receipt.note.isBlank()) R.string.note_add_action else R.string.note_edit_action),
                    color = accentEmphasis(receipt.dayIndex))
            }
        }
    }
    // A closed sheet consumes the result, including one that lands after it closed, so the next
    // sheet never opens on a stale Saved or Failed.
    LaunchedEffect(noteOpen, noteSave) { if (!noteOpen && noteSave != null) onNoteSaveHandled() }
    if (noteOpen && !receipt.cardio) {
        NoteSheet(initialText = receipt.note, onSave = onSetNote, save = noteSave, onDismiss = { noteOpen = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptScaffold(
    title: String,
    planLine: String,
    metaLine: String,
    showShare: Boolean,
    shareEnabled: Boolean,
    onShare: () -> Unit,
    bottomBar: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = {}, actions = {
                if (showShare) IconButton(onClick = onShare, enabled = shareEnabled) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.receipt_share_button))
                }
            })
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.navigationBarsPadding()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.widthIn(max = ReadableWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        content = bottomBar)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = ReadableWidth).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(planLine, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                    Text(metaLine, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
        }
    }
}

@Composable
internal fun ReceiptDoneButton(dayIndex: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = dayAccent(dayIndex), contentColor = onDayAccent(dayIndex))) {
        Text(stringResource(R.string.receipt_done))
    }
}
