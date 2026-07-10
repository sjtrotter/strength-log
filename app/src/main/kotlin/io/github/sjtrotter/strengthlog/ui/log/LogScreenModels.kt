package io.github.sjtrotter.strengthlog.ui.log

import io.github.sjtrotter.strengthlog.transfer.health.ExternalSessionRow

/** Immutable render model for the Log screen (PLAN.md A1, issue #14, extended by
 *  the #17 Health Connect read path). */
data class LogUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val health: HealthSectionUi = HealthSectionUi(),
)

/**
 * The Log screen's Health Connect section (#17 read path). When no provider is
 * installed the section stays hidden ([available] false); when available but not
 * yet permitted it shows a single "Connect Health Connect" affordance; once
 * connected it lists other apps' sessions (clearly external) and, if the latest
 * recorded bodyweight differs from the configured one, offers [bodyweightPrompt].
 */
data class HealthSectionUi(
    val available: Boolean = false,
    val connected: Boolean = false,
    val externalSessions: List<ExternalSessionRow> = emptyList(),
    val bodyweightPrompt: BodyweightPromptUi? = null,
)

/**
 * The "bodyweight changed — update your GOALs?" prompt (#17, A3). Surfaced, never
 * auto-applied (GOAL-vs-ACTUAL): the user chooses to apply, which updates the
 * configured bodyweight, or dismisses.
 */
data class BodyweightPromptUi(
    val currentDisplay: String,
    val healthConnectDisplay: String,
)

/**
 * One reverse-chronological row: a completed [io.github.sjtrotter.strengthlog
 * .data.db.entity.WorkoutSessionEntity] plus its set count, always visible.
 * [exerciseGroups] is populated only once the row is expanded — most rows stay
 * collapsed, so the Log screen doesn't pay for every session's sets up front.
 */
data class SessionListItem(
    val sessionId: Long,
    val dateDisplay: String,
    val dayLetter: String,
    val dayIndex: Int,
    val dayTitle: String,
    val setCount: Int,
    val bodyweightDisplay: String,
    val expanded: Boolean,
    val exerciseGroups: List<SessionExerciseGroup>? = null,
)

/** One exercise's sets within an expanded session, in first-appearance order. */
data class SessionExerciseGroup(
    val exerciseName: String,
    val sets: List<SessionSetSummary>,
)

/** One logged set's display: its kind label (R1/TOP/B/O/plain number) and `w×r`. */
data class SessionSetSummary(
    val kindLabel: String,
    val weightRepsDisplay: String,
)
