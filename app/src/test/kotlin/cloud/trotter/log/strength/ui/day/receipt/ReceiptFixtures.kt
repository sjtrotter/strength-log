package cloud.trotter.log.strength.ui.day.receipt

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.workoutDayFixture
import java.time.LocalDate
import java.time.ZoneId

internal fun receiptFixture(): WorkoutReceipt {
    fun weighted(name: String, weights: List<Double>, reps: List<Int>, planned: PlannedSets, record: RecordMark? = null, lighter: Int = 0): ReceiptRow {
        val sets = weights.zip(reps).map { (weight, count) -> ReceiptSet(weight, count, 0) }
        val lines = if (lighter > 0) listOf(ValueLine(weights.first(), listOf(0))) else {
            weights.distinct().sortedDescending().map { weight -> ValueLine(weight, weights.indices.filter { weights[it] == weight }) }
        }
        return ReceiptRow(name, TrackingType.WEIGHTED, ReceiptRow.Shape.STRAIGHT, sets, lines, lighter, planned, record)
    }
    fun single(row: ReceiptRow) = ReceiptGroup(null, listOf(row))
    fun top(name: String, weight: Double, reps: Int, kind: PrKind, previous: ReceiptSet) = single(ReceiptRow(
        name, TrackingType.WEIGHTED, ReceiptRow.Shape.TOP_SET, listOf(ReceiptSet(weight, reps, 0)), listOf(ValueLine(weight, listOf(0))),
        0, null, RecordMark(kind, null, 0, previous),
    ))
    return WorkoutReceipt(
        sessionId = 1, cardio = false, dayId = "A", dayIndex = 0, dayTitle = "Squat focus", planName = "Four-day strength",
        completedAt = LocalDate.of(2026, 11, 3).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        minutes = 58, unit = WeightUnit.LB, recordsChecked = true, allPlansDone = false,
        groups = listOf(
            top("Barbell Back Squat", 245.0, 5, PrKind.WEIGHT, ReceiptSet(240.0, 5, 0)),
            top("Romanian Deadlift", 205.0, 6, PrKind.REPS, ReceiptSet(205.0, 5, 0)),
            ReceiptGroup(3, listOf(
                weighted("Barbell Bench Press", List(3) { 185.0 }, listOf(5, 5, 4), PlannedSets(3, 185.0, 5, 0)),
                weighted("Barbell Row", listOf(155.0, 155.0, 135.0), listOf(8, 8, 10), PlannedSets(3, 155.0, 8, 0)),
            )),
            single(weighted("Dumbbell Curl", listOf(35.0, 35.0, 30.0), listOf(10, 10, 8), PlannedSets(3, 35.0, 10, 0),
                RecordMark(PrKind.REPS, null, 0, ReceiptSet(35.0, 9, 0)))),
            single(weighted("Dumbbell Lateral Raise", listOf(25.0, 20.0, 15.0, 10.0), listOf(12, 10, 10, 12), PlannedSets(3, 25.0, 12, 0), lighter = 3)),
            single(ReceiptRow("Pull-up", TrackingType.REPS, ReceiptRow.Shape.STRAIGHT, listOf(12, 10, 9).map { ReceiptSet(0.0, it, 0) },
                listOf(ValueLine(null, listOf(0, 1, 2))), 0, PlannedSets(3, 0.0, 10, 0), null)),
            single(ReceiptRow("Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT, listOf(75, 70).map { ReceiptSet(0.0, 0, it) },
                listOf(ValueLine(null, listOf(0, 1))), 0, PlannedSets(2, 0.0, 0, 60), RecordMark(PrKind.HOLD, null, 0, ReceiptSet(0.0, 0, 70)))),
            single(ReceiptRow("Face Pull", TrackingType.WEIGHTED, ReceiptRow.Shape.SKIPPED, emptyList(), emptyList(), 0, null, null)),
        ), activity = null, cardioRows = emptyList(),
    )
}

internal fun cardioReceiptFixture(): WorkoutReceipt = receiptFixture().copy(
    cardio = true, dayId = "B", dayIndex = 1, dayTitle = "Intervals", planName = "5K build", minutes = 34, allPlansDone = true,
    groups = emptyList(), activity = CardioActivity.TRACK_RUN, cardioRows = listOf(
        CardioReceiptRow(CardioRole.WARMUP, 1, null, null, listOf(CardioInterval(CardioSetStatus.FINISHED, 600, null)), emptyList(), null),
        CardioReceiptRow(CardioRole.WORK, 6, null, 400, listOf(101, 98, 104, 100, 99, 105).map { CardioInterval(CardioSetStatus.FINISHED, it, 400) },
            List(6) { 90 }, CardioRecordMark(PrKind.FASTER, 1, 102, null)),
        CardioReceiptRow(CardioRole.COOLDOWN, 1, null, null, listOf(CardioInterval(CardioSetStatus.FINISHED, 300, null)), emptyList(), null),
    ),
)

internal fun timeTargetFixture(): WorkoutReceipt = cardioReceiptFixture().copy(dayTitle = "Easy run", minutes = 30, activity = CardioActivity.ROAD_RUN,
    cardioRows = listOf(CardioReceiptRow(CardioRole.WORK, 1, 1800, null, listOf(CardioInterval(CardioSetStatus.FINISHED, 1800, 5210)),
        emptyList(), CardioRecordMark(PrKind.FARTHER, 0, null, 4980))))

internal fun attemptFixture(): FinishAttempt {
    val receipt = receiptFixture()
    return FinishAttempt("run", 1, receipt.dayId, receipt.dayIndex, receipt.dayTitle, receipt.planName,
        receipt.completedAt - 58 * 60_000, receipt.completedAt)
}

internal fun finishingFixture(): DayUiState {
    val base = workoutDayFixture()
    val source = base.exercises.first()
    val names = listOf("Barbell Back Squat", "Romanian Deadlift", "Barbell Bench Press", "Dumbbell Curl", "Dumbbell Lateral Raise", "Pull-up")
    return base.copy(exercises = names.mapIndexed { index, name ->
        source.copy(programExerciseId = index + 1L, position = index, title = name, isSuperset = index == 2,
            partnerTitle = if (index == 2) "Barbell Row" else null, rows = source.rows.take(3))
    })
}
