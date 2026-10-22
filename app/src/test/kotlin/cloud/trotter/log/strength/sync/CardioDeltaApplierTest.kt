package cloud.trotter.log.strength.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.WatchCardioBlock
import cloud.trotter.log.strength.domain.sync.WatchCardioSetResult
import cloud.trotter.log.strength.domain.sync.WatchCardioSetSpec
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioDeltaApplierTest {
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var scope: CoroutineScope
    private val recordedStamps = mutableListOf<Long>()
    private val ackLog = object : CardioAckLog {
        override val acksFlow = flowOf(CardioAcks())
        override suspend fun record(stamp: Long) { recordedStamps += stamp }
    }
    private val publisher = object : SessionPublisher {
        var cardioPublishes = 0
        override suspend fun publish(sessionId: Long) = Unit
        override suspend fun publishAll(sessionIds: List<Long>) = true
        override suspend fun publishCardio(sessionId: Long) { cardioPublishes++ }
    }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File.createTempFile("cardio-applier", ".preferences_pb") }
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), SettingsStore(dataStore))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun delta(stamp: Long = 10L, startedAt: Long = 1_000L) = CardioDelta(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Easy Zone 2",
        startedAt = startedAt, completedAt = startedAt + 60_000L,
        seconds = 60, stepsCompleted = 0, stamp = stamp,
    )

    @Test fun `structured run logs activity and sets and advances its originating plan once`() = runTest {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val spec = WatchCardioSetSpec("WORK", "TIME", 60)
        val block = WatchCardioBlock("TRACK_RUN", listOf(spec)).toDomain()
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
        val other = repo.createAuthoredPlan("Other", "Other day")
        repo.publishCreatedAuthoredPlan(other)
        val result = WatchCardioSetResult(spec, "FINISHED", 60, 200, "MANUAL")
        val event = delta().copy(planId = planId, activity = "TRACK_RUN", sets = listOf(result), stepsCompleted = 1, finishesDay = true)
        val applier = CardioDeltaApplier(repo, ackLog, publisher)
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(event))
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals("TRACK_RUN", session.activity)
        assertEquals(planId, session.planId)
        assertEquals(listOf(result.toDomain()), repo.cardioSetsFor(session.id))
        assertEquals("B", db.planDao().plan(planId)?.suggestedDayId)
        assertEquals("A", db.planDao().plan(other)?.suggestedDayId)
        // Change the rotation before replay: a duplicate must not reset it to B.
        db.planDao().updateSuggestedDay(planId, "C")
        assertEquals(CardioDeltaApplier.Outcome.DUPLICATE, applier.apply(event.copy(stamp = 99L)))
        assertEquals("C", db.planDao().plan(planId)?.suggestedDayId)
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(1, publisher.cardioPublishes)
        assertEquals(listOf(10L, 99L), recordedStamps)
    }

    @Test fun `old watch retains summary logging without set rows or rotation`() = runTest {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val block = WatchCardioBlock("TRACK_RUN", listOf(WatchCardioSetSpec("WORK", "TIME", 60))).toDomain()
        repo.replaceProgram(Program(listOf("A", "B").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
        val applier = CardioDeltaApplier(repo, ackLog, publisher)
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(delta()))
        assertEquals(listOf(10L), recordedStamps)
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(null, session.activity)
        assertEquals(emptyList<CardioSetResult>(), repo.cardioSetsFor(session.id))
        assertEquals(60, session.seconds)
        assertEquals("A", db.planDao().plan(planId)?.suggestedDayId)
    }

    @Test fun `start eligibility survives edits from cardio-only to mixed and from mixed to cardio-only`() = runTest {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val spec = WatchCardioSetSpec("WORK", "TIME", 60)
        val block = WatchCardioBlock("TRACK_RUN", listOf(spec)).toDomain()
        val cardioOnly = Program(listOf("A", "B").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        })
        val mixed = Program(cardioOnly.days.map {
            it.copy(exercises = listOf(ProgramExercise("bb_back_squat")))
        })
        val applier = CardioDeltaApplier(repo, ackLog, publisher)
        listOf(true to mixed, false to cardioOnly).forEachIndexed { index, (eligibleAtStart, edited) ->
            repo.replaceProgram(edited)
            db.planDao().updateSuggestedDay(planId, "A")
            val event = delta(startedAt = 1_000L + index * 100_000L).copy(
                planId = planId, activity = "TRACK_RUN", finishesDay = eligibleAtStart,
                sets = listOf(WatchCardioSetResult(spec, "FINISHED", 60)),
            )
            assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(event))
            assertEquals(if (eligibleAtStart) "B" else "A", db.planDao().plan(planId)?.suggestedDayId)
        }
        assertEquals(2, repo.cardioSessionsFlow.first().size)
    }

    @Test fun `structured old-watch completion without eligibility logs sets without finishing the day`() = runTest {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val spec = WatchCardioSetSpec("WORK", "TIME", 60)
        val block = WatchCardioBlock("TRACK_RUN", listOf(spec)).toDomain()
        repo.replaceProgram(Program(listOf("A", "B").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
        val result = WatchCardioSetResult(spec, "FINISHED", 60)
        val event = delta().copy(planId = planId, sets = listOf(result))
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, CardioDeltaApplier(repo, ackLog, publisher).apply(event))
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(listOf(result.toDomain()), repo.cardioSetsFor(session.id))
        assertEquals("A", db.planDao().plan(planId)?.suggestedDayId)
    }

    @Test fun `offline A delivered after phone finishes B logs without rewinding C`() = runTest {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val spec = WatchCardioSetSpec("WORK", "TIME", 60)
        val block = WatchCardioBlock("TRACK_RUN", listOf(spec)).toDomain()
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
        val result = WatchCardioSetResult(spec, "FINISHED", 60)
        val offline = delta().copy(planId = planId, activity = "TRACK_RUN", finishesDay = true, sets = listOf(result))
        // The phone has moved on to B while A's watch completion is offline.
        db.planDao().updateSuggestedDay(planId, "B")
        repo.logCardioSession(CardioSessionEntity(dayId = "B", mode = "OUTDOOR_RUN", hard = false,
            label = "Phone B", startedAt = 100_000, completedAt = 160_000, seconds = 60,
            stepsCompleted = 1, planId = planId, activity = "TRACK_RUN"),
            listOf(result.toDomain()), finishDay = true)
        assertEquals("C", db.planDao().plan(planId)?.suggestedDayId)
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, CardioDeltaApplier(repo, ackLog, publisher).apply(offline))
        assertEquals("C", db.planDao().plan(planId)?.suggestedDayId)
        val loggedA = repo.cardioSessionsFlow.first().single { it.dayId == "A" }
        assertEquals(offline.startedAt, loggedA.startedAt)
        assertEquals(listOf(result.toDomain()), repo.cardioSetsFor(loggedA.id))
        assertEquals(1, publisher.cardioPublishes)
    }

    @Test fun `optional activity can be absent when a sender supplies sets`() = runTest {
        val result = WatchCardioSetResult(WatchCardioSetSpec("WORK", "TIME", 60), "FINISHED", 60)
        val event = delta().copy(sets = listOf(result))
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, CardioDeltaApplier(repo, ackLog, publisher).apply(event))
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(null, session.activity)
        assertEquals(listOf(result.toDomain()), repo.cardioSetsFor(session.id))
    }

    @Test fun `invalid structured prescriptions are acknowledged without partial logging`() = runTest {
        val invalid = WatchCardioSetSpec("WORK", "TIME", 0)
        val event = delta().copy(activity = "TRACK_RUN", sets = listOf(
            WatchCardioSetResult(invalid, "FINISHED", 60),
        ))
        assertEquals(CardioDeltaApplier.Outcome.INVALID, CardioDeltaApplier(repo, ackLog, publisher).apply(event))
        assertEquals(emptyList<CardioSessionEntity>(), repo.cardioSessionsFlow.first())
        assertEquals(listOf(event.stamp), recordedStamps)
    }

    @Test fun `a replayed delta inserts and publishes once but still acks`() = runTest {
        val applier = CardioDeltaApplier(repo, ackLog, publisher)
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(delta()))
        assertEquals(CardioDeltaApplier.Outcome.DUPLICATE, applier.apply(delta()))
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(1, publisher.cardioPublishes)
        assertEquals(listOf(10L, 10L), recordedStamps)
    }

    @Test fun `same content under a new stamp dedupes but advances the ack`() = runTest {
        val applier = CardioDeltaApplier(repo, ackLog, publisher)
        applier.apply(delta(stamp = 10L))
        assertEquals(CardioDeltaApplier.Outcome.DUPLICATE, applier.apply(delta(stamp = 11L)))
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(1, publisher.cardioPublishes)
        assertEquals(listOf(10L, 11L), recordedStamps)
    }

    @Test fun `an older stamp arriving late still inserts its session`() = runTest {
        val applier = CardioDeltaApplier(repo, ackLog, publisher)
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(delta(stamp = 101L, startedAt = 5_000L)))
        assertEquals(
            CardioDeltaApplier.Outcome.APPLIED,
            applier.apply(delta(stamp = 100L, startedAt = 1_000L)),
        )
        assertEquals(2, repo.cardioSessionsFlow.first().size)
        assertEquals(listOf(101L, 100L), recordedStamps)
    }

    @Test fun `an invalid delta is acked and dropped, never replayed forever`() = runTest {
        val applier = CardioDeltaApplier(repo, ackLog, publisher)
        assertEquals(
            CardioDeltaApplier.Outcome.INVALID,
            applier.apply(delta(stamp = 12L).copy(mode = "FUTURE_MODE")),
        )
        assertEquals(0, repo.cardioSessionsFlow.first().size)
        assertEquals(listOf(12L), recordedStamps)
    }

    @Test fun `explicit-plan cardio stays with its plan and old-watch cardio uses selection`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Test", "", emptyList(), cardio = null))))
        val planP = repo.selectedPlanIdFlow.first()!!
        repo.renamePlan(planP, "Strength")
        val planQ = repo.duplicatePlan(planP, "Running")
        repo.selectPlan(planQ)
        val applier = CardioDeltaApplier(repo, ackLog, publisher)

        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(delta().copy(planId = planP)))
        val planned = repo.cardioSessionsFlow.first().single()
        assertEquals(planP, planned.planId)
        assertEquals("Strength", planned.planName)
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(delta(stamp = 11L, startedAt = 100_000L)))
        val legacy = repo.cardioSessionsFlow.first().first { it.startedAt == 100_000L }
        assertEquals(planQ, legacy.planId)
        assertEquals("Running", legacy.planName)
        // Plan identity does not change content identity, even across selection.
        assertEquals(CardioDeltaApplier.Outcome.DUPLICATE, applier.apply(delta(stamp = 12L).copy(planId = planQ)))
        assertEquals(2, repo.cardioSessionsFlow.first().size)
        assertEquals(listOf(10L, 11L, 12L), recordedStamps)
    }
}
