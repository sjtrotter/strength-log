@file:OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)

package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScreenScaffold
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.wear.theme.Surface
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent

/**
 * Screen 2 (spec §9): GOAL as an accent-colored numeral, then one round per
 * row with ± weight/reps and a done tick; a superset partner's round renders
 * as a sub-row beneath with its own ± controls but no tick of its own — the
 * one-tick-per-round rule (spec §8.2), enforced by [onMainDoneToggle] alone.
 */
@Composable
fun ExerciseDetailScreen(
    state: ExerciseDetailUiState,
    onWeightStep: (index: Int, up: Boolean) -> Unit,
    onRepsStep: (index: Int, up: Boolean) -> Unit,
    onMainDoneToggle: (index: Int, done: Boolean) -> Unit,
    onPartnerWeightStep: (index: Int, up: Boolean) -> Unit,
    onPartnerRepsStep: (index: Int, up: Boolean) -> Unit,
) {
    val columnState = rememberResponsiveColumnState()
    val accent = dayAccent(state.accentIndex)

    ScreenScaffold(scrollState = columnState) {
        ScalingLazyColumn(columnState = columnState) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(
                        text = "GOAL " + state.goalDisplay + if (state.perHand) "/hand" else "",
                        color = accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
                }
            }
            items(state.rows) { row ->
                SetRoundCard(
                    row = row,
                    partnerName = state.partnerName,
                    onWeightStep = { up -> onWeightStep(row.index, up) },
                    onRepsStep = { up -> onRepsStep(row.index, up) },
                    onDoneToggle = { onMainDoneToggle(row.index, !row.done) },
                    onPartnerWeightStep = { up -> onPartnerWeightStep(row.index, up) },
                    onPartnerRepsStep = { up -> onPartnerRepsStep(row.index, up) },
                )
            }
        }
    }
}

@Composable
private fun SetRoundCard(
    row: SetRowUiState,
    partnerName: String?,
    onWeightStep: (Boolean) -> Unit,
    onRepsStep: (Boolean) -> Unit,
    onDoneToggle: () -> Unit,
    onPartnerWeightStep: (Boolean) -> Unit,
    onPartnerRepsStep: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(10.dp))
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(row.kindLabel, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlusMinusValue(WeightStepper.format(row.weightDisplay), { onWeightStep(false) }, { onWeightStep(true) })
                Text(" × ", color = TextSecondary, fontSize = 13.sp)
                PlusMinusValue(row.reps.toString(), { onRepsStep(false) }, { onRepsStep(true) })
            }
            DoneTick(done = row.done, onToggle = onDoneToggle)
        }
        row.partner?.let { partner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text("↳ $partnerName", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                PlusMinusValue(WeightStepper.format(partner.weightDisplay), { onPartnerWeightStep(false) }, { onPartnerWeightStep(true) })
                Text(" × ", color = TextSecondary, fontSize = 13.sp)
                PlusMinusValue(partner.reps.toString(), { onPartnerRepsStep(false) }, { onPartnerRepsStep(true) })
            }
        }
    }
}
