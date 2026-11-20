package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import java.time.LocalDate
import java.time.ZoneOffset

internal fun logSessionFixture(expanded: Boolean) = SessionListItem(
    sessionId = 7L,
    dateDisplay = "Jul 6, 2026",
    dayLetter = "A",
    dayIndex = 0,
    dayTitle = "DAY A · LOWER",
    setCount = 21,
    bodyweightDisplay = "185 lb",
    expanded = expanded,
    exerciseGroups = if (expanded) {
        listOf(SessionExerciseGroup("Back Squat", listOf(SessionSetSummary("TOP", "225×5"))))
    } else {
        null
    },
)

internal fun cardioTrendHistoryFixture(): Pair<List<CardioSessionEntity>,
    Map<Long, List<CardioSetResult>>> {
    fun result(end: Measure, seconds: Int, metres: Int,
        atEnd: Int? = null) = CardioSetResult(
        CardioSetSpec(CardioRole.WORK, end),
        CardioSetStatus.FINISHED, seconds, metres,
        DistanceSource.MANUAL,
        metresAtEnd = atEnd, secondsAtEnd = if (atEnd != null) 900 else null,
    )
    val results = listOf(
        result(Measure.Distance(2000), 492, 2000),
        result(Measure.Distance(2000), 485, 2000),
        result(Measure.Distance(2000), 477, 2000),
        result(Measure.Time(900), 904, 3427, 3412),
        result(Measure.Time(900), 904, 3485, 3470),
        result(Measure.Time(600), 600, 2300),
    )
    val sessions = results.mapIndexed { index, set ->
        val completed = LocalDate.of(2026, 11, index + 1).atTime(12, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        CardioSessionEntity(
            id = index + 1L, dayId = if (index == 4) "C" else "B", mode = "LOW_IMPACT", hard = false, label = "Rower",
            startedAt = completed - set.elapsedSeconds * 1000L, completedAt = completed,
            seconds = set.elapsedSeconds, stepsCompleted = 1, activity = "ROWER",
        )
    }
    return sessions to results.mapIndexed { index, set -> index + 1L to listOf(set) }.toMap()
}
