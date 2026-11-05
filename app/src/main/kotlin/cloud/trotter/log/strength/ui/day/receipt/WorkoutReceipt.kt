package cloud.trotter.log.strength.ui.day.receipt

import android.os.Parcelable
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import kotlinx.parcelize.Parcelize

@Parcelize
data class WorkoutReceipt(
    /** workout_session.id, or cardio_session.id when [cardio]. The live handle for share and note. */
    val sessionId: Long,
    val cardio: Boolean,
    val dayId: String,
    /** The completed day's accent, even after the rotation advances. */
    val dayIndex: Int,
    val dayTitle: String,
    val planName: String,
    val completedAt: Long,
    val minutes: Int?,
    val unit: WeightUnit,
    /** False when the standing bests could not be read; the screen says so and no row is a record. */
    val recordsChecked: Boolean,
    /** Two or more plans on Today and every one has a session completed on the civil day of [completedAt]. */
    val allPlansDone: Boolean,
    val groups: List<ReceiptGroup>,          // strength; empty for cardio
    val activity: CardioActivity?,           // cardio only
    val cardioRows: List<CardioReceiptRow>,  // cardio; empty for strength
    val note: String = "",
) : Parcelable

/** One lift, or one superset of two lifts. [rounds] is null for a single lift. */
@Parcelize
data class ReceiptGroup(val rounds: Int?, val rows: List<ReceiptRow>) : Parcelable

@Parcelize
data class ReceiptRow(
    val name: String,
    val tracking: TrackingType,
    val shape: Shape,
    /** Every done set, in logged order. Empty only for [Shape.SKIPPED]. */
    val sets: List<ReceiptSet>,
    /** Collapsed value lines, heaviest first. Indices into [sets]. One or two lines; see §1.5. */
    val lines: List<ValueLine>,
    /** When three or more weights were used: [lines] holds the heaviest set only, this is the rest. */
    val lighterSets: Int,
    /** The slot's prescription at Finish, independent of its live row count. */
    val planned: PlannedSets?,
    val record: RecordMark?,
) : Parcelable {
    enum class Shape {
        /** Ramped structure, a TOP set logged. Never opens. */
        TOP_SET,
        /** Ramped structure, no TOP set logged, something else was. Never opens. */
        HEAVIEST_NO_TOP,
        /** Straight sets, reps or holds. Opens. */
        STRAIGHT,
        /** No set logged, whether the lift was skipped in the run or never reached. */
        SKIPPED,
    }
}

@Parcelize data class ReceiptSet(val weightLb: Double, val reps: Int, val seconds: Int) : Parcelable

/** [weightLb] is null for REPS and TIMED rows (one line, every set). */
@Parcelize data class ValueLine(val weightLb: Double?, val setIndices: List<Int>) : Parcelable

@Parcelize data class PlannedSets(val count: Int, val weightLb: Double, val reps: Int, val seconds: Int) : Parcelable

/** Separate saved state preserves the field order of existing receipt parcels. */
@Parcelize
data class ReceiptSetCounts(val sessionId: Long, val total: Int, val remaining: Int) : Parcelable

/** [kind] is the label; [alsoKind] is spoken after it and never drawn. [setIndex] indexes [ReceiptRow.sets]. */
@Parcelize data class RecordMark(val kind: PrKind, val alsoKind: PrKind?, val setIndex: Int, val previous: ReceiptSet) : Parcelable

@Parcelize
data class CardioReceiptRow(
    val role: CardioRole,                 // Partial RECOVERY gets its own row; finished recovery folds into recoverySeconds
    val count: Int,                       // WORK intervals in this row; 1 for warm-up and cool-down
    val endSeconds: Int?,                 // the planned Measure.Time
    val endMetres: Int?,                  // the planned Measure.Distance
    val intervals: List<CardioInterval>,  // results in order
    val recoverySeconds: List<Int>,       // elapsed of the RECOVERY sets that followed this row's intervals
    val record: CardioRecordMark?,
) : Parcelable

@Parcelize data class CardioInterval(val status: CardioSetStatus, val elapsedSeconds: Int, val metres: Int?) : Parcelable

/** [previousSeconds] for FASTER, [previousMetres] for FARTHER. */
@Parcelize data class CardioRecordMark(val kind: PrKind, val intervalIndex: Int, val previousSeconds: Int?, val previousMetres: Int?) : Parcelable

@Parcelize
data class FinishAttempt(
    val runId: String,
    val planId: Long,
    val dayId: String,
    val dayIndex: Int,
    val dayTitle: String,
    val planName: String,
    val startedAt: Long?,
    val tappedAt: Long,
    val failed: Boolean = false,
) : Parcelable

/** Completion is presentation only; the recorded session and rotation stay unchanged. */
internal val WorkoutReceipt.loggedSetCount: Int get() = if (cardio) {
    cardioRows.sumOf { row -> row.intervals.count { it.status != CardioSetStatus.SKIPPED } + row.recoverySeconds.size }
} else groups.sumOf { group -> group.rows.sumOf { it.sets.size } }

internal val WorkoutReceipt.plannedSetCount: Int get() = if (cardio) {
    cardioRows.sumOf { it.intervals.size + it.recoverySeconds.size }
} else groups.sumOf { group -> group.rows.sumOf { row ->
    row.planned?.count ?: row.sets.size
} }

internal val WorkoutReceipt.isPartial: Boolean get() = if (cardio) {
    cardioRows.any { row -> row.intervals.any { it.status != CardioSetStatus.FINISHED } }
} else groups.any { group -> group.rows.any { row ->
    row.shape == ReceiptRow.Shape.SKIPPED ||
        !DayScreenBuilder.allDone(row.sets.size, row.planned?.count ?: row.sets.size)
} }

/** Older receipt bundles omitted plans on closed and skipped rows. */
internal val WorkoutReceipt.hasPlannedSetCount: Boolean get() = cardio ||
    groups.all { group -> group.rows.all { it.planned != null } }
