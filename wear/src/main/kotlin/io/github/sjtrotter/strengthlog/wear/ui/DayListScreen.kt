@file:OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)

package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScreenScaffold
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent

/**
 * Screen 1 (spec §9): the suggested day's exercise list — name + ✓ progress
 * only. Glanceable, so no set-level detail here; tap an exercise for that.
 */
@Composable
fun DayListScreen(state: DayListUiState, onExerciseClick: (Long) -> Unit) {
    val columnState = rememberResponsiveColumnState()
    val accent = dayAccent(state.accentIndex)

    ScreenScaffold(scrollState = columnState) {
        ScalingLazyColumn(columnState = columnState) {
            item {
                Text(
                    text = state.dayTitle,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(state.rows) { row ->
                Chip(
                    label = { Text(row.name, maxLines = 2) },
                    secondaryLabel = { Text(progressLabel(row)) },
                    onClick = { onExerciseClick(row.programExerciseId) },
                    colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun progressLabel(row: DayListRow): String =
    if (row.allDone) "✓ done" else "${row.doneCount}/${row.totalCount}"
