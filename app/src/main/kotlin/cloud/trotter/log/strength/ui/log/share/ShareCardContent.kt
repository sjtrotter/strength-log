package cloud.trotter.log.strength.ui.log.share

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.log.LogScreenBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Every string the session share card draws (docs/briefs/session-share.md
 * §2), and nothing else — this is the card's content allowlist enforced in
 * code: no bodyweight, no goals, no notes. [dayIndex] is metadata for the
 * painter's accent color, not a rendered line. [ShareCardPainter] draws
 * exactly this and derives nothing further.
 */
data class ShareCardContent(
    val dateLine: String,
    val dayIndex: Int,
    val dayLine: String,
    val liftLines: List<ShareLiftLine>,
    /** "+2 MORE", present only when [liftLines] was capped short of the full list (§2). */
    val overflowLine: String?,
    val footerLine: String,
)

/** One lift's row on the card: its name and its heaviest done set's value, already formatted. */
data class ShareLiftLine(val name: String, val value: String)

/**
 * Builds [ShareCardContent] from a completed session — a pure function of the
 * session, its logged sets and the display unit, so every shape (weighted/
 * timed/reps lifts, overflow, volume/duration formatting) is unit-testable on
 * the JVM without touching [ShareCardPainter]'s Canvas.
 */
object ShareCardContentBuilder {

    /** Lift lines shown before overflow kicks in (§2: "cap at 6 lines"). */
    private const val MAX_LIFT_LINES = 6

    /** Real lift lines shown once overflow applies — one line is spent on "+N MORE". */
    private const val MAX_LIFT_LINES_WITH_OVERFLOW = MAX_LIFT_LINES - 1

    private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun build(
        session: WorkoutSessionEntity,
        sets: List<SessionSetEntity>,
        unit: WeightUnit,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ShareCardContent {
        val doneSets = sets.filter { it.done }
        val lifts = liftLines(doneSets, unit)

        return ShareCardContent(
            dateLine = dateLine(session.completedAt, zone),
            dayIndex = LogScreenBuilder.dayIndex(session.dayId),
            dayLine = "DAY ${session.dayId} · ${session.dayTitle}".uppercase(Locale.US),
            liftLines = lifts.take(if (lifts.size > MAX_LIFT_LINES) MAX_LIFT_LINES_WITH_OVERFLOW else MAX_LIFT_LINES),
            overflowLine = if (lifts.size > MAX_LIFT_LINES) "+${lifts.size - MAX_LIFT_LINES_WITH_OVERFLOW} MORE" else null,
            footerLine = footerLine(session, doneSets, unit),
        )
    }

    /** "WEDNESDAY · JUL 30", device-local (matches [LogScreenBuilder.dateDisplay]'s zone convention). */
    private fun dateLine(completedAtMillis: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(completedAtMillis).atZone(zone)
        return "${WEEKDAY_FORMAT.format(date)} · ${MONTH_DAY_FORMAT.format(date)}".uppercase(Locale.US)
    }

    /**
     * One line per exercise, first-appearance order, preserving the same
     * grouping shape as [LogScreenBuilder.groupByExercise] but over done sets
     * only — an exercise nothing was completed for has no "heaviest done set"
     * to show and is left off the card entirely.
     */
    private fun liftLines(doneSets: List<SessionSetEntity>, unit: WeightUnit): List<ShareLiftLine> {
        val byExercise = LinkedHashMap<String, MutableList<SessionSetEntity>>()
        for (set in doneSets) byExercise.getOrPut(set.exerciseId) { mutableListOf() }.add(set)

        return byExercise.values.map { group ->
            ShareLiftLine(group.first().exerciseName.uppercase(Locale.US), heaviestSetDisplay(group, unit))
        }
    }

    /**
     * The group's heaviest set, formatted per §2's three shapes. Tracking type
     * is read from the *values* of the group's first set ([SetFormatter
     * .trackingOfValues], SSOT with the legacy-history rule the Log screen
     * already applies) rather than the exercise's current catalog entry, then
     * every set in the group is ranked the same way — a session never mixes
     * tracks within one exercise, but a defensive filter keeps this honest if
     * legacy data ever did.
     */
    private fun heaviestSetDisplay(group: List<SessionSetEntity>, unit: WeightUnit): String {
        val tracking = SetFormatter.trackingOfValues(group.first().weightLb, group.first().reps, group.first().seconds)
        val candidates = group.filter { SetFormatter.trackingOfValues(it.weightLb, it.reps, it.seconds) == tracking }
            .ifEmpty { group }

        val best = when (tracking) {
            TrackingType.WEIGHTED -> candidates.maxWithOrNull(compareBy({ it.weightLb }, { it.reps }))
            TrackingType.TIMED -> candidates.maxWithOrNull(compareBy({ it.seconds }, { it.weightLb }))
            TrackingType.REPS -> candidates.maxWithOrNull(compareBy { it.reps })
        } ?: candidates.first()

        return when (tracking) {
            TrackingType.WEIGHTED -> "${WeightStepper.format(unit.fromLb(best.weightLb))} × ${best.reps}"
            TrackingType.REPS -> "×${best.reps}"
            TrackingType.TIMED -> minutesSeconds(best.seconds)
        }
    }

    /** Always m:ss (unlike [cloud.trotter.log.strength.domain.units.SecondsStepper.format]'s
     *  under-a-minute "45s" shape) — the card reads like a workout clock, per §2's "0:45". */
    private fun minutesSeconds(seconds: Int): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        return "$minutes:${remainder.toString().padStart(2, '0')}"
    }

    /**
     * "21 SETS · 38 MIN · 12,450 LB": set count is completed rounds
     * ([Slot.isRound]); tonnage covers all done rows
     * (Σ weightLb × reps, matching [cloud.trotter.log.strength.ui.log
     * .JournalBuilder]'s volume convention), duration in whole minutes from
     * completedAt − startedAt when a start was recorded, omitted otherwise —
     * a session logged before session-start capture existed carries no
     * fabricated duration.
     */
    private fun footerLine(session: WorkoutSessionEntity, doneSets: List<SessionSetEntity>, unit: WeightUnit): String {
        val rounds = doneSets.count { Slot.isRound(it.slot) }
        val parts = mutableListOf(if (rounds == 1) "1 SET" else "$rounds SETS")
        session.startedAt?.let { startedAt ->
            val minutes = (session.completedAt - startedAt).toDouble().div(60_000.0).roundToLong()
            parts += "$minutes MIN"
        }
        val tonnageDisplay = unit.fromLb(doneSets.sumOf { it.weightLb * it.reps })
        parts += "${groupedWhole(tonnageDisplay)} ${unit.name}"
        return parts.joinToString(" · ")
    }

    /** Grouped-digit whole number ("12,450") — the one place on the card that needs it. */
    private fun groupedWhole(value: Double): String = String.format(Locale.US, "%,d", Math.round(value))
}
