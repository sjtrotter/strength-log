package cloud.trotter.log.strength.ui.today

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.components.LiftListItem
import cloud.trotter.log.strength.ui.components.NoProgramState
import cloud.trotter.log.strength.ui.components.ProgramLoadingState
import cloud.trotter.log.strength.ui.components.liftListItemColors
import cloud.trotter.log.strength.ui.text.TodayCoachKind
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.text.resolve
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import cloud.trotter.log.strength.ui.day.DaySelection

/**
 * The Today screen from the 2026-10-12 design handoff: a day index, a concise
 * plan, and one action. [state] renders and [actions] carries intents outward.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, actions: TodayActions) {
    if (!state.hasProgram) {
        if (state.loading) ProgramLoadingState() else NoProgramState(actions.onSetUpProgram)
        return
    }
    val planName = state.planName.takeIf { state.activePlans.size > 1 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.today_settings_description),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ReadableWidth)
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        DayIndex(
                            dayIndex = state.dayIndex,
                            letter = state.dayId,
                            size = DayIndexSize.Large,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            planName?.let { name ->
                                TextButton(
                                    onClick = actions.onShowPlanPicker,
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Text(
                                text = stringResource(R.string.today_day_label, state.dayId),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = state.dayTitle,
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (state.lifts.isEmpty() && state.cardio == null) {
                                Text(
                                    text = stringResource(R.string.day_no_exercises),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.coachLine?.takeIf { state.lifts.isNotEmpty() || state.cardio != null }?.let { coachLine ->
                                Text(
                                    text = coachLine.resolve(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    state.lifeLine?.let { lifeLine ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = lifeLine.resolve(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClick = actions.onOpenLog),
                        )
                    }

                    if (state.lifts.isNotEmpty() || state.cardio != null) {
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider()
                        state.lifts.forEachIndexed { index, lift ->
                            LiftListItem(index + 1, lift.name, lift.setCount)
                            HorizontalDivider()
                        }
                        state.cardio?.let { cardio ->
                            ListItem(
                                headlineContent = { Text(cardio.activity?.let { cloud.trotter.log.strength.ui.day.cardioActivityName(it) } ?: cardio.label) },
                                supportingContent = { Text(cardio.loggedSeconds?.let {
                                    stringResource(R.string.day_cardio_logged, cloud.trotter.log.strength.ui.day.formatCardioTime(it))
                                } ?: cardio.detail) },
                                colors = liftListItemColors(),
                            )
                            HorizontalDivider()
                        }
                    }

                    state.standaloneCardio?.let { cardioDay ->
                        TextButton(
                            onClick = { actions.onOpenCardio(DaySelection(state.planId, cardioDay.dayId)) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = dayAccent(state.dayIndex),
                            ),
                        ) {
                            Text(cardioDay.loggedSeconds?.let {
                                stringResource(R.string.day_cardio_logged, cloud.trotter.log.strength.ui.day.formatCardioTime(it))
                            } ?: cardioDay.line)
                        }
                    }
                }
                StartButton(
                    actionLabel = state.actionLabel.resolve(),
                    dayIndex = state.dayIndex,
                    onClick = { actions.onStart(DaySelection(state.planId, state.dayId)) },
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
    if (state.activePlans.size > 1 && state.planPickerOpen) {
        AppModalBottomSheet(
            onDismissRequest = actions.onDismissPlanPicker,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                text = stringResource(R.string.today_switch_plan),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().selectableGroup()) {
                items(state.activePlans, key = { it.id }) { plan ->
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = plan.selected,
                            role = Role.RadioButton,
                            onClick = { actions.onSelectPlan(plan.id) },
                        ),
                        leadingContent = { RadioButton(selected = plan.selected, onClick = null) },
                        headlineContent = { Text(plan.name) },
                        supportingContent = {
                            Text(
                                plan.nextDayTitle?.let { stringResource(R.string.plans_next_day, it) }
                                    ?: stringResource(R.string.plans_no_next_day),
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StartButton(
    actionLabel: String,
    dayIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "startButtonPress",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = dayAccent(dayIndex),
            contentColor = onDayAccent(dayIndex),
        ),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.titleMedium,
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

@Preview(showBackground = true, backgroundColor = 0xFFF1EFEA)
@Composable
private fun TodayScreenLightPreview() {
    TodayScreenPreviewContent(theme = ThemePreference.LIGHT)
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F)
@Composable
private fun TodayScreenInProgressPreview() {
    TodayScreenPreviewContent(inProgress = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0F, fontScale = 2.0f)
@Composable
private fun TodayScreenFontScale200Preview() {
    TodayScreenPreviewContent()
}

@Composable
private fun TodayScreenPreviewContent(
    inProgress: Boolean = false,
    theme: ThemePreference = ThemePreference.DARK,
) {
    val state = TodayUiState(
        hasProgram = true,
        dayId = "B",
        dayIndex = 1,
        dayTitle = "Lower body",
        coachLine = if (inProgress) {
            TodayScreenBuilder.progressLine(4, 18)
        } else {
            UiText.TodayCoach(TodayCoachKind.WEEKS, "4 Aug", 10)
        },
        lifts = listOf(
            TodayLift("Barbell back squat", 5),
            TodayLift("Romanian deadlift", 4),
            TodayLift("Bulgarian split squat", 3),
            TodayLift("Seated leg curl", 3),
            TodayLift("Standing calf raise", 3),
        ),
        cardio = TodayCardio("Zone 2", "20–25 min easy, conversational pace."),
        actionLabel = TodayScreenBuilder.actionLabel("B", if (inProgress) 4 else 0, 18),
    )
    AppTheme(preference = theme) {
        TodayScreen(
            state = state,
            actions = TodayActions(onStart = {}, onOpenSettings = {}, onOpenLog = {}, onSetUpProgram = {}),
        )
    }
}
