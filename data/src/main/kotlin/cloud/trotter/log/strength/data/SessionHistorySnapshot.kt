package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.units.WeightUnit

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
    val cardioSessions: List<CardioSessionEntity> = emptyList(),
)
