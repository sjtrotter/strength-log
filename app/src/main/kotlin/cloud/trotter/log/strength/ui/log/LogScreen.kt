package cloud.trotter.log.strength.ui.log

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.transfer.health.ExternalSessionRow
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.BackAction
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.CheckmarkToggle
import cloud.trotter.log.strength.ui.components.Stepper
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.units.SecondsStepper
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.theme.StepperRepsValue
import cloud.trotter.log.strength.ui.components.DayBadge
import cloud.trotter.log.strength.ui.components.EmptyJournalState
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.BorderStrong
import cloud.trotter.log.strength.ui.theme.SummaryLine
import cloud.trotter.log.strength.ui.theme.Surface3
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.accentEmphasis
import cloud.trotter.log.strength.ui.theme.readableWidth
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.text.resolve
import kotlinx.coroutines.launch

/**
 * The journal (PLAN.md A1, issue #14; docs/briefs/journal.md): the three
 * derived sections — trajectory, volume, calendar — over the read-only,
 * reverse-chronological list of completed sessions that has always lived here.
 * Expanding a row shows that session's sets grouped by exercise. Stateless in
 * the Compose sense, matching [cloud.trotter.log.strength.ui.day.DayScreen]
 * — [state] renders, [actions] carries every intent back to [LogViewModel].
 */
@Composable
fun LogScreen(state: LogUiState, actions: LogActions) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var deleteConfirmation by remember { mutableStateOf<Long?>(null) }
    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            LogHeader(actions.onBack)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }

                if (state.journal.trajectories.isNotEmpty()) {
                    item(key = "trajectory-header") { LogSectionHeader(stringResource(R.string.log_trajectory_header)) }
                    items(state.journal.trajectories, key = { "traj-${it.exerciseId}" }) { card ->
                        TrajectoryCardView(card)
                    }
                }
                state.journal.volume?.let { volume ->
                    item(key = "volume-header") { LogSectionHeader(stringResource(R.string.log_volume_header)) }
                    item(key = "volume") { VolumeCardView(volume) }
                }
                state.journal.calendar?.let { calendar ->
                    item(key = "calendar-header") { LogSectionHeader(stringResource(R.string.log_calendar_header)) }
                    item(key = "calendar") {
                        CalendarCardView(
                            month = calendar,
                            onPage = actions.onPageCalendar,
                            onSelectSession = { sessionId ->
                                sessionRowIndex(state, sessionId)?.let { index ->
                                    scope.launch { listState.animateScrollToItem(index) }
                                }
                            },
                        )
                    }
                }

                state.health.bodyweightPrompt?.let { prompt ->
                    item(key = "bw-prompt") {
                        BodyweightPromptCard(prompt, actions.onApplyBodyweight, actions.onDismissBodyweight)
                    }
                }
                if (state.health.available) {
                    item(key = "hc-status") {
                        HealthConnectSection(
                            section = state.health,
                            onConnect = actions.onConnectHealth,
                            onPublishPast = actions.onPublishPastWorkouts,
                        )
                    }
                }

                if (state.sessions.isEmpty()) {
                    item(key = "empty") {
                        EmptyJournalState(
                            hasProgram = state.hasProgram,
                            onStartSession = actions.onStartSession,
                            onSetUpProgram = actions.onSetUpProgram,
                        )
                    }
                } else {
                    items(state.sessions, key = { if (it.cardioId != null) "cardio-${it.cardioId}" else "strength-${it.sessionId}" }) { session ->
                        if (session.undoPending) UndoDeletedSessionRow(session.dayIndex, actions.onUndoDeleteSession) else if (session.cardioId != null) CardioSessionCard(session) else
                            SessionCard(
                                session,
                                onToggle = { actions.onToggleExpanded(session.sessionId) },
                                onShare = { actions.onShare(session.sessionId) },
                                onToggleEdit = { actions.onToggleEdit(session.sessionId) },
                                onWeightChange = { setId, value -> actions.onWeightChange(session.sessionId, setId, value) },
                                onRepsChange = { setId, value -> actions.onRepsChange(session.sessionId, setId, value) },
                                onSecondsChange = { setId, value -> actions.onSecondsChange(session.sessionId, setId, value) },
                                onDoneChange = { setId, value -> actions.onDoneChange(session.sessionId, setId, value) },
                                onDelete = { deleteConfirmation = session.sessionId },
                            )
                    }
                }

                if (state.health.externalSessions.isNotEmpty()) {
                    item(key = "external-header") { LogSectionHeader(stringResource(R.string.log_external_header)) }
                    items(state.health.externalSessions, key = { "ext-${it.title}-${it.dateDisplay}-${it.sourceLabel}" }) { row ->
                        ExternalSessionCard(row)
                    }
                }

                item { Spacer(Modifier.size(8.dp)) }
            }
        }
        deleteConfirmation?.let { sessionId ->
            AppAlertDialog(
                onDismissRequest = { deleteConfirmation = null },
                title = { Text(stringResource(R.string.log_delete_dialog_title)) },
                text = { Text(stringResource(R.string.log_delete_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteConfirmation = null
                        actions.onDeleteSession(sessionId)
                    }) { Text(stringResource(R.string.log_delete_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmation = null }) { Text(stringResource(R.string.log_delete_keep)) }
                },
            )
        }
    }
}

@Composable
private fun CardioSessionCard(item: SessionListItem) {
    val spoken = stringResource(R.string.log_cardio_semantics, item.cardioSemantics.orEmpty(), item.cardioDuration.orEmpty())
    val summary = stringResource(R.string.log_cardio_summary, item.cardioSummary.orEmpty(), item.cardioDuration.orEmpty())
    AppCard(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = spoken
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DayBadge(dayIndex = item.dayIndex, letter = item.dayLetter)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.dayTitle, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text(item.dateDisplay, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(summary, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The LazyColumn index of [sessionId]'s row, counting the items emitted above it
 * — the best-effort target for a calendar tap (§1.3: no new state, no scroll
 * bookkeeping). Must be kept in step with the item order in [LogScreen] above;
 * an unknown session simply yields null and the tap does nothing.
 */
private fun sessionRowIndex(state: LogUiState, sessionId: Long): Int? {
    val position = state.sessions.indexOfFirst { it.sessionId == sessionId }
    if (position < 0) return null
    var index = 1 // leading spacer
    if (state.journal.trajectories.isNotEmpty()) index += 1 + state.journal.trajectories.size
    if (state.journal.volume != null) index += 2
    if (state.journal.calendar != null) index += 2
    if (state.health.bodyweightPrompt != null) index += 1
    if (state.health.available) index += 1
    return index + position
}

@Composable
private fun LogHeader(onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackAction(onBack)
            Text(stringResource(R.string.log_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(thickness = 1.dp, color = Border)
    }
}

@Composable
private fun SessionCard(
    item: SessionListItem,
    onToggle: () -> Unit,
    onShare: () -> Unit,
    onToggleEdit: () -> Unit,
    onWeightChange: (Long, Double) -> Unit,
    onRepsChange: (Long, Int) -> Unit,
    onSecondsChange: (Long, Int) -> Unit,
    onDoneChange: (Long, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (item.expanded) 180f else 0f, tween(200), label = "logChevron")
    val disclosureLabel = stringResource(if (item.expanded) R.string.log_collapse_label else R.string.log_expand_label)
    val disclosureState = stringResource(if (item.expanded) R.string.log_expanded_state else R.string.log_collapsed_state)
    AppCard {
        // SHARE is a separate action when expanded, so disclosure belongs to
        // this header region rather than the M3 card container.
        Row(
            modifier = Modifier
                // Reserving the target here keeps it disjoint from the SHARE
                // row instead of borrowing touch space from another action.
                .minimumInteractiveComponentSize()
                .fillMaxWidth()
                .pressable(
                    onClickLabel = disclosureLabel,
                    role = Role.Button,
                    onClick = onToggle,
                )
                .semantics { stateDescription = disclosureState },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayBadge(dayIndex = item.dayIndex, letter = item.dayLetter)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.dayTitle, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text(item.dateDisplay, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    pluralStringResource(R.plurals.log_session_set_count, item.setCount, item.setCount),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                item.bodyweightDisplay?.let {
                    Text(stringResource(R.string.log_session_bodyweight, it), color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                "\u25BC",
                color = TextFaint,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.rotate(chevronRotation).clearAndSetSemantics {},
            )
        }
        Column(Modifier.animateContentSize(tween(220))) {
            if (item.expanded) {
                Spacer(Modifier.size(10.dp))
                if (item.exerciseGroups == null) {
                    Text(stringResource(R.string.log_session_loading), color = TextFaint, style = MaterialTheme.typography.bodySmall)
                } else {
                    item.exerciseGroups.forEach { group ->
                        if (item.editing) EditableExerciseGroupRow(
                            group, item.unit, onWeightChange, onRepsChange, onSecondsChange, onDoneChange,
                        ) else ExerciseGroupRow(group)
                    }
                    Spacer(Modifier.size(4.dp))
                    SessionFooter(item, onShare, onToggleEdit, onDelete)
                }
            }
        }
    }
}

@Composable
private fun EditableExerciseGroupRow(
    group: SessionExerciseGroup,
    unit: cloud.trotter.log.strength.domain.units.WeightUnit,
    onWeightChange: (Long, Double) -> Unit,
    onRepsChange: (Long, Int) -> Unit,
    onSecondsChange: (Long, Int) -> Unit,
    onDoneChange: (Long, Boolean) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(group.exerciseName, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
        group.sets.forEach { set ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(set.kindLabel, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                if (set.tracking == TrackingType.WEIGHTED ||
                    (set.tracking == TrackingType.TIMED && set.weightLb > 0.0)
                ) {
                    Stepper(
                        value = unit.fromLb(set.weightLb),
                        onValueChange = { onWeightChange(set.id, it) },
                        step = { WeightStepper.increment(it, unit) },
                        round = { WeightStepper.round(it, unit) },
                        format = WeightStepper::format,
                    )
                }
                if (set.tracking == TrackingType.TIMED) {
                    Stepper(
                        value = set.seconds.toDouble(),
                        onValueChange = { onSecondsChange(set.id, it.toInt()) },
                        step = { SecondsStepper.increment(it.toInt()).toDouble() },
                        format = { SecondsStepper.format(it.toInt()) },
                        valueTextStyle = StepperRepsValue,
                    )
                } else {
                    Stepper(
                        value = set.reps.toDouble(),
                        onValueChange = { onRepsChange(set.id, it.toInt()) },
                        step = { 1.0 },
                        minValue = 1.0,
                        format = { it.toInt().toString() },
                        valueTextStyle = StepperRepsValue,
                        valueMinWidth = 36.dp,
                    )
                }
                Spacer(Modifier.weight(1f))
                CheckmarkToggle(checked = set.done, onCheckedChange = { onDoneChange(set.id, it) })
            }
        }
    }
}

@Composable
private fun SessionFooter(item: SessionListItem, onShare: () -> Unit, onToggleEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (item.editing) {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) { Text(stringResource(R.string.log_delete_session), style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.weight(1f))
        if (!item.editing) ShareButton(dayIndex = item.dayIndex, onClick = onShare)
        TextButton(onClick = onToggleEdit) {
            Text(stringResource(if (item.editing) R.string.log_edit_done else R.string.log_edit), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun UndoDeletedSessionRow(dayIndex: Int, onUndo: () -> Unit) {
    TextButton(
        onClick = onUndo,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = accentEmphasis(dayIndex)),
    ) {
        Text(stringResource(R.string.log_session_deleted), color = TextFaint, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.log_undo), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ExerciseGroupRow(group: SessionExerciseGroup) {
    val separator = " \u00B7 "
    val summaries = group.sets.map {
        stringResource(R.string.log_set_summary, it.kindLabel, it.weightRepsDisplay)
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(group.exerciseName, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
        Text(
            summaries.joinToString(separator),
            color = TextSecondary,
            style = SummaryLine,
        )
    }
}

/**
 * The share affordance (#103, docs/briefs/session-share.md §1): one quiet
 * text button in the expanded row's action register, caps, pressed = that
 * day's accent — no icon, no fill, nothing on a collapsed row. Since the M3
 * card migration the disclosure lives on the header row, so SHARE is a
 * *sibling* action, not a nested one — it never also toggles the row because
 * nothing above it is clickable at all.
 *
 * Its M3-owned 48dp target (#123) is still *reserved* rather than borrowed, so
 * it lands on the row's own air instead of claiming the detail lines above it.
 */
@Composable
private fun ShareButton(dayIndex: Int, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shareLabel = stringResource(R.string.log_share_label)
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { onClick(label = shareLabel, action = null) },
        interactionSource = interactionSource,
        colors = ButtonDefaults.textButtonColors(contentColor = if (pressed) accentEmphasis(dayIndex) else TextSecondary),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
    ) { Text(stringResource(R.string.log_share_button), style = MaterialTheme.typography.labelLarge) }
}

/**
 * The "bodyweight changed — update your GOALs?" prompt (#17, A3). Surfaced, never
 * auto-applied: Apply writes the new bodyweight into config; Dismiss leaves GOALs
 * exactly as they were.
 */
@Composable
private fun BodyweightPromptCard(prompt: BodyweightPromptUi, onApply: () -> Unit, onDismiss: () -> Unit) {
    AppCard {
        Text(stringResource(R.string.log_bodyweight_title), color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(R.string.log_bodyweight_body, prompt.healthConnectDisplay, prompt.currentDisplay),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PromptButton(text = stringResource(R.string.log_bodyweight_update_button), emphasized = true, onClick = onApply)
            PromptButton(text = stringResource(R.string.log_bodyweight_dismiss_button), emphasized = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun PromptButton(text: String, emphasized: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = { Text(text, style = MaterialTheme.typography.labelLarge) }
    if (emphasized) {
        FilledTonalButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Surface3, contentColor = TextPrimary),
            border = BorderStroke(1.dp, BorderStrong),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = { content() },
        )
    }
}

/**
 * What Health Connect is actually doing (#158). Before this, a provider that had
 * never been granted and one whose grant had been reset looked the same from
 * here — nothing published, nothing said. Two states, no ambiguity: without the
 * workout-write grant the section *is* the Connect affordance; with it, one
 * quiet line, carrying the one-shot backfill offer (#159) while there is history
 * Health Connect has never seen. The status is a statement, not a card: a
 * working export shouldn't ask for attention every visit.
 */
@Composable
private fun HealthConnectSection(section: HealthSectionUi, onConnect: () -> Unit, onPublishPast: () -> Unit) {
    if (!section.publishing) {
        ConnectHealthCard(onConnect)
        return
    }
    Column {
        Text(stringResource(R.string.log_health_connected), color = TextFaint, style = MaterialTheme.typography.bodySmall)
        section.backfill?.let { offer ->
            TextButton(
                onClick = onPublishPast,
                enabled = offer.enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) { Text(offer.label.resolve(), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Shown when a Health Connect provider is present but the workout-write grant
 *  isn't (#17, #158): the lazy, user-initiated permission entry point. */
@Composable
private fun ConnectHealthCard(onConnect: () -> Unit) {
    AppCard(onClick = onConnect) {
        Text(stringResource(R.string.log_health_connect_title), color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(R.string.log_health_connect_body),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** One external strength session from Health Connect — clearly labeled so it's
 *  never confused with the user's own logged history. */
@Composable
private fun ExternalSessionCard(row: ExternalSessionRow) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(row.dateDisplay, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            // Another app's provenance is body copy; caps overlines mark our sections.
            Text(row.sourceLabel, color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Callbacks the screen forwards to [LogViewModel]. */
data class LogActions(
    val onBack: () -> Unit,
    val onToggleExpanded: (Long) -> Unit,
    val onPageCalendar: (Int) -> Unit,
    val onConnectHealth: () -> Unit,
    /** The one-shot backfill tap (#159): publish the history that predates the grant. */
    val onPublishPastWorkouts: () -> Unit,
    val onApplyBodyweight: () -> Unit,
    val onDismissBodyweight: () -> Unit,
    val onShare: (Long) -> Unit,
    val onToggleEdit: (Long) -> Unit = {},
    val onWeightChange: (Long, Long, Double) -> Unit = { _, _, _ -> },
    val onRepsChange: (Long, Long, Int) -> Unit = { _, _, _ -> },
    val onSecondsChange: (Long, Long, Int) -> Unit = { _, _, _ -> },
    val onDoneChange: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    val onDeleteSession: (Long) -> Unit = {},
    val onUndoDeleteSession: () -> Unit = {},
    /** The empty journal's way out (#127): straight to the workout that will fill it. */
    val onStartSession: () -> Unit,
    /** The same slot's way out when there is no program to start (#127). */
    val onSetUpProgram: () -> Unit,
)

@Preview(showBackground = true, heightDp = 700, backgroundColor = 0xFF0D0D0F)
@Composable
private fun LogScreenPreview() {
    LogScreenPreviewContent(ThemePreference.DARK)
}

@Preview(showBackground = true, heightDp = 700, backgroundColor = 0xFFF1EFEA)
@Composable
private fun LogScreenLightPreview() {
    LogScreenPreviewContent(ThemePreference.LIGHT)
}

@Composable
private fun LogScreenPreviewContent(theme: ThemePreference) {
    val state = LogUiState(
        health = HealthSectionUi(
            available = true,
            publishing = true,
            backfill = BackfillOfferUi(label = LogScreenBuilder.backfillLabel(12, false), enabled = true),
        ),
        sessions = listOf(
            SessionListItem(
                sessionId = 2,
                dateDisplay = "Jul 6, 2026",
                dayLetter = "A",
                dayIndex = 0,
                dayTitle = "Lower — squat focus",
                setCount = 9,
                bodyweightDisplay = "182",
                expanded = true,
                exerciseGroups = listOf(
                    SessionExerciseGroup(
                        "Barbell Back Squat",
                        listOf(
                            SessionSetSummary("R1", "130×5"),
                            SessionSetSummary("TOP", "235×5"),
                            SessionSetSummary("B/O", "175×8"),
                        ),
                    ),
                    SessionExerciseGroup(
                        "Seated Leg Curl",
                        listOf(SessionSetSummary("1", "90×10"), SessionSetSummary("2", "90×10")),
                    ),
                ),
            ),
            SessionListItem(
                sessionId = 1,
                dateDisplay = "Jul 3, 2026",
                dayLetter = "B",
                dayIndex = 1,
                dayTitle = "Upper — push focus",
                setCount = 12,
                bodyweightDisplay = "181",
                expanded = false,
            ),
        ),
        journal = JournalUiState(
            trajectories = listOf(
                TrajectoryCard(
                    exerciseId = "bb_back_squat",
                    exerciseName = "Barbell Back Squat",
                    dayIndex = 0,
                    points = listOf(
                        TrajectoryPoint(205f, newHigh = true),
                        TrajectoryPoint(205f, newHigh = false),
                        TrajectoryPoint(215f, newHigh = true),
                        TrajectoryPoint(215f, newHigh = false),
                        TrajectoryPoint(225f, newHigh = true),
                        TrajectoryPoint(235f, newHigh = true),
                    ),
                    goalValue = 235f,
                    goalLabel = "GOAL 235",
                    goalMet = true,
                    latestLabel = "235",
                    caption = "6 SESSIONS · SINCE MAY 4",
                    axisMin = 200f,
                    axisMax = 240f,
                    gridlines = listOf(TrajectoryGridline(205f, "205"), TrajectoryGridline(235f, "235")),
                ),
            ),
            volume = VolumeChart(
                bars = listOf(0.4f, 0.6f, 0f, 0.7f, 0.9f, 1f, 0.8f, 0f, 0.5f, 0.7f, 0.85f, 0.62f)
                    .mapIndexed { i, fraction ->
                        VolumeBar(
                            fraction = fraction,
                            trained = fraction > 0f,
                            label = when (i) {
                                5 -> "19.8K"
                                11 -> "12.4K"
                                else -> null
                            },
                        )
                    },
            ),
            calendar = CalendarMonth(
                title = "JULY 2026",
                monthOffset = 0,
                canPageBack = true,
                canPageForward = false,
                leadingBlanks = 2,
                days = (1..31).map { day ->
                    val dayLetter = when (day % 7) {
                        1 -> "A"
                        3 -> "B"
                        5 -> "C"
                        else -> null
                    }
                    val spoken = if (dayLetter != null) "day $dayLetter, 1 session" else "no session"
                    CalendarDay(
                        dayOfMonth = day,
                        label = if (day == 6) "July $day, today, $spoken" else "July $day, $spoken",
                        dayLetter = dayLetter,
                        dayIndex = day % 3,
                        sessionId = day.toLong(),
                        isToday = day == 6,
                    )
                },
            ),
        ),
    )

    AppTheme(preference = theme) {
        LogScreen(
            state = state,
            actions = LogActions(
                onBack = {},
                onToggleExpanded = {},
                onPageCalendar = {},
                onConnectHealth = {},
                onPublishPastWorkouts = {},
                onApplyBodyweight = {},
                onDismissBodyweight = {},
                onShare = {},
                onStartSession = {},
                onSetUpProgram = {},
            ),
        )
    }
}
