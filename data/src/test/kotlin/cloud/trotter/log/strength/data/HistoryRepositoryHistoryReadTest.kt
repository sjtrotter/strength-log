package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.records.PersonalBests
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.seeding.CarryForward
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/**
 * [HistoryRepository.sessionSummariesFlow] and [HistoryRepository.lastPerformed]
 * serve the Log screen + day-card "last time" chip through [SessionDao]'s
 * queries. Robolectric + in-memory Room, same pattern as
 * [HistoryRepositorySessionHistoryImportTest] — the query is the thing under
 * test, not the reduction (that's [LastPerformedTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryRepositoryHistoryReadTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: HistoryRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("history-read-settings", ".preferences_pb")
        }
        val settings = SettingsStore(dataStore)
        repo = HistoryRepository(
            db = db,
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
            plans = PlanRepository(db, settings),
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun session(completedAt: Long, dayTitle: String = "Lower") =
        WorkoutSessionEntity(id = 0, dayId = "A", dayTitle = dayTitle, startedAt = null, completedAt = completedAt, bodyweightLb = 180.0)

    private fun set(exerciseId: String, name: String, kind: SetKind, weightLb: Double, reps: Int, done: Boolean) =
        SessionSetEntity(
            id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
            setIndex = 0, kind = kind.name, weightLb = weightLb, reps = reps, done = done,
        )

    /** Older session: a full, all-done squat ramp plus a bench accessory set. */
    private suspend fun seedOlderSession() {
        repo.importSessionHistory(
            listOf(
                ImportedSession(
                    session(completedAt = 1_000L),
                    listOf(
                        set("bb_back_squat", "Barbell Back Squat", SetKind.RAMP, 130.0, 5, done = true),
                        set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 235.0, 5, done = true),
                        set("bb_back_squat", "Barbell Back Squat", SetKind.BACKOFF, 175.0, 8, done = true),
                        set("bb_bench", "Barbell Bench Press", SetKind.WORK, 185.0, 5, done = true),
                    ),
                ),
            ),
            newCustomExercises = emptyList(),
        )
    }

    /** Newer session: the TOP set was left unchecked, so it must not count as
     *  "last performed" — only the completed ramp row should. */
    private suspend fun seedNewerSessionWithAnUncheckedTop() {
        repo.importSessionHistory(
            listOf(
                ImportedSession(
                    session(completedAt = 2_000L, dayTitle = "Lower — heavier"),
                    listOf(
                        set("bb_back_squat", "Barbell Back Squat", SetKind.RAMP, 135.0, 5, done = true),
                        set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, 3, done = false),
                    ),
                ),
            ),
            newCustomExercises = emptyList(),
        )
    }

    // --- sessionSummariesFlow --------------------------------------------------

    @Test
    fun sessionSummariesAreNewestFirstWithAnAggregatedSetCount() = runTest {
        seedOlderSession()
        seedNewerSessionWithAnUncheckedTop()

        val summaries = repo.sessionSummariesFlow.first()

        assertEquals(2, summaries.size)
        assertEquals("Lower — heavier", summaries[0].session.dayTitle) // newest first
        assertEquals(1, summaries[0].setCount)
        assertEquals("Lower", summaries[1].session.dayTitle)
        assertEquals(4, summaries[1].setCount)
    }

    @Test
    fun aSessionWithNoSetsStillAppearsWithACountOfZero() = runTest {
        repo.importSessionHistory(
            listOf(ImportedSession(session(completedAt = 500L), emptyList())),
            newCustomExercises = emptyList(),
        )

        val summaries = repo.sessionSummariesFlow.first()
        assertEquals(1, summaries.size)
        assertEquals(0, summaries.single().setCount)
    }

    @Test
    fun aSessionWithOnlyUndoneSetsStillAppearsWithACountOfZero() = runTest {
        repo.importSessionHistory(
            listOf(ImportedSession(session(completedAt = 500L), listOf(
                set("bb_back_squat", "Barbell Back Squat", SetKind.WORK, 130.0, 5, done = false),
            ))),
            newCustomExercises = emptyList(),
        )

        val summary = repo.sessionSummariesFlow.first().single()
        assertEquals(0, summary.setCount)
        assertFalse(repo.sessionSets(summary.session.id).single().done)
    }

    @Test
    fun trajectoryIncludesAStraightSetWeightPrAndExcludesWarmupsUncheckedAndEmptySets() = runTest {
        val squat = "bb_back_squat"
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L), listOf(
                set(squat, "Squat", SetKind.RAMP, 45.0, 5, true),
            )),
            ImportedSession(session(2_000L), listOf(
                set(squat, "Squat", SetKind.TOP, 50.0, 5, true),
            )),
            ImportedSession(session(3_000L), listOf(
                set(squat, "Squat", SetKind.WORK, 50.0, 5, true),
                set(squat, "Squat", SetKind.WORK, 55.0, 5, true),
                set(squat, "Squat", SetKind.WORK, 55.0, 5, true),
                set(squat, "Squat", SetKind.RAMP, 90.0, 5, true),
                set(squat, "Squat", SetKind.WORK, 100.0, 5, false),
            )),
            ImportedSession(session(4_000L), listOf(
                set(squat, "Squat", SetKind.WORK, 0.0, 0, true),
            )),
        ), emptyList())

        assertEquals(PrKind.WEIGHT, PersonalBests.prKind(
            LoggedSet(55.0, 5, SetKind.WORK, done = true),
            LoggedSet(50.0, 5, SetKind.TOP, done = true),
        ))
        val points = repo.topSetHistoryFlow.first()
        assertEquals(listOf(2_000L, 3_000L), points.map { it.completedAt })
        assertEquals(listOf(50.0, 55.0), points.map { it.topWeightLb })
        assertEquals(listOf(squat, squat), points.map { it.exerciseId })
    }

    // --- lastPerformed (batched, "last time" chip) -----------------------------

    @Test
    fun lastPerformedPicksTheNewestSessionsHeaviestDoneSetPerExercise() = runTest {
        seedOlderSession()
        seedNewerSessionWithAnUncheckedTop()

        val result = repo.lastPerformed(listOf("bb_back_squat", "bb_bench", "ghost_unknown"))

        // Newest session's TOP was unchecked, so its completed ramp row wins —
        // not the older session's (heavier, but stale) TOP, and not the
        // unchecked 245x3.
        assertEquals(LastPerformed(135.0, 5), result["bb_back_squat"])
        assertEquals(LastPerformed(185.0, 5), result["bb_bench"])
        assertNull(result["ghost_unknown"])
    }

    @Test
    fun lastPerformedBatchesTheWholeDayInOneCall() = runTest {
        seedOlderSession()

        // A day with three exercise ids resolves in the one call — this test
        // exists to pin the *shape* of the read (one query, one round trip),
        // not just its correctness per id.
        val result = repo.lastPerformed(listOf("bb_back_squat", "bb_bench", "never_logged"))

        assertEquals(2, result.size)
        assertEquals(LastPerformed(235.0, 5), result["bb_back_squat"])
    }

    @Test
    fun lastPerformedOfAnEmptyIdListIsAnEmptyMapWithoutQuerying() = runTest {
        assertEquals(emptyMap<String, LastPerformed>(), repo.lastPerformed(emptyList()))
    }

    @Test
    fun `last sets come from the newest session's working sets`() = runTest {
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L), List(3) { index ->
                set("bb_bench", "Bench", SetKind.WORK, 220.0, 5, true).copy(setIndex = index)
            }),
            ImportedSession(session(2_000L), listOf(
                set("bb_bench", "Bench", SetKind.RAMP, 135.0, 5, true).copy(setIndex = 0),
                set("bb_bench", "Bench", SetKind.WORK, 225.0, 4, true).copy(setIndex = 3),
                set("bb_bench", "Bench", SetKind.WORK, 225.0, 5, true).copy(setIndex = 2),
                set("bb_bench", "Bench", SetKind.WORK, 225.0, 5, true).copy(setIndex = 1),
                set("bb_bench", "Bench", SetKind.EXTRA, 245.0, 5, false).copy(setIndex = 4),
            )),
        ), emptyList())

        val result = repo.lastSets(listOf("bb_bench", "unknown"))
        assertEquals(listOf(
            LoggedSet(225.0, 5, SetKind.WORK, done = true),
            LoggedSet(225.0, 5, SetKind.WORK, done = true),
            LoggedSet(225.0, 4, SetKind.WORK, done = true),
        ), result["bb_bench"])
        assertNull(result["unknown"])
        assertTrue(repo.lastSets(emptyList()).isEmpty())
    }

    @Test
    fun `last sets stay empty after a warmup only session`() = runTest {
        seedOlderSession()
        seedNewerSessionWithAnUncheckedTop()
        assertEquals(emptyList<LoggedSet>(), repo.lastSets(listOf("bb_back_squat"))["bb_back_squat"])
        assertEquals(LastPerformed(135.0, 5), repo.lastPerformed(listOf("bb_back_squat"))["bb_back_squat"])
    }

    @Test
    fun `slotSessions returns planned and actual rows for the newest session of the day and nothing without plannedJson`() = runTest {
        assertTrue(repo.slotSessions(10L, "A").isEmpty())
        val planned = listOf(LoggedSet(70.0, 5, SetKind.WORK), LoggedSet(70.0, 5, SetKind.EXTRA))
        val encoded = SessionPlanJson.encode(listOf(
            PlannedTrackDto(0, "bb_back_squat", Slot.MAIN, sets = planned.map(SetDto::of)),
            PlannedTrackDto(1, "ez_curl", Slot.SS, retired = true, sets = planned.map(SetDto::of)),
        ))
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L).copy(planId = 10L, plannedJson = encoded), emptyList()),
            ImportedSession(session(2_000L).copy(planId = 10L, plannedJson = encoded), listOf(
                set("bb_back_squat", "Squat", SetKind.EXTRA, 75.0, 6, false).copy(setIndex = 1, plannedIndex = 1, trackPosition = 0),
                set("bb_back_squat", "Squat", SetKind.WORK, 70.0, 4, true).copy(
                    setIndex = 0, plannedIndex = 0, trackPosition = 0, seconds = 30, startedAtMillis = 100L, completedAtMillis = 200L, kind = "FUTURE",
                ),
                set("bb_back_squat", "Squat", SetKind.EXTRA, 80.0, 2, true).copy(setIndex = 2, trackPosition = 0),
                set("ez_curl", "Curl", SetKind.WORK, 50.0, 10, true).copy(slot = Slot.SS, plannedIndex = 0, trackPosition = 1),
            )),
            ImportedSession(session(3_000L).copy(planId = 10L, dayId = "B", plannedJson = encoded), emptyList()),
            ImportedSession(session(4_000L).copy(planId = 20L, plannedJson = encoded), emptyList()),
        ), emptyList())

        val result = repo.slotSessions(10L, "A")
        assertEquals(setOf("bb_back_squat" to Slot.MAIN, "ez_curl" to Slot.SS), result.keys)
        assertEquals(SlotSession(planned, listOf(
            LoggedSet(70.0, 4, SetKind.WORK, done = true, seconds = 30, startedAtMillis = 100L, completedAtMillis = 200L, plannedIndex = 0),
            LoggedSet(75.0, 6, SetKind.EXTRA, plannedIndex = 1),
            LoggedSet(80.0, 2, SetKind.EXTRA, done = true),
        ), false), result["bb_back_squat" to Slot.MAIN])
        assertEquals(SlotSession(planned, listOf(LoggedSet(50.0, 10, SetKind.WORK, done = true, plannedIndex = 0)), true), result["ez_curl" to Slot.SS])

        // A tied completion uses the newer session id, even when that archive has no prescription.
        repo.importSessionHistory(listOf(ImportedSession(session(2_000L).copy(planId = 10L), emptyList())), emptyList())
        assertTrue(repo.slotSessions(10L, "A").isEmpty())
    }

    @Test
    fun `slotSessions keeps the last duplicate track and its own actuals so a miss earns no offer`() = runTest {
        val first = listOf(LoggedSet(100.0, 5, SetKind.WORK))
        val second = listOf(LoggedSet(50.0, 5, SetKind.WORK))
        val encoded = SessionPlanJson.encode(listOf(
            PlannedTrackDto(0, "bb_back_squat", Slot.MAIN, sets = first.map(SetDto::of)),
            PlannedTrackDto(1, "bb_back_squat", Slot.MAIN, sets = second.map(SetDto::of)),
        ))
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L).copy(planId = 10L, plannedJson = encoded), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 100.0, 5, true).copy(trackPosition = 0, plannedIndex = 0),
                set("bb_back_squat", "Squat", SetKind.WORK, 50.0, 4, true).copy(trackPosition = 1, plannedIndex = 0),
            )),
        ), emptyList())

        val result = repo.slotSessions(10L, "A")

        assertEquals(setOf("bb_back_squat" to Slot.MAIN), result.keys)
        val track = result.getValue("bb_back_squat" to Slot.MAIN)
        assertEquals(SlotSession(second, listOf(LoggedSet(50.0, 4, SetKind.WORK, done = true, plannedIndex = 0)), false), track)
        assertNull(CarryForward.offer(track.planned, track.actual, WeightUnit.LB))
    }

    @Test
    fun `progressionSessions offers a heavier Day B squat on Day A while Last stays per slot`() = runTest {
        val opening = listOf(LoggedSet(45.0, 5, SetKind.WORK))
        val elsewhere = listOf(LoggedSet(30.0, 5, SetKind.RAMP), LoggedSet(50.0, 5, SetKind.WORK))
        val ownJson = SessionPlanJson.encode(listOf(
            PlannedTrackDto(0, "bb_back_squat", Slot.MAIN, sets = opening.map(SetDto::of)),
        ))
        val otherJson = SessionPlanJson.encode(listOf(
            PlannedTrackDto(2, "bb_back_squat", Slot.SS, sets = elsewhere.map(SetDto::of)),
        ))
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L).copy(planId = 10L, plannedJson = ownJson), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 45.0, 4, true).copy(trackPosition = 0, plannedIndex = 0),
            )),
            ImportedSession(session(2_000L).copy(planId = 10L, dayId = "B", plannedJson = otherJson), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 55.0, 5, true).copy(
                    slot = Slot.SS, setIndex = 1, trackPosition = 2, plannedIndex = 1),
            )),
            ImportedSession(session(3_000L).copy(planId = 20L, dayId = "B", plannedJson = ownJson), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 100.0, 5, true).copy(trackPosition = 0, plannedIndex = 0),
            )),
            ImportedSession(session(4_000L).copy(plannedJson = ownJson), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 100.0, 5, true).copy(trackPosition = 0, plannedIndex = 0),
            )),
        ), emptyList())

        val evidence = repo.progressionSessions(10L, listOf("bb_back_squat", "unknown"))
        assertEquals(setOf("bb_back_squat"), evidence.keys)
        assertEquals(listOf(elsewhere, opening), evidence.getValue("bb_back_squat").map { it.planned })
        assertEquals(55.0, CarryForward.offer(opening, WeightUnit.LB, evidence.getValue("bb_back_squat"))!!, 0.0)
        val own = repo.slotSessions(10L, "A").getValue("bb_back_squat" to Slot.MAIN)
        assertNull(CarryForward.offer(own.planned, own.actual, WeightUnit.LB))
        assertEquals(opening, CarryForward.next(own.planned, own.actual))
        assertEquals(SlotLast(LastPerformed(45.0, 4), true, 10L, null, "A", listOf(LoggedSet(45.0, 4, SetKind.WORK, done = true))),
            repo.lastPerformedForSlots(10L, "A", listOf("bb_back_squat"))["bb_back_squat" to Slot.MAIN])
        assertTrue(repo.progressionSessions(10L, emptyList()).isEmpty())
        assertTrue(repo.progressionSessions(30L, listOf("bb_back_squat")).isEmpty())
    }

    @Test
    fun `progressionSessions keeps an older success after misses elsewhere and respects archive identities`() = runTest {
        val opening = listOf(LoggedSet(45.0, 5, SetKind.WORK))
        val planned = listOf(LoggedSet(50.0, 5, SetKind.WORK))
        val encoded = SessionPlanJson.encode(listOf(
            PlannedTrackDto(0, "bb_back_squat", Slot.MAIN, sets = planned.map(SetDto::of)),
        ))
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L).copy(planId = 10L, dayId = "B", plannedJson = encoded), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 55.0, 5, true).copy(trackPosition = 0, plannedIndex = 0),
            )),
        ), emptyList())
        assertEquals(55.0, CarryForward.offer(opening, WeightUnit.LB,
            repo.progressionSessions(10L, listOf("bb_back_squat")).getValue("bb_back_squat"))!!, 0.0)

        for (row in listOf(
            set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 4, true).copy(trackPosition = 0, plannedIndex = 0),
            set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 5, false).copy(trackPosition = 0, plannedIndex = 0),
            set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 5, true).copy(trackPosition = 0),
            set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 5, true).copy(trackPosition = 1, plannedIndex = 0),
        )) {
            repo.importSessionHistory(listOf(
                ImportedSession(session(2_000L).copy(planId = 10L, dayId = "B", plannedJson = encoded), listOf(row)),
            ), emptyList())
            val evidence = repo.progressionSessions(10L, listOf("bb_back_squat")).getValue("bb_back_squat")
            assertNull(CarryForward.offer(opening, WeightUnit.LB, evidence.take(1)))
            assertEquals(55.0, CarryForward.offer(opening, WeightUnit.LB, evidence)!!, 0.0)
        }

        val duplicate = SessionPlanJson.encode(listOf(
            PlannedTrackDto(0, "bb_back_squat", Slot.MAIN, sets = planned.map(SetDto::of)),
            PlannedTrackDto(1, "bb_back_squat", Slot.MAIN, sets = planned.map(SetDto::of)),
            PlannedTrackDto(2, "bb_back_squat", Slot.SS, retired = true, sets = planned.map(SetDto::of)),
        ))
        repo.importSessionHistory(listOf(
            ImportedSession(session(3_000L).copy(planId = 10L, dayId = "B", plannedJson = duplicate), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 5, true).copy(trackPosition = 0, plannedIndex = 0),
                set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 4, true).copy(trackPosition = 1, plannedIndex = 0),
                set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 5, true).copy(slot = Slot.SS, trackPosition = 2, plannedIndex = 0),
            )),
        ), emptyList())
        val evidence = repo.progressionSessions(10L, listOf("bb_back_squat")).getValue("bb_back_squat")
        assertEquals(listOf(LoggedSet(60.0, 4, SetKind.WORK, done = true, plannedIndex = 0)), evidence.first().actual)
        assertNull(CarryForward.offer(opening, WeightUnit.LB, evidence.take(1)))
        assertEquals(55.0, CarryForward.offer(opening, WeightUnit.LB, evidence)!!, 0.0)

        repo.importSessionHistory(listOf(
            ImportedSession(session(3_000L).copy(planId = 10L, dayId = "B"), emptyList()),
        ), emptyList())
        assertEquals(evidence, repo.progressionSessions(10L, listOf("bb_back_squat")).getValue("bb_back_squat"))
    }

    @Test
    fun `progressionSessions finds the latest qualifying set after a newer skipped squat`() = runTest {
        val opening = listOf(LoggedSet(45.0, 5, SetKind.WORK))
        val encoded = SessionPlanJson.encode(listOf(
            PlannedTrackDto(0, "bb_back_squat", Slot.MAIN, sets = opening.map(SetDto::of)),
        ))
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L).copy(planId = 10L, plannedJson = encoded), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 45.0, 4, true).copy(trackPosition = 0, plannedIndex = 0),
            )),
            ImportedSession(session(2_000L).copy(planId = 10L, dayId = "B", plannedJson = encoded), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 60.0, 5, true).copy(trackPosition = 0, plannedIndex = 0),
            )),
            ImportedSession(session(3_000L).copy(planId = 10L, dayId = "B", plannedJson = encoded), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 55.0, 5, true).copy(trackPosition = 0, plannedIndex = 0),
            )),
            ImportedSession(session(4_000L).copy(planId = 10L, dayId = "B", plannedJson = encoded), emptyList()),
        ), emptyList())

        val evidence = repo.progressionSessions(10L, listOf("bb_back_squat")).getValue("bb_back_squat")
        assertEquals(55.0, CarryForward.offer(opening, WeightUnit.LB, evidence)!!, 0.0)
        assertTrue(repo.slotSessions(10L, "B").getValue("bb_back_squat" to Slot.MAIN).actual.isEmpty())
        assertEquals(SlotLast(LastPerformed(45.0, 4), true, 10L, null, "A", listOf(LoggedSet(45.0, 4, SetKind.WORK, done = true))),
            repo.lastPerformedForSlots(10L, "A", listOf("bb_back_squat"))["bb_back_squat" to Slot.MAIN])
    }

    @Test
    fun `lastPerformedForSlots prefers the same plan day and slot and labels the fallback`() = runTest {
        repo.importSessionHistory(listOf(
            ImportedSession(session(1_000L).copy(planId = 10L, planName = "Own"), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 70.0, 5, true),
                set("bb_back_squat", "Squat", SetKind.WORK, 65.0, 8, true),
            )),
            ImportedSession(session(2_000L).copy(planId = 10L, planName = "Own", dayId = "B"), listOf(
                set("bb_back_squat", "Squat", SetKind.WORK, 80.0, 5, true),
                set("ez_curl", "Curl", SetKind.WORK, 50.0, 10, true).copy(slot = Slot.SS, seconds = 20),
                set("ez_curl", "Curl", SetKind.WORK, 100.0, 10, false),
            )),
        ), emptyList())
        val result = repo.lastPerformedForSlots(10L, "A", listOf("bb_back_squat", "ez_curl", "unknown"))
        assertEquals(4, result.size)
        assertEquals(SlotLast(LastPerformed(70.0, 5), true, 10L, "Own", "A", listOf(
            LoggedSet(70.0, 5, SetKind.WORK, done = true), LoggedSet(65.0, 8, SetKind.WORK, done = true))), result["bb_back_squat" to Slot.MAIN])
        assertEquals(SlotLast(LastPerformed(80.0, 5), false, 10L, "Own", "B", listOf(LoggedSet(80.0, 5, SetKind.WORK, done = true))), result["bb_back_squat" to Slot.SS])
        for (slot in listOf(Slot.MAIN, Slot.SS)) {
            assertEquals(SlotLast(LastPerformed(50.0, 10, 20), false, 10L, "Own", "B", listOf(LoggedSet(50.0, 10, SetKind.WORK, done = true, seconds = 20))), result["ez_curl" to slot])
        }
        assertTrue(repo.lastPerformedForSlots(10L, "A", emptyList()).isEmpty())
    }

    @Test
    fun sessionSetsReturnsAllRowsForOneSessionInInsertionOrder() = runTest {
        seedOlderSession()
        val sessionId = repo.sessionsFlow.first().single().id

        val sets = repo.sessionSets(sessionId)

        assertEquals(listOf("bb_back_squat", "bb_back_squat", "bb_back_squat", "bb_bench"), sets.map { it.exerciseId })
    }

    @Test
    fun updateSessionSetPersistsEveryEditableField() = runTest {
        seedOlderSession()
        val sessionId = repo.sessionsFlow.first().single().id
        val original = repo.sessionSets(sessionId).first()

        repo.updateSessionSet(original.copy(weightLb = 142.5, reps = 7, seconds = 45, done = false))

        val changed = repo.sessionSets(sessionId).first()
        assertEquals(original.id, changed.id)
        assertEquals(142.5, changed.weightLb, 0.0)
        assertEquals(7, changed.reps)
        assertEquals(45, changed.seconds)
        assertFalse(changed.done)
    }

    @Test
    fun deleteSessionAndUndoRestoreTheExactRows() = runTest {
        seedOlderSession()
        val session = repo.sessionsFlow.first().single()
        val sets = repo.sessionSets(session.id)

        val deleted = requireNotNull(repo.deleteSession(session.id))
        assertTrue(repo.sessionSummariesFlow.first().isEmpty())
        assertTrue(repo.sessionSets(session.id).isEmpty())

        repo.restoreSession(deleted)

        assertEquals(session, repo.sessionsFlow.first().single())
        assertEquals(sets, repo.sessionSets(session.id))
    }
}
