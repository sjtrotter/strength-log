package io.github.sjtrotter.strengthlog.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.CheckmarkToggle
import io.github.sjtrotter.strengthlog.ui.components.Stepper
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Done
import io.github.sjtrotter.strengthlog.ui.theme.Surface
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent

/**
 * The day screen (spec §8.2). Stateless in the Compose sense: it renders a
 * [DayUiState] and forwards every intent to [DayViewModel]; all logic lives there
 * and in [DayScreenBuilder]. Styling comes entirely from the design system
 * (AppCard/Stepper/CheckmarkToggle/dayAccent, spec §8.5).
 */
@Composable
fun DayScreen(state: DayUiState, actions: DayActions) {
    KeepScreenOn(state.keepScreenOn)
    val accent = dayAccent(state.dayIndex)

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        if (!state.hasProgram) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Preparing your program…", color = TextSecondary)
            }
            return@Box
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DayHeader(state, accent, actions)
            }
            items(state.exercises, key = { it.programExerciseId }) { card ->
                ExerciseCard(card, state.unit, accent, actions)
            }
            state.cardio?.let { cardio ->
                item { CardioCard(cardio) }
            }
            item {
                DoneButton(nextDayId = state.nextDayId, accent = accent, onClick = actions.onDone)
            }
            item {
                Footer(state, actions)
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

// --- header + tabs -----------------------------------------------------------

@Composable
private fun DayHeader(state: DayUiState, accent: Color, actions: DayActions) {
    Column(Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsTab(actions.onOpenSettings)
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tabs.forEach { tab ->
                    DayTab(tab, onClick = { actions.onSelectDay(tab.dayId) })
                }
            }
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text = "Day ${state.viewDayId} · ${state.dayTitle}",
            color = TextPrimary,
            style = MaterialTheme.typography.displayLarge,
        )
        Text(text = state.emphasisLine, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        if (state.isOverride) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Viewing Day ${state.viewDayId} (override). Suggested next: Day ${state.suggestedDayId}.",
                color = accent,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SettingsTab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = TextSecondary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun DayTab(tab: DayTab, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Suggested-next marker: a small accent dot above the badge (spec §8.2).
        Box(
            Modifier
                .size(5.dp)
                .background(
                    if (tab.isSuggested) dayAccent(tab.dayIndex) else Color.Transparent,
                    RoundedCornerShape(50),
                ),
        )
        Spacer(Modifier.size(2.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .alpha(if (tab.isSelected) 1f else 0.5f)
                .background(dayAccent(tab.dayIndex), RoundedCornerShape(8.dp))
                .then(
                    if (tab.isSuggested) Modifier.border(2.dp, TextPrimary, RoundedCornerShape(8.dp)) else Modifier,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(tab.dayId, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// --- exercise card -----------------------------------------------------------

@Composable
private fun ExerciseCard(card: ExerciseCardState, unit: WeightUnit, accent: Color, actions: DayActions) {
    val leftBorder = if (card.allDone) Done else Border
    AppCard(modifier = Modifier.drawWithContent {
        drawContent()
        // On top of AppCard's own background: a green strip marks a finished card
        // (spec §8.2), muted otherwise so every card reads with the same left edge.
        drawRect(color = leftBorder, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
    }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.onToggleCollapse(card.programExerciseId) },
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.allDone) {
                        DoneChip()
                        Spacer(Modifier.width(6.dp))
                    }
                    if (card.isMain) {
                        Badge("MAIN", accent, TextPrimary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        card.title,
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (card.hasWarmupHint) {
                    Spacer(Modifier.size(4.dp))
                    Badge("+1 WARM-UP", Surface, TextSecondary, outlined = true)
                }
            }
            GoalBlock(card.goalDisplay, card.perHand, accent)
        }

        if (card.collapsed) {
            Spacer(Modifier.size(8.dp))
            Text(card.collapsedSummary, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            return@AppCard
        }

        if (card.isMain) {
            Spacer(Modifier.size(6.dp))
            Text(DayScreenBuilder.MAIN_HELPER, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (card.isSuperset && card.partnerGoalDisplay != null) {
            Spacer(Modifier.size(4.dp))
            Text(
                "↳ partner GOAL ${card.partnerGoalDisplay}${if (card.partnerPerHand) "/hand" else ""}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.size(8.dp))
        card.rows.forEach { row ->
            SetRow(card, row, unit, accent, actions)
            if (card.isSuperset && row.partner != null) {
                PartnerSubRow(card.programExerciseId, row, unit, actions)
            }
        }
        AddSetButton { actions.onAddSet(card.programExerciseId, card.isSuperset) }
    }
}

@Composable
private fun SetRow(card: ExerciseCardState, row: SetRowState, unit: WeightUnit, accent: Color, actions: DayActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindLabel(row.kindLabel, row.isTop, accent)
            Spacer(Modifier.width(8.dp))
            Stepper(
                value = row.weightDisplay,
                onValueChange = {
                    actions.onWeightChange(card.programExerciseId, Slot.MAIN, row.index, WeightStepper.round(it, unit))
                },
                step = { WeightStepper.increment(it, unit) },
                format = WeightStepper::format,
            )
            Spacer(Modifier.weight(1f))
            CheckmarkToggle(
                checked = row.done,
                onCheckedChange = { actions.onToggleDone(card.programExerciseId, row.index, it, card.isSuperset) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stepper(
                value = row.reps.toDouble(),
                onValueChange = { actions.onRepsChange(card.programExerciseId, Slot.MAIN, row.index, it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
            )
            Text(" reps", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            RemoveButton { actions.onRemoveSet(card.programExerciseId, row.index, card.isSuperset) }
        }
    }
}

@Composable
private fun PartnerSubRow(programExerciseId: Long, row: SetRowState, unit: WeightUnit, actions: DayActions) {
    val partner = row.partner ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, bottom = 6.dp)
            .background(Background)
            .dashedTopBorder(Border)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("↳", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Stepper(
                value = partner.weightDisplay,
                onValueChange = {
                    actions.onWeightChange(programExerciseId, Slot.SS, row.index, WeightStepper.round(it, unit))
                },
                step = { WeightStepper.increment(it, unit) },
                format = WeightStepper::format,
            )
            Spacer(Modifier.width(8.dp))
            Stepper(
                value = partner.reps.toDouble(),
                onValueChange = { actions.onRepsChange(programExerciseId, Slot.SS, row.index, it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
            )
            Text(" reps", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- small pieces ------------------------------------------------------------

@Composable
private fun GoalBlock(goalDisplay: String, perHand: Boolean, accent: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text("GOAL", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(goalDisplay, color = accent, style = MaterialTheme.typography.displayLarge)
        if (perHand) {
            Text("/hand", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun KindLabel(label: String, isTop: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .then(if (isTop) Modifier.background(accent.copy(alpha = 0.18f), RoundedCornerShape(6.dp)) else Modifier)
            .then(if (isTop) Modifier.border(1.dp, accent, RoundedCornerShape(6.dp)) else Modifier)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (isTop) accent else TextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun Badge(text: String, fill: Color, textColor: Color, outlined: Boolean = false) {
    Box(
        modifier = Modifier
            .background(if (outlined) Color.Transparent else fill, RoundedCornerShape(4.dp))
            .then(if (outlined) Modifier.border(1.dp, Border, RoundedCornerShape(4.dp)) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelLarge, fontSize = 11.sp)
    }
}

@Composable
private fun DoneChip() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(Done, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = Background, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AddSetButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .dashedTopBorder(Border)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ add set", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("×", color = TextSecondary, style = MaterialTheme.typography.titleLarge)
    }
}

// --- cardio, done, footer ----------------------------------------------------

@Composable
private fun CardioCard(cardio: CardioSuggestion) {
    var open by remember { mutableStateOf(false) }
    AppCard(modifier = Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (cardio.hard) "CARDIO FINISHER — HARD" else "CARDIO FINISHER",
                color = TextSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(if (open) "–" else "+", color = TextSecondary, style = MaterialTheme.typography.titleLarge)
        }
        Text(cardio.label, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        if (open) {
            Spacer(Modifier.size(4.dp))
            Text(cardio.detail, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DoneButton(nextDayId: String?, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .background(accent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (nextDayId != null) "DONE — ADVANCE TO DAY $nextDayId" else "DONE",
            color = TextPrimary,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun Footer(state: DayUiState, actions: DayActions) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep screen on", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.keepScreenOn,
                onCheckedChange = actions.onKeepScreenOnChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Done),
            )
        }
        Text(
            "Rotation, not calendar — days advance when you finish them, not on a schedule. " +
                "The weights you log are your living record; GOALs are a reference, not a rule.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Clear today's checkmarks",
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onClearChecks)
                .padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// --- modifiers / effects -----------------------------------------------------

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

private fun Modifier.dashedTopBorder(color: Color): Modifier = drawBehind {
    val stroke = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    drawLine(
        brush = SolidColor(color),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
        strokeWidth = stroke.width,
        pathEffect = stroke.pathEffect,
    )
}

/** Callbacks the screen forwards to [DayViewModel] — one place, so previews stay trivial. */
data class DayActions(
    val onSelectDay: (String) -> Unit,
    val onWeightChange: (Long, String, Int, Double) -> Unit,
    val onRepsChange: (Long, String, Int, Int) -> Unit,
    val onToggleDone: (Long, Int, Boolean, Boolean) -> Unit,
    val onAddSet: (Long, Boolean) -> Unit,
    val onRemoveSet: (Long, Int, Boolean) -> Unit,
    val onToggleCollapse: (Long) -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit,
    val onClearChecks: () -> Unit,
    val onDone: () -> Unit,
    val onOpenSettings: () -> Unit,
)
