package cloud.trotter.log.strength.ui.test

import cloud.trotter.log.strength.ui.wizard.equipmentLabel
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.model.Equipment
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.ui.wizard.testLabel

/** The stored slots own the dose, including a trimmed or substituted movement. */
data class TestTrainingUi(val name: String, val slots: List<Pair<String, ProgramExercise>>, val reason: Int? = null,
    val equipment: List<Equipment> = emptyList())

object TestTraining {
    fun event(program: Program, answers: SetupAnswers, eventId: String): TestTrainingUi? {
        val event = TestEvents.find(eventId) ?: return null
        return movement(program, answers, event.exerciseId, eventId)
    }

    fun lift(program: Program, answers: SetupAnswers, exerciseId: String): TestTrainingUi =
        movement(program, answers, exerciseId, null)

    private fun movement(program: Program, answers: SetupAnswers, id: String, eventId: String?): TestTrainingUi {
        val entry = ExerciseLibrary.get(id)
        val slots = program.days.flatMap { day -> day.exercises.filter {
            if (eventId != null) it.prescription?.test?.eventId == eventId else it.exerciseId == id
        }.map { day.id to it } }
        val reason = when {
            slots.isNotEmpty() -> null
            !entry.availableWith(answers.equipment, answers.excluded) -> R.string.test_training_equipment
            answers.days == 1 && answers.branch == PlanBranch.BOTH -> R.string.test_training_one_day
            answers.branch == PlanBranch.BOTH && answers.together() == BothAnswers.Together.SAME_VISIT -> R.string.test_training_same_visit
            else -> R.string.test_training_missing
        }
        return TestTrainingUi(entry.name, slots, reason, entry.equipment)
    }
}

@Composable
fun testTrainingText(ui: TestTrainingUi): String {
    val detail = ui.reason?.let { reason ->
        if (reason == R.string.test_training_equipment) {
            val names = ui.equipment.map { equipmentName(it).lowercase(java.util.Locale.US) }
            val needs = if (names.size < 2) names.joinToString() else stringResource(R.string.test_training_equipment_list,
                names.dropLast(1).joinToString(", "), names.last())
            stringResource(reason, needs)
        } else stringResource(reason)
    } ?: ui.slots.groupBy { it.second }.entries.map { (slot, rows) ->
        val target = when (val aim = slot.prescription?.target) {
            is PrescribedTarget.RepsInTime -> stringResource(R.string.test_training_window, slot.targetSets,
                aim.repAim ?: 0, ClockText.duration(aim.seconds))
            is PrescribedTarget.Hold -> stringResource(R.string.test_training_sets, slot.targetSets, ClockText.duration(aim.seconds))
            is PrescribedTarget.Reps -> stringResource(R.string.test_training_sets, slot.targetSets, aim.reps.toString())
            is PrescribedTarget.LoadReps -> stringResource(R.string.test_training_sets, slot.targetSets, aim.reps.toString())
            null -> stringResource(R.string.test_training_sets, slot.targetSets, (slot.rule?.target?.reps ?: 0).toString())
        }
        val dose = stringResource(if (rows.size == 1) R.string.test_training_day else R.string.test_training_days,
            target, testList(rows.map { it.first }))
        val event = slot.prescription?.test?.let { TestEvents.find(it.eventId) }
        if (event != null && slot.exerciseId != event.exerciseId)
            stringResource(R.string.test_training_easier, dose, ExerciseLibrary.get(slot.exerciseId).name) else dose
    }.joinToString("; ")
    return stringResource(R.string.test_training_line, ui.name, detail)
}

@Composable
private fun equipmentName(equipment: Equipment): String =
    stringResource(equipmentLabel(equipment))

@Composable
fun testRunTrainingText(test: FitnessTest, program: Program): String {
    val name = stringResource(testLabel(test))
    if (test == FitnessTest.COOPER) return stringResource(R.string.test_training_cooper, name)
    if (test == FitnessTest.PACER || test == FitnessTest.HAMR) return stringResource(R.string.test_training_shuttle, name)
    val runs = program.days.filter { it.cardioBlock != null }
    val intervals = runs.filter { it.cardioRule?.session == RunSession.TEST_INTERVALS }.map { it.id }
    val ladder = runs.filter { it.cardioRule?.session == RunSession.LADDER }.map { it.id }
    val tempo = runs.filter { it.cardioRule?.session == RunSession.TEMPO }.map { it.id }
    val easy = runs.filter { it.id !in intervals + ladder + tempo }.map { it.id }
    val lines = buildList {
        if (intervals.isNotEmpty()) add(R.string.test_training_run_intervals to intervals)
        if (tempo.isNotEmpty()) add(R.string.test_training_run_tempo to tempo)
        if (ladder.isNotEmpty()) add(R.string.test_training_run_ladder to ladder)
        if (easy.isNotEmpty()) add(R.string.test_training_run_easy to easy)
    }.map { (resource, days) -> stringResource(resource, testList(days)) }
    return stringResource(R.string.test_training_line, name, lines.joinToString(", "))
}

@Composable
private fun testList(values: List<String>): String = if (values.size < 2) values.joinToString()
    else stringResource(R.string.test_training_equipment_list, values.dropLast(1).joinToString(", "), values.last())
