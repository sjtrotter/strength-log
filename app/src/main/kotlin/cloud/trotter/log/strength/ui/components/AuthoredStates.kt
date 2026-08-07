package cloud.trotter.log.strength.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.dayAccent
import kotlinx.coroutines.delay

/**
 * The app's edge states (issue #127), built as one shape rather than three
 * placeholder strings: the 2dp accent rule Today already draws above its
 * headline, a caps overline, a sentence, and — where the state is one the
 * lifter can act on — a single bordered action.
 *
 * They live together because they are one register. A screen with nothing to
 * show still has something to say, and what it says has to sound like the rest
 * of the app: no spinner, no Material progress, no encouragement.
 */

/** How long a load is allowed to take before it is worth drawing (see [ProgramLoadingState]). */
const val LOADING_REVEAL_DELAY_MS = 260L

/** One full pass of [SweepingRule], and how many day accents it cycles through. */
private const val SWEEP_MS = 820
private const val SWEEP_PASSES = 4

/** The rule every authored state opens with — Today's headline rule, reused. */
private val RuleWidth = 44.dp
private val RuleHeight = 2.dp

/**
 * The frame between launch and the first program read — the blank
 * start-destination frame and the day/Today screens' pre-resolution state.
 *
 * Nothing is drawn for [LOADING_REVEAL_DELAY_MS]. Resolving the program off
 * Room and DataStore normally takes a fraction of that, and a loading state
 * that appears for a tenth of a second and vanishes is a flash, not an answer
 * — so on a fast load this stays what it was before: the app's own background.
 */
@Composable
fun ProgramLoadingState(modifier: Modifier = Modifier) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_REVEAL_DELAY_MS)
        revealed = true
    }
    Box(modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        if (revealed) {
            AuthoredState(overline = "PREPARING YOUR PROGRAM", rule = { SweepingRule() })
        }
    }
}

/**
 * The program is resolved and there isn't one: a fresh install that skipped
 * the wizard, or a restore from a backup taken before a program existed. The
 * distinction from [ProgramLoadingState] is the whole point — this state ends
 * when the lifter acts, so it offers the action instead of waiting.
 */
@Composable
fun NoProgramState(onSetUpProgram: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        NoProgram(onSetUpProgram)
    }
}

/**
 * The journal before the first completed session. Sits inside the journal's
 * own list rather than replacing the screen: the sections above it (Health
 * Connect, an import prompt) still have their own things to say.
 *
 * With no program it shows the no-program state instead. An empty journal and
 * no program are one situation, not two, and offering START A SESSION here
 * would promise a workout that lands on NO PROGRAM YET.
 */
@Composable
fun EmptyJournalState(
    hasProgram: Boolean,
    onStartSession: () -> Unit,
    onSetUpProgram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inset = modifier.padding(vertical = 36.dp)
    if (!hasProgram) {
        NoProgram(onSetUpProgram, inset)
        return
    }
    AuthoredState(
        overline = "YOUR FIRST SESSION WILL LAND HERE",
        body = "Finish a workout and it lands in this list — every set, the day it belonged " +
            "to, and what it added to your trajectory.",
        action = { PillAction("START A SESSION", TextSecondary, onStartSession, border = Border) },
        modifier = inset,
    )
}

/** The no-program content itself, so the journal can host it inline without a
 *  second copy of the copy. */
@Composable
private fun NoProgram(onSetUpProgram: () -> Unit, modifier: Modifier = Modifier) {
    AuthoredState(
        overline = "NO PROGRAM YET",
        body = "The wizard builds your rotation from a few answers — days, emphasis, " +
            "and the equipment you actually have.",
        action = { PillAction("RUN THE SETUP WIZARD", dayAccent(0), onSetUpProgram) },
        modifier = modifier,
    )
}

/**
 * The shared shape: rule, overline, optional sentence, optional action. The
 * [rule] slot is the only thing that varies structurally — loading animates it,
 * everything else draws it still.
 */
@Composable
private fun AuthoredState(
    overline: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    accent: Color = dayAccent(0),
    rule: @Composable () -> Unit = { StaticRule(accent) },
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rule()
        Text(overline, color = accent, style = MaterialTheme.typography.labelSmall)
        body?.let {
            Text(
                it,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}

@Composable
private fun StaticRule(accent: Color) {
    Box(Modifier.width(RuleWidth).height(RuleHeight).background(accent))
}

/**
 * The same rule, filling. One clock drives both halves of the motion: the
 * animated value runs 0 → [SWEEP_PASSES], its whole part picks the day accent
 * and its fraction fills the rule, so the colour can never step out of time
 * with the fill. Each pass is one day of the rotation in its own earth tone —
 * the only thing on screen that could honestly be said while the program is
 * still being read.
 */
@Composable
private fun SweepingRule() {
    val transition = rememberInfiniteTransition(label = "programLoading")
    val elapsed by transition.animateFloat(
        initialValue = 0f,
        targetValue = SWEEP_PASSES.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(SWEEP_MS * SWEEP_PASSES, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "programLoadingSweep",
    )
    val pass = elapsed.toInt().coerceIn(0, SWEEP_PASSES - 1)
    val filled = FastOutSlowInEasing.transform(elapsed - pass)
    Box(Modifier.width(RuleWidth).height(RuleHeight).background(Border)) {
        if (filled > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(dayAccent(pass)))
        }
    }
}

/**
 * A bordered caps action, the shape Setup's escape hatch already uses — sized
 * to its label rather than the screen, because an edge state's action is one
 * offer, not a screen-wide commitment like DONE.
 */
@Composable
private fun PillAction(label: String, color: Color, onClick: () -> Unit, border: Color = color) {
    Box(
        modifier = Modifier
            .widthIn(min = 200.dp)
            .heightIn(min = 52.dp)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            // No onClickLabel: the label below is real text, so it already is
            // the accessible name — repeating it as the action verb would have
            // TalkBack say it twice.
            .pressable(role = Role.Button, shape = RoundedCornerShape(12.dp), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Preview(showBackground = true, heightDp = 400, backgroundColor = 0xFF0D0D0F)
@Composable
private fun NoProgramStatePreview() {
    AppTheme { NoProgramState(onSetUpProgram = {}) }
}

@Preview(showBackground = true, heightDp = 300, backgroundColor = 0xFF0D0D0F, fontScale = 2.0f)
@Composable
private fun EmptyJournalStatePreview() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(Background)) {
            EmptyJournalState(hasProgram = true, onStartSession = {}, onSetUpProgram = {})
        }
    }
}
