package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.GpsSet
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Fix(val elapsedMillis: Long, val latitude: Double, val longitude: Double, val accuracyMetres: Double)

interface SetMeter {
    fun measure(spec: CardioSetSpec, startMillis: Long, endMillis: Long): GpsSet?
    fun settled(momentMillis: Long, nowMillis: Long): Boolean
}

/** Pure and single-threaded; holds no coordinates beyond the anchor and the last accepted fix. */
class DistanceTrack(private val maxMetresPerSecond: Double) : SetMeter {
    private val times = mutableListOf<Long>()
    private val values = mutableListOf<Double>()
    private val moveTimes = mutableListOf<Long>()
    private val moveMetres = mutableListOf<Double>()
    private var anchor: Fix? = null
    private var lastFix: Fix? = null
    private var anchorMetres = 0.0

    val lastFixMillis: Long? get() = lastFix?.elapsedMillis

    fun add(fix: Fix): Boolean {
        val last = lastFix
        if (fix.accuracyMetres > GpsSet.RESOLUTION_METRES ||
            (last != null && fix.elapsedMillis <= last.elapsedMillis)) return false
        if (last != null) {
            val gap = fix.elapsedMillis - last.elapsedMillis
            if (gap <= MAX_GAP_MILLIS && distance(last, fix) / (gap / 1000.0) > maxMetresPerSecond) return false
        }
        val base = anchor
        if (base == null) {
            anchor = fix
            moveTimes.add(fix.elapsedMillis)
            moveMetres.add(anchorMetres)
        } else {
            val step = distance(base, fix)
            if (step > max(MIN_STEP_METRES, base.accuracyMetres + fix.accuracyMetres)) {
                anchorMetres += step
                anchor = fix
                moveTimes.add(fix.elapsedMillis)
                moveMetres.add(anchorMetres)
            }
        }
        times.add(fix.elapsedMillis)
        values.add(anchorMetres)
        lastFix = fix
        return true
    }

    fun metresAt(elapsedMillis: Long): Double? {
        if (times.isEmpty()) return null
        val next = lowerBound(elapsedMillis.toDouble())
        if (next == 0) return values.first()
        if (next == times.size) return values.last()
        val fraction = (elapsedMillis - times[next - 1]).toDouble() / (times[next] - times[next - 1])
        return values[next - 1] + fraction * (values[next] - values[next - 1])
    }

    fun fresh(elapsedMillis: Long): Boolean = fresh(elapsedMillis.toDouble())

    private fun fresh(moment: Double): Boolean {
        val next = lowerBound(moment)
        if (next == times.size) return false
        val before = if (times[next].toDouble() == moment) next else next - 1
        return before >= 0 && moment - times[before] <= FRESH_MILLIS && times[next] - moment <= FRESH_MILLIS
    }

    override fun settled(momentMillis: Long, nowMillis: Long): Boolean =
        (lastFixMillis?.let { it >= momentMillis } == true) || nowMillis >= momentMillis + FRESH_MILLIS

    override fun measure(spec: CardioSetSpec, startMillis: Long, endMillis: Long): GpsSet? {
        val end = spec.end
        if (end is Measure.Distance && end.metres < MIN_DISTANCE_END_METRES) return null
        val first = lowerBound((startMillis - FRESH_MILLIS).toDouble())
        if (first == times.size || times[first] > endMillis) return null
        val base = requireNotNull(metresAt(startMillis))
        val metres = whole(requireNotNull(metresAt(endMillis)) - base)
        val complete = covered(startMillis, endMillis)
        val reading = if (!complete || !fresh(startMillis)) null else when (end) {
            is Measure.Time -> {
                val deadline = startMillis + end.seconds * 1000L
                if (deadline <= endMillis && fresh(deadline)) whole(requireNotNull(metresAt(deadline)) - base)
                else null
            }
            is Measure.Distance -> crossing(base + end.metres, startMillis, endMillis)?.let { moment ->
                if (fresh(moment)) whole((moment - startMillis) / 1000.0) else null
            }
        }
        return GpsSet(metres, reading, complete)
    }

    fun covered(startMillis: Long, nowMillis: Long): Boolean {
        if (!near(startMillis) || !near(nowMillis)) return false
        var next = max(1, lowerBound(startMillis.toDouble()))
        while (next < times.size && times[next - 1] < nowMillis) {
            val gap = min(times[next], nowMillis) - max(times[next - 1], startMillis)
            if (gap > MAX_GAP_MILLIS) return false
            next++
        }
        return true
    }

    fun metresPerSecond(nowMillis: Long): Double? {
        val latest = moveTimes.binarySearch(nowMillis).let { if (it >= 0) it else -it - 2 }
        if (latest < 0 || nowMillis - moveTimes[latest] > SPEED_WINDOW_MILLIS) return null
        val base = moveTimes.binarySearch(nowMillis - SPEED_WINDOW_MILLIS).let { if (it >= 0) it else -it - 2 }
        if (base < 0 || moveMetres[latest] - moveMetres[base] < MIN_SPEED_METRES) return null
        return (moveMetres[latest] - moveMetres[base]) / ((moveTimes[latest] - moveTimes[base]) / 1000.0)
    }

    private fun near(moment: Long): Boolean {
        val next = lowerBound(moment.toDouble())
        return (next < times.size && times[next] - moment <= FRESH_MILLIS) ||
            (next > 0 && moment - times[next - 1] <= FRESH_MILLIS)
    }

    private fun crossing(target: Double, startMillis: Long, endMillis: Long): Double? {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) / 2
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        if (low == values.size || low == 0) return null
        val moment = times[low - 1] + (target - values[low - 1]) /
            (values[low] - values[low - 1]) * (times[low] - times[low - 1])
        return moment.takeIf { it >= startMillis && it <= endMillis }
    }

    private fun lowerBound(moment: Double): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val middle = (low + high) / 2
            if (times[middle] < moment) low = middle + 1 else high = middle
        }
        return low
    }

    // Floating-point roundoff must not drop an exact whole metre or second.
    private fun whole(value: Double): Int = floor(value + 1e-7).toInt()

    private fun distance(from: Fix, to: Fix): Double {
        val latitude = Math.toRadians(to.latitude - from.latitude)
        val longitude = Math.toRadians(to.longitude - from.longitude)
        val a = sin(latitude / 2) * sin(latitude / 2) +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) * sin(longitude / 2) * sin(longitude / 2)
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        fun forActivity(activity: CardioActivity): DistanceTrack =
            DistanceTrack(if (CardioFamily.of(activity) == CardioFamily.RIDE) 30.0 else 12.0)

        const val MAX_GAP_MILLIS = 60_000L
        const val FRESH_MILLIS = 10_000L
        const val MIN_STEP_METRES = 10.0
        const val MIN_DISTANCE_END_METRES = 400
        const val SPEED_WINDOW_MILLIS = 30_000L
        const val MIN_SPEED_METRES = 20.0
        const val EARTH_RADIUS_METRES = 6_371_000.0
    }
}
