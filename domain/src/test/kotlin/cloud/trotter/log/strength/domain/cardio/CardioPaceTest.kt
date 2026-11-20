package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.DistanceUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardioPaceTest {
    private fun pace(activity: CardioActivity, seconds: Int, metres: Int?, setting: DistanceUnit = DistanceUnit.KM) =
        CardioPace.of(activity, listOf(CardioPace.Effort(seconds, metres)), setting)

    @Test fun `rower pace is per 500 m`() {
        assertEquals("2:10 /500 m", pace(CardioActivity.ROWER, 900, 3470)?.text)
    }

    @Test fun `run pace follows the setting`() {
        assertEquals("5:42 /km", pace(CardioActivity.ROAD_RUN, 1710, 5000)?.text)
        assertEquals("9:39 /mi", pace(CardioActivity.ROAD_RUN, 1800, 5000, DistanceUnit.MI)?.text)
        assertEquals(PacePer.MI, pace(CardioActivity.TRACK_RUN, 1800, 5000, DistanceUnit.MI)?.per)
    }

    @Test fun `walk pace`() {
        assertEquals("12:30 /km", pace(CardioActivity.OUTDOOR_WALK, 3000, 4000)?.text)
    }

    @Test fun `rides and elliptical have no pace`() {
        for (activity in listOf(CardioActivity.OUTDOOR_RIDE, CardioActivity.STATIONARY_BIKE, CardioActivity.ELLIPTICAL)) {
            assertNull(pace(activity, 1800, 5000))
        }
    }

    @Test fun `work pace sums intervals and skips sets without a distance or skipped`() {
        fun result(seconds: Int, metres: Int?, role: CardioRole = CardioRole.WORK,
            status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
            CardioSetSpec(role, Measure.Distance(400)), status, seconds, metres, metres?.let { DistanceSource.MANUAL })
        val results = listOf(result(100, 400), result(104, 400, status = CardioSetStatus.SHORTENED),
            result(300, null), result(30, 400, status = CardioSetStatus.SKIPPED), result(90, 400, CardioRole.RECOVERY))
        assertEquals("4:15 /km", CardioPace.ofWork(CardioActivity.TRACK_RUN, results, DistanceUnit.KM)?.text)
    }

    @Test fun `an hour or more per unit reads h mm ss`() {
        assertEquals("1:01:01 /km", pace(CardioActivity.OUTDOOR_WALK, 3661, 1000)?.text)
    }

    @Test fun `pace rounds half up and bounds empty zero and very slow efforts`() {
        assertEquals("0:11 /km", pace(CardioActivity.ROAD_RUN, 21, 2000)?.text)
        assertNull(pace(CardioActivity.ROAD_RUN, 20, null))
        assertNull(pace(CardioActivity.ROAD_RUN, 20, 0))
        assertNull(CardioPace.of(CardioActivity.ROAD_RUN, emptyList(), DistanceUnit.KM))
        assertEquals("24:00:00 /km", pace(CardioActivity.OUTDOOR_WALK, 86400, 1000)?.text)
        assertNull(pace(CardioActivity.OUTDOOR_WALK, 86401, 1000))
        assertEquals("16:40 /km", CardioPace.of(CardioActivity.ROAD_RUN,
            List(3) { CardioPace.Effort(1_000_000_000, 1_000_000_000) }, DistanceUnit.KM)?.text)
    }
}
