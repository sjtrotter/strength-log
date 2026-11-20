package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
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

    @Test fun `work pace sums intervals and uses planned finished distances`() {
        fun result(seconds: Int, metres: Int?, role: CardioRole = CardioRole.WORK,
            status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
            CardioSetSpec(role, Measure.Distance(400)), status, seconds, metres, metres?.let { DistanceSource.MANUAL })
        val results = listOf(result(100, 400), result(104, 400, status = CardioSetStatus.SHORTENED),
            result(300, null), result(30, 400, status = CardioSetStatus.SKIPPED), result(90, 400, CardioRole.RECOVERY))
        // #531: Finished distance work without meters uses its planned distance, including the 300-second set.
        assertEquals("7:00 /km", CardioPace.ofWork(CardioActivity.TRACK_RUN, results, DistanceUnit.KM)?.text)
    }

    @Test fun `an hour or more per unit reads h mm ss`() {
        assertEquals("1:01:01 /km", pace(CardioActivity.OUTDOOR_WALK, 3661, 1000)?.text)
    }

    @Test fun `finished distance work uses at end meters without GPS`() {
        val row = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.FINISHED, 477, metresAtEnd = 2000, secondsAtEnd = 477)
        assertEquals("1:59 /500 m", CardioPace.ofWork(CardioActivity.ROWER, listOf(row), DistanceUnit.KM)?.text)
        val track = row.copy(spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400)),
            elapsedSeconds = 100, metresAtEnd = 400, secondsAtEnd = 100)
        assertEquals("4:10 /km", CardioPace.ofWork(CardioActivity.TRACK_RUN, listOf(track), DistanceUnit.KM)?.text)
        val measured = row.copy(metres = 2100, distanceSource = DistanceSource.GPS)
        assertEquals("1:54 /500 m", CardioPace.ofWork(CardioActivity.ROWER, listOf(measured), DistanceUnit.KM)?.text)
    }

    @Test fun `missing total distance has no fallback for shortened work or time ends`() {
        val row = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.SHORTENED, 477)
        assertNull(CardioPace.ofWork(CardioActivity.ROWER, listOf(row), DistanceUnit.KM))
        assertNull(CardioPace.ofWork(CardioActivity.ROWER, listOf(row.copy(metresAtEnd = 2000)), DistanceUnit.KM))
        val timed = row.copy(spec = CardioSetSpec(CardioRole.WORK, Measure.Time(477)),
            status = CardioSetStatus.FINISHED, metresAtEnd = 2000, secondsAtEnd = 477)
        assertNull(CardioPace.ofWork(CardioActivity.ROWER, listOf(timed), DistanceUnit.KM))
    }

    @Test fun `distance fallback pairs at end meters with at end seconds`() {
        val row = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.FINISHED, 510, metresAtEnd = 2000, secondsAtEnd = 477)
        assertEquals("1:59 /500 m", CardioPace.ofWork(CardioActivity.ROWER, listOf(row), DistanceUnit.KM)?.text)
        val measured = row.copy(metres = 2100, distanceSource = DistanceSource.GPS)
        assertEquals("2:01 /500 m", CardioPace.ofWork(CardioActivity.ROWER, listOf(measured), DistanceUnit.KM)?.text)
        val noEndTime = row.copy(elapsedSeconds = 477, secondsAtEnd = null)
        assertEquals("1:59 /500 m", CardioPace.ofWork(CardioActivity.ROWER, listOf(noEndTime), DistanceUnit.KM)?.text)
    }

    @Test fun `legacy finished distance work uses planned meters`() {
        val row = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.FINISHED, 477)
        assertEquals("1:59 /500 m", CardioPace.ofWork(CardioActivity.ROWER, listOf(row), DistanceUnit.KM)?.text)
    }

    @Test fun `timed sprint reps do not contribute wall clock seconds to pace`() {
        val work = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(400)),
            CardioSetStatus.FINISHED, 100, metresAtEnd = 400, secondsAtEnd = 100)
        val sprint = work.copy(elapsedSeconds = 200,
            timing = SprintTime(5500, TimingMethod.HAND, StartProtocol.STANDING))
        assertNull(CardioPace.ofWork(CardioActivity.TRACK_RUN, listOf(sprint), DistanceUnit.KM))
        assertEquals("4:10 /km", CardioPace.ofWork(CardioActivity.TRACK_RUN,
            listOf(work, sprint, sprint.copy(metres = 400, distanceSource = DistanceSource.MANUAL)), DistanceUnit.KM)?.text)
    }

    @Test fun `zero or missing time has no pace`() {
        assertNull(pace(CardioActivity.ROAD_RUN, 0, 5000))
        assertNull(pace(CardioActivity.ROAD_RUN, -1, 5000))
        val missing = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.FINISHED, 0, metresAtEnd = 2000)
        assertNull(CardioPace.ofWork(CardioActivity.ROWER, listOf(missing), DistanceUnit.KM))
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
