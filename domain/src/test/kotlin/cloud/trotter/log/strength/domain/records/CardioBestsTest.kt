package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CardioBestsTest {
    private fun result(
        end: Measure = Measure.Distance(400),
        seconds: Int = 102,
        metres: Int? = null,
        role: CardioRole = CardioRole.WORK,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
        aim: Measure? = null,
    ) = CardioSetResult(CardioSetSpec(role, end, aim), status, seconds,
        metres, metres?.let { DistanceSource.MANUAL })

    @Test
    fun `six track intervals produce one fastest record against the standing best`() {
        val activity = CardioActivity.TRACK_RUN
        val standing = CardioBests.bests(activity, listOf(result()))
        val session = listOf(result(Measure.Time(600), 600, role = CardioRole.WARMUP)) +
            listOf(101, 98, 104, 100, 99, 105).flatMap { seconds ->
                listOf(result(seconds = seconds), result(Measure.Time(90), 90, role = CardioRole.RECOVERY))
            }

        val record = CardioBests.records(activity, session, standing).single()

        assertEquals(CardioRecordKey(activity, Measure.Distance(400)), record.key)
        assertEquals(98, record.best.elapsedSeconds)
        assertEquals(102, record.previous.elapsedSeconds)
        assertEquals(PrKind.FASTER, record.kind)
    }

    @Test
    fun `road thirty minute record requires finished work with recorded distance`() {
        val activity = CardioActivity.ROAD_RUN
        val end = Measure.Time(1800)
        val standing = CardioBests.bests(activity, listOf(result(end, 1800, 4980)))
        val record = CardioBests.records(activity, listOf(result(end, 1800, 5210)), standing).single()

        assertEquals(5210, record.best.metres)
        assertEquals(4980, record.previous.metres)
        assertEquals(PrKind.FARTHER, record.kind)
        assertTrue(CardioBests.records(activity, listOf(result(end, 1800)), standing).isEmpty())
        assertTrue(CardioBests.records(activity, listOf(
            result(end, 1700, 5300, status = CardioSetStatus.SHORTENED),
        ), standing).isEmpty())
    }

    @Test
    fun `ties first performances and slower or shorter results are never records`() {
        val activity = CardioActivity.TRACK_RUN
        val standing = CardioBests.bests(activity, listOf(result(seconds = 98)))
        assertTrue(CardioBests.records(activity, listOf(result(seconds = 98), result(seconds = 98)), standing).isEmpty())
        assertTrue(CardioBests.records(activity, listOf(result(seconds = 99)), standing).isEmpty())
        assertTrue(CardioBests.records(activity, listOf(result(seconds = 95)), emptyMap()).isEmpty())
        val timed = result(Measure.Time(1800), 1800, 4980)
        val timedStanding = CardioBests.bests(activity, listOf(timed))
        assertTrue(CardioBests.records(activity, listOf(timed, timed.copy(metres = 4900)), timedStanding).isEmpty())
    }

    @Test
    fun `activity and exact planned measure keep independent records without pace inference`() {
        val track = CardioActivity.TRACK_RUN
        val treadmill = CardioActivity.TREADMILL_RUN
        val standing = CardioBests.bests(track, listOf(result()))
        val faster = result(seconds = 95)
        assertTrue(CardioBests.records(treadmill, listOf(faster), standing).isEmpty())
        assertTrue(CardioBests.records(track, listOf(result(Measure.Distance(5000), 1000)), standing).isEmpty())
        assertTrue(CardioBests.records(track, listOf(result(Measure.Time(102), 102, 500)), standing).isEmpty())
        val timedStanding = CardioBests.bests(track, listOf(result(Measure.Time(1800), 1800, 4980)))
        assertTrue(CardioBests.records(track, listOf(result(Measure.Time(1801), 1801, 5300)), timedStanding).isEmpty())
        assertEquals(102, standing.values.single().elapsedSeconds)
        assertEquals(PrKind.FASTER, CardioBests.records(track, listOf(faster), standing).single().kind)
        assertFailsWith<IllegalArgumentException> {
            CardioBests.compare(faster, result(Measure.Distance(5000), 1000))
        }
    }

    @Test
    fun `only finished work counts on either side and aim does not split the record`() {
        val activity = CardioActivity.TRACK_RUN
        val excluded = CardioRole.entries.filter { it != CardioRole.WORK }.map { role ->
            result(seconds = 90, role = role)
        } + listOf(CardioSetStatus.SHORTENED, CardioSetStatus.SKIPPED).map { status ->
            result(seconds = 90, status = status)
        }
        assertTrue(CardioBests.bests(activity, excluded).isEmpty())
        val standing = CardioBests.bests(activity, listOf(result()))
        assertTrue(CardioBests.records(activity, excluded, standing).isEmpty())
        val faster = result(seconds = 98, aim = Measure.Time(105))
        assertEquals(1, CardioBests.records(activity, listOf(faster), standing).size)
        val timed = result(Measure.Time(1800), 1800, 5210)
        assertTrue(CardioBests.bests(activity, listOf(timed.copy(metres = null, distanceSource = null))).isEmpty())
    }

    @Test
    fun `multiple keys reduce separately and ties keep the first result`() {
        val activity = CardioActivity.ROAD_RUN
        val distance = result(seconds = 98)
        val time = result(Measure.Time(1800), 1800, 5210)
        val tiedDistance = distance.copy(metres = 400, distanceSource = DistanceSource.GPS)
        val bests = CardioBests.bests(activity, listOf(distance, tiedDistance, time))
        assertEquals(2, bests.size)
        assertEquals(distance, bests[CardioRecordKey(activity, distance.spec.end)])
        val standing = CardioBests.bests(activity, listOf(result(), result(Measure.Time(1800), 1800, 4980)))
        assertEquals(listOf(PrKind.FASTER, PrKind.FARTHER),
            CardioBests.records(activity, listOf(distance, tiedDistance, time), standing).map { it.kind })
    }
}
