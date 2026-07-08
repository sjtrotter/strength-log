package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * The read surface for CSV history export (PLAN.md A2, issue #16): every
 * completed session and its sets, plus the user's current display unit. Like
 * [FullSnapshot], both lists come from a query with an explicit `ORDER BY`, so
 * exporting the same state twice produces byte-identical CSV. `:transfer`
 * consumes this instead of touching Room directly (D9).
 */
data class SessionHistorySnapshot(
    val unit: WeightUnit,
    val sessions: List<WorkoutSessionEntity>,
    val sessionSets: List<SessionSetEntity>,
)
