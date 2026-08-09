package cloud.trotter.log.strength.domain.glance

import cloud.trotter.log.strength.domain.sync.WatchDay

/** Progress through a watch day, counting main-track rounds only. */
data class DayProgress(val done: Int, val total: Int) {

    companion object {
        fun of(day: WatchDay): DayProgress = DayProgress(
            done = day.exercises.sumOf { exercise -> exercise.sets.count { it.done } },
            total = day.exercises.sumOf { it.sets.size },
        )
    }
}
