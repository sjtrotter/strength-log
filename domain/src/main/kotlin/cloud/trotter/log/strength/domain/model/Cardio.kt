package cloud.trotter.log.strength.domain.model

enum class CardioActivity {
    ROAD_RUN, TRAIL_RUN, TRACK_RUN, TREADMILL_RUN,
    OUTDOOR_WALK, TREADMILL_WALK, OUTDOOR_RIDE, STATIONARY_BIKE, ELLIPTICAL, ROWER,
}

enum class CardioRole { WARMUP, WORK, RECOVERY, COOLDOWN }

sealed interface Measure {
    data class Time(val seconds: Int) : Measure
    data class Distance(val metres: Int) : Measure
}

/** One cardio set: [end] finishes it (time by itself, distance with a tap);
 *  [aim], when set, is the other measure to beat ("400 m, aim 1:45"). */
data class CardioSetSpec(
    val role: CardioRole,
    val end: Measure,
    val aim: Measure? = null,
    val hard: Boolean = false,
) {
    init {
        require(aim == null || (end is Measure.Time) != (aim is Measure.Time)) {
            "aim must be a different kind of measure from end"
        }
        validate(end)
        aim?.let(::validate)
    }

    private fun validate(measure: Measure) {
        when (measure) {
            is Measure.Time -> require(measure.seconds in 1..86_400) { "seconds must be 1..86400" }
            is Measure.Distance -> require(measure.metres in 1..200_000) { "metres must be 1..200000" }
        }
    }
}

fun CardioSetSpec.endValue(): Int = when (val end = end) {
    is Measure.Time -> end.seconds
    is Measure.Distance -> end.metres
}

enum class CardioSetStatus { FINISHED, SHORTENED, SKIPPED }

enum class DistanceSource { MANUAL, GPS, HEALTH_CONNECT }

/** A supplied reading's source: TYPED by the user, GPS measured between two fresh fixes of the phone's track.
 *  Stored by name; new constants go at the end. Null means derived from the totals or no reading. */
enum class ReadingSource { TYPED, GPS }

/** What the phone's GPS saw over one set: [metres] from its start to its end, [complete] when the track
 *  covered the whole set, and [reading] at its planned end (metres at a time end, seconds at a distance end)
 *  when a fresh fix lies on each side of that moment. Only a complete set has a reading. */
data class GpsSet(val metres: Int, val reading: Int?, val complete: Boolean) {
    init { require(metres >= 0 && (reading == null || (complete && reading >= 0))) }

    companion object {
        /** The worst accuracy the track accepts, and the margin a tap must miss by before GPS replaces it. */
        const val RESOLUTION_METRES = 20
    }
}

data class CardioSetResult(
    val spec: CardioSetSpec,
    val status: CardioSetStatus,
    val elapsedSeconds: Int,
    val metres: Int? = null,
    val distanceSource: DistanceSource? = null,
    /** #526: the reading when the planned end was reached, written at that moment by the phone and watch executors
     *  and the past-session form (PR 2b): [secondsAtEnd] always, [metresAtEnd] when a distance is known. Null on a set
     *  that never reached its end and on rows from before v25. [elapsedSeconds] and [metres] stay the totals, so the
     *  overrun is the difference. */
    val metresAtEnd: Int? = null,
    val secondsAtEnd: Int? = null,
    val readingSource: ReadingSource? = null,
    /** A timed sprint rep's time (design §3.1.3); null on every other set. */
    val timing: SprintTime? = null,
) {
    init {
        require(elapsedSeconds >= 0) { "elapsedSeconds must not be negative" }
        require(metres == null || metres >= 0) { "metres must not be negative" }
        require((metres != null) == (distanceSource != null)) { "distanceSource is required iff metres is present" }
        require(metresAtEnd == null || metresAtEnd >= 0) { "metresAtEnd must not be negative" }
        require(secondsAtEnd == null || secondsAtEnd >= 0) { "secondsAtEnd must not be negative" }
    }
}

data class CardioBlock(val activity: CardioActivity, val sets: List<CardioSetSpec>) {
    /** A lone timed set is a steady effort: it counts up and ends when the lifter stops, not at its planned time. */
    val openEnded: Boolean get() = sets.size == 1 && sets.single().end is Measure.Time

    init {
        require(sets.isNotEmpty()) { "cardio block must have at least one set" }
    }
}

/** What a cardio block's increase changes. Stored by name; new constants go at the end. */
enum class ProgressMeasure { DISTANCE, TIME, PACE }

/** The wizard's activity groups; History totals use the same grouping (#497). */
enum class CardioFamily(val activities: List<CardioActivity>) {
    RUN(listOf(CardioActivity.ROAD_RUN, CardioActivity.TRAIL_RUN, CardioActivity.TRACK_RUN, CardioActivity.TREADMILL_RUN)),
    RIDE(listOf(CardioActivity.OUTDOOR_RIDE, CardioActivity.STATIONARY_BIKE)),
    ROW(listOf(CardioActivity.ROWER)),
    WALK(listOf(CardioActivity.OUTDOOR_WALK, CardioActivity.TREADMILL_WALK)),
    ELLIPTICAL(listOf(CardioActivity.ELLIPTICAL));

    companion object {
        fun of(activity: CardioActivity): CardioFamily = entries.first { activity in it.activities }
    }
}

/** One saved route point: [seconds] since the run's start, and the anchor fix's latitude and longitude in
 *  1e-5 degrees (about 1.1 m). Only stored when the user turns on Save route. */
data class RoutePoint(val seconds: Int, val latitudeE5: Int, val longitudeE5: Int)
