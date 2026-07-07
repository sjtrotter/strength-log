package io.github.sjtrotter.strengthlog.ui.day

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.SetRow
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Done
import io.github.sjtrotter.strengthlog.ui.theme.DoneButtonLabel
import io.github.sjtrotter.strengthlog.ui.theme.SummaryLine
import io.github.sjtrotter.strengthlog.ui.theme.Surface
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TabLetter
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.accentBorder
import io.github.sjtrotter.strengthlog.ui.theme.accentSoft
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent
import io.github.sjtrotter.strengthlog.ui.theme.onDayAccent
import kotlinx.coroutines.delay

/**
 * The day screen (spec §8.2). Stateless in the Compose sense: it renders a
 * [DayUiState] and forwards every intent to [DayViewModel]; all logic lives there
 * and in [DayScreenBuilder]. Styling comes entirely from the design system
 * (AppCard/SetRow/dayAccent, spec §8.5, design-pass restyle per
 * docs/design-handoff — visual QA is against `day_screen_reference.html`).
 */
@Composable
fun DayScreen(state: DayUiState, actions: DayActions) {
    KeepScreenOn(state.keepScreenOn)
    val accent = dayAccent(state.dayIndex)
    val onAccent = onDayAccent(state.dayIndex)
    val soft = accentSoft(state.dayIndex)

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
                DayHeader(state, accent, soft, actions)
            }
            items(state.exercises, key = { it.programExerciseId }) { card ->
                ExerciseCard(card, state.unit, accent, onAccent, soft, actions)
            }
            state.cardio?.let { cardio ->
                item { CardioCard(cardio) }
            }
            item {
                DoneButton(nextDayId = state.nextDayId, accent = accent, onAccent = onAccent, onClick = actions.onDone)
            }
            item {
                Footer(state, accent, onAccent, actions)
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

// --- header + tabs -----------------------------------------------------------

@Composable
private fun DayHeader(state: DayUiState, accent: Color, accentSoftColor: Color, actions: DayActions) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTab(actions.onOpenSettings)
            state.tabs.forEach { tab ->
                DayTab(tab, onClick = { actions.onSelectDay(tab.dayId) })
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            state.viewDayId?.let {
                Text(text = "DAY ${it.uppercase()}", color = accent, style = MaterialTheme.typography.labelSmall)
            }
            Text(text = state.dayTitle, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text(text = state.emphasisLine, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (state.isOverride && state.suggestedDayId != null) {
                Spacer(Modifier.size(3.dp))
                OverridePill(accent = accent, accentSoftColor = accentSoftColor, suggestedDayId = state.suggestedDayId)
            }
        }
    }
}

@Composable
private fun OverridePill(accent: Color, accentSoftColor: Color, suggestedDayId: String) {
    val text = buildAnnotatedString {
        append("OVERRIDE · SUGGESTED NEXT: ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("DAY ${suggestedDayId.uppercase()}")
        }
    }
    Box(
        modifier = Modifier
            .background(accentSoftColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = accent, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SettingsTab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = TextSecondary, style = TabLetter.copy(fontSize = 15.sp))
    }
}

@Composable
private fun DayTab(tab: DayTab, onClick: () -> Unit) {
    val accent = dayAccent(tab.dayIndex)
    val showSuggestedRing = tab.isSuggested && !tab.isSelected
    // Border-color uses the muted 55% blend; the suggested ring is the pure accent.
    val borderColor = if (showSuggestedRing) accentBorder(tab.dayIndex) else Border
    Box(
        modifier = Modifier
            .size(40.dp)
            .drawBehind {
                if (showSuggestedRing) {
                    val ringInset = (-2).dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(ringInset, ringInset),
                        size = Size(size.width - 2 * ringInset, size.height - 2 * ringInset),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    // Suggested-next dot at the tab's top-right corner, with a
                    // background-colored ring so it reads as cut out of the tab.
                    val dotCenter = Offset(size.width, 0f)
                    drawCircle(color = Background, radius = 6.dp.toPx(), center = dotCenter)
                    drawCircle(color = accent, radius = 4.dp.toPx(), center = dotCenter)
                }
            }
            .background(if (tab.isSelected) accent else Surface, RoundedCornerShape(10.dp))
            .then(if (tab.isSelected) Modifier else Modifier.border(1.dp, borderColor, RoundedCornerShape(10.dp)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            tab.dayId,
            color = if (tab.isSelected) onDayAccent(tab.dayIndex) else accent,
            style = TabLetter,
        )
    }
}

// --- exercise card -----------------------------------------------------------

@Composable
private fun ExerciseCard(
    card: ExerciseCardState,
    unit: WeightUnit,
    accent: Color,
    onAccent: Color,
    accentSoftColor: Color,
    actions: DayActions,
) {
    var previousAllDone by remember { mutableStateOf(card.allDone) }
    var displayCollapsed by remember { mutableStateOf(card.collapsed) }
    LaunchedEffect(card.collapsed, card.allDone) {
        val justFinished = card.allDone && !previousAllDone
        previousAllDone = card.allDone
        if (card.collapsed && justFinished) {
            // Auto-collapse only (the last tick just landed): let the ✓ chip and
            // green edge register before the card folds — an animation-layer
            // delay, not a change to DayScreenBuilder's (already-tested) collapse
            // decision. A manual header tap collapses/expands instantly (below).
            delay(420)
            displayCollapsed = true
        } else {
            displayCollapsed = card.collapsed
        }
    }

    val doneEdge by animateFloatAsState(
        targetValue = if (card.allDone) 1f else 0f,
        animationSpec = tween(200),
        label = "cardDoneEdge",
    )
    AppCard(modifier = Modifier.drawWithContent {
        drawContent()
        // A finished card gets a 3dp green left edge (spec §8.2); non-done cards
        // carry only AppCard's hairline border (reference: no muted strip).
        if (doneEdge > 0f) drawRect(color = Done.copy(alpha = doneEdge), size = Size(3.dp.toPx(), size.height))
    }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.onToggleCollapse(card.programExerciseId) },
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                    if (card.isMain) Badge("MAIN", accent, onAccent)
                    if (card.hasWarmupHint) Badge("+1 WARM-UP", Color.Transparent, TextSecondary, outlined = true)
                    if (card.allDone) Badge("✓", Done, Background)
                }
            }
            if (!displayCollapsed) GoalBlock(card.goalDisplay, card.perHand, accent)
        }

        // Widen this section 10dp into the card gutter so the TOP row can bleed
        // (see [bleedHorizontal]); every other element is inset back to the card
        // content edge. fillMaxWidth keeps the width constant so only height
        // animates on collapse.
        val rowInset = Modifier.padding(horizontal = 10.dp)
        Column(Modifier.bleedHorizontal(10.dp).fillMaxWidth().animateContentSize(tween(320))) {
            if (displayCollapsed) {
                Spacer(Modifier.size(6.dp))
                Text(card.collapsedSummary, color = TextSecondary, style = SummaryLine, modifier = rowInset)
            } else {
                if (card.isMain) {
                    Spacer(Modifier.size(6.dp))
                    Text(DayScreenBuilder.MAIN_HELPER, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = rowInset)
                }
                if (card.isSuperset) {
                    Spacer(Modifier.size(6.dp))
                    Text(DayScreenBuilder.SUPERSET_HELPER, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = rowInset)
                }

                Spacer(Modifier.size(4.dp))
                var cascadeOrdinal = 0
                card.rows.forEach { row ->
                    val ordinal = cascadeOrdinal
                    if (!row.isTop) cascadeOrdinal++
                    SetRow(
                        kindLabel = row.kindLabel,
                        accent = accent,
                        accentSoft = accentSoftColor,
                        weight = row.weightDisplay,
                        onWeightChange = {
                            actions.onWeightChange(card.programExerciseId, Slot.MAIN, row.index, WeightStepper.round(it, unit))
                        },
                        weightStep = { WeightStepper.increment(it, unit) },
                        weightFormat = WeightStepper::format,
                        weightRound = { WeightStepper.round(it, unit) },
                        reps = row.reps,
                        onRepsChange = { actions.onRepsChange(card.programExerciseId, Slot.MAIN, row.index, it) },
                        modifier = if (row.isTop) Modifier else rowInset,
                        isTop = row.isTop,
                        ticked = row.done,
                        onToggleDone = { actions.onToggleDone(card.programExerciseId, row.index, it, card.isSuperset) },
                        onRemove = { actions.onRemoveSet(card.programExerciseId, row.index, card.isSuperset) },
                        cascadeOrdinal = ordinal,
                    )
                    val partner = row.partner
                    if (card.isSuperset && partner != null) {
                        SetRow(
                            kindLabel = "",
                            accent = accent,
                            accentSoft = accentSoftColor,
                            weight = partner.weightDisplay,
                            onWeightChange = {
                                actions.onWeightChange(card.programExerciseId, Slot.SS, row.index, WeightStepper.round(it, unit))
                            },
                            weightStep = { WeightStepper.increment(it, unit) },
                            weightFormat = WeightStepper::format,
                            weightRound = { WeightStepper.round(it, unit) },
                            reps = partner.reps,
                            onRepsChange = { actions.onRepsChange(card.programExerciseId, Slot.SS, row.index, it) },
                            // 10dp base inset + 30dp superset indent; dims with the round's tick.
                            modifier = Modifier.padding(start = 40.dp, end = 10.dp),
                            isSubRow = true,
                            ticked = row.done,
                        )
                    }
                }
                if (card.rows.isNotEmpty()) {
                    Spacer(Modifier.size(2.dp))
                    AddSetButton(modifier = rowInset, isSuperset = card.isSuperset) { actions.onAddSet(card.programExerciseId, card.isSuperset) }
                }
            }
        }
    }
}

// --- small pieces ------------------------------------------------------------

@Composable
private fun GoalBlock(goalDisplay: String, perHand: Boolean, accent: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text("GOAL", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(goalDisplay, color = accent, style = MaterialTheme.typography.displayLarge)
        if (perHand) {
            Text("/hand", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Badge(text: String, fill: Color, textColor: Color, outlined: Boolean = false) {
    Box(
        modifier = Modifier
            .background(fill, RoundedCornerShape(4.dp))
            .then(if (outlined) Modifier.border(1.dp, Border, RoundedCornerShape(4.dp)) else Modifier)
            // Outline badges pad 7/2 (tighter, to offset the 1px border); filled 8/3.
            .padding(horizontal = if (outlined) 7.dp else 8.dp, vertical = if (outlined) 2.dp else 3.dp),
    ) {
        Text(text.uppercase(), color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AddSetButton(modifier: Modifier = Modifier, isSuperset: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .dashedBorder(Border, radius = 8.dp)
            .background(if (pressed) Surface2 else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (isSuperset) "+ ADD ROUND" else "+ ADD SET",
            color = TextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// --- cardio, done, footer ----------------------------------------------------

@Composable
private fun CardioCard(cardio: CardioSuggestion) {
    // Saveable so LazyColumn eviction and rotation don't snap it shut (defaults closed).
    var open by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "cardioChevron")
    AppCard(modifier = Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Cardio finisher",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                )
                Spacer(Modifier.size(5.dp))
                Badge(
                    if (cardio.hard) "HARD · ${cardio.label}" else "EASY · ${cardio.label}",
                    Color.Transparent,
                    TextSecondary,
                    outlined = true,
                )
            }
            Text(
                "▼",
                color = TextFaint,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        Column(Modifier.animateContentSize(tween(320))) {
            if (open) {
                Spacer(Modifier.size(10.dp))
                Text(cardio.detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DoneButton(nextDayId: String?, accent: Color, onAccent: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "doneButtonPress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(56.dp)
            .background(accent, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (nextDayId != null) "DONE — ADVANCE TO DAY ${nextDayId.uppercase()}" else "DONE",
            color = onAccent,
            style = DoneButtonLabel,
        )
    }
}

@Composable
private fun Footer(state: DayUiState, accent: Color, onAccent: Color, actions: DayActions) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Rotation, not calendar — days advance when you finish them, not on a schedule. " +
                "The weights you log are your living record; GOALs are a reference, not a rule.",
            color = TextFaint,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            QuietButton(onClick = actions.onClearChecks)
            KeepScreenOnSwitch(
                checked = state.keepScreenOn,
                onCheckedChange = actions.onKeepScreenOnChange,
                accent = accent,
                onAccent = onAccent,
            )
        }
    }
}

@Composable
private fun QuietButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .border(1.dp, Border, RoundedCornerShape(50))
            .background(if (pressed) Surface2 else Color.Transparent, RoundedCornerShape(50))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        // The one labelLarge element the reference leaves mixed-case (no caps).
        Text("Clear today's checkmarks", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun KeepScreenOnSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, accent: Color, onAccent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) },
    ) {
        Text("Keep screen on", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        val trackColor by animateColorAsState(if (checked) accent else Surface2, tween(200), label = "switchTrack")
        val thumbOffset by animateFloatAsState(if (checked) 18f else 2f, tween(200), label = "switchThumb")
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(24.dp)
                .background(trackColor, RoundedCornerShape(50))
                .border(1.dp, if (checked) accent else Border, RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset.dp, top = 2.dp)
                    .size(18.dp)
                    .background(if (checked) onAccent else TextSecondary, CircleShape),
            )
        }
    }
}

// --- modifiers / effects -----------------------------------------------------

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
    drawRoundRect(color = color, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
}

/**
 * Renders [bleed] wider on each side than the width it reports to its parent,
 * placed shifted left by [bleed] — so the child spills symmetrically into the
 * parent's padding without changing the parent's layout. The TOP set row uses
 * this to bleed into the card gutter; because the widened bounds belong to this
 * node, the clip inside `animateContentSize` no longer cuts the fill.
 */
private fun Modifier.bleedHorizontal(bleed: Dp): Modifier = layout { measurable, constraints ->
    // No bleed under an unbounded-width parent (nothing to spill into).
    val extra = if (constraints.hasBoundedWidth) bleed.roundToPx() * 2 else 0
    val widened =
        if (extra > 0) constraints.copy(maxWidth = constraints.maxWidth + extra) else constraints
    val placeable = measurable.measure(widened)
    val reported = (placeable.width - extra).coerceAtLeast(0)
    layout(reported, placeable.height) { placeable.place(if (extra > 0) -bleed.roundToPx() else 0, 0) }
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

// --- preview: the reference scenario (day_screen_reference.html) ------------

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun DayScreenPreview() {
    fun row(index: Int, kind: String, isTop: Boolean, w: Double, r: Int, done: Boolean = false) =
        SetRowState(index = index, kindLabel = kind, isTop = isTop, weightDisplay = w, reps = r, done = done)

    val previewState = DayUiState(
        hasProgram = true,
        tabs = listOf(
            DayTab("A", 0, isSuggested = false, isSelected = true),
            DayTab("B", 1, isSuggested = true, isSelected = false),
            DayTab("C", 2, isSuggested = false, isSelected = false),
            DayTab("D", 3, isSuggested = false, isSelected = false),
        ),
        viewDayId = "A",
        dayIndex = 0,
        dayTitle = "Lower — squat focus",
        emphasisLine = "hip-hinge hamstrings · gastroc calves",
        unit = WeightUnit.LB,
        suggestedDayId = "B",
        nextDayId = "B",
        exercises = listOf(
            ExerciseCardState(
                programExerciseId = 1,
                title = "Barbell Back Squat",
                isMain = true,
                isSuperset = false,
                hasWarmupHint = true,
                goalDisplay = "235",
                perHand = false,
                allDone = false,
                collapsed = false,
                collapsedSummary = "5 sets · GOAL 235",
                rows = listOf(
                    row(0, "R1", isTop = false, w = 130.0, r = 5),
                    row(1, "R2", isTop = false, w = 165.0, r = 5),
                    row(2, "R3", isTop = false, w = 190.0, r = 5),
                    row(3, "R4", isTop = false, w = 210.0, r = 3),
                    row(4, "TOP", isTop = true, w = 235.0, r = 5),
                    row(5, "B/O", isTop = false, w = 175.0, r = 8),
                ),
            ),
            ExerciseCardState(
                programExerciseId = 2,
                title = "SS: EZ-Bar Curl + Rope Pushdown",
                isMain = false,
                isSuperset = true,
                hasWarmupHint = false,
                goalDisplay = "60",
                perHand = false,
                partnerGoalDisplay = "50",
                allDone = false,
                collapsed = false,
                collapsedSummary = "3 sets · GOAL 60",
                rows = listOf(
                    SetRowState(0, "1", false, 60.0, 12, false, PartnerRowState(50.0, 15)),
                    SetRowState(1, "2", false, 60.0, 11, false, PartnerRowState(50.0, 14)),
                    SetRowState(2, "3", false, 60.0, 10, false, PartnerRowState(50.0, 12)),
                ),
            ),
            ExerciseCardState(
                programExerciseId = 3,
                title = "Seated Leg Curl",
                isMain = false,
                isSuperset = false,
                hasWarmupHint = false,
                goalDisplay = "90",
                perHand = false,
                allDone = true,
                collapsed = true,
                collapsedSummary = "90×10 · 90×10 · 90×9",
                rows = listOf(
                    row(0, "1", false, 90.0, 10, done = true),
                    row(1, "2", false, 90.0, 10, done = true),
                    row(2, "3", false, 90.0, 9, done = true),
                ),
            ),
        ),
        cardio = CardioSuggestion("Zone 2", "20-25 min easy — legs were heavy today, keep it conversational.", hard = false),
        keepScreenOn = false,
    )

    AppTheme {
        DayScreen(
            state = previewState,
            actions = DayActions(
                onSelectDay = {},
                onWeightChange = { _, _, _, _ -> },
                onRepsChange = { _, _, _, _ -> },
                onToggleDone = { _, _, _, _ -> },
                onAddSet = { _, _ -> },
                onRemoveSet = { _, _, _ -> },
                onToggleCollapse = {},
                onKeepScreenOnChange = {},
                onClearChecks = {},
                onDone = {},
                onOpenSettings = {},
            ),
        )
    }
}
