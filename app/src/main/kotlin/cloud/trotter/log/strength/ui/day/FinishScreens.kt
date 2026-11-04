package cloud.trotter.log.strength.ui.day

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.LOADING_REVEAL_DELAY_MS
import cloud.trotter.log.strength.ui.components.backGesturePreview
import cloud.trotter.log.strength.ui.components.rememberBackGestureProgress
import cloud.trotter.log.strength.ui.day.receipt.FinishAttempt
import cloud.trotter.log.strength.ui.day.receipt.GhostRow
import cloud.trotter.log.strength.ui.day.receipt.SkippedReceiptRow
import cloud.trotter.log.strength.ui.day.receipt.SupersetGroup
import cloud.trotter.log.strength.ui.day.receipt.receiptMeta
import cloud.trotter.log.strength.ui.theme.accentEmphasis
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun FinishingScreen(attempt: FinishAttempt, state: DayUiState, modifier: Modifier = Modifier) {
    BackHandler(enabled = true) {}
    var revealed by remember(attempt.runId) { mutableStateOf(false) }
    LaunchedEffect(attempt.runId) {
        delay(LOADING_REVEAL_DELAY_MS)
        revealed = true
    }
    val announcement = stringResource(R.string.receipt_finishing)
    ReceiptScaffold(
        title = attempt.dayTitle,
        planLine = attempt.planName,
        metaLine = receiptMeta(attempt.tappedAt, attempt.minutes()),
        showShare = true,
        shareEnabled = false,
        onShare = {},
        modifier = modifier,
        bottomBar = { ReceiptDoneButton(attempt.dayIndex, {}, enabled = false) },
    ) {
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
        if (revealed && !state.loading) {
            state.exercises.forEachIndexed { index, card ->
                if (card.isSuperset) {
                    SupersetGroup(card.rows.size) {
                        GhostRow(card.title, index, stringResource(R.string.receipt_superset_gutter, 1))
                        card.partnerTitle?.let { GhostRow(it, index, stringResource(R.string.receipt_superset_gutter, 2)) }
                    }
                } else GhostRow(card.title, index)
            }
            state.skippedLifts.forEach { SkippedReceiptRow(it.title) }
        }
    }
}

@Composable
internal fun FinishFailedScreen(attempt: FinishAttempt, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val backProgress = rememberBackGestureProgress(onBack = onBack)
    ReceiptScaffold(
        title = attempt.dayTitle,
        planLine = attempt.planName,
        metaLine = receiptMeta(attempt.tappedAt, attempt.minutes()),
        showShare = false,
        shareEnabled = false,
        onShare = {},
        modifier = modifier.backGesturePreview { backProgress.value },
        bottomBar = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accentEmphasis(attempt.dayIndex))) {
                    Text(stringResource(R.string.receipt_back_to_workout))
                }
                Button(onClick = onRetry, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = dayAccent(attempt.dayIndex), contentColor = onDayAccent(attempt.dayIndex))) {
                    Text(stringResource(R.string.receipt_try_again))
                }
            }
        },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_receipt_error), contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column {
                Text(stringResource(R.string.receipt_finish_failed_title), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.receipt_finish_failed_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun FinishAttempt.minutes(): Int? = startedAt?.let { ((tappedAt - it) / 60_000.0).roundToInt().coerceAtLeast(1) }
