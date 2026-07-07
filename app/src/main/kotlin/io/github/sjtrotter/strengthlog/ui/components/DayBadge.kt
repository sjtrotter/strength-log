package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent
import io.github.sjtrotter.strengthlog.ui.theme.onDayAccent

/** Day-letter chip, tinted with that day's accent (see [dayAccent], the SSOT). */
@Composable
fun DayBadge(dayIndex: Int, letter: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(dayAccent(dayIndex), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = onDayAccent(dayIndex),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun DayBadgePreview() {
    AppTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "A", 1 to "B", 2 to "C", 3 to "D", 4 to "A").forEach { (index, letter) ->
                DayBadge(dayIndex = index, letter = letter)
            }
        }
    }
}
