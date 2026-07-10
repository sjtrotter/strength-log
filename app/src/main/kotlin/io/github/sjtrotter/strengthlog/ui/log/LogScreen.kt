package io.github.sjtrotter.strengthlog.ui.log

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.transfer.health.ExternalSessionRow
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.DayBadge
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.BorderStrong
import io.github.sjtrotter.strengthlog.ui.theme.SummaryLine
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.Surface3
import io.github.sjtrotter.strengthlog.ui.theme.TabLetter
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary

/**
 * The History Log screen (PLAN.md A1, issue #14): a read-only, reverse-
 * chronological list of completed sessions. Expanding a row shows that
 * session's sets grouped by exercise. No charts (deferred to v2, PLAN.md A10).
 * Stateless in the Compose sense, matching [io.github.sjtrotter.strengthlog
 * .ui.day.DayScreen] — [state] renders, [actions] carries every intent back
 * to [LogViewModel].
 */
@Composable
fun LogScreen(state: LogUiState, actions: LogActions) {
    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            LogHeader(actions.onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }

                state.health.bodyweightPrompt?.let { prompt ->
                    item(key = "bw-prompt") {
                        BodyweightPromptCard(prompt, actions.onApplyBodyweight, actions.onDismissBodyweight)
                    }
                }
                if (state.health.available && !state.health.connected) {
                    item(key = "hc-connect") { ConnectHealthCard(actions.onConnectHealth) }
                }

                if (state.sessions.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No workouts logged yet.",
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 40.dp),
                        )
                    }
                } else {
                    items(state.sessions, key = { it.sessionId }) { session ->
                        SessionCard(session, onToggle = { actions.onToggleExpanded(session.sessionId) })
                    }
                }

                if (state.health.externalSessions.isNotEmpty()) {
                    item(key = "external-header") { ExternalSectionHeader() }
                    items(state.health.externalSessions, key = { "ext-${it.title}-${it.dateDisplay}-${it.sourceLabel}" }) { row ->
                        ExternalSessionCard(row)
                    }
                }

                item { Spacer(Modifier.size(8.dp)) }
            }
        }
    }
}

@Composable
private fun LogHeader(onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackButton(onBack)
            Text("Log", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Back", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Text("←", color = TextSecondary, style = TabLetter, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun SessionCard(item: SessionListItem, onToggle: () -> Unit) {
    val chevronRotation by animateFloatAsState(if (item.expanded) 180f else 0f, tween(200), label = "logChevron")
    AppCard(
        modifier = Modifier
            .clickable(onClickLabel = if (item.expanded) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (item.expanded) "Expanded" else "Collapsed" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayBadge(dayIndex = item.dayIndex, letter = item.dayLetter)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.dayTitle, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text(item.dateDisplay, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${item.setCount} sets", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("BW ${item.bodyweightDisplay}", color = TextFaint, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                "▼",
                color = TextFaint,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.rotate(chevronRotation).clearAndSetSemantics {},
            )
        }
        Column(Modifier.animateContentSize(tween(220))) {
            if (item.expanded) {
                Spacer(Modifier.size(10.dp))
                if (item.exerciseGroups == null) {
                    Text("Loading…", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                } else {
                    item.exerciseGroups.forEach { group -> ExerciseGroupRow(group) }
                }
            }
        }
    }
}

@Composable
private fun ExerciseGroupRow(group: SessionExerciseGroup) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(group.exerciseName, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
        Text(
            group.sets.joinToString(" · ") { "${it.kindLabel}: ${it.weightRepsDisplay}" },
            color = TextSecondary,
            style = SummaryLine,
        )
    }
}

/**
 * The "bodyweight changed — update your GOALs?" prompt (#17, A3). Surfaced, never
 * auto-applied: Apply writes the new bodyweight into config; Dismiss leaves GOALs
 * exactly as they were.
 */
@Composable
private fun BodyweightPromptCard(prompt: BodyweightPromptUi, onApply: () -> Unit, onDismiss: () -> Unit) {
    AppCard {
        Text("Bodyweight changed", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            "Health Connect has ${prompt.healthConnectDisplay}; your GOALs use ${prompt.currentDisplay}. " +
                "Update your bodyweight to recompute GOALs?",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PromptButton(text = "Update", emphasized = true, onClick = onApply)
            PromptButton(text = "Not now", emphasized = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun PromptButton(text: String, emphasized: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (emphasized) Surface3 else Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, if (emphasized) BorderStrong else Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, color = if (emphasized) TextPrimary else TextSecondary, style = MaterialTheme.typography.labelLarge)
    }
}

/** Shown when a Health Connect provider is present but not yet permitted (#17):
 *  the lazy, user-initiated permission entry point. */
@Composable
private fun ConnectHealthCard(onConnect: () -> Unit) {
    AppCard(modifier = Modifier.clickable(onClick = onConnect)) {
        Text("Connect Health Connect", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            "Share your workouts and see sessions logged by other apps. On-device only — nothing leaves your phone.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ExternalSectionHeader() {
    Column(Modifier.padding(top = 8.dp)) {
        Text("FROM OTHER APPS", color = TextFaint, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.size(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
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
            Text(row.sourceLabel, color = TextFaint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Callbacks the screen forwards to [LogViewModel]. */
data class LogActions(
    val onBack: () -> Unit,
    val onToggleExpanded: (Long) -> Unit,
    val onConnectHealth: () -> Unit,
    val onApplyBodyweight: () -> Unit,
    val onDismissBodyweight: () -> Unit,
)

@Preview(showBackground = true, heightDp = 700, backgroundColor = 0xFF0D0D0F)
@Composable
private fun LogScreenPreview() {
    val state = LogUiState(
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
    )

    AppTheme {
        LogScreen(
            state = state,
            actions = LogActions(
                onBack = {},
                onToggleExpanded = {},
                onConnectHealth = {},
                onApplyBodyweight = {},
                onDismissBodyweight = {},
            ),
        )
    }
}
