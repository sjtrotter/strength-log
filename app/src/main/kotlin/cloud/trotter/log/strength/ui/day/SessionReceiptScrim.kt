package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.StepperValue
import cloud.trotter.log.strength.ui.theme.SummaryLine
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The session receipt (#126): the last thing a finished workout says. A ledger,
 * not a trophy — the day's own accent on the headline, hairlines between three
 * facts (what was done, the heaviest thing in it, where the rotation now
 * stands), and two ways out. No confetti, no streaks, no medal; the app spends
 * its one celebration on [CascadeScrim], and that scrim renders *above* this
 * one when a lift actually moved.
 *
 * Modal on purpose: the day screen underneath has already advanced to the next
 * day, so nothing behind this surface is still the workout that just ended and
 * none of it should be reachable. Unlike the cascade there is no tap-anywhere
 * dismiss — SHARE sits on this surface, and a stray tap that closed it would be
 * a stray tap that ate the share.
 */
@Composable
internal fun SessionReceiptScrim(
    receipt: SessionReceipt,
    onShare: () -> Unit,
    onFinish: () -> Unit,
    onEditNote: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = dayAccent(receipt.dayIndex)
    val headlineScale = remember(receipt.sessionId) { Animatable(0.92f) }
    val ledgerRows = 1 + (if (receipt.strongest != null) 1 else 0) + (if (receipt.nextDayLine != null) 1 else 0)
    val rowProgress = remember(receipt.sessionId, ledgerRows) {
        List(ledgerRows) { Animatable(0f) }
    }
    LaunchedEffect(receipt.sessionId) {
        launch {
            headlineScale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
        rowProgress.forEachIndexed { index, progress ->
            launch {
                delay(index * 60L)
                progress.animateTo(1f, tween(180))
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.97f))
            // Makes this surface the hit target for anything that lands on it,
            // so the advanced day behind stays untouchable. A no-op `clickable`
            // would do the same, but it would also merge the whole receipt into
            // one nameless clickable node for TalkBack — and for anything else
            // reading the semantics tree. The events are not consumed: the
            // receipt's own buttons sit inside this box and must still get them.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            }
            .systemBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Coverage and readability are two different jobs. The scrim above has
        // to be the whole window — it is hiding one — but the ledger inside it
        // is a column of text like any other, so it takes the same cap the day
        // screen behind it does. Without this the receipt is the one surface
        // that goes full-bleed on a tablet, and it arrives the instant DONE
        // fires, right after that screen capped itself.
        Column(Modifier.widthIn(max = ReadableWidth).fillMaxWidth()) {
            Text(
                text = receipt.headline,
                color = accent,
                style = DisplayXl,
                maxLines = 2,
                modifier = Modifier.graphicsLayer {
                    scaleX = headlineScale.value
                    scaleY = headlineScale.value
                },
            )
            if (receipt.dayTitle.isNotBlank()) {
                Text(
                    text = receipt.dayTitle,
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(Modifier.size(24.dp))
            ReceiptRule()
            LedgerEntrance(rowProgress[0]) {
                ReceiptRow(label = stringResource(R.string.receipt_sets_label), value = receipt.setCount.toString())
            }
            var rowIndex = 1
            receipt.strongest?.let { lift ->
                ReceiptRule()
                LedgerEntrance(rowProgress[rowIndex++]) {
                    Column {
                        ReceiptRow(label = stringResource(R.string.receipt_strongest_label), value = lift.value)
                        Text(
                            text = lift.name.uppercase(),
                            color = TextFaint,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        )
                    }
                }
            }
            receipt.nextDayLine?.let { next ->
                ReceiptRule()
                LedgerEntrance(rowProgress[rowIndex]) {
                    ReceiptRow(label = stringResource(R.string.receipt_next_label), value = next, valueColor = TextSecondary, big = false)
                }
            }
            ReceiptRule()

            if (receipt.note.isNotBlank()) {
                Text(
                    receipt.note,
                    color = TextFaint,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            TextButton(onClick = onEditNote, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.note_add_action), style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.size(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ReceiptButtonLabel(stringResource(R.string.receipt_share_button))
                }
                Button(
                    onClick = onFinish,
                    modifier = Modifier.weight(1.6f),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = onDayAccent(receipt.dayIndex),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ReceiptButtonLabel(stringResource(R.string.receipt_back_to_today_button))
                }
            }
        }
    }
}

@Composable
private fun LedgerEntrance(progress: Animatable<Float, *>, content: @Composable () -> Unit) {
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        },
    ) {
        content()
    }
}

/** One ledger line: what it is on the left, what it was on the right. */
@Composable
private fun ReceiptRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    big: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = TextFaint, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value,
            color = valueColor,
            style = if (big) StepperValue else SummaryLine,
            textAlign = TextAlign.End,
        )
    }
}

/** The receipt's own hairline — the same 1dp [Border] rule the day screen's
 *  chrome uses, doing the separating a card would otherwise be hired for. */
@Composable
private fun ReceiptRule() {
    HorizontalDivider(thickness = 1.dp, color = Border)
}

@Composable
private fun ReceiptButtonLabel(label: String) {
    Text(
        text = label,
        style = DoneButtonLabel,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.heightIn(min = 36.dp),
    )
}

@Preview(showBackground = true, heightDp = 720, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SessionReceiptScrimPreview() {
    AppTheme {
        SessionReceiptScrim(
            receipt = SessionReceipt(
                sessionId = 1,
                dayIndex = 0,
                headline = "DAY A COMPLETE",
                dayTitle = "Lower — Squat",
                completedSetCount = 18,
                totalSetCount = 18,
                setCount = 18,
                strongest = ReceiptLift("Barbell Back Squat", "245×5"),
                nextDayLine = "DAY B · UPPER",
            ),
            onShare = {},
            onFinish = {},
        )
    }
}
