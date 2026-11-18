package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.mapping.toDomainOrNull
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.progression.LoggedCardio

/** The family's sessions completed by [asOfMillis], with their readable set results. Room only. */
internal suspend fun StrengthDatabase.cardioLog(family: CardioFamily, asOfMillis: Long): List<LoggedCardio> {
    val names = family.activities.map { it.name }
    val results = cardioSetResultDao().forActivities(names).groupBy { it.cardioSessionId }
    return cardioSessionDao().forActivities(names).filter { it.completedAt <= asOfMillis }.map { session ->
        LoggedCardio(session.completedAt, results[session.id].orEmpty().mapNotNull { it.toDomainOrNull() })
    }
}
