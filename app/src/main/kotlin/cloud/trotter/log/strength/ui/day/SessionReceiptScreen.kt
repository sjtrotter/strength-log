package cloud.trotter.log.strength.ui.day

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.accentEmphasis
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The finished workout's opaque receipt, replacing the workout until Done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionReceiptScreen(
    receipt: SessionReceipt,
    onShare: () -> Unit,
    onFinishSession: () -> Unit,
    onSetReceiptNote: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var receiptNoteOpen by rememberSaveable(receipt.sessionId) { mutableStateOf(false) }
    var played by rememberSaveable(receipt.sessionId) { mutableStateOf(false) }
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    // Capture the decision before the effect marks this receipt played. Restore
    // starts at the final frame, including when interrupted during the strike.
    val animate = remember(receipt.sessionId) { !played && !reducedMotion }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(receipt.sessionId) {
        if (!played) {
            played = true
            if (receipt.newTopSets.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    val backProgress = rememberBackGestureProgress(enabled = !receiptNoteOpen, onBack = onFinishSession)
    val date = remember(receipt.completedAt) {
        Instant.ofEpochMilli(receipt.completedAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))
    }
    Scaffold(
        modifier = modifier.fillMaxSize().backGesturePreview { backProgress.value },
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    if (receipt.cardio == null) IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.receipt_share_button))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = ReadableWidth).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.receipt_headline, receipt.dayId),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(date, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val cardio = receipt.cardio
                if (cardio != null) {
                    Text(cardioActivityName(cardio.block.activity), style = MaterialTheme.typography.titleMedium)
                    CardioSummary(cardio.resultSets, cardio.results.sumOf { it.seconds })
                } else {
                    val sets = if (receipt.completedSetCount == receipt.totalSetCount) {
                        pluralStringResource(R.plurals.receipt_sets, receipt.setCount, receipt.setCount)
                    } else {
                        stringResource(R.string.receipt_sets_partial, receipt.completedSetCount, receipt.totalSetCount)
                    }
                    Text(receipt.minutes?.let { stringResource(R.string.receipt_sets_in_minutes, sets, it) } ?: sets,
                        style = MaterialTheme.typography.bodyLarge)
                }
                if (receipt.newTopSets.isNotEmpty()) {
                    Spacer(Modifier.size(16.dp))
                    Text(stringResource(R.string.receipt_new_top_set), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    receipt.newTopSets.forEach { lift ->
                        ReceiptTopSetRow(lift, receipt.dayIndex, animate)
                    }
                }
                receipt.nextDayLine?.let { next ->
                    Spacer(Modifier.size(16.dp))
                    Text(stringResource(R.string.receipt_next), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(next, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.size(16.dp))
                if (cardio == null) {
                    if (receipt.note.isNotBlank()) {
                        Text(receipt.note, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { receiptNoteOpen = true }) {
                        Text(stringResource(if (receipt.note.isBlank()) R.string.note_add_action else R.string.note_edit_action))
                    }
                }
                Button(
                    onClick = onFinishSession,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dayAccent(receipt.dayIndex),
                        contentColor = onDayAccent(receipt.dayIndex),
                    ),
                ) { Text(stringResource(R.string.receipt_done)) }
            }
        }
    }
    if (receiptNoteOpen && receipt.cardio == null) {
        NoteSheet(receipt.note, onSetReceiptNote) { receiptNoteOpen = false }
    }
}

@Composable
private fun ReceiptTopSetRow(lift: CascadeLift, dayIndex: Int, animate: Boolean) {
    val decimalPlaces = maxOf(
        lift.metDisplay.substringAfter('.', "").length,
        lift.newDisplay.substringAfter('.', "").length,
    )
    val countScale = when (decimalPlaces) {
        0 -> 1
        1 -> 10
        else -> 100
    }
    val oldGoal = lift.metDisplay.toDoubleOrNull()?.let { (it * countScale).roundToInt() }
    val newGoal = lift.newDisplay.toDoubleOrNull()?.let { (it * countScale).roundToInt() }
    val strike = remember(lift) { Animatable(if (animate) 0f else 1f) }
    var countTarget by remember(lift) { mutableIntStateOf((if (animate) oldGoal else newGoal) ?: 0) }
    val displayedGoal by animateIntAsState(countTarget, tween(500), label = "receiptGoalCount")
    LaunchedEffect(lift, animate) {
        if (animate) {
            strike.animateTo(1f, tween(300))
            if (newGoal != null) countTarget = newGoal
        }
    }
    val description = stringResource(R.string.receipt_top_set_description, lift.name, lift.newDisplay, lift.metDisplay)
    val strikeColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(lift.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(lift.metDisplay, style = MaterialTheme.typography.bodyLarge, color = strikeColor,
            modifier = Modifier.drawBehind {
                val y = size.height / 2f
                drawLine(strikeColor, Offset(0f, y), Offset(size.width * strike.value, y), strokeWidth = 2.dp.toPx())
            })
        Text(
            if (oldGoal != null && newGoal != null) WeightStepper.format(displayedGoal.toDouble() / countScale) else lift.newDisplay,
            style = MaterialTheme.typography.titleLarge,
            color = accentEmphasis(dayIndex),
        )
    }
}
