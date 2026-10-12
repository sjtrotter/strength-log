package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.theme.DayIndexLetter
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

enum class DayIndexSize(
    val width: Dp,
    val height: Dp,
    val fontDp: Dp,
    val cornerRadius: Dp,
) {
    Large(112.dp, 128.dp, 104.dp, 8.dp),
    Medium(44.dp, 52.dp, 38.dp, 6.dp),
    Small(32.dp, 36.dp, 26.dp, 5.dp),
}

/**
 * The day index: a letter on its day's accent. The redesign's one expressive
 * object; the same block at three sizes (spec: HANDOFF.md).
 */
@Composable
fun DayIndex(
    dayIndex: Int,
    letter: String,
    size: DayIndexSize,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.day_index_description, letter)
    Surface(
        modifier = modifier
            .size(size.width, size.height)
            .semantics { contentDescription = description },
        color = dayAccent(dayIndex),
        contentColor = onDayAccent(dayIndex),
        shape = RoundedCornerShape(size.cornerRadius),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = letter,
                style = DayIndexLetter.copy(
                    fontSize = with(LocalDensity.current) { size.fontDp.toSp() },
                ),
                modifier = Modifier.clearAndSetSemantics { testTag = DAY_INDEX_LETTER_TAG },
            )
        }
    }
}

internal const val DAY_INDEX_LETTER_TAG = "day-index-letter"
