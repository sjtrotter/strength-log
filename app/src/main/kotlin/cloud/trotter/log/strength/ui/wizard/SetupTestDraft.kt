package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.mainRow
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestFormat
import cloud.trotter.log.strength.domain.standards.TestPick

internal fun SetupDraft.chooseFitnessTest(): SetupDraft = if (fitnessTest == true) this else copy(fitnessTest = true, branch = PlanBranch.RUN,
    runGoal = RunGoal.FITNESS_TEST, cardioFirst = true, goal = LiftGoal.GENERAL, step = SetupStep.GOAL,
    together = null, tests = emptyList(), testRun = false, testLifts = emptyList(), testDate = null,
    eventDate = null, goalSeconds = null, testScore = null, sprintCardio = false)

private fun SetupDraft.testGoal(): SetupDraft = copy(
    goal = if (testRun || testLifts.isNotEmpty()) LiftGoal.STRENGTH else LiftGoal.GENERAL)

internal fun SetupDraft.pick(pick: TestPick): SetupDraft = when (pick) {
    is TestPick.Run -> if (fitnessTest != true || testRun) this else copy(testRun = true, test = pick.test,
        goalSeconds = null, testScore = null, runGoal = RunGoal.FITNESS_TEST,
        cardioFirst = if (branch == PlanBranch.LIFT) true else cardioFirst).testGoal()
    is TestPick.Library -> {
        val entry = pick.entry
        val format = TestFormat.eligible(entry).firstOrNull()
        when {
            format == null || tests.any { TestEvents.find(it.eventId)?.exerciseId == entry.id } ||
                testLifts.any { it.exerciseId == entry.id } -> this
            format == TestFormat.HEAVIEST_FOR_REPS -> if (testLifts.any {
                mainRow(ExerciseLibrary.get(it.exerciseId).pattern) == mainRow(entry.pattern)
            }) this else copy(testLifts = testLifts + LiftTestDraft(entry.id,
                if (mainRow(entry.pattern) == 1) 3 else 5)).testGoal()
            else -> copy(tests = tests + TestDraft(TestEvents.derivedId(entry.id, format),
                windowSeconds = if (format == TestFormat.REPS_IN_WINDOW) 60 else null))
        }
    }
}.let { changed ->
    if (SetupFlowStateBuilder.branch(this) == PlanBranch.RUN && SetupFlowStateBuilder.branch(changed) == PlanBranch.BOTH)
        changed.copy(goal = LiftGoal.STRENGTH, cardioFirst = true) else changed
}

internal fun SetupDraft.removeTestPick(id: String): SetupDraft {
    val changedRules = testRun && id == test.name || testLifts.any { it.exerciseId == id }
    val changed = copy(tests = tests.filterNot { it.eventId == id },
        testLifts = testLifts.filterNot { it.exerciseId == id }, testRun = testRun && id != test.name)
    return if (changedRules) changed.testGoal() else changed
}

internal fun SetupDraft.testFormat(exerciseId: String, format: TestFormat): SetupDraft {
    val entry = ExerciseLibrary.find(exerciseId) ?: return this
    if (format !in TestFormat.eligible(entry) || format.setType == null) return this
    return copy(tests = tests.map { test ->
        val event = TestEvents.find(test.eventId)
        if (event == null || event.exerciseId != exerciseId || event.setType == format.setType) test
        else test.copy(eventId = TestEvents.derivedId(exerciseId, format),
            windowSeconds = if (format == TestFormat.REPS_IN_WINDOW) 60 else null)
    })
}

internal fun SetupDraft.testEdition(exerciseId: String, editionId: String?): SetupDraft = copy(tests = tests.map { test ->
    val event = TestEvents.find(test.eventId)
    if (event == null || event.exerciseId != exerciseId) test else {
        val format = TestFormat.entries.single { it.setType == event.setType }
        val edition = TestEvents.editions(exerciseId, format).firstOrNull { it.id == editionId }
        test.copy(eventId = edition?.id ?: TestEvents.derivedId(exerciseId, format),
            windowSeconds = if (format == TestFormat.REPS_IN_WINDOW) edition?.windowSeconds ?: 60 else null)
    }
})
