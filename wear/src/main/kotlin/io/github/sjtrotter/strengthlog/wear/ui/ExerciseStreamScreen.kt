package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.Condensed
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.TextTertiary
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent

/** The reserved bottom slot's height (redesign §1.3) — the tick is the ONLY thing ever laid out here. */
private val TICK_SLOT_HEIGHT = 56.dp

/**
 * Exercise stream (redesign digest §1.1-§1.3): one round at a time, scoped to
 * this exercise only, entirely READ-ONLY — every value shown is the phone's
 * prescription, pre-formatted per tracking type in [WatchUiModels]. The tick
 * is the only interaction; there is no crown, no stepper, no edit of any kind.
 *
 * Structural tick fix (§1.3): the root is a [Column], not a [Box] overlay. A
 * fixed top slot holds the back label, the center content sits in a
 * `weight(1f)` slot, and a *reserved* bottom slot ([TICK_SLOT_HEIGHT]) holds
 * only the tick button — nothing else is ever laid out there, so the old
 * tick/content overlap is structurally impossible rather than padded away.
 * [ProgressRingCanvas] stays a full-size, non-interactive background draw
 * underneath the column.
 */
@Composable
fun ExerciseStreamScreen(
    state: ExerciseStreamUiState,
    currentIndex: Int,
    onBack: () -> Unit,
    onTick: () -> Unit,
) {
    val round = state.rounds.getOrNull(currentIndex) ?: return
    val accent = dayAccent(state.accentIndex)

    Box(Modifier.fillMaxSize().background(Background)) {
        ProgressRingCanvas(
            segments = ringSegments(state.rounds.size, state.rounds.map { it.done }, currentIndex),
            accent = accent,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            BackButton(
                dayId = state.dayId,
                onClick = onBack,
                modifier = Modifier.padding(top = 16.dp),
            )

            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.isSuperset -> SupersetRoundContent(state, round, currentIndex, accent)
                    state.tracking == TrackingType.REPS -> RepsRoundContent(state, round, currentIndex, accent)
                    state.tracking == TrackingType.TIMED -> TimedRoundContent(state, round, currentIndex, accent)
                    else -> MainLiftRoundContent(state, round, currentIndex, accent)
                }
            }

            Box(
                Modifier.fillMaxWidth().height(TICK_SLOT_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                TickButton(done = round.done, accent = accent, onClick = onTick)
            }
        }
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
private fun RoundHeader(state: ExerciseStreamUiState, round: RoundUiState, index: Int, accent: Color) {
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
}

/** The phone's prescribed GOAL for this exercise (redesign §1.2, optional caption) — no new math,
 *  just [ExerciseStreamUiState.goalDisplay] shown under the header. */
@Composable
private fun GoalCaption(goalDisplay: String) {
    Text(
        text = "goal $goalDisplay".uppercase(),
        color = TextTertiary,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** The big read-only hero numeral (redesign §1.2) — grows to 54sp now that the ± steppers are gone. */
@Composable
private fun HeroNumeral(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 54.sp,
        style = TextStyle(fontFeatureSettings = "tnum"),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** WEIGHTED: the working weight is the hero numeral, "× reps" is the read-only secondary line. */
@Composable
private fun MainLiftRoundContent(state: ExerciseStreamUiState, round: RoundUiState, index: Int, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundHeader(state, round, index, accent)
        GoalCaption(state.goalDisplay)
        HeroNumeral(round.heroDisplay)
        Text(
            text = round.secondaryDisplay,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * REPS (bodyweight): the rep count is the hero numeral, formatted with the multiplier glyph
 * ("×12") matching [io.github.sjtrotter.strengthlog.domain.standards.SetFormatter]'s REPS
 * output — there is NO weight control at all, a bodyweight movement never reads "0 lb". A small
 * "reps" caption sits under the numeral to name the unit.
 */
@Composable
private fun RepsRoundContent(state: ExerciseStreamUiState, round: RoundUiState, index: Int, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundHeader(state, round, index, accent)
        GoalCaption(state.goalDisplay)
        HeroNumeral(round.heroDisplay)
        Text(
            text = "reps",
            color = TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * TIMED (hold/carry): the hold is the hero numeral, formatted like the phone ("45s" / "1:30").
 * When the goal carries load (weighted plank) the added load shows as a read-only "+25"
 * caption — added load is a phone-side setup value, never edited on the wrist.
 */
@Composable
private fun TimedRoundContent(state: ExerciseStreamUiState, round: RoundUiState, index: Int, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundHeader(state, round, index, accent)
        GoalCaption(state.goalDisplay)
        HeroNumeral(round.heroDisplay)
        if (state.hasAddedLoad) {
            Text(
                text = state.addedLoadDisplay,
                color = TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** Superset: one summary line per round ("185×5") for the main track, one tick for both. */
@Composable
private fun SupersetRoundContent(state: ExerciseStreamUiState, round: RoundUiState, index: Int, accent: Color) {
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
        Text(
            text = round.heroDisplay,
            color = TextPrimary,
            fontFamily = Condensed,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(top = 6.dp),
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
            Text(
                text = partner.summaryDisplay,
                color = TextPrimary,
                fontFamily = Condensed,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}
