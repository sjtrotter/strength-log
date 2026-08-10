package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.StepperValue
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.accentBright

/**
 * The cascade's moment (docs/briefs/journal.md §2): a scrim over the day screen
 * with the number that was standing struck through and the number that replaced
 * it. One haptic on entry, tap or back to dismiss. No confetti, no particles —
 * the strike and the new number are the whole event.
 *
 * Every lift that cascaded on the same DONE stacks as a line here; there is
 * never a second scrim.
 */
@Composable
internal fun CascadeScrim(
    ceremony: CascadeCeremony,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(ceremony) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }

    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.94f))
            .clickable(interaction, indication = null, onClickLabel = stringResource(R.string.cascade_dismiss_action), onClick = onDismiss)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The scrim covers the window; the lines inside it do not. A long lift
        // name is centre-aligned text, and uncapped it would set itself across a
        // whole tablet — the same cap the day screen behind uses keeps the
        // strike and the new number reading as one block.
        Column(
            modifier = Modifier.widthIn(max = ReadableWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ceremony.lifts.forEach { lift ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        lift.name.uppercase(),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        lift.metDisplay,
                        color = TextSecondary,
                        style = StepperValue.copy(textDecoration = TextDecoration.LineThrough),
                    )
                    Text(lift.newDisplay, color = accentBright(ceremony.dayIndex), style = DisplayXl)
                }
            }
            Text(stringResource(R.string.cascade_new_ramp_label), color = TextFaint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Preview(showBackground = true, heightDp = 640, backgroundColor = 0xFF0D0D0F)
@Composable
private fun CascadeScrimPreview() {
    AppTheme {
        CascadeScrim(
            ceremony = CascadeCeremony(
                dayIndex = 0,
                lifts = listOf(CascadeLift("Barbell Back Squat", "235", "245")),
            ),
            onDismiss = {},
        )
    }
}
