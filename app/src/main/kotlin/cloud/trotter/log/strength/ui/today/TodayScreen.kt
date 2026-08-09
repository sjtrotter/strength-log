package cloud.trotter.log.strength.ui.today

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TabLetter
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.accentBorder
import cloud.trotter.log.strength.ui.theme.chromeVerticalPadding
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import cloud.trotter.log.strength.ui.theme.readableWidth

/**
 * The Today screen (issue #121): an editorial glance at the next workout in
 * rotation. Stateless in the Compose sense — [state] renders and [actions]
 * carries every intent back to the owner; the ruled summary itself is read-only.
 */
@Composable
fun TodayScreen(state: TodayUiState, actions: TodayActions) {
    val accent = dayAccent(state.dayIndex)
    val onAccent = onDayAccent(state.dayIndex)

    Box(Modifier.fillMaxSize().background(Background)) {
        if (!state.hasProgram) {
            // Two different silences (#127): one that ends on its own, and one
            // that only ends when the lifter does something about it.
            if (state.loading) ProgramLoadingState() else NoProgramState(actions.onSetUpProgram)
            return@Box
        }
        Column(readableWidth()) {
            TodayHeader(actions)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.size(20.dp))
                Box(Modifier.width(40.dp).height(2.dp).background(accent))
                Spacer(Modifier.size(12.dp))
                Text(state.overline, color = accent, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.size(6.dp))
                Text(
                    state.dayLine,
                    color = TextPrimary,
                    style = MaterialTheme.typography.displayLarge,
                    maxLines = 2,
                )
                if (state.emphasisLine.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Text(state.emphasisLine, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(Modifier.size(22.dp))
                Text(state.statLine, color = TextSecondary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(10.dp))
                Hairline()
                state.lifts.forEach { lift ->
                    LiftRow(lift)
                    Hairline()
                }

                state.cardio?.let { cardio ->
                    Spacer(Modifier.size(14.dp))
                    Text("CARDIO FINISHER", color = TextFaint, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        if (cardio.hard) "HARD · ${cardio.label}" else "EASY · ${cardio.label}",
                        color = accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.size(3.dp))
                    Text(cardio.detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }

                if (state.rotation.isNotEmpty()) {
                    Spacer(Modifier.size(24.dp))
                    Text("ROTATION", color = TextFaint, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.size(8.dp))
                    val nextId = state.rotation.firstOrNull { it.isNext }?.dayId?.uppercase()
                    val description = "Rotation: " +
                        state.rotation.joinToString(", ") { "Day ${it.dayId}" } +
                        if (nextId != null) ". Next is Day $nextId." else "."
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                    ) {
                        state.rotation.forEach { mark -> RotationChip(mark) }
                    }
                }

                state.lastSession?.let { lastSession ->
                    Spacer(Modifier.size(22.dp))
                    Hairline()
                    Spacer(Modifier.size(12.dp))
                    Text("LAST SESSION", color = TextFaint, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.size(4.dp))
                    Text(lastSession, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(24.dp))
            }
            StartBar(state.actionLabel, accent, onAccent, actions.onStart)
        }
    }
}

@Composable
private fun TodayHeader(actions: TodayActions) {
    val verticalPadding = chromeVerticalPadding()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("TODAY", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            LogButton(actions.onOpenLog)
            SettingsButton(actions.onOpenSettings)
        }
        Hairline()
    }
}

@Composable
private fun LogButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .pressable(
                onClickLabel = "Open log",
                role = Role.Button,
                shape = RoundedCornerShape(10.dp),
                onClick = onClick,
            )
            .semantics { contentDescription = "Open log" }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("LOG", color = TextSecondary, style = TabLetter.copy(fontSize = 13.sp), modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(40.dp, 40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .pressable(
                onClickLabel = "Settings",
                role = Role.Button,
                shape = RoundedCornerShape(10.dp),
                onClick = onClick,
            )
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = TextSecondary, style = TabLetter.copy(fontSize = 15.sp), modifier = Modifier.clearAndSetSemantics {})
    }
}

/** 1dp separator between fixed chrome and the scrolling summary. */
@Composable
private fun Hairline() {
    HorizontalDivider(thickness = 1.dp, color = Border)
}

@Composable
private fun LiftRow(lift: TodayLift) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            lift.name,
            color = if (lift.isMain) TextPrimary else TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text("${lift.setCount} sets", color = TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RotationChip(mark: RotationMark) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .defaultMinSize(24.dp, 24.dp)
            .then(
                if (mark.isNext) Modifier.background(dayAccent(mark.dayIndex), shape)
                else Modifier.border(1.dp, accentBorder(mark.dayIndex), shape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark.dayId.uppercase(),
            color = if (mark.isNext) onDayAccent(mark.dayIndex) else dayAccent(mark.dayIndex),
            style = TabLetter.copy(fontSize = 12.sp),
        )
    }
}

@Composable
private fun StartBar(actionLabel: String, accent: Color, onAccent: Color, onStart: () -> Unit) {
    val verticalPadding = chromeVerticalPadding()
    Column(Modifier.fillMaxWidth().background(Background)) {
        Hairline()
        Box(Modifier.padding(horizontal = 16.dp, vertical = verticalPadding)) {
            StartButton(actionLabel, accent, onAccent, onStart)
        }
    }
}

@Composable
private fun StartButton(actionLabel: String, accent: Color, onAccent: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "startButtonPress",
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 56.dp),
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Text(
            actionLabel,
            style = DoneButtonLabel,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun TodayScreenPreview() {
    TodayScreenPreviewContent()
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun TodayScreenInProgressPreview() {
    TodayScreenPreviewContent(inProgress = true)
}

// Font-scale audit (A7): the primary action can wrap to two lines without
// clipping, just as DayScreen's fixed bottom action does.
@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F, fontScale = 2.0f)
@Composable
private fun TodayScreenFontScale200Preview() {
    TodayScreenPreviewContent()
}

@Composable
private fun TodayScreenPreviewContent(inProgress: Boolean = false) {
    val state = TodayUiState(
        hasProgram = true,
        dayId = "B",
        dayIndex = 1,
        dayLine = "DAY B · LOWER",
        overline = if (inProgress) "IN PROGRESS" else "NEXT IN ROTATION",
        emphasisLine = "SQUAT FOCUS · HAMSTRINGS · CALVES",
        statLine = if (inProgress) "4 / 18 SETS" else "5 LIFTS · 18 SETS",
        lifts = listOf(
            TodayLift("Barbell Back Squat", 5, isMain = true),
            TodayLift("Romanian Deadlift", 4, isMain = false),
            TodayLift("Bulgarian Split Squat", 3, isMain = false),
            TodayLift("Seated Leg Curl", 3, isMain = false),
            TodayLift("Standing Calf Raise", 3, isMain = false),
        ),
        cardio = TodayCardio("ZONE 2", "20–25 min easy — keep it conversational.", hard = false),
        actionLabel = if (inProgress) "CONTINUE — 4 OF 18 SETS" else "START DAY B",
        lastSession = if (inProgress) null else "Jul 30, 2026 · 18 sets · Back Squat 245",
        rotation = listOf(
            RotationMark("A", 0, isNext = false),
            RotationMark("B", 1, isNext = true),
            RotationMark("C", 2, isNext = false),
            RotationMark("D", 3, isNext = false),
        ),
    )
    AppTheme {
        TodayScreen(
            state = state,
            actions = TodayActions(onStart = {}, onOpenSettings = {}, onOpenLog = {}, onSetUpProgram = {}),
        )
    }
}
