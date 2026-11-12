package cloud.trotter.log.strength.ui.day.receipt

import android.content.res.Resources
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.SlotSession
import cloud.trotter.log.strength.data.serialization.ReasonCode
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.day.NextTime
import kotlin.math.abs

data class ReceiptLiftLine(
    val weightLb: Double,
    val reps: Int?,
    val seconds: Int?,
    val delta: Double?,
    val offered: Boolean,
    val reason: String?,
    val cadence: Int,
    val hasOffer: Boolean,
)

object ReceiptLines {
    private fun storedLine(rows: List<LoggedSet>, session: SlotSession?, keepToPlan: Boolean, rule: SlotRule?): ReceiptLiftLine? {
        if (keepToPlan || rule == null || session == null || session.retired || session.reason == "NO_SETS" ||
            session.actual.none { it.done }) return null
        val weight = WorkingRows.workingWeight(rows) ?: return null
        val anchor = WorkingRows.workingIndices(rows).firstOrNull()?.let { rows[it] } ?: return null
        return ReceiptLiftLine(weight, anchor.reps.takeIf { weight == 0.0 && anchor.seconds == 0 },
            anchor.seconds.takeIf { it > 0 }, null, false, session.reason, rule.everyExposures, session.offer != null)
    }

    fun strength(rows: List<LoggedSet>, session: SlotSession?, keepToPlan: Boolean, rule: SlotRule?,
                 unit: WeightUnit, chipGate: Boolean): ReceiptLiftLine? {
        val stored = storedLine(rows, session, keepToPlan, rule) ?: return null
        val offer = (DayScreenBuilder.nextTime(rows, session, false, unit, offersOn = true, rule = rule) as? NextTime.Offer)
            ?.takeIf { it.applied || chipGate } ?: return stored
        return stored.copy(weightLb = offer.offerLb, reps = offer.reps ?: stored.reps,
            delta = offer.takeUnless { it.calibration || it.reps != null }?.let {
                DayScreenBuilder.offerDelta(it.offerLb, it.openingLb, unit).takeIf { delta -> delta != 0.0 }
            }, offered = !offer.applied)
    }

    fun whyLine(rows: List<LoggedSet>, session: SlotSession, rule: SlotRule, unit: WeightUnit): ReceiptLiftLine? {
        val stored = storedLine(rows, session, false, rule) ?: return null
        val offer = session.offer ?: return stored.takeUnless { it.reason in setOf("ALL_MADE", "EASY_SESSION", "STALL") }
        val delta = DayScreenBuilder.offerDelta(offer.toLb, offer.fromLb, unit)
        val reps = offer.toReps.takeIf { delta == 0.0 }
        val applied = (DayScreenBuilder.nextTime(rows, session, false, unit, offersOn = true, rule = rule)
            as? NextTime.Offer)?.applied == true
        return stored.copy(weightLb = offer.toLb, reps = reps, delta = delta.takeIf {
            !offer.calibration && reps == null && it != 0.0
        }, offered = !applied)
    }

    fun value(line: ReceiptLiftLine, unit: WeightUnit, resources: Resources): String = when {
        line.reps != null -> resources.getString(R.string.next_time_reps, line.reps)
        line.seconds != null -> ClockText.minutesSeconds(line.seconds)
        else -> SetFormatter.weight(line.weightLb, unit)
    }

    fun signedDelta(delta: Double): String = (if (delta < 0) "−" else "+") + WeightStepper.format(abs(delta))

    fun label(line: ReceiptLiftLine, unit: WeightUnit, resources: Resources): String {
        val value = value(line, unit, resources)
        val changed = when {
            line.delta != null -> resources.getString(if (line.offered) R.string.next_time_offered else R.string.next_time_applied,
                value, signedDelta(line.delta))
            line.offered -> resources.getString(R.string.next_time_offered_plain, value)
            else -> value
        }
        return resources.getString(R.string.receipt_next_time, changed)
    }

    fun spoken(line: ReceiptLiftLine, unit: WeightUnit, resources: Resources): String {
        val unitWord = resources.getString(if (unit == WeightUnit.LB) R.string.receipt_say_pounds else R.string.receipt_say_kilograms)
        val value = when {
            line.reps != null -> resources.getString(R.string.next_time_reps, line.reps)
            line.seconds != null -> value(line, unit, resources)
            else -> WeightStepper.format(unit.fromLb(line.weightLb)) + " " + unitWord
        }
        val delta = line.delta
        if (delta == null) return resources.getString(R.string.receipt_say_next_time_same,
            if (line.offered) resources.getString(R.string.next_time_offered_plain, value) else value)
        val key = when {
            line.offered && delta > 0 -> R.string.receipt_say_next_time_offered_up
            line.offered -> R.string.receipt_say_next_time_offered_down
            delta > 0 -> R.string.receipt_say_next_time_up
            else -> R.string.receipt_say_next_time_down
        }
        return resources.getString(key, value, WeightStepper.format(abs(delta)) + " " + unitWord)
    }

    fun why(name: String, line: ReceiptLiftLine, unit: WeightUnit, resources: Resources): String? {
        if (ReasonCode.decode(line.reason) == null) return null
        val repChange = line.reps != null && line.hasOffer
        val key = when (line.reason) {
            "ALL_MADE" -> if (repChange) R.string.receipt_why_all_made_reps else R.string.receipt_why_all_made
            "EASY_SESSION" -> R.string.receipt_why_easy
            "STREAK" -> R.string.receipt_why_streak
            "MAX_SET" -> R.string.receipt_why_max
            "REP_MISS" -> R.string.receipt_why_rep_miss
            "EFFORT_MISS" -> R.string.receipt_why_effort_miss
            "INCOMPLETE" -> R.string.receipt_why_incomplete
            "STALL" -> if (repChange) R.string.receipt_why_stall_reps else R.string.receipt_why_stall
            "LOWERED" -> R.string.receipt_why_lowered
            "LAYOFF" -> if (line.hasOffer) R.string.receipt_why_layoff_offer else R.string.receipt_why_layoff
            "CALIBRATING" -> R.string.receipt_why_calibrating
            "CALIBRATED" -> R.string.receipt_why_calibrated
            "ADD_WEIGHT" -> R.string.receipt_why_add_weight
            else -> return null
        }
        val value = if (repChange || line.reason == "ADD_WEIGHT") line.reps?.toString() ?: value(line, unit, resources)
            else value(line, unit, resources)
        val nextWord = resources.getString(if (line.offered) R.string.receipt_why_offered else R.string.receipt_why_next_time)
        return resources.getString(key, name, value, line.delta?.let(::signedDelta).orEmpty(), line.cadence, nextWord)
    }
}
