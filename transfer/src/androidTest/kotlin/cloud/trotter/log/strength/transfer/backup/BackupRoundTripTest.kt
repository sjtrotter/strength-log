package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.TrackEdit
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import kotlinx.coroutines.flow.map
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
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun mid_setup_answers_without_a_program_survive_export_wipe_import() = runTest {
        val answers = wizardAnswers.copy(config = wizardAnswers.config.copy(weightUnit = WeightUnit.KG))
        repository.plans.setWizardAnswers(answers)
        settings.setUnit(WeightUnit.KG)
        assertFalse(settings.wizardCompleteFlow.first())
        assertTrue(repository.plans.programFlow.first().days.isEmpty())
        val backup = service.export()

        service.import(emptyBackupJson())
        assertTrue(db.planDao().allPlans().isEmpty())
        assertEquals(WizardAnswers(), repository.plans.wizardAnswersFlow.first())

        service.import(backup)

        assertEquals(answers, repository.plans.wizardAnswersFlow.first())
        assertEquals(answers, backupRepository.exportSnapshot().answers)
        assertEquals(PlanRecipeJson.encode(answers), db.planDao().allPlans().single().recipeJson)
        assertEquals(WeightUnit.KG, settings.unitFlow.first())
        assertFalse(settings.wizardCompleteFlow.first())
        assertTrue(repository.plans.programFlow.first().days.isEmpty())
        assertEquals(backup, service.export())
    }

    @Test
    fun export_wipe_import_reproduces_every_piece_of_state() = runTest {
        // --- build a rich, realistic state -----------------------------------
        repository.plans.setWizardAnswers(wizardAnswers)
        settings.setWizardComplete(true)
        settings.setUnit(WeightUnit.KG)
        settings.setRestTimerEnabled(false)
        settings.setRestOverride(RestCategory.TOP, 210)
        settings.setRestOverride(RestCategory.LIGHT, 30)
        settings.setKeepScreenOn(true)

        val customId = repository.catalog.addCustomExercise(
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
                ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 4, note = "belt on top"),
                ProgramExercise("bb_row", targetSets = 3),
                ProgramExercise("bb_bench", targetSets = 3),
            ),
            cardioBlock = CardioBlocks.fromLegacy(CardioSuggestion("Intervals", "5 min easy, then 4x2min hard/easy", hard = true), false, CardioActivity.TREADMILL_RUN),
        )
        val dayB = ProgramDay(
            id = "B",
            title = "Day B",
            emphasisLine = "Bench-focused upper",
            exercises = listOf(ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 4)),
        )
        repository.replaceProgram(Program(listOf(dayA, dayB)))

        val squatId = db.programDao().exerciseAt(PlanEntity.LEGACY_ID, "A", 0)!!.id
        val rowId = db.programDao().exerciseAt(PlanEntity.LEGACY_ID, "A", 1)!!.id
        repository.applyToRun(repository.dayTracksFlow(repository.plans.planIdOfSlot(squatId) ?: -1, "A").first().runId.orEmpty(), requireNotNull(repository.plans.planIdOfSlot(squatId)), "A", listOf(
            TrackEdit(squatId, Slot.MAIN, createsMissing = true) { listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)) },
        ))
        repository.applyToRun(repository.dayTracksFlow(repository.plans.planIdOfSlot(rowId) ?: -1, "A").first().runId.orEmpty(), requireNotNull(repository.plans.planIdOfSlot(rowId)), "A", listOf(
            TrackEdit(rowId, Slot.MAIN, createsMissing = true) { listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true)) },
        ))
        val sessionId = repository.finishRun(repository.openRunFlow.first()!!.id)!!
        repository.history.setSessionNote(sessionId, "Felt smooth")
        repository.setKeepToPlan(rowId, true)
        // A fresh post-advance edit, so the exported live logs carry current work.
        repository.applyToRun(repository.dayTracksFlow(repository.plans.planIdOfSlot(squatId) ?: -1, "A").first().runId.orEmpty(), requireNotNull(repository.plans.planIdOfSlot(squatId)), "A", listOf(
            TrackEdit(squatId, Slot.MAIN, createsMissing = true) { listOf(LoggedSet(250.0, 5, SetKind.TOP)) },
        ))

        val secondAnswers = wizardAnswers.copy(
            daysPerWeek = 5,
            config = wizardAnswers.config.copy(emphasis = GoalEmphasis.PHYSIQUE),
        )
        db.planDao().insertPlan(PlanEntity(
            2, "Test prep", PlanOrigin.GENERATED.name, 1, 2_000,
            recipeJson = PlanRecipeJson.encode(secondAnswers), suggestedDayId = "C",
        ))
        db.programDao().upsertDays(listOf(
            ProgramDayEntity(2, "A", 0, "Test A", "Bench-focused", null, nextRunId = "run-2A"),
            ProgramDayEntity(2, "B", 1, "Test B", "Squat-focused", null, nextRunId = "run-2B"),
            ProgramDayEntity(2, "C", 2, "Test C", "Row-focused", null, nextRunId = "run-2C"),
        ))
        val benchId = db.programDao().insertExercise(
            ProgramExerciseEntity(21, 2, "A", 0, "bb_bench", "RAMP_TOP_BACKOFF", 3, null, "pause"),
        )
        db.programDao().insertLogs(listOf(
            ExerciseLogEntity(
                2, "A", benchId, Slot.MAIN, SetJson.encodeSets(listOf(LoggedSet(135.0, 8, SetKind.WORK))), 3_000,
            ),
        ))
        settings.setSelectedPlanId(2)

        // --- snapshot the "before" state -------------------------------------
        // The app converts plans at launch; do the same so the restore, which also converts, has nothing new to add.
        repository.plans.runPlanSettingsFixupIfNeeded()
        val before = captureState()
        val backup = service.export()

        // --- wipe: importing an empty backup must clear everything ------------
        service.import(emptyBackupJson())
        assertTrue(repository.plans.programFlow.first().days.isEmpty())
        assertTrue(repository.history.sessionsFlow.first().isEmpty())
        assertTrue(repository.catalog.catalogFlow.first().entries.none { it.id == customId })
        assertEquals(null, repository.plans.suggestedDayFlow.first())
        // The wipe backup predates nothing — it simply carries no rest keys, so
        // the tuned buckets must fall back to RestPolicy's defaults, not linger.
        assertEquals(RestSettings(), settings.restSettingsFlow.first())
        // Same story for keep-screen-on: the empty backup carries no key, so the
        // restore must land on the absent-means-off default.
        assertEquals(false, settings.keepScreenOnFlow.first())

        // --- import the real backup and compare deep-equal --------------------
        service.import(backup)
        assertEquals(before, captureState())
        val restored = backupRepository.exportSnapshot()
        assertEquals(CURRENT_SCHEMA_VERSION, BackupCodec().decode(backup).schemaVersion)
        assertTrue(restored.exercises.first { it.id == rowId }.keepToPlan)
        assertEquals("Felt smooth", restored.sessions.single().note)
        assertTrue(settings.legacySessionNotesFlow.first().isEmpty())
        assertEquals(2, restored.plans.size)
        assertEquals(2L, restored.selectedPlanId)
        assertEquals(PlanRecipeJson.encode(wizardAnswers), restored.plans.first().recipeJson)
        assertEquals(PlanRecipeJson.encode(secondAnswers), restored.plans.last().recipeJson)
        assertEquals(listOf("B", "C"), restored.plans.map { it.suggestedDayId })

        // Spot-check a couple of load-bearing details beyond structural equality.
        assertEquals("C", repository.plans.suggestedDayFlow.first())
        assertEquals(WeightUnit.KG, settings.unitFlow.first())
        assertEquals(true, settings.keepScreenOnFlow.first())
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
        val sessions = repository.history.sessionsFlow.first()
        return State(
            snapshot = backupRepository.exportSnapshot(),
            program = repository.plans.programFlow.first(),
            logsA = repository.dayTracksFlow(repository.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().sortedBy { it.programExerciseId },
            logsB = repository.dayTracksFlow(repository.plans.selectedPlanIdFlow.first()!!, "B").map { it.tracks }.first().sortedBy { it.programExerciseId },
            sessions = sessions,
            sessionSets = sessions.associate { it.id to db.sessionDao().setsForSession(it.id) },
            catalog = repository.catalog.catalogFlow.first().entries,
            answers = repository.plans.wizardAnswersFlow.first(),
            unit = settings.unitFlow.first(),
            wizardComplete = settings.wizardCompleteFlow.first(),
            suggestedDay = repository.plans.suggestedDayFlow.first(),
            restSettings = settings.restSettingsFlow.first(),
            keepScreenOn = settings.keepScreenOnFlow.first(),
        )
    }
}
