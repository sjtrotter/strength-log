@file:OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)

package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScreenScaffold
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import io.github.sjtrotter.strengthlog.wear.theme.Border
import io.github.sjtrotter.strengthlog.wear.theme.Surface
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.accentSoft
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent
import io.github.sjtrotter.strengthlog.wear.theme.onDayAccent

/**
 * Today list (design digest §1.1): day pill + optional real focus/label
 * subtitle, then the shared `listRows` renderer — exactly one row (the first
 * not-yet-complete exercise) gets the "up next" treatment. Tapping a row opens
 * its stream on [DayListRow.firstUndoneIndex], not necessarily round 1.
 */
@Composable
fun DayListScreen(state: DayListUiState, onExerciseClick: (programExerciseId: Long, startRound: Int) -> Unit) {
    val columnState = rememberResponsiveColumnState()
    val accent = dayAccent(state.accentIndex)
    val onAccent = onDayAccent(state.accentIndex)
    val accentSoftColor = accentSoft(state.accentIndex)
    val upNextId = upNextIndex(state.rows)?.let { state.rows[it].programExerciseId }

    ScreenScaffold(scrollState = columnState) {
        ScalingLazyColumn(columnState = columnState) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DayPill(dayId = state.dayId, accentColor = accent, onAccentColor = onAccent)
                }
            }
            state.subtitle?.let { subtitle ->
                item {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                    )
                }
            }
            items(state.rows) { row ->
                ExerciseListRow(
                    row = row,
                    upNext = row.programExerciseId == upNextId,
                    accent = accent,
                    accentSoftColor = accentSoftColor,
                    onClick = { onExerciseClick(row.programExerciseId, row.firstUndoneIndex) },
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ExerciseListRow(
    row: DayListRow,
    upNext: Boolean,
    accent: Color,
    accentSoftColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(if (upNext) accentSoftColor else Surface, RoundedCornerShape(10.dp))
            .border(1.dp, if (upNext) accent else Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = row.name,
                color = if (row.allDone) TextSecondary else TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
            )
            row.partnerName?.let { partner ->
                Text(
                    text = "↳ $partner",
                    color = TextSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (row.allDone) {
            CheckBadge()
        } else {
            Text(
                text = "${row.doneCount}/${row.totalCount}",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
