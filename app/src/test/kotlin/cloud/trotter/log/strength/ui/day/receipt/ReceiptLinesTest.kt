package cloud.trotter.log.strength.ui.day.receipt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.NextOffer
import cloud.trotter.log.strength.data.SlotSession
import cloud.trotter.log.strength.data.serialization.ReasonCode
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.Reason
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceiptLinesTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val rule = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.0125, 1, scheme = Scheme.STRENGTH)
    private fun rows(weight: Double = 185.0, reps: Int = 5) = List(3) { LoggedSet(weight, reps, SetKind.WORK) }
    private fun session(offer: NextOffer? = NextOffer(185.0, 190.0), rough: Boolean = false, reason: String = "ALL_MADE") =
        SlotSession(rows(), rows().map { it.copy(done = true) }, false, offer, rough = rough, reason = reason)
    private fun line(weight: Double, session: SlotSession = session(), gate: Boolean = true, unit: WeightUnit = WeightUnit.LB,
                     inputRule: SlotRule = rule): ReceiptLiftLine = assertNotNull(
        ReceiptLines.strength(rows(weight), session, false, inputRule, unit, gate))
    private fun text(line: ReceiptLiftLine, unit: WeightUnit = WeightUnit.LB) = ReceiptLines.label(line, unit, resources)

    @Test fun `applied load shows its absolute value and delta`() {
        assertEquals("Next time: 190 lb (+5)", text(line(190.0)))
        assertEquals("Next time, 190 pounds, up 5 pounds.", ReceiptLines.spoken(line(190.0), WeightUnit.LB, resources))
    }
    @Test fun `Ask offers only while the chip gate is open`() {
        assertEquals("Next time: 190 lb offered (+5)", text(line(185.0, inputRule = rule.copy(nextTime = NextTimeMode.ASK))))
        assertEquals("Next time: 185 lb", text(line(185.0, gate = false)))
    }
    @Test fun `applied changes stay visible with the switch off`() {
        assertEquals("Next time: 190 lb (+5)", text(line(190.0, gate = false)))
    }
    @Test fun `a kilogram bench increase uses the engine value`() {
        val working = rows(WeightUnit.KG.toLb(100.0))
        val outcome = Progression.afterSession(rule, working, Progression.Exposure(working,
            working.mapIndexed { i, set -> set.copy(done = true, rir = 2, plannedIndex = i) }, 130),
            Progression.Context(WeightUnit.KG, setOf(Equipment.BARBELL), LoadSteps.defaults(WeightUnit.KG), 130))
        val after = outcome.rows.first().weightLb
        val local = SlotSession(working, working.map { it.copy(done = true) }, false,
            NextOffer(working.first().weightLb, after), reason = "ALL_MADE")
        val line = assertNotNull(ReceiptLines.strength(outcome.rows, local, false, rule, WeightUnit.KG, true))
        assertEquals("Next time: 102.5 kg (+2.5)", text(line, WeightUnit.KG))
    }
    @Test fun `no offer shows the stored load`() {
        assertEquals("Next time: 185 lb", text(line(185.0, session(offer = null, reason = "REP_MISS"))))
    }
    @Test fun `rep offers use reps for applied and offered values`() {
        val offer = session(NextOffer(185.0, 185.0, 8, 9))
        for ((reps, expected) in listOf(9 to "Next time: 9 reps", 8 to "Next time: 9 reps offered")) {
            val line = assertNotNull(ReceiptLines.strength(rows(reps = reps), offer, false, rule, WeightUnit.LB, true))
            assertEquals(expected, text(line))
        }
    }
    @Test fun `Rough day holds the stored value and hides the chip offer`() {
        val local = session(NextOffer(185.0, 175.0), rough = true)
        assertEquals("Next time: 185 lb", text(line(185.0, local)))
        assertNull(DayScreenBuilder.nextTime(rows(), local, false, WeightUnit.LB, true, rule))
    }
    @Test fun `mixed lift modes keep applied and offered labels distinct`() {
        assertEquals(listOf("Next time: 190 lb (+5)", "Next time: 100 lb offered (+5)"), listOf(
            text(line(190.0)), text(line(95.0, session(NextOffer(95.0, 100.0)), inputRule = rule.copy(nextTime = NextTimeMode.ASK)))))
    }
    @Test fun `Why uses stored codes and signed deltas`() {
        assertEquals("Bench Press 190 lb next time (+5): all sets made.",
            ReceiptLines.why("Bench Press", line(190.0), WeightUnit.LB, resources))
        assertEquals("Overhead Press 175 lb next time (−10): two misses in a row.",
            ReceiptLines.why("Overhead Press", line(175.0, session(NextOffer(185.0, 175.0), reason = "STALL")), WeightUnit.LB, resources))
        assertEquals("Chest Press 20 lb next time: you lowered it today.",
            ReceiptLines.why("Chest Press", line(20.0, session(NextOffer(25.0, 20.0), reason = "LOWERED")), WeightUnit.LB, resources))
        assertEquals("Light week, 3 days left.", resources.getQuantityString(R.plurals.receipt_why_light_remaining, 3, 3))
        assertEquals("Light week, 1 day left.", resources.getQuantityString(R.plurals.receipt_why_light_remaining, 1, 1))
    }
    @Test fun `timed next time uses the workout clock format`() {
        val timed = rows(0.0, 0).map { it.copy(seconds = 60) }
        val session = SlotSession(timed, timed.map { it.copy(done = true) }, false, reason = "TIMED")
        val line = assertNotNull(ReceiptLines.strength(timed, session, false, rule, WeightUnit.LB, true))
        assertEquals("Next time: 1:00", text(line))
    }

    @Test fun `Why keeps the archived offer when Ask chips are off`() {
        val ask = rule.copy(nextTime = NextTimeMode.ASK)
        val session = session()
        assertEquals("Next time: 185 lb", text(line(185.0, session, gate = false, inputRule = ask)))
        val why = ReceiptLines.why("Bench Press", assertNotNull(ReceiptLines.whyLine(rows(), session, ask, WeightUnit.LB)),
            WeightUnit.LB, resources)
        assertEquals("Bench Press 190 lb offered (+5): all sets made.", why)
        assertFalse(assertNotNull(why).contains("()"))
    }

    @Test fun `Why omits change reasons without an archived offer`() {
        listOf("ALL_MADE", "EASY_SESSION", "STALL").forEach { reason ->
            assertNull(ReceiptLines.whyLine(rows(), session(offer = null, reason = reason), rule, WeightUnit.LB))
        }
    }

    @Test fun `Why uses archived rep changes and stored rows for applied status`() {
        val repSession = session(NextOffer(185.0, 185.0, 8, 9))
        for ((reps, word) in listOf(8 to "offered", 9 to "next time")) {
            val why = assertNotNull(ReceiptLines.whyLine(rows(reps = reps), repSession, rule, WeightUnit.LB))
            assertEquals("Bench Press 9 reps $word: all sets made.", ReceiptLines.why("Bench Press", why, WeightUnit.LB, resources))
        }
        val drop = session(NextOffer(185.0, 175.0), reason = "STALL")
        assertEquals("Bench Press 175 lb offered (−10): two misses in a row.", ReceiptLines.why("Bench Press",
            assertNotNull(ReceiptLines.whyLine(rows(), drop, rule, WeightUnit.LB)), WeightUnit.LB, resources))
    }

    @Test fun `reason codes are literal and unknown codes stay unknown`() {
        val pinned = listOf(Reason.NoSets to "NO_SETS", Reason.LightCycle to "LIGHT_CYCLE", Reason.Layoff to "LAYOFF",
            Reason.Calibrating to "CALIBRATING", Reason.Calibrated to "CALIBRATED", Reason.Lowered to "LOWERED",
            Reason.AllMade to "ALL_MADE", Reason.EasySession to "EASY_SESSION", Reason.Streak to "STREAK",
            Reason.MaxSet to "MAX_SET", Reason.RepMiss to "REP_MISS", Reason.Stall to "STALL", Reason.EffortMiss to "EFFORT_MISS",
            Reason.Incomplete to "INCOMPLETE", Reason.AddWeight to "ADD_WEIGHT", Reason.Timed to "TIMED")
        pinned.forEach { (reason, code) -> assertEquals(code, ReasonCode.encode(reason)); assertEquals(reason, ReasonCode.decode(code)) }
        assertNull(ReasonCode.decode("BOGUS"))
        assertNull(ReasonCode.decode(null))
    }
}
