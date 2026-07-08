package io.github.sjtrotter.strengthlog.ui.log

/** Immutable render model for the Log screen (PLAN.md A1, issue #14). */
data class LogUiState(
    val sessions: List<SessionListItem> = emptyList(),
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
