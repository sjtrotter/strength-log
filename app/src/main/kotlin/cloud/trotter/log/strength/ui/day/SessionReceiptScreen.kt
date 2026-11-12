package cloud.trotter.log.strength.ui.day

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.ui.day.receipt.ReceiptLines
import cloud.trotter.log.strength.ui.day.receipt.ReceiptMutation
import cloud.trotter.log.strength.ui.day.receipt.ReceiptProgressState
import cloud.trotter.log.strength.ui.day.receipt.spokenCardioNextLine
import cloud.trotter.log.strength.ui.day.receipt.cardioNextLine
import cloud.trotter.log.strength.ui.day.receipt.cardioWhyLine
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.components.distanceUnitFor
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
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSetCounts
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
    setCounts: ReceiptSetCounts? = null,
    progress: ReceiptProgressState = ReceiptProgressState(),
    mutation: ReceiptMutation? = null,
    onMutationHandled: () -> Unit = {},
    onUndo: () -> Unit = {},
    onRoughDay: (Boolean) -> Unit = {},
    onCardioEffort: (CardioEffort?) -> Unit = {},
) {
    val counts = setCounts?.takeIf { !receipt.cardio && it.sessionId == receipt.sessionId }
    val resources = LocalContext.current.resources
    val snackbar = remember { SnackbarHostState() }
    var whyOpen by rememberSaveable(receipt.sessionId) { mutableStateOf(false) }
    LaunchedEffect(mutation) {
        if (mutation != null) {
            snackbar.showSnackbar(when (mutation) {
                ReceiptMutation.Undone -> resources.getString(R.string.receipt_changes_undone)
                is ReceiptMutation.DayOpen -> resources.getString(R.string.receipt_undo_day_open, mutation.dayId)
                ReceiptMutation.Failed -> resources.getString(R.string.receipt_undo_failed)
                is ReceiptMutation.RoughDayOpen -> resources.getString(R.string.receipt_rough_day_day_open, mutation.dayId)
                ReceiptMutation.RoughDayFailed -> resources.getString(R.string.receipt_rough_day_failed)
                ReceiptMutation.EffortFailed -> resources.getString(R.string.receipt_effort_failed)
            })
            onMutationHandled()
        }
    }
    val partial = counts?.let { it.remaining > 0 } ?: receipt.isPartial
    var noteOpen by rememberSaveable(receipt.sessionId) { mutableStateOf(false) }
    val noteDrafts = rememberSaveableStateHolder()
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
    val backProgress = rememberBackGestureProgress(enabled = !noteOpen && !whyOpen, onBack = onFinishSession)
    ReceiptScaffold(
        title = stringResource(if (partial) R.string.operation_receipt_saved else R.string.receipt_title_done, receipt.dayTitle),
        planLine = receipt.planName,
        metaLine = receiptMeta(receipt.completedAt, receipt.minutes),
        showShare = !receipt.cardio,
        shareEnabled = true,
        onShare = onShare,
        modifier = modifier.backGesturePreview { backProgress.value },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (receipt.allPlansDone && !partial) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp).heightIn(min = 40.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Done)
                    Text(stringResource(R.string.receipt_all_plans_done), style = MaterialTheme.typography.bodyLarge)
                }
            }
            ReceiptDoneButton(receipt.dayIndex, onFinishSession)
        },
    ) {
        if (partial) Text(if (counts != null || receipt.hasPlannedSetCount) {
                stringResource(R.string.operation_receipt_partial_count, receipt.loggedSetCount, counts?.total ?: receipt.plannedSetCount)
            } else stringResource(R.string.operation_receipt_plan_count_missing, receipt.loggedSetCount),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        if (!receipt.recordsChecked) Text(stringResource(R.string.receipt_records_not_checked),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        if (receipt.groups.isNotEmpty()) {
            receipt.groups.forEachIndexed { groupIndex, group ->
                val rows: @Composable ColumnScope.() -> Unit = {
                    group.rows.forEachIndexed { rowIndex, row ->
                        key(receipt.sessionId, groupIndex, rowIndex) {
                            StrengthReceiptRow(if (receipt.recordsChecked) row else row.copy(record = null), receipt.unit, receipt.dayIndex,
                                "${receipt.sessionId}:$groupIndex:$rowIndex", { roll.value },
                                if (group.rounds != null) stringResource(R.string.receipt_superset_gutter, rowIndex + 1) else null,
                                nextTime = progress.lifts.firstOrNull { it.ref.groupIndex == groupIndex && it.ref.rowIndex == rowIndex }?.line)
                        }
                    }
                }
                if (group.rounds == null) rows() else SupersetGroup(group.rounds, rows)
            }
        }
        if (receipt.cardioRows.isNotEmpty()) {
            receipt.activity?.let {
                Text(cardioActivityName(it), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
            }
            receipt.cardioRows.forEachIndexed { index, row ->
                key(receipt.sessionId, index) {
                    CardioReceiptRowItem(if (receipt.recordsChecked) row else row.copy(record = null), receipt.dayIndex,
                        "${receipt.sessionId}:cardio:$index", { roll.value }, distanceUnitFor(receipt.activity),
                        progress.cardioSets.getOrNull(index).orEmpty())
                }
            }
        }
        if (receipt.cardioRows.isNotEmpty() && progress.cardioSessionId != null) {
            CardioEffortRow(progress.cardioEffort, onCardioEffort, "receipt-cardio-effort")
            val spokenNext = spokenCardioNextLine(progress).orEmpty()
            cardioNextLine(progress)?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).clearAndSetSemantics { contentDescription = spokenNext }) }
        }
        if (progress.lightWeekNext) Text(stringResource(R.string.receipt_light_week_next),
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(Modifier.padding(horizontal = 4.dp)) {
            if (progress.undoVisible) TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.receipt_undo_changes))
            }
            if (progress.whyVisible) TextButton(onClick = { whyOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.receipt_why))
            }
        }
        if (!receipt.cardio && progress.lifts.isNotEmpty()) ListItem(
            headlineContent = { Text(stringResource(R.string.receipt_rough_day)) },
            supportingContent = { Text(stringResource(R.string.receipt_rough_day_detail)) },
            leadingContent = { Checkbox(progress.rough, onCheckedChange = null, enabled = progress.roughEnabled) },
            modifier = Modifier.toggleable(progress.rough, enabled = progress.roughEnabled,
                role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onRoughDay),
        )
        if (receipt.note.isNotBlank()) Text(receipt.note, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp))
        if (!receipt.cardio) {
            TextButton(onClick = { noteOpen = true }, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp)) {
                Text(stringResource(if (receipt.note.isBlank()) R.string.note_add_action else R.string.note_edit_action),
                    color = accentEmphasis(receipt.dayIndex))
            }
        }
    }
    if (whyOpen) ReceiptWhySheet(receipt, progress) { whyOpen = false }
    // A closed sheet consumes the result, including one that lands after it closed, so the next
    // sheet never opens on a stale Saved or Failed.
    LaunchedEffect(noteOpen, noteSave) {
        if (!noteOpen && noteSave != null) {
            if (noteSave is NoteSave.Saved) noteDrafts.removeState(receipt.sessionId)
            onNoteSaveHandled()
        }
    }
    if (noteOpen && !receipt.cardio) {
        noteDrafts.SaveableStateProvider(receipt.sessionId) {
            NoteSheet(initialText = receipt.note, onSave = onSetNote, save = noteSave, onDismiss = { noteOpen = false })
        }
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
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = snackbarHost,
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

@Composable
internal fun CardioEffortRow(selected: CardioEffort?, onPick: (CardioEffort?) -> Unit, tagPrefix: String) {
    EffortRow(selected?.let { Effort.valueOf(it.name) }, { onPick(it?.let { word -> CardioEffort.valueOf(word.name) }) },
        tagPrefix, entries = listOf(Effort.EASY, Effort.GOOD, Effort.HARD), label = stringResource(R.string.cardio_how_was_it))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptWhySheet(receipt: WorkoutReceipt, progress: ReceiptProgressState, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    AppModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = { Text(stringResource(R.string.receipt_why), style = MaterialTheme.typography.titleLarge) },
        body = {
            if (progress.rough) Text(stringResource(R.string.receipt_why_rough))
            else progress.lifts.forEach { lift ->
                lift.whyLine?.let { ReceiptLines.why(lift.name, it, receipt.unit, resources) }?.let { Text(it) }
            }
            if (progress.roughStartedLight) Text(stringResource(R.string.receipt_why_rough_light))
            else if (progress.lightRemaining > 0) Text(resources.getQuantityString(R.plurals.receipt_why_light_remaining,
                progress.lightRemaining, progress.lightRemaining))
            else if (progress.lightEnded) Text(stringResource(R.string.receipt_why_light_done))
            cardioWhyLine(progress, receipt.activity?.let { cardioActivityName(it) }.orEmpty())?.let { Text(it) }
        })
}
