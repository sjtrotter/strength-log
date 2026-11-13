package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.units.DistanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class CardioLegsTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val timed = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(1500)),
        CardioSetStatus.FINISHED, 1690, 5600, DistanceSource.MANUAL, metresAtEnd = 5000,
        secondsAtEnd = 1500, readingSource = ReadingSource.TYPED)

    @Test fun timeEndShowsMeasuredAndTypedLegsInKilometers() {
        for (source in ReadingSource.entries) {
            assertEquals(CardioLegs("5.00 km at 25:00", "3:10, 0.60 km past the target"),
                CardioLegs.of(timed.copy(readingSource = source), DistanceUnit.KM, resources))
        }
    }

    @Test fun derivedAndMissingReadingsNeverShowLegs() {
        assertNull(CardioLegs.of(timed.copy(metresAtEnd = null, readingSource = null), DistanceUnit.KM, resources))
        assertNull(CardioLegs.of(timed.copy(readingSource = null), DistanceUnit.KM, resources))
        assertNull(CardioLegs.of(timed.copy(metresAtEnd = null), DistanceUnit.KM, resources))
        assertNull(CardioLegs.of(timed.copy(elapsedSeconds = 1500), DistanceUnit.KM, resources))
        assertNull(CardioLegs.of(timed.copy(status = CardioSetStatus.SHORTENED), DistanceUnit.KM, resources))
        assertNull(CardioLegs.of(timed.copy(metres = 4900), DistanceUnit.KM, resources))
    }

    @Test fun distanceEndUsesTimeAtTheTargetAndMeasuredOverrun() {
        val set = timed.copy(spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(5000)),
            elapsedSeconds = 1532, metres = 5100, secondsAtEnd = 1480)
        assertEquals(CardioLegs("24:40 at 3.11 mi", "0:52, 0.06 mi past the target"),
            CardioLegs.of(set, DistanceUnit.MI, resources))
        assertNull(CardioLegs.of(set.copy(secondsAtEnd = null), DistanceUnit.MI, resources))
        assertNull(CardioLegs.of(set.copy(readingSource = null), DistanceUnit.MI, resources))
        assertNull(CardioLegs.of(set.copy(metres = 5000), DistanceUnit.MI, resources))
    }
}
