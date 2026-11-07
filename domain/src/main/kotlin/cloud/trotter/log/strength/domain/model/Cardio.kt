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

enum class CardioSetStatus { FINISHED, SHORTENED, SKIPPED }

enum class DistanceSource { MANUAL, GPS, HEALTH_CONNECT }

data class CardioSetResult(
    val spec: CardioSetSpec,
    val status: CardioSetStatus,
    val elapsedSeconds: Int,
    val metres: Int? = null,
    val distanceSource: DistanceSource? = null,
) {
    init {
        require(elapsedSeconds >= 0) { "elapsedSeconds must not be negative" }
        require(metres == null || metres >= 0) { "metres must not be negative" }
        require((metres != null) == (distanceSource != null)) { "distanceSource is required iff metres is present" }
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
