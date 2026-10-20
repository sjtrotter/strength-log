package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

        repo.updateSets("A", slotOne, Slot.MAIN, planOneSets)

        val emissions = mutableListOf<List<LoggedSlot>>()
        val emitted = Channel<Unit>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.logFlow("A").collect {
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
        repo.updateSets("A", slotOne, Slot.MAIN, sets(225.0))
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
    fun `swap by position clears only the selected plan log for that slot`() = runTest {
        assertSwapClearsOnlySelectedPlanSlot { repo.swapExercise("A", 0, "db_goblet_squat") }
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
        val mainSets = sets(225.0)

        repo.updateSets("A", 11, Slot.MAIN, mainSets)

        assertSlotLogsInPlanOne(mapOf(Slot.MAIN to mainSets))
    }

    @Test
    fun `paired update writes both tracks under the slot plan despite another selection`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val mainSets = sets(225.0)
        val ssSets = sets(115.0)

        repo.updateSetsPaired("A", 11, mainSets, ssSets)

        assertSlotLogsInPlanOne(mapOf(Slot.MAIN to mainSets, Slot.SS to ssSets))
    }

    @Test
    fun `seed writes under the slot plan despite another selection and never overwrites`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val mainSets = sets(225.0)

        assertTrue(repo.seedIfEmpty("A", 11, Slot.MAIN, mainSets))
        assertFalse(repo.seedIfEmpty("A", 11, Slot.MAIN, sets(235.0)))

        assertSlotLogsInPlanOne(mapOf(Slot.MAIN to mainSets))
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

        repo.updateSets("B", 11, Slot.MAIN, sets(235.0))
        repo.updateSetsPaired("B", 11, sets(245.0), sets(115.0))
        assertFalse(repo.seedIfEmpty("B", 11, Slot.SS, sets(125.0)))
        repo.swapExerciseById("B", 11, "db_goblet_squat")

        assertEquals(listOf(storedLog), db.programDao().allLogs())
        assertEquals(exercises, db.programDao().allExercises())
    }

    @Test
    fun `slot mutations ignore a missing slot`() = runTest {
        insertTwoProgramsAndSelectPlanTwo()
        val exercises = db.programDao().allExercises()

        repo.updateSets("A", 99, Slot.MAIN, sets(235.0))
        repo.updateSetsPaired("A", 99, sets(245.0), sets(115.0))
        assertFalse(repo.seedIfEmpty("A", 99, Slot.MAIN, sets(125.0)))
        repo.swapExerciseById("A", 99, "db_goblet_squat")

        assertTrue(db.programDao().allLogs().isEmpty())
        assertEquals(exercises, db.programDao().allExercises())
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

        val sessionId = repo.advanceDay("A")

        val session = db.sessionDao().sessionById(sessionId)!!
        assertEquals(2L, session.planId)
        assertEquals("Test prep", session.planName)
        assertEquals("2026-10-19", db.programDao().logsForDay(planOneId, "A").single().checkDate)
        assertEquals("", db.programDao().logsForDay(2, "A").single().checkDate)
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

    private fun program(title: String, exerciseId: String) = Program(
        listOf(
            ProgramDay(
                id = "A",
                title = title,
                emphasisLine = "",
                exercises = listOf(ProgramExercise(exerciseId = exerciseId)),
                cardio = null,
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
            ProgramDayEntity(planId, "A", 0, title, "", null),
        )
        return db.programDao().insertExercise(
            ProgramExerciseEntity(id, planId, "A", 0, exerciseId, true, 3, "", false, null, ""),
        )
    }

    private fun sets(weightLb: Double) = listOf(
        LoggedSet(weightLb, 5, SetKind.WORK, done = true),
    )

    private fun log(planId: Long, slotId: Long, sets: List<LoggedSet>) = ExerciseLogEntity(
        planId = planId,
        dayId = "A",
        programExerciseId = slotId,
        slot = Slot.MAIN,
        setsJson = SetJson.encodeSets(sets),
        checkDate = "2026-10-19",
        updatedAt = 1_000,
    )
}
