package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.ExerciseSearch
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.mainRow
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestFormat
import cloud.trotter.log.strength.domain.standards.TestPick

enum class TestPickFilter { ALL, STRENGTH, CARDIO, HOLDS }

object TestPickSearch {
    fun filter(query: String, filter: TestPickFilter, runLabel: (FitnessTest) -> String): List<TestPick> {
        val search = ExerciseSearch(query)
        return buildList {
            ExerciseLibrary.entries.forEach { entry ->
                val formats = TestFormat.eligible(entry)
                if (formats.isNotEmpty() && search.matches(entry.name) && when (filter) {
                    TestPickFilter.ALL -> true
                    TestPickFilter.STRENGTH -> TestFormat.MAX_HOLD !in formats
                    TestPickFilter.HOLDS -> TestFormat.MAX_HOLD in formats
                    TestPickFilter.CARDIO -> false
                }) add(TestPick.Library(entry))
            }
            if (filter == TestPickFilter.ALL || filter == TestPickFilter.CARDIO) {
                FitnessTest.entries.filter { search.matches(runLabel(it)) }.forEach { add(TestPick.Run(it)) }
            }
        }
    }

    internal fun disabledReason(pick: TestPick, draft: SetupDraft): Int? = when (pick) {
        is TestPick.Run -> when {
            draft.fitnessTest != true -> R.string.setup_flow_test_run_entry
            !draft.testRun -> null
            draft.test == pick.test -> R.string.setup_flow_test_picked
            else -> R.string.setup_flow_test_one_run
        }
        is TestPick.Library -> when {
            draft.tests.any { TestEvents.find(it.eventId)?.exerciseId == pick.entry.id } ||
                draft.testLifts.any { it.exerciseId == pick.entry.id } -> R.string.setup_flow_test_picked
            pick.entry.mainCapable && draft.testLifts.any {
                mainRow(ExerciseLibrary.get(it.exerciseId).pattern) == mainRow(pick.entry.pattern)
            } -> R.string.setup_flow_test_one_lift
            else -> null
        }
    }
}

internal fun testLabel(test: FitnessTest): Int = when (test) {
    FitnessTest.TWO_MILE -> R.string.setup_flow_test_two_mile
    FitnessTest.ONE_AND_HALF_MILE -> R.string.setup_flow_test_mile_and_half
    FitnessTest.COOPER -> R.string.setup_flow_test_cooper
    FitnessTest.PACER -> R.string.setup_flow_test_pacer
    FitnessTest.HAMR -> R.string.setup_flow_test_hamr
}
