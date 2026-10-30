package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.CardioSetResult

/**
 * The read surface for CSV history export (PLAN.md A2): every completed session
 * and its sets, plus the user's current display unit. Like [FullSnapshot], all
 * rows come from queries with an explicit `ORDER BY`, so exporting the same state
 * twice produces byte-identical CSV. Cardio sets keep position order; a session
 * with any undecodable set is omitted from [cardioSets] so it exports as a legacy
 * summary. `:transfer` consumes this instead of touching Room directly (D9).
 */
data class SessionHistorySnapshot(
    val unit: WeightUnit,
    val sessions: List<WorkoutSessionEntity>,
    val sessionSets: List<SessionSetEntity>,
    val cardioSessions: List<CardioSessionEntity> = emptyList(),
    val cardioSets: Map<Long, List<CardioSetResult>> = emptyMap(),
)
