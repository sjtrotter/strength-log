package io.github.sjtrotter.strengthlog.transfer.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The A2 acceptance test: export -> wipe -> import must reproduce every piece of a
 * user's state exactly. Read back through the repository (the same surface the app
 * uses), not the raw tables, so it proves the state the app *sees* is identical.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest : BackupTestHarness() {

    private val wizardAnswers = WizardAnswers(
        daysPerWeek = 5,
        split = SplitTemplate.UPPER_LOWER,
        anchorScheme = AnchorScheme.BIG_4,
        deadliftVariant = DeadliftVariant.CONVENTIONAL,
        cardio = CardioPrefs(CardioMode.TREADMILL, CardioPlacement.SEPARATE_DAYS, fiveKGoal = false),
        config = LifterConfig(bodyweightLb = 210, age = 33, level = ExperienceLevel.ADVANCED, emphasis = GoalEmphasis.STRENGTH),
        equipment = setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.RACK),
    )

    /** A minimal but structurally valid empty backup used to wipe the device. */
    private fun emptyBackupJson(): String = BackupCodec().encode(
        BackupDocument(
            settings = SettingsBackup(
                bodyweightLb = 235, age = 40, level = "INTERMEDIATE", emphasis = "BALANCED",
                cardioMode = "OUTDOOR_RUN", cardioPlacement = "FINISHERS", fiveKGoal = true,
                daysPerWeek = 4, split = "FULL_BODY", anchorScheme = "PROTOTYPE",
                deadliftVariant = "TRAP_BAR", equipment = emptyList(), weightUnit = "LB",
                wizardComplete = false, suggestedDay = null,
            ),
        ),
    )

    @Test
    fun export_wipe_import_reproduces_every_piece_of_state() = runTest {
        // --- build a rich, realistic state -----------------------------------
        repository.setWizardAnswers(wizardAnswers)
        repository.setWizardComplete(true)
        repository.setUnit(WeightUnit.KG)

        val customId = repository.addCustomExercise(
            name = "Cable Hack Squat",
            pattern = MovementPattern.SQUAT_BILATERAL,
            equipment = listOf(Equipment.MACHINE, Equipment.CABLE),
            perHand = false,
            goalStartLb = 80.0,
        )

        val dayA = ProgramDay(
            id = "A",
            title = "Day A",
            emphasisLine = "Squat-focused lower",
            exercises = listOf(
                ProgramExercise("bb_back_squat", isMain = true, targetSets = 4, repSchemeLabel = "5/5/5/3", hasWarmupHint = true, note = "belt on top"),
                ProgramExercise("bb_row", targetSets = 3, repSchemeLabel = "8-12"),
                ProgramExercise("bb_bench", targetSets = 3, repSchemeLabel = "6-10"),
            ),
            cardio = CardioSuggestion("Intervals", "5 min easy, then 4x2min hard/easy", hard = true),
        )
        val dayB = ProgramDay(
            id = "B",
            title = "Day B",
            emphasisLine = "Bench-focused upper",
            exercises = listOf(ProgramExercise("bb_bench", isMain = true, targetSets = 4)),
            cardio = null,
        )
        repository.replaceProgram(Program(listOf(dayA, dayB)))

        val squatId = db.programDao().exerciseAt("A", 0)!!.id
        val rowId = db.programDao().exerciseAt("A", 1)!!.id
        repository.updateSets("A", squatId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))
        repository.updateSets("A", rowId, Slot.MAIN, listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true)))
        repository.advanceDay("A") // history + rotation A -> B + clears A's checks
        // A fresh post-advance edit, so the exported live logs carry current work.
        repository.updateSets("A", squatId, Slot.MAIN, listOf(LoggedSet(250.0, 5, SetKind.TOP)))

        // --- snapshot the "before" state -------------------------------------
        val before = captureState()
        val backup = service.export()

        // --- wipe: importing an empty backup must clear everything ------------
        service.import(emptyBackupJson())
        assertTrue(repository.programFlow.first().days.isEmpty())
        assertTrue(repository.sessionsFlow.first().isEmpty())
        assertTrue(repository.catalogFlow.first().entries.none { it.id == customId })
        assertEquals(null, repository.suggestedDayFlow.first())

        // --- import the real backup and compare deep-equal --------------------
        service.import(backup)
        assertEquals(before, captureState())

        // Spot-check a couple of load-bearing details beyond structural equality.
        assertEquals("B", repository.suggestedDayFlow.first())
        assertEquals(WeightUnit.KG, repository.unitFlow.first())
        assertEquals(listOf(LoggedSet(250.0, 5, SetKind.TOP)), repository.logFlow("A").first().first { it.programExerciseId == squatId }.sets)
    }

    /** Everything the app can observe, as one comparable value. */
    private data class State(
        val program: Program,
        val logsA: List<Any>,
        val logsB: List<Any>,
        val sessions: List<Any>,
        val sessionSets: Map<Long, List<Any>>,
        val catalog: List<Any>,
        val answers: WizardAnswers,
        val unit: WeightUnit,
        val wizardComplete: Boolean,
        val suggestedDay: String?,
    )

    private suspend fun captureState(): State {
        val sessions = repository.sessionsFlow.first()
        return State(
            program = repository.programFlow.first(),
            logsA = repository.logFlow("A").first().sortedBy { it.programExerciseId },
            logsB = repository.logFlow("B").first().sortedBy { it.programExerciseId },
            sessions = sessions,
            sessionSets = sessions.associate { it.id to db.sessionDao().setsForSession(it.id) },
            catalog = repository.catalogFlow.first().entries,
            answers = repository.wizardAnswersFlow.first(),
            unit = repository.unitFlow.first(),
            wizardComplete = repository.wizardCompleteFlow.first(),
            suggestedDay = repository.suggestedDayFlow.first(),
        )
    }
}
