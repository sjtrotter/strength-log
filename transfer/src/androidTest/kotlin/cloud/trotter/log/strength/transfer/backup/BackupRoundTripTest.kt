package cloud.trotter.log.strength.transfer.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
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
        daysPerWeek = 3,
        split = SplitTemplate.UPPER_LOWER,
        anchorScheme = AnchorScheme.BIG_4,
        deadliftVariant = DeadliftVariant.CONVENTIONAL,
        cardio = CardioPrefs(CardioMode.TREADMILL, CardioPlacement.SEPARATE_DAYS, fiveKGoal = false),
        config = LifterConfig(bodyweightLb = 210, age = 33, level = ExperienceLevel.ADVANCED, emphasis = GoalEmphasis.STRENGTH),
        equipment = setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.RACK),
    )

    @Test
    fun export_wipe_import_reproduces_every_piece_of_state() = runTest {
        // --- build a rich, realistic state -----------------------------------
        repository.setWizardAnswers(wizardAnswers)
        repository.setWizardComplete(true)
        repository.setUnit(WeightUnit.KG)
        repository.setRestTimerEnabled(false)
        repository.setRestOverride(RestCategory.TOP, 210)
        repository.setRestOverride(RestCategory.LIGHT, 30)
        repository.setKeepScreenOn(true)

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

        val squatId = db.programDao().exerciseAt(PlanEntity.LEGACY_ID, "A", 0)!!.id
        val rowId = db.programDao().exerciseAt(PlanEntity.LEGACY_ID, "A", 1)!!.id
        repository.updateSets("A", squatId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))
        repository.updateSets("A", rowId, Slot.MAIN, listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true)))
        repository.advanceDay("A") // history + rotation A -> B + clears A's checks
        // A fresh post-advance edit, so the exported live logs carry current work.
        repository.updateSets("A", squatId, Slot.MAIN, listOf(LoggedSet(250.0, 5, SetKind.TOP)))

        val secondAnswers = wizardAnswers.copy(
            daysPerWeek = 5,
            config = wizardAnswers.config.copy(emphasis = GoalEmphasis.PHYSIQUE),
        )
        db.planDao().insertPlan(PlanEntity(
            2, "Test prep", PlanOrigin.GENERATED.name, 1, 2_000,
            recipeJson = PlanRecipeJson.encode(secondAnswers), suggestedDayId = "C",
        ))
        db.programDao().upsertDays(listOf(
            ProgramDayEntity(2, "A", 0, "Test A", "Bench-focused", null),
            ProgramDayEntity(2, "B", 1, "Test B", "Squat-focused", null),
            ProgramDayEntity(2, "C", 2, "Test C", "Row-focused", null),
        ))
        val benchId = db.programDao().insertExercise(
            ProgramExerciseEntity(21, 2, "A", 0, "bb_bench", true, 3, "8", false, null, "pause"),
        )
        db.programDao().insertLogs(listOf(
            ExerciseLogEntity(
                2, "A", benchId, Slot.MAIN, SetJson.encodeSets(listOf(LoggedSet(135.0, 8, SetKind.WORK))),
                "2026-10-20", 3_000,
            ),
        ))
        settings.setSelectedPlanId(2)

        // --- snapshot the "before" state -------------------------------------
        val before = captureState()
        val backup = service.export()

        // --- wipe: importing an empty backup must clear everything ------------
        service.import(emptyBackupJson())
        assertTrue(repository.programFlow.first().days.isEmpty())
        assertTrue(repository.sessionsFlow.first().isEmpty())
        assertTrue(repository.catalogFlow.first().entries.none { it.id == customId })
        assertEquals(null, repository.suggestedDayFlow.first())
        // The wipe backup predates nothing — it simply carries no rest keys, so
        // the tuned buckets must fall back to RestPolicy's defaults, not linger.
        assertEquals(RestSettings(), repository.restSettingsFlow.first())
        // Same story for keep-screen-on: the empty backup carries no key, so the
        // restore must land on the absent-means-off default.
        assertEquals(false, repository.keepScreenOnFlow.first())

        // --- import the real backup and compare deep-equal --------------------
        service.import(backup)
        assertEquals(before, captureState())
        val restored = repository.exportSnapshot()
        assertEquals(2, restored.plans.size)
        assertEquals(2L, restored.selectedPlanId)
        assertEquals(PlanRecipeJson.encode(wizardAnswers), restored.plans.first().recipeJson)
        assertEquals(PlanRecipeJson.encode(secondAnswers), restored.plans.last().recipeJson)
        assertEquals(listOf("B", "C"), restored.plans.map { it.suggestedDayId })

        // Spot-check a couple of load-bearing details beyond structural equality.
        assertEquals("C", repository.suggestedDayFlow.first())
        assertEquals(WeightUnit.KG, repository.unitFlow.first())
        assertEquals(true, repository.keepScreenOnFlow.first())
        val squatLog = db.programDao().allLogs().first { it.planId == PlanEntity.LEGACY_ID && it.programExerciseId == squatId }
        assertEquals(listOf(LoggedSet(250.0, 5, SetKind.TOP)), SetJson.decodeSets(squatLog.setsJson))
    }

    /** Everything the app can observe, as one comparable value. */
    private data class State(
        val snapshot: FullSnapshot,
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
        val restSettings: RestSettings,
        val keepScreenOn: Boolean,
    )

    private suspend fun captureState(): State {
        val sessions = repository.sessionsFlow.first()
        return State(
            snapshot = repository.exportSnapshot(),
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
            restSettings = repository.restSettingsFlow.first(),
            keepScreenOn = repository.keepScreenOnFlow.first(),
        )
    }
}
