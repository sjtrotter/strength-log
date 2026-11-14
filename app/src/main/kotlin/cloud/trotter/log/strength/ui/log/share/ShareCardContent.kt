package cloud.trotter.log.strength.ui.log.share

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.records.PersonalBests
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.DistanceUnit
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
    val exerciseLines: List<ShareExerciseLine>,
    val footerLine: String,
)

/** One exercise's row on the card, already formatted. */
data class ShareExerciseLine(val name: String, val value: String)

data class ShareCardioBlock(val name: String, val seconds: Int, val metres: Int?)

/**
 * Builds [ShareCardContent] from a completed session — a pure function of the
 * session, its logged sets and the display unit, so every shape (weighted/
 * timed/reps lifts, cardio, volume/duration formatting) is unit-testable on
 * the JVM without touching [ShareCardPainter]'s Canvas.
 */
object ShareCardContentBuilder {

    private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun build(
        session: WorkoutSessionEntity,
        sets: List<SessionSetEntity>,
        unit: WeightUnit,
        zone: ZoneId = ZoneId.systemDefault(),
        cardioBlocks: List<ShareCardioBlock> = emptyList(),
        distanceUnit: DistanceUnit = DistanceUnit.suggestedFor(unit),
    ): ShareCardContent {
        val doneSets = sets.filter { it.done }
        val exercises = liftLines(doneSets, unit) + cardioBlocks.map { block ->
            ShareExerciseLine(block.name.uppercase(Locale.US), listOfNotNull(
                block.metres?.let { distanceUnit.format(it) }, ClockText.minutesSeconds(block.seconds),
            ).joinToString(" · ").uppercase(Locale.US))
        }

        return ShareCardContent(
            dateLine = dateLine(session.completedAt, zone),
            dayIndex = Program.accentIndex(session.dayId),
            dayLine = "DAY ${session.dayId} · ${session.dayTitle}".uppercase(Locale.US),
            exerciseLines = exercises,
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
     * only — an exercise without a qualifying set has no best
     * to show and is left off the card entirely.
     */
    private fun liftLines(doneSets: List<SessionSetEntity>, unit: WeightUnit): List<ShareExerciseLine> {
        val byExercise = LinkedHashMap<String, MutableList<SessionSetEntity>>()
        for (set in doneSets) byExercise.getOrPut(set.exerciseId) { mutableListOf() }.add(set)

        return byExercise.values.mapNotNull { group ->
            val value = bestSetDisplay(group, unit) ?: return@mapNotNull null
            ShareExerciseLine(group.first().exerciseName.uppercase(Locale.US), value)
        }
    }

    private fun bestSetDisplay(group: List<SessionSetEntity>, unit: WeightUnit): String? {
        val best = PersonalBests.best(group.map {
            LoggedSet(it.weightLb, it.reps, SetKind.valueOf(it.kind), done = true, seconds = it.seconds)
        }) ?: return null

        return when (PersonalBests.tracking(best)) {
            TrackingType.WEIGHTED -> "${WeightStepper.format(unit.fromLb(best.weightLb))} × ${best.reps}"
            TrackingType.REPS -> "×${best.reps}"
            TrackingType.TIMED -> ClockText.minutesSeconds(best.seconds)
        }
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
