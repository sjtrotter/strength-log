package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.generator.CardioBlocks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.standards.SetFormatter
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerRepositoryPlanScopeTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("plan-scope-settings", ".preferences_pb")
            },
        )
        val journalStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("plan-scope-journal", ".preferences_pb")
        }
        journal = RestoreJournal(journalStore, settings)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
            clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `replace on a fresh database creates and selects My plan`() = runTest {
        repo.replaceProgram(program("First plan", "bb_back_squat"))

        val plan = repo.plansFlow.first().single()
        assertEquals(PlanEntity.LEGACY_NAME, plan.name)
        assertEquals(plan.id, settings.selectedPlanIdFlow.first())
        assertEquals(listOf("First plan"), repo.programFlow.first().days.map { it.title })
    }

    @Test
    fun `replace with only an archived plan creates and selects a new plan`() = runTest {
        val archived = plan(1, "Archived", position = 4, archived = true)
        db.planDao().insertPlan(archived)
        val archivedSlot = insertProgram(1, "Archived program", "bb_back_squat", id = 11)
        db.programDao().upsertLog(log(1, archivedSlot, sets(225.0)))
        settings.setSelectedPlanId(1)
        val archivedDays = db.programDao().daysForPlan(1)
        val archivedExercises = db.programDao().exercisesForPlan(1)
        val archivedLogs = db.programDao().logsForDay(1, "A")
        val replacement = program("New program", "bb_bench")

        repo.replaceProgram(replacement)

        val plans = db.planDao().allPlans()
        assertEquals(2, plans.size)
        assertEquals(archived, plans.first())
        val created = plans.last()
        assertEquals(PlanEntity.LEGACY_NAME, created.name)
        assertEquals(PlanOrigin.GENERATED.name, created.origin)
        assertEquals(5, created.position)
        assertEquals(Instant.parse("2026-10-19T12:00:00Z").toEpochMilli(), created.createdAt)
        assertFalse(created.archived)
        assertEquals(created.id, settings.selectedPlanIdFlow.first())
        assertEquals(created.id, repo.selectedPlanIdFlow.first())
        assertEquals(replacement, repo.programFlow.first())
        assertEquals(listOf("New program"), db.programDao().daysForPlan(created.id).map { it.title })
        assertEquals(listOf("bb_bench"), db.programDao().exercisesForPlan(created.id).map { it.exerciseId })
        assertEquals(archivedDays, db.programDao().daysForPlan(1))
        assertEquals(archivedExercises, db.programDao().exercisesForPlan(1))
        assertEquals(archivedLogs, db.programDao().logsForDay(1, "A"))
    }

    @Test
    fun `live writes and flows follow the selected plan`() = runTest {
        repo.replaceProgram(program("Plan one", "bb_back_squat"))
        val planOneId = repo.plansFlow.first().single().id
        val slotOne = db.programDao().exerciseAt(planOneId, "A", 0)!!.id
        insertPlanTwo()
        val slotTwo = insertProgram(2, "Plan two", "bb_bench", id = 22)
        val planOneSets = sets(225.0)
        val planTwoSets = sets(135.0)
        db.programDao().upsertLog(log(2, slotTwo, planTwoSets))

        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slotOne) ?: -1, "A").first().runId.orEmpty(), "A", slotOne, Slot.MAIN, planOneSets)

        val emissions = mutableListOf<List<LoggedSlot>>()
        val emitted = Channel<Unit>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.selectedPlanIdFlow.flatMapLatest { repo.dayTracksFlow(it!!, "A") }.map { it.tracks }.collect {
                emissions.add(it)
                emitted.send(Unit)
            }
        }
        emitted.receive()
        assertEquals(planOneSets, emissions.single().single().sets)
        settings.setSelectedPlanId(2)
        emitted.receive()
        assertEquals(2, emissions.size)
        assertEquals(slotTwo, emissions[1].single().programExerciseId)
        assertEquals(planTwoSets, emissions[1].single().sets)
        collector.cancelAndJoin()
        assertEquals(
            planOneSets,
            SetJson.decodeSets(db.programDao().logsForDay(planOneId, "A").single().setsJson),
        )
    }

    @Test
    fun `replace clears only the selected plan`() = runTest {
        repo.replaceProgram(program("Plan one", "bb_back_squat"))
        val planOneId = repo.plansFlow.first().single().id
        val slotOne = db.programDao().exerciseAt(planOneId, "A", 0)!!.id
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slotOne) ?: -1, "A").first().runId.orEmpty(), "A", slotOne, Slot.MAIN, sets(225.0))
        insertPlanTwo()
        val slotTwo = insertProgram(2, "Plan two", "bb_bench", id = 22)
        db.programDao().upsertLog(log(2, slotTwo, sets(135.0)))

        repo.replaceProgram(program("Replacement", "db_goblet_squat"))

        assertEquals(listOf("Replacement"), db.programDao().daysForPlan(planOneId).map { it.title })
        assertEquals(listOf("Plan two"), db.programDao().daysForPlan(2).map { it.title })
        assertEquals(listOf(22L), db.programDao().exercisesForPlan(2).map { it.id })
        assertEquals(listOf(slotTwo), db.programDao().logsForDay(2, "A").map { it.programExerciseId })
    }

    @Test
    fun `swap by id clears only the selected plan log for that slot`() = runTest {
        assertSwapClearsOnlySelectedPlanSlot { slotId ->
            repo.swapExerciseById("A", slotId, "db_goblet_squat")
        }
    }

    @Test
    fun `swap by id changes the slot and clears both tracks in its own plan`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val preservedLogs = listOf(
            log(2, 22, sets(135.0)),
            log(2, 22, sets(65.0)).copy(slot = Slot.SS),
            log(2, 11, sets(95.0)),
            log(2, 11, sets(45.0)).copy(slot = Slot.SS),
        )
        db.programDao().insertLogs(
            preservedLogs + listOf(
                log(1, 11, sets(225.0)),
                log(1, 11, sets(115.0)).copy(slot = Slot.SS),
            ),
        )
        val planTwoExercises = db.programDao().exercisesForPlan(2)

        repo.swapExerciseById("A", 11, "db_goblet_squat")

        assertEquals("db_goblet_squat", db.programDao().exerciseAt(1, "A", 0)!!.exerciseId)
        assertEquals(preservedLogs.toSet(), db.programDao().allLogs().toSet())
        assertEquals(planTwoExercises, db.programDao().exercisesForPlan(2))
    }

    @Test
    fun `update sets writes under the slot plan despite another selection`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val main = sets(225.0)

        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(11) ?: -1, "A").first().runId.orEmpty(), "A", 11, Slot.MAIN, main)

        assertSlotLogsInPlanOne(mapOf(Slot.MAIN to main))
    }

    @Test
    fun `paired update writes both tracks under the slot plan despite another selection`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        db.programDao().setSupersetExerciseId(11, "bb_bench")
        val main = sets(225.0)
        val ss = sets(115.0)

        repo.updateSetsPaired(repo.dayTracksFlow(repo.planIdOfSlot(11) ?: -1, "A").first().runId.orEmpty(), "A", 11, main, ss)

        assertSlotLogsInPlanOne(mapOf(Slot.MAIN to main, Slot.SS to ss))
    }

    @Test
    fun `seed writes under the slot plan despite another selection and never overwrites`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val main = sets(225.0)

        assertTrue(repo.seedIfEmpty("A", 11, Slot.MAIN, main))
        assertFalse(repo.seedIfEmpty("A", 11, Slot.MAIN, sets(235.0)))

        assertSlotLogsInPlanOne(mapOf(Slot.MAIN to main))
    }

    @Test
    fun `seed never replaces an empty stored track in the slot plan`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val emptyLog = log(1, 11, emptyList())
        db.programDao().upsertLog(emptyLog)

        assertFalse(repo.seedIfEmpty("A", 11, Slot.MAIN, sets(225.0)))

        assertEquals(listOf(emptyLog), db.programDao().allLogs())
    }

    @Test
    fun `slot mutations ignore a mismatched day`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val storedLog = log(1, 11, sets(225.0))
        db.programDao().upsertLog(storedLog)
        val exercises = db.programDao().allExercises()

        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(11) ?: -1, "B").first().runId.orEmpty(), "B", 11, Slot.MAIN, sets(235.0))
        repo.updateSetsPaired(repo.dayTracksFlow(repo.planIdOfSlot(11) ?: -1, "B").first().runId.orEmpty(), "B", 11, sets(245.0), sets(115.0))
        assertFalse(repo.seedIfEmpty("B", 11, Slot.SS, sets(125.0)))
        repo.swapExerciseById("B", 11, "db_goblet_squat")

        assertEquals(listOf(storedLog), db.programDao().allLogs())
        assertEquals(exercises, db.programDao().allExercises())
    }

    @Test
    fun `slot mutations ignore a missing slot`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val exercises = db.programDao().allExercises()

        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(99) ?: -1, "A").first().runId.orEmpty(), "A", 99, Slot.MAIN, sets(235.0))
        repo.updateSetsPaired(repo.dayTracksFlow(repo.planIdOfSlot(99) ?: -1, "A").first().runId.orEmpty(), "A", 99, sets(245.0), sets(115.0))
        assertFalse(repo.seedIfEmpty("A", 99, Slot.MAIN, sets(125.0)))
        repo.swapExerciseById("A", 99, "db_goblet_squat")

        assertTrue(db.programDao().allLogs().isEmpty())
        assertEquals(exercises, db.programDao().allExercises())
    }

    @Test
    fun `cardio advance records today and clears only its checks without rotating`() = runTest {
        val strength = program("Lower", "bb_back_squat").days.single()
        val cardio = ProgramDay(
            id = "C", title = "Cardio + Core", emphasisLine = "",
            exercises = listOf(ProgramExercise("plank")),
            cardioBlock = CardioBlocks.fromLegacy(CardioSuggestion("Zone 2", "20 minutes", hard = false), true, CardioActivity.ROAD_RUN),
            kind = ProgramDayKind.CARDIO,
        )
        repo.replaceProgram(Program(listOf(strength, strength.copy(id = "B", title = "Upper"), cardio)))
        val plan = repo.plansFlow.first().single()
        db.planDao().updateSuggestedDay(plan.id, "B")
        val cardioSlot = repo.daySlotsFlow("C").first().single().programExerciseId
        val coreSets = listOf(LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 30))
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(cardioSlot) ?: -1, "C").first().runId.orEmpty(), "C", cardioSlot, Slot.MAIN, coreSets)
        val strengthSlot = repo.daySlotsFlow("A").first().single().programExerciseId
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(strengthSlot) ?: -1, "A").first().runId.orEmpty(), "A", strengthSlot, Slot.MAIN, sets(225.0))
        val otherDayLogs = db.programDao().logsForDay(plan.id, "A")
        val suggestedBefore = repo.suggestedDayFlow.first()

        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val session = db.sessionDao().sessionById(sessionId)!!
        assertEquals("C", session.dayId)
        assertEquals("Cardio + Core", session.dayTitle)
        assertEquals(Instant.parse("2026-10-19T12:00:00Z").toEpochMilli(), session.completedAt)
        assertEquals(plan.id, session.planId)
        assertEquals(plan.name, session.planName)
        val recordedSet = repo.sessionSets(sessionId).single()
        assertTrue(recordedSet.done)
        assertEquals(30, recordedSet.seconds)
        assertEquals(coreSets.map { it.copy(done = false) }, repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "C").map { it.tracks }.first().single().sets)
        assertEquals(otherDayLogs, db.programDao().logsForDay(plan.id, "A"))
        assertEquals("B", suggestedBefore)
        assertEquals(suggestedBefore, repo.suggestedDayFlow.first())
    }

    @Test
    fun `advance stamps the selected plan and clears only its checks`() = runTest {
        repo.replaceProgram(program("Plan one", "bb_back_squat"))
        val planOneId = repo.plansFlow.first().single().id
        val slotOne = db.programDao().exerciseAt(planOneId, "A", 0)!!.id
        db.programDao().upsertLog(log(planOneId, slotOne, sets(225.0)))
        db.planDao().insertPlan(plan(2, "Test prep", position = 1))
        val slotTwo = insertProgram(2, "Plan two", "bb_bench", id = 22)
        db.programDao().upsertLog(log(2, slotTwo, sets(135.0)))
        settings.setSelectedPlanId(2)

        val preview = repo.dayTracksFlow(2, "A").first()
        repo.updateSets(preview.runId!!, "A", slotTwo, Slot.MAIN, sets(135.0).map { it.copy(done = true) })
        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val session = db.sessionDao().sessionById(sessionId)!!
        assertEquals(2L, session.planId)
        assertEquals("Test prep", session.planName)
    }

    @Test
    fun `seed once is isolated by plan`() = runTest {
        db.planDao().insertPlans(listOf(plan(1, "One", 0), plan(2, "Two", 1)))
        insertProgram(1, "Plan one", "bb_back_squat", id = 11)
        insertProgram(2, "Plan two", "bb_bench", id = 22)
        settings.setSelectedPlanId(1)

        assertTrue(repo.seedIfEmpty("A", 11, Slot.MAIN, sets(100.0)))
        assertFalse(repo.seedIfEmpty("A", 11, Slot.MAIN, sets(110.0)))
        settings.setSelectedPlanId(2)
        assertTrue(repo.seedIfEmpty("A", 22, Slot.MAIN, sets(200.0)))
        assertFalse(repo.seedIfEmpty("A", 22, Slot.MAIN, sets(210.0)))

        assertEquals(100.0, SetJson.decodeSets(db.programDao().logsForDay(1, "A").single().setsJson).single().weightLb, 0.0)
        assertEquals(200.0, SetJson.decodeSets(db.programDao().logsForDay(2, "A").single().setsJson).single().weightLb, 0.0)
    }

    @Test
    fun `archived or missing selection falls back by plan position`() = runTest {
        db.planDao().insertPlans(
            listOf(
                plan(1, "Archived", position = 0, archived = true),
                plan(2, "Later", position = 2),
                plan(3, "First active", position = 1),
            ),
        )

        settings.setSelectedPlanId(999)
        assertEquals(3L, repo.selectedPlanIdFlow.first())
        settings.setSelectedPlanId(1)
        assertEquals(3L, repo.selectedPlanIdFlow.first())
    }

    @Test
    fun `snapshot round trip restores two plans and legacy snapshot creates one`() = runTest {
        repo.replaceProgram(program("Plan one", "bb_back_squat"))
        val slotOne = db.programDao().exerciseAt(1, "A", 0)!!.id
        db.programDao().upsertLog(log(1, slotOne, sets(225.0)))
        insertPlanTwo()
        val slotTwo = insertProgram(2, "Plan two", "bb_bench", id = 22)
        db.programDao().upsertLog(log(2, slotTwo, sets(135.0)))
        settings.setSelectedPlanId(2)
        val snapshot = repo.exportSnapshot()

        repo.importSnapshot(snapshot, journal)

        assertEquals(listOf(1L, 2L), db.planDao().allPlans().map { it.id })
        assertEquals(listOf("Plan one"), db.programDao().daysForPlan(1).map { it.title })
        assertEquals(listOf("Plan two"), db.programDao().daysForPlan(2).map { it.title })
        assertEquals(listOf(slotOne), db.programDao().logsForDay(1, "A").map { it.programExerciseId })
        assertEquals(listOf(slotTwo), db.programDao().logsForDay(2, "A").map { it.programExerciseId })
        assertEquals(2L, settings.selectedPlanIdFlow.first())

        val legacy = snapshot.copy(
            plans = emptyList(),
            selectedPlanId = null,
            days = snapshot.days.filter { it.planId == 1L },
            exercises = snapshot.exercises.filter { it.planId == 1L },
            logs = snapshot.logs.filter { it.planId == 1L },
            answersCarryRecipe = true,
        )
        repo.importSnapshot(legacy, journal)

        val restored = db.planDao().allPlans().single()
        assertEquals(PlanEntity.LEGACY_ID, restored.id)
        assertEquals(PlanEntity.LEGACY_NAME, restored.name)
        assertEquals(listOf("Plan one"), db.programDao().daysForPlan(PlanEntity.LEGACY_ID).map { it.title })
        assertEquals(PlanEntity.LEGACY_ID, settings.selectedPlanIdFlow.first())
    }

    @Test
    fun `generated plan appends selects and preserves the previous plan`() = runTest {
        repo.replaceProgram(program("Original", "bb_back_squat"))
        val oldAnswers = WizardAnswers(daysPerWeek = 3)
        repo.setWizardAnswers(oldAnswers)
        db.programDao().upsertDay(ProgramDayEntity(1, "B", 1, "Original next", "", null))
        db.planDao().updateSuggestedDay(1, "B")
        val slot = db.programDao().exercisesForPlan(1).single().id
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot) ?: -1, "A").first().runId.orEmpty(), "A", slot, Slot.MAIN, sets(225.0))
        val before = db.planDao().plan(1)
        val days = db.programDao().daysForPlan(1)
        val exercises = db.programDao().exercisesForPlan(1)
        val logs = db.programDao().logsForPlan(1)
        val answers = WizardAnswers(config = oldAnswers.config.copy(bodyweightLb = 210))

        val created = repo.createGeneratedPlan("4-day full body", answers, program("New", "bb_bench"))

        assertEquals(1L, settings.selectedPlanIdFlow.first())
        assertEquals(GlobalLifter.of(oldAnswers.config), settings.globalLifterFlow.first())
        repo.publishCreatedPlan(created, answers)
        repo.publishCreatedPlan(created, answers)

        assertEquals(listOf(1L, created), db.planDao().allPlans().map { it.id })
        assertEquals(1, db.planDao().plan(created)!!.position)
        assertEquals(created, settings.selectedPlanIdFlow.first())
        assertEquals(PlanRecipeJson.encode(answers), db.planDao().plan(created)!!.recipeJson)
        assertEquals("A", db.planDao().plan(created)!!.suggestedDayId)
        assertEquals(210, repo.configFlow.first().bodyweightLb)
        assertEquals(program("New", "bb_bench"), repo.programFlow.first())
        assertEquals(before, db.planDao().plan(1))
        assertEquals(days, db.programDao().daysForPlan(1))
        assertEquals(exercises, db.programDao().exercisesForPlan(1))
        assertEquals(logs, db.programDao().logsForPlan(1))
        val createdPlan = db.planDao().plan(created)
        repo.selectPlan(1)
        assertEquals(before, db.planDao().plan(1))
        assertEquals(createdPlan, db.planDao().plan(created))
        assertEquals(oldAnswers.daysPerWeek, repo.wizardAnswersFlow.first().daysPerWeek)
    }

    @Test
    fun `creation does not write DataStore even before the settings migration`() = runTest {
        val store = FlakyDataStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("create-plan-settings", ".preferences_pb")
        }).apply { failOnUpdate = 1 }
        val unpublishedSettings = SettingsStore(store)
        val creator = TrackerRepository(
            db, db.programDao(), db.sessionDao(), db.customExerciseDao(), unpublishedSettings,
        )
        val answers = WizardAnswers(config = WizardAnswers().config.copy(bodyweightLb = 210))

        val created = creator.createGeneratedPlan("Full body", answers, program("First", "bb_bench"))

        assertTrue(creator.planHasDays(created))
        assertEquals(null, unpublishedSettings.selectedPlanIdFlow.first())
        assertEquals(GlobalLifter(), unpublishedSettings.globalLifterFlow.first())
        assertFalse(unpublishedSettings.planSettingsFixupDoneFlow.first())
    }

    @Test
    fun `publication does not select a missing plan`() = runTest {
        repo.replaceProgram(program("Original", "bb_back_squat"))
        val plans = db.planDao().allPlans()
        val answers = WizardAnswers(config = WizardAnswers().config.copy(bodyweightLb = 210))

        repeat(2) { repo.publishCreatedPlan(Long.MAX_VALUE, answers) }

        assertEquals(1L, settings.selectedPlanIdFlow.first())
        assertEquals(GlobalLifter.of(answers.config), settings.globalLifterFlow.first())
        assertEquals(plans, db.planDao().allPlans())
    }

    @Test
    fun `publication does not select an archived plan`() = runTest {
        repo.replaceProgram(program("Original", "bb_back_squat"))
        insertPlanTwo()
        assertTrue(repo.archivePlan(2))
        val plans = db.planDao().allPlans()
        val answers = WizardAnswers(config = WizardAnswers().config.copy(bodyweightLb = 210))

        repeat(2) { repo.publishCreatedPlan(2, answers) }

        assertEquals(1L, settings.selectedPlanIdFlow.first())
        assertEquals(GlobalLifter.of(answers.config), settings.globalLifterFlow.first())
        assertEquals(plans, db.planDao().allPlans())
    }

    @Test
    fun `generated plan fills a selected settings-only plan`() = runTest {
        repo.setWizardAnswers(WizardAnswers(daysPerWeek = 3))
        val id = repo.selectedPlanIdFlow.first()!!
        val global = settings.globalLifterFlow.first()
        val answers = WizardAnswers(config = WizardAnswers().config.copy(bodyweightLb = 210))

        assertEquals(id, repo.createGeneratedPlan("Full body", answers, program("First", "bb_bench")))

        assertEquals(1, db.planDao().allPlans().size)
        assertEquals("Full body", db.planDao().plan(id)!!.name)
        assertEquals(PlanRecipeJson.encode(answers), db.planDao().plan(id)!!.recipeJson)
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
        assertEquals(listOf("First"), db.programDao().daysForPlan(id).map { it.title })
        assertEquals(global, settings.globalLifterFlow.first())
        repo.publishCreatedPlan(id, answers)
        assertEquals(GlobalLifter.of(answers.config), settings.globalLifterFlow.first())
        assertEquals(id, settings.selectedPlanIdFlow.first())
    }

    @Test
    fun `duplicate rekeys both log tracks inserts after source and leaves selection and history alone`() = runTest {
        repo.replaceProgram(program("Original", "bb_back_squat"))
        repo.setWizardAnswers(WizardAnswers(daysPerWeek = 3))
        val source = db.planDao().plan(1)!!
        val slot = db.programDao().exercisesForPlan(1).single()
        db.programDao().setSupersetExerciseId(slot.id, "bb_bench")
        db.programDao().setExerciseNote(slot.id, "Keep the brace through both lifts")
        val cardioDay = ProgramDay(
            id = "C",
            title = "Easy run",
            emphasisLine = "Conversational pace",
            exercises = emptyList(),
            cardioBlock = CardioBlocks.fromLegacy(CardioSuggestion("Easy run", "20 minutes", hard = false, mode = "OUTDOOR_RUN"), true, CardioActivity.ROAD_RUN),
            kind = ProgramDayKind.CARDIO,
        ).toEntity(1).copy(position = 1)
        db.programDao().upsertDay(cardioDay)
        db.programDao().insertLogs(listOf(
            log(1, slot.id, sets(225.0)),
            log(1, slot.id, sets(135.0)).copy(slot = Slot.SS),
        ))
        insertPlanTwo()
        db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Original", null, 100, 180, 1, source.name))
        db.cardioSessionDao().insert(CardioSessionEntity(
            dayId = "C", mode = "OUTDOOR_RUN", hard = false, label = "Easy run",
            startedAt = 200, completedAt = 1_400, seconds = 1_200, stepsCompleted = 1, planId = 1,
        ))
        val history = db.sessionDao().allSessions()
        val cardioHistory = db.cardioSessionDao().all()

        val copy = repo.duplicatePlan(1, "Original copy")

        val plans = db.planDao().allPlans()
        assertEquals(listOf(1L, copy, 2L), plans.map { it.id })
        assertEquals(listOf(0, 1, 2), plans.map { it.position })
        assertEquals(1L, settings.selectedPlanIdFlow.first())
        val copiedPlan = db.planDao().plan(copy)!!
        assertEquals(source.recipeJson, copiedPlan.recipeJson)
        assertEquals(source.suggestedDayId, copiedPlan.suggestedDayId)
        assertEquals(source.origin, copiedPlan.origin)
        assertEquals(
            db.programDao().daysForPlan(1).map { it.copy(planId = copy, nextRunId = "", closedAt = null) },
            db.programDao().daysForPlan(copy).map { it.copy(nextRunId = "", closedAt = null) },
        )
        val copiedCardioDay = db.programDao().daysForPlan(copy).single { it.dayId == "C" }
        assertEquals(ProgramDayKind.CARDIO.name, copiedCardioDay.kind)
        assertNull(copiedCardioDay.cardioJson)
        assertTrue(copiedCardioDay.cardioBlockJson != null)
        assertEquals(cardioDay.cardioBlockJson, copiedCardioDay.cardioBlockJson)
        val copiedSlot = db.programDao().exercisesForPlan(copy).single()
        assertTrue(slot.id != copiedSlot.id)
        assertEquals(db.programDao().exercisesForPlan(1).single().copy(id = copiedSlot.id, planId = copy), copiedSlot)
        assertEquals("Keep the brace through both lifts", copiedSlot.note)
        assertEquals("bb_bench", copiedSlot.supersetExerciseId)
        val sourceLogs = db.programDao().logsForPlan(1)
        assertEquals(sourceLogs.map { it.copy(planId = copy, programExerciseId = copiedSlot.id) }, db.programDao().logsForPlan(copy))
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(copiedSlot.id) ?: -1, "A").first().runId.orEmpty(), "A", copiedSlot.id, Slot.MAIN, sets(245.0))
        assertEquals(sourceLogs, db.programDao().logsForPlan(1))
        assertEquals(history, db.sessionDao().allSessions())
        assertEquals(cardioHistory, db.cardioSessionDao().all())
        assertEquals(1, db.sessionDao().allSessions().size)
        assertEquals(1, db.cardioSessionDao().all().size)
    }

    @Test
    fun `archive refuses selected and last active and restore preserves data`() = runTest {
        repo.replaceProgram(program("Original", "bb_back_squat"))
        assertFalse(repo.archivePlan(1))
        val copy = repo.duplicatePlan(1, "Copy")
        repo.selectPlan(copy)
        val days = db.programDao().allDays()
        assertFalse(repo.archivePlan(copy))
        assertTrue(repo.archivePlan(1))
        assertFalse(repo.archivePlan(copy))
        settings.setSelectedPlanId(1)
        assertEquals(copy, repo.selectedPlanIdFlow.first())
        repo.restorePlan(1)
        assertFalse(db.planDao().plan(1)!!.archived)
        assertEquals(1L, repo.selectedPlanIdFlow.first())
        assertEquals(days, db.programDao().allDays())
    }

    @Test
    fun `select refuses archived and missing plans and rename validates trimmed names`() = runTest {
        repo.replaceProgram(program("Original", "bb_back_squat"))
        val copy = repo.duplicatePlan(1, "Copy")
        assertTrue(repo.archivePlan(copy))
        repo.selectPlan(copy)
        repo.selectPlan(999)
        assertEquals(1L, settings.selectedPlanIdFlow.first())
        repo.renamePlan(1, "   ")
        repo.renamePlan(1, "a".repeat(41))
        assertEquals(PlanEntity.LEGACY_NAME, db.planDao().plan(1)!!.name)
        repo.renamePlan(1, "  Renamed  ")
        assertEquals("Renamed", db.planDao().plan(1)!!.name)
    }

    @Test
    fun `summaries resolve each next title and latest workout or cardio ignoring unknown plans`() = runTest {
        repo.replaceProgram(program("Original", "bb_back_squat"))
        val copy = repo.duplicatePlan(1, "Copy")
        val empty = db.planDao().insertPlan(plan(0, "Empty", 2))
        db.programDao().upsertDay(ProgramDayEntity(copy, "B", 1, "Copy next", "", null))
        db.planDao().updateSuggestedDay(copy, "B")
        db.sessionDao().insertSessions(listOf(
            WorkoutSessionEntity(0, "A", "Original", null, 100, 180, 1),
            WorkoutSessionEntity(0, "A", "Original", null, 200, 180, 1),
            WorkoutSessionEntity(0, "A", "Copy", null, 400, 180, copy),
            WorkoutSessionEntity(0, "A", "Unknown", null, 999, 180),
        ))
        db.cardioSessionDao().insertAll(listOf(
            CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run",
                startedAt = 0, completedAt = 300, seconds = 300, stepsCompleted = 1, planId = 1),
            CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run",
                startedAt = 0, completedAt = 350, seconds = 350, stepsCompleted = 1, planId = copy),
            CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Unknown",
                startedAt = 0, completedAt = 1000, seconds = 1000, stepsCompleted = 1),
        ))
        assertTrue(repo.archivePlan(copy))

        val summaries = repo.planSummariesFlow.first()

        assertEquals(listOf(1L, copy, empty), summaries.map { it.id })
        assertEquals(listOf("A", "B", null), summaries.map { it.nextDayId })
        assertEquals(listOf("Original", "Copy next", null), summaries.map { it.nextDayTitle })
        assertEquals(listOf(300L, 400L, null), summaries.map { it.lastSessionAt })
        assertEquals(listOf(true, false, false), summaries.map { it.selected })
        assertEquals(listOf(false, true, false), summaries.map { it.archived })
    }

    @Test
    fun `explicit plan reads keep working values and resolve only the owning slots`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        db.programDao().insertLogs(listOf(
            log(1, 11, sets(225.0)),
            log(2, 22, sets(135.0)),
        ))
        assertEquals(1L, repo.planIdOfSlot(11))
        assertEquals(2L, repo.planIdOfSlot(22))
        assertEquals(null, repo.planIdOfSlot(99))
        assertEquals(11L, repo.slotsFor(1, "A").first().single().programExerciseId)
        assertEquals(emptyList<ProgramSlot>(), repo.slotsFor(1, "missing").first())
        val today = kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate())
        val log = repo.dayTracksFlow(1, "A").map { it.tracks }.first().single()
        assertEquals(11L, log.programExerciseId)
        assertFalse(log.sets.single().done)
        today.value = today.value.plusDays(1)
        assertFalse(repo.dayTracksFlow(1, "A").map { it.tracks }.first().single().sets.single().done)
    }

    @Test
    fun `cardio preserves an explicit missing plan and refreshes an existing plan name`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val cardio = CardioSessionEntity(
            dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Zone 2",
            startedAt = 200L, completedAt = 60_200L, seconds = 60, stepsCompleted = 1,
            planId = 1, planName = "Old name",
        )
        val existingId = repo.logCardioSession(cardio)
        assertEquals(1L, repo.cardioSession(existingId)?.planId)
        assertEquals("One", repo.cardioSession(existingId)?.planName)
        val missingId = repo.logCardioSession(cardio.copy(planId = 99, planName = "Archived elsewhere"))
        assertEquals(99L, repo.cardioSession(missingId)?.planId)
        assertEquals("Archived elsewhere", repo.cardioSession(missingId)?.planName)
        db.planDao().updateSuggestedDay(2, "A")
        db.programDao().upsertDay(ProgramDayEntity(2, "B", 1, "Next", "", null))
        val before = db.planDao().allPlans()
        val legacyId = repo.logCardioSession(cardio.copy(planId = null), finishDay = true)
        assertNull(repo.cardioSession(legacyId)?.planId)
        assertNull(repo.cardioSession(legacyId)?.planName)
        assertEquals(before, db.planDao().allPlans())
    }

    private suspend fun insertTwoProgramsAndSelectPlanTwo() {
        db.planDao().insertPlans(listOf(plan(1, "One", 0), plan(2, "Two", 1)))
        insertProgram(1, "Plan one", "bb_back_squat", id = 11)
        insertProgram(2, "Plan two", "bb_bench", id = 22)
        settings.setSelectedPlanId(2)
    }

    private suspend fun assertSlotLogsInPlanOne(expected: Map<String, List<LoggedSet>>) {
        val logs = db.programDao().allLogs()
        assertEquals(expected.size, logs.size)
        assertEquals(expected.keys, logs.map { it.slot }.toSet())
        logs.forEach { log ->
            assertEquals(1L, log.planId)
            assertEquals("A", log.dayId)
            assertEquals(11L, log.programExerciseId)
            assertEquals(expected.getValue(log.slot), SetJson.decodeSets(log.setsJson))
        }
    }

    private suspend fun assertSwapClearsOnlySelectedPlanSlot(swap: suspend (Long) -> Unit) {
        repo.replaceProgram(program("Plan one", "bb_back_squat"))
        val slotOne = db.programDao().exerciseAt(1, "A", 0)!!.id
        insertPlanTwo()
        val slotTwo = insertProgram(2, "Plan two", "bb_bench", id = 22)
        val preservedLogs = listOf(
            log(1, slotOne, sets(225.0)),
            // The log key includes planId: the same slot id in another plan must survive too.
            log(1, slotTwo, sets(185.0)),
            log(1, slotTwo, sets(95.0)).copy(slot = Slot.SS),
            log(2, slotTwo, sets(135.0)).copy(dayId = "B"),
            log(2, 99, sets(100.0)),
        )
        db.programDao().insertLogs(
            preservedLogs + listOf(
                log(2, slotTwo, sets(135.0)),
                log(2, slotTwo, sets(65.0)).copy(slot = Slot.SS),
            ),
        )
        settings.setSelectedPlanId(2)

        swap(slotTwo)

        assertEquals(preservedLogs.toSet(), db.programDao().allLogs().toSet())
        assertEquals("bb_back_squat", db.programDao().exerciseAt(1, "A", 0)!!.exerciseId)
        assertEquals(slotTwo, db.programDao().exerciseAt(2, "A", 0)!!.id)
        assertEquals("db_goblet_squat", db.programDao().exerciseAt(2, "A", 0)!!.exerciseId)
    }

    @Test
    fun `a completed rep window archives as a plain reps row`() = runTest {
        val prescription = Prescription(PrescribedTarget.RepsInTime(120, 50))
        val day = program("Rep window", "bb_back_squat").days.single().copy(exercises = listOf(
            ProgramExercise("bb_back_squat", targetSets = 1, prescription = prescription),
        ))
        repo.replaceProgram(Program(listOf(day)))
        val id = repo.daySlotsFlow("A").first().single().programExerciseId
        val completed = SetSeeder.seedPrescribed(1, prescription).single().copy(reps = 42, done = true)
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(id) ?: -1, "A").first().runId.orEmpty(), "A", id, Slot.MAIN, listOf(completed))

        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val row = repo.sessionSets(sessionId).single()
        assertEquals(0.0, row.weightLb, 0.0)
        assertEquals(42, row.reps)
        assertEquals(0, row.seconds)
        assertEquals(SetKind.WORK.name, row.kind)
        assertTrue(row.done)
        assertEquals(TrackingType.REPS, SetFormatter.trackingOfValues(row.weightLb, row.reps, row.seconds))
    }

    @Test
    fun `removal uses the slot plan floor even when the other origin is selected`() = runTest {
        db.planDao().insertPlans(listOf(
            plan(1, "Generated", 0),
            plan(2, "Authored", 1).copy(origin = PlanOrigin.AUTHORED.name),
        ))
        val day = program("Day", "bb_back_squat").days.single().copy(exercises = List(4) { ProgramExercise("bb_back_squat") })
        listOf(1L, 2L).forEach { planId ->
            db.programDao().upsertDay(day.toEntity(planId))
            day.exercises.forEachIndexed { position, exercise ->
                db.programDao().insertExercise(exercise.toEntity(planId, "A", position))
            }
        }
        val generated = db.programDao().exercisesForDay(1, "A")
        val authored = db.programDao().exercisesForDay(2, "A")
        db.programDao().upsertLog(log(1, generated[0].id, sets(235.0)))
        db.programDao().upsertLog(log(1, generated[1].id, sets(225.0)))
        db.programDao().upsertLog(log(2, authored.last().id, sets(25.0)))

        settings.setSelectedPlanId(2)
        assertTrue(repo.removeExercise(generated[0].id))
        assertTrue(repo.removeExercise(generated[1].id))
        assertTrue(repo.removeExercise(generated[2].id))
        assertFalse(repo.removeExercise(generated.last().id))
        assertEquals(listOf(generated.last()), db.programDao().exercisesForDay(1, "A"))
        assertTrue(db.programDao().logsForDay(1, "A").isEmpty())
        assertEquals(2L, settings.selectedPlanIdFlow.first())

        settings.setSelectedPlanId(1)
        authored.take(3).forEach { assertTrue(repo.removeExercise(it.id)) }
        assertFalse(repo.removeExercise(authored.last().id))
        assertEquals(listOf(authored.last()), db.programDao().exercisesForDay(2, "A"))
        assertEquals(1, db.programDao().logsForDay(2, "A").size)
        assertEquals(1L, settings.selectedPlanIdFlow.first())
        assertFalse(repo.removeExercise(-1L))
    }

    @Test
    fun `superset assignment and swap accept prescribed slots and preserve MAIN working values`() = runTest {
        val prescribed = ProgramExercise("plank", prescription = Prescription(PrescribedTarget.Reps(12)))
        val day = program("Authored", "plank").days.single().copy(exercises = listOf(prescribed))
        repo.replaceProgram(Program(listOf(day)))
        val slot = db.programDao().allExercises().single()
        db.programDao().upsertLog(log(slot.planId, slot.id, listOf(LoggedSet(0.0, 12, SetKind.WORK))))
        val mainOnly = db.programDao().allLogs()

        repo.setSupersetPartner("A", slot.id, "rope_pushdown")

        assertEquals(slot.copy(supersetExerciseId = "rope_pushdown"), db.programDao().exercise(slot.id))
        assertEquals(mainOnly, db.programDao().allLogs())
        db.programDao().setSupersetExerciseId(slot.id, "plank")
        db.programDao().upsertLog(log(slot.planId, slot.id, sets(25.0)).copy(slot = Slot.SS))
        repo.setSupersetPartner("A", slot.id, "rope_pushdown")
        assertEquals("rope_pushdown", db.programDao().exercise(slot.id)!!.supersetExerciseId)
        assertEquals(mainOnly, db.programDao().allLogs())
    }

    @Test
    fun `superset edits use slot identity after deletion in generated and authored plans`() = runTest {
        for (origin in PlanOrigin.entries) {
            val day = program("Day", "bb_back_squat").days.single().copy(exercises = listOf(
                ProgramExercise("bb_back_squat"), ProgramExercise("bb_bench"), ProgramExercise("ez_curl"),
            ))
            repo.replaceProgram(Program(listOf(day)))
            val planId = repo.selectedPlanIdFlow.first()!!
            val plan = db.planDao().plan(planId)!!
            db.planDao().updatePlan(plan.copy(origin = origin.name))
            val slots = db.programDao().exercisesForDay(planId, "A")
            assertTrue(repo.removeExercise(slots[0].id))
            assertEquals(listOf(1, 2), db.programDao().exercisesForDay(planId, "A").map { it.position })
            db.programDao().upsertLog(log(planId, slots[1].id, sets(135.0)))
            val main = db.programDao().allLogs()

            repo.setSupersetPartner("A", slots[1].id, "rope_pushdown")

            assertEquals(slots[1].copy(supersetExerciseId = "rope_pushdown"), db.programDao().exercise(slots[1].id))
            assertEquals(slots[2], db.programDao().exercise(slots[2].id))
            assertEquals(main, db.programDao().allLogs())
            db.programDao().upsertLog(log(planId, slots[1].id, sets(45.0)).copy(slot = Slot.SS))

            repo.removeSupersetPartner("A", slots[1].id)

            assertEquals(slots.drop(1), db.programDao().exercisesForDay(planId, "A"))
            assertEquals(main, db.programDao().allLogs())
        }
    }

    @Test
    fun `finishing kept slots archives edits and reseeds targets while remembered logs persist`() = runTest {
        val prescription = Prescription(PrescribedTarget.LoadReps(37.5, 8), resistance = "red band")
        val day = program("Physio", "bb_back_squat").days.single().copy(exercises = listOf(
            ProgramExercise("bb_back_squat", targetSets = 2, prescription = prescription, keepToPlan = true),
            ProgramExercise("bb_bench"),
        ))
        repo.replaceProgram(Program(listOf(day, day.copy(id = "B"))))
        val plan = repo.plansFlow.first().single()
        db.planDao().updatePlan(plan.copy(origin = PlanOrigin.AUTHORED.name, recipeJson = null))
        val slots = repo.daySlotsFlow("A").first()
        val authoredId = slots[0].programExerciseId
        val generatedId = slots[1].programExerciseId
        val otherDayId = repo.daySlotsFlow("B").first().first().programExerciseId
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(authoredId) ?: -1, "A").first().runId.orEmpty(), "A", authoredId, Slot.MAIN, sets(50.0))
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(generatedId) ?: -1, "A").first().runId.orEmpty(), "A", generatedId, Slot.MAIN, sets(135.0))
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(otherDayId) ?: -1, "B").first().runId.orEmpty(), "B", otherDayId, Slot.MAIN, sets(60.0))
        val otherDayLogs = db.programDao().logsForDay(plan.id, "B")

        val preview = repo.dayTracksFlow(plan.id, "A").first()
        for (track in preview.tracks) {
            repo.updateSets(preview.runId!!, "A", track.programExerciseId, track.slot, track.sets.map { it.copy(done = true) })
        }
        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val archived = repo.sessionSets(sessionId)
        assertEquals(mapOf("bb_back_squat" to 50.0, "bb_bench" to 135.0), archived.associate { it.exerciseId to it.weightLb })
        assertTrue(archived.all { it.done })
        assertEquals(listOf(generatedId), db.programDao().logsForDay(plan.id, "A").map { it.programExerciseId })
        assertEquals(otherDayLogs, db.programDao().logsForDay(plan.id, "B"))
        assertEquals("B", repo.suggestedDayFlow.first())
        val session = db.sessionDao().sessionById(sessionId)!!
        assertEquals(plan.id, session.planId)
        assertEquals(plan.name, session.planName)

        // The next observation sees the missing authored track and uses the stored target.
        val next = repo.daySlotsFlow("A").first().first().exercise
        val seed = SetSeeder.seedPrescribed(next.targetSets, next.prescription!!)
        assertTrue(repo.seedIfEmpty("A", authoredId, Slot.MAIN, seed))
        assertFalse(repo.seedIfEmpty("A", generatedId, Slot.MAIN, seed))
        val logs = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().associate { it.programExerciseId to it.sets }
        assertEquals(List(2) { LoggedSet(37.5, 8, SetKind.WORK) }, logs[authoredId])
        assertEquals(sets(135.0).map { it.copy(done = false) }, logs[generatedId])
        assertEquals(archived, repo.sessionSets(sessionId))
    }

    @Test
    fun `duplicate preserves every prescription and rekeys its slot`() = runTest {
        val targets = listOf(
            PrescribedTarget.LoadReps(37.5, 8), PrescribedTarget.Reps(12),
            PrescribedTarget.Hold(45, 25.0), PrescribedTarget.RepsInTime(120, 50),
        )
        val day = program("Authored", "plank").days.single().copy(exercises = targets.mapIndexed { index, target ->
            ProgramExercise("plank", prescription = Prescription(target, true, "red band"), keepToPlan = index % 2 == 0)
        })
        repo.replaceProgram(Program(listOf(day)))
        val source = repo.plansFlow.first().single()
        val originals = db.programDao().exercisesForPlan(source.id)
        originals.forEach { PrescriptionJson.validate(it.prescriptionJson!!) }

        val copyId = repo.duplicatePlan(source.id, "Copy")

        val copies = db.programDao().exercisesForPlan(copyId)
        assertEquals(originals.map { it.prescriptionJson }, copies.map { it.prescriptionJson })
        assertEquals(originals.map { it.keepToPlan }, copies.map { it.keepToPlan })
        assertTrue(copies.map { it.id }.toSet().intersect(originals.map { it.id }.toSet()).isEmpty())
        assertEquals(day.exercises, repo.slotsFor(copyId, "A").first().map { it.exercise })
    }

    @Test
    fun `duplicate preserves authored cardio days and their ordered block sets`() = runTest {
        val sourceId = repo.createAuthoredPlan("Running", "Track")
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(
            CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
            CardioSetSpec(CardioRole.COOLDOWN, Measure.Distance(500)),
        ))
        val day = ProgramDay("A", "Track", "", emptyList(),
            kind = ProgramDayKind.CARDIO, cardioBlock = block)
        db.programDao().upsertDay(day.toEntity(sourceId))

        val copyId = repo.duplicatePlan(sourceId, "Running copy")

        val original = db.programDao().daysForPlan(sourceId).single()
        val copied = db.programDao().daysForPlan(copyId).single()
        assertEquals(
            original.copy(planId = copyId, nextRunId = "", closedAt = null),
            copied.copy(nextRunId = "", closedAt = null),
        )
        assertEquals(CardioBlockJson.encode(block), copied.cardioBlockJson)
        assertEquals(day, copied.toDomain(emptyList()))
        assertTrue(db.programDao().exercisesForPlan(copyId).isEmpty())
    }

    private fun program(title: String, exerciseId: String) = Program(
        listOf(
            ProgramDay(
                id = "A",
                title = title,
                emphasisLine = "",
                exercises = listOf(ProgramExercise(exerciseId = exerciseId)),
            ),
        ),
    )

    private fun plan(id: Long, name: String, position: Int, archived: Boolean = false) = PlanEntity(
        id = id,
        name = name,
        origin = PlanOrigin.GENERATED.name,
        position = position,
        createdAt = 1_000,
        archived = archived,
    )

    private suspend fun insertPlanTwo() {
        db.planDao().insertPlan(plan(2, "Plan two", position = 1))
    }

    private suspend fun insertProgram(planId: Long, title: String, exerciseId: String, id: Long): Long {
        db.programDao().upsertDay(
            ProgramDayEntity(planId, "A", 0, title, "", null, nextRunId = "run-$planId-A"),
        )
        return db.programDao().insertExercise(
            ProgramExerciseEntity(id, planId, "A", 0, exerciseId, true, 3, "", false, null, ""),
        )
    }

    private fun sets(weightLb: Double) = listOf(
        LoggedSet(weightLb, 5, SetKind.WORK),
    )

    private fun log(planId: Long, slotId: Long, sets: List<LoggedSet>) = ExerciseLogEntity(
        planId = planId,
        dayId = "A",
        programExerciseId = slotId,
        slot = Slot.MAIN,
        setsJson = SetJson.encodeSets(sets),

        updatedAt = 1_000,
    )
}
