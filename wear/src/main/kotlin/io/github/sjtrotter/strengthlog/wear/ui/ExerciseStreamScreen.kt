package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.Condensed
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.TextTertiary
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent

/**
 * Exercise stream (design digest §1.2): one round at a time, scoped to this
 * exercise only. The progress ring, back button and tick button are shared
 * between the main-lift and superset layouts; only the center content and its
 * numeral sizes differ.
 *
 * Rotary crown -> weight: [onRotaryScrollEvent] accumulates raw scroll pixels
 * via [RotaryAccumulator] into whole detents, then feeds the *signed detent
 * count* to [onWeightScroll] in one shot. This is deliberately not a loop over
 * [onWeightStep]: a single-step edit computes an absolute target from the
 * composition-captured weight, so N synchronous single-steps would all target
 * the same value and advance only one step — [onWeightScroll] instead steps
 * cumulatively (via [scrolledWeightLb], the same [WeightStepper] increment the
 * ± buttons use, never a literal 5) so N detents move N steps. Reps stay
 * buttons-only (digest: "the physical crown is hard-wired to weight ... reps
 * only move via the on-screen ± buttons").
 */
@Composable
fun ExerciseStreamScreen(
    state: ExerciseStreamUiState,
    currentIndex: Int,
    onBack: () -> Unit,
    onWeightStep: (index: Int, up: Boolean) -> Unit,
    onWeightScroll: (index: Int, detents: Int) -> Unit,
    onRepsStep: (index: Int, up: Boolean) -> Unit,
    onPartnerWeightStep: (index: Int, up: Boolean) -> Unit,
    onPartnerRepsStep: (index: Int, up: Boolean) -> Unit,
    onTick: () -> Unit,
) {
    val round = state.rounds.getOrNull(currentIndex) ?: return
    val accent = dayAccent(state.accentIndex)
    val focusRequester = remember { FocusRequester() }
    val rotary = remember(state.programExerciseId) { RotaryAccumulator() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .onRotaryScrollEvent { event ->
                val steps = rotary.onScroll(event.verticalScrollPixels)
                if (steps != 0) onWeightScroll(currentIndex, steps)
                steps != 0
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        ProgressRingCanvas(
            segments = ringSegments(state.rounds.size, state.rounds.map { it.done }, currentIndex),
            accent = accent,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )

        BackButton(
            dayId = state.dayId,
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        )

        Box(Modifier.align(Alignment.Center).padding(horizontal = 26.dp)) {
            if (state.isSuperset) {
                SupersetRoundContent(
                    state = state,
                    round = round,
                    index = currentIndex,
                    accent = accent,
                    onWeightStep = onWeightStep,
                    onRepsStep = onRepsStep,
                    onPartnerWeightStep = onPartnerWeightStep,
                    onPartnerRepsStep = onPartnerRepsStep,
                )
            } else {
                MainLiftRoundContent(
                    state = state,
                    round = round,
                    index = currentIndex,
                    accent = accent,
                    onWeightStep = onWeightStep,
                    onRepsStep = onRepsStep,
                )
            }
        }

        TickButton(
            done = round.done,
            accent = accent,
            onClick = onTick,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun BackButton(dayId: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "‹ day $dayId".uppercase(),
        color = TextTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun MainLiftRoundContent(
    state: ExerciseStreamUiState,
    round: RoundUiState,
    index: Int,
    accent: Color,
    onWeightStep: (Int, Boolean) -> Unit,
    onRepsStep: (Int, Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.name,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                text = round.kindLabel.uppercase(),
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = "set ${index + 1} / ${state.rounds.size}".uppercase(),
                color = TextTertiary,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            StepperButton(size = 32.dp, glyph = "−", onClick = { onWeightStep(index, false) })
            Text(
                text = WeightStepper.format(round.weightDisplay),
                color = TextPrimary,
                fontFamily = Condensed,
                fontWeight = FontWeight.Bold,
                fontSize = 46.sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 84.dp),
            )
            StepperButton(size = 32.dp, glyph = "+", onClick = { onWeightStep(index, true) })
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            StepperButton(size = 24.dp, glyph = "−", onClick = { onRepsStep(index, false) })
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = round.reps.toString(),
                    color = TextPrimary,
                    fontFamily = Condensed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(" reps", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 1.dp))
            }
            StepperButton(size = 24.dp, glyph = "+", onClick = { onRepsStep(index, true) })
        }
    }
}

@Composable
private fun SupersetRoundContent(
    state: ExerciseStreamUiState,
    round: RoundUiState,
    index: Int,
    accent: Color,
    onWeightStep: (Int, Boolean) -> Unit,
    onRepsStep: (Int, Boolean) -> Unit,
    onPartnerWeightStep: (Int, Boolean) -> Unit,
    onPartnerRepsStep: (Int, Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.name,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                text = "superset · one tick".uppercase(),
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = "round ${index + 1} / ${state.rounds.size}".uppercase(),
                color = TextTertiary,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
        }
        WeightRepsRow(
            weightDisplay = round.weightDisplay,
            reps = round.reps,
            weightFontSize = 26.sp,
            buttonSize = 26.dp,
            onWeightStep = { up -> onWeightStep(index, up) },
            onRepsStep = { up -> onRepsStep(index, up) },
        )
        state.partnerName?.let { partnerName ->
            Text(
                text = "↳ $partnerName",
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        round.partner?.let { partner ->
            WeightRepsRow(
                weightDisplay = partner.weightDisplay,
                reps = partner.reps,
                weightFontSize = 22.sp,
                buttonSize = 22.dp,
                onWeightStep = { up -> onPartnerWeightStep(index, up) },
                onRepsStep = { up -> onPartnerRepsStep(index, up) },
            )
        }
    }
}

@Composable
private fun WeightRepsRow(
    weightDisplay: Double,
    reps: Int,
    weightFontSize: TextUnit,
    buttonSize: Dp,
    onWeightStep: (Boolean) -> Unit,
    onRepsStep: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepperButton(size = buttonSize, glyph = "−", onClick = { onWeightStep(false) })
        Text(
            text = WeightStepper.format(weightDisplay),
            color = TextPrimary,
            fontFamily = Condensed,
            fontWeight = FontWeight.Bold,
            fontSize = weightFontSize,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
        StepperButton(size = buttonSize, glyph = "+", onClick = { onWeightStep(true) })
        Text(" × ", color = TextSecondary, fontSize = 13.sp)
        StepperButton(size = buttonSize, glyph = "−", onClick = { onRepsStep(false) })
        Text(
            text = reps.toString(),
            color = TextPrimary,
            fontFamily = Condensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = weightFontSize,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
        StepperButton(size = buttonSize, glyph = "+", onClick = { onRepsStep(true) })
    }
}
