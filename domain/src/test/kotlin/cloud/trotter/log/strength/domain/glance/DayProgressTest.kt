package cloud.trotter.log.strength.domain.glance

import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import kotlin.test.Test
import kotlin.test.assertEquals

class DayProgressTest {

    @Test
    fun `counts done and total main-track rounds across exercises`() {
        val day = WatchDay(
            dayId = "A",
            title = "Lower",
            accentIndex = 0,
            exercises = listOf(
                exercise(mainDone = listOf(true, false), partnerDone = listOf(true, true)),
                exercise(mainDone = listOf(true), partnerDone = emptyList()),
            ),
        )

        assertEquals(DayProgress(done = 2, total = 3), DayProgress.of(day))
    }

    private fun exercise(mainDone: List<Boolean>, partnerDone: List<Boolean>) = WatchExercise(
        programExerciseId = 1L,
        slot = "main",
        name = "Lift",
        goal = 0.0,
        perHand = false,
        supersetPartnerName = null,
        sets = mainDone.map(::set),
        ssSets = partnerDone.map(::set),
    )

    private fun set(done: Boolean) = WatchSet(0.0, 5, "WORK", done)
}
