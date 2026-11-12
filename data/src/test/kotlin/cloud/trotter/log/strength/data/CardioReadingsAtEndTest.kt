package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.records.GoalProgress
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioReadingsAtEndTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private lateinit var history: HistoryRepository
    private val session = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = true, label = "Track",
        startedAt = 1_000, completedAt = 451_000, seconds = 450, stepsCompleted = 1,
        activity = "TRACK_RUN",
    )

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-readings", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings)
        history = HistoryRepository(db, settings, plans)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, plans = plans, history = history)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun time(seconds: Int, aim: Int? = null) =
        CardioSetSpec(CardioRole.WORK, Measure.Time(seconds), aim?.let { Measure.Distance(it) })

    private fun result(spec: CardioSetSpec, elapsed: Int, metres: Int? = null, status: CardioSetStatus = CardioSetStatus.FINISHED) =
        CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL })

    @Test fun `logging stamps the readings each set reached`() = runTest {
        val sets = listOf(
            result(time(300).copy(role = CardioRole.WARMUP), 300),
            result(CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105)), 98, 400),
            result(time(90).copy(role = CardioRole.RECOVERY), 90),
            result(CardioSetSpec(CardioRole.WORK, Measure.Distance(400)), 60, 300, CardioSetStatus.SHORTENED),
            result(time(300).copy(role = CardioRole.COOLDOWN), 0, status = CardioSetStatus.SKIPPED),
        )
        val id = repo.logCardioSession(session, sets)
        val rows = db.cardioSetResultDao().forSession(id)
        assertEquals(listOf(null to 300, 400 to 98, null to 90, null to null, null to null),
            rows.map { it.metresAtEnd to it.secondsAtEnd })
        rows.forEachIndexed { position, row ->
            assertEquals(sets[position].toEntity(id, position).copy(id = row.id),
                row.copy(metresAtEnd = null, secondsAtEnd = null))
        }
    }

    @Test fun `a steady run past its end stores its time at the end and no distance`() = runTest {
        val id = repo.logCardioSession(session.copy(activity = "ROAD_RUN"), listOf(result(time(1500), 1690, 5000)))
        val row = history.cardioSetsFor(id).single()
        assertEquals(1690, row.elapsedSeconds)
        assertEquals(5000, row.metres)
        assertEquals(1500, row.secondsAtEnd)
        assertNull(row.metresAtEnd)
        assertTrue(history.cardioBestsFor(CardioActivity.ROAD_RUN).isEmpty())
    }

    @Test fun `a row that already has readings keeps them`() = runTest {
        val row = result(time(900), 904, 3485).copy(metresAtEnd = 3470, secondsAtEnd = 900, readingSource = ReadingSource.TYPED)
        val id = repo.logCardioSession(session.copy(activity = "ROAD_RUN"), listOf(row))
        assertEquals(row, history.cardioSetsFor(id).single())
        assertEquals(3470, FinishedRule.reading(history.cardioBestsFor(CardioActivity.ROAD_RUN).values.single()))
    }

    @Test fun `a note-only edit keeps a phone overrun row without record or goal credit`() = runTest {
        val id = repo.logCardioSession(session.copy(
            activity = "ROAD_RUN", seconds = 1690, completedAt = session.startedAt + 1_690_000,
        ), listOf(result(time(1500), 1690, 5000)))
        val before = db.cardioSetResultDao().forSession(id).single()

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 1690, 5000, "  Easy  ")

        assertEquals(before, db.cardioSetResultDao().forSession(id).single())
        assertEquals("Easy", history.cardioSession(id)?.note)
        val row = history.cardioSetsFor(id).single()
        assertEquals(Measure.Time(1500), row.spec.end)
        assertEquals(1690, row.elapsedSeconds)
        assertEquals(5000, row.metres)
        assertEquals(null to 1500, row.metresAtEnd to row.secondsAtEnd)
        assertTrue(history.cardioBestsFor(CardioActivity.ROAD_RUN).isEmpty())
        assertNull(GoalProgress.bestTimed(PlanGoal.Timed(CardioActivity.ROAD_RUN, 5000, 1800),
            listOf(CardioActivity.ROAD_RUN to listOf(row))))
    }

    @Test fun `a note-only edit keeps a supplied distance-end time`() = runTest {
        val supplied = result(CardioSetSpec(CardioRole.WORK, Measure.Distance(2414)), 761, 2414)
            .copy(distanceSource = DistanceSource.GPS, metresAtEnd = 2414, secondsAtEnd = 750, readingSource = ReadingSource.TYPED)
        val id = repo.logCardioSession(session, listOf(supplied))
        val before = db.cardioSetResultDao().forSession(id).single()

        history.updateCardioSession(id, CardioActivity.TRACK_RUN, 761, 2414, "At the track")

        assertEquals(before, db.cardioSetResultDao().forSession(id).single())
        assertEquals(supplied, history.cardioSetsFor(id).single())
        assertEquals("At the track", history.cardioSession(id)?.note)
    }

    @Test fun `a hand-logged distance edit recomputes its readings and its spec follows duration edits`() = runTest {
        val id = repo.logCardioSession(session.copy(activity = "ROAD_RUN"), listOf(ManualCardio.result(900, 3000)))

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 900, 3100, "Corrected distance")

        val distanceEdited = history.cardioSetsFor(id).single()
        assertEquals(Measure.Time(900), distanceEdited.spec.end)
        assertEquals(3100 to 900, distanceEdited.metresAtEnd to distanceEdited.secondsAtEnd)

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 910, 3200, "Corrected duration")

        val durationEdited = history.cardioSetsFor(id).single()
        assertEquals(Measure.Time(910), durationEdited.spec.end)
        assertEquals(3200 to 910, durationEdited.metresAtEnd to durationEdited.secondsAtEnd)
    }

    @Test fun `a distance edit keeps a supplied reading and the planned end`() = runTest {
        val supplied = result(time(900), 904, 3485).copy(metresAtEnd = 3470, secondsAtEnd = 900, readingSource = ReadingSource.TYPED)
        val id = repo.logCardioSession(session.copy(activity = "ROAD_RUN"), listOf(supplied))

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 904, 3500, "Corrected total")

        assertEquals(supplied.copy(metres = 3500), history.cardioSetsFor(id).single())
    }

    @Test fun `a measure edit keeps the planned end of a phone overrun`() = runTest {
        val id = repo.logCardioSession(session.copy(activity = "ROAD_RUN"), listOf(result(time(1500), 1690, 5000)))

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 1700, 5100, "Corrected totals")

        val edited = history.cardioSetsFor(id).single()
        assertEquals(Measure.Time(1500), edited.spec.end)
        assertEquals(1700, edited.elapsedSeconds)
        assertEquals(5100, edited.metres)
        assertEquals(null to 1500, edited.metresAtEnd to edited.secondsAtEnd)
        assertTrue(history.cardioBestsFor(CardioActivity.ROAD_RUN).isEmpty())
    }

    @Test fun `distance amendments keep supplied readings`() = runTest {
        val supplied = listOf(
            result(time(900), 904, 3485).copy(metresAtEnd = 3470, secondsAtEnd = 900, readingSource = ReadingSource.TYPED),
            result(CardioSetSpec(CardioRole.WORK, Measure.Distance(2414)), 761, 2414)
                .copy(distanceSource = DistanceSource.GPS, metresAtEnd = 2414, secondsAtEnd = 750, readingSource = ReadingSource.TYPED),
        )
        val id = repo.logCardioSession(session, supplied)

        supplied.forEachIndexed { position, row ->
            history.amendCardioSetDistance(id, position, 3500)
            assertEquals(row.copy(metres = 3500, distanceSource = DistanceSource.MANUAL), history.cardioSetsFor(id)[position])

            history.amendCardioSetDistance(id, position, null)
            assertEquals(row.copy(metres = null, distanceSource = null), history.cardioSetsFor(id)[position])
        }
    }

    @Test fun `a distance entered after logging becomes the reading`() = runTest {
        val id = repo.logCardioSession(session, listOf(result(time(900, aim = 3000), 900)))
        assertEquals(null to 900, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
        history.amendCardioSetDistance(id, 0, 3100)
        val row = history.cardioSetsFor(id).single()
        assertEquals(3100 to 900, row.metresAtEnd to row.secondsAtEnd)
        assertEquals(3100, row.metres)
        assertEquals(DistanceSource.MANUAL, row.distanceSource)
        history.amendCardioSetDistance(id, 0, null)
        val cleared = history.cardioSetsFor(id).single()
        assertEquals(null to 900, cleared.metresAtEnd to cleared.secondsAtEnd)
        val other = repo.logCardioSession(session.copy(startedAt = 2_000), listOf(result(time(900), 904)))
        history.amendCardioSetDistance(other, 0, 3485)
        assertEquals(null to 900, history.cardioSetsFor(other).single().let { it.metresAtEnd to it.secondsAtEnd })
    }

    @Test fun `editing a one-set session recomputes its readings`() = runTest {
        val id = repo.logCardioSession(session.copy(activity = "ROAD_RUN"), listOf(ManualCardio.result(2413, 7100)))
        assertEquals(7100 to 2413, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 2412, 7000, "")
        val edited = history.cardioSetsFor(id).single()
        assertEquals(7000 to 2412, edited.metresAtEnd to edited.secondsAtEnd)
        assertEquals(Measure.Time(2412), edited.spec.end)
        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 2412, null, "")
        assertEquals(null to 2412, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
    }

    @Test fun `logging an exact GPS finish keeps its unread distance unknown`() = runTest {
        val row = CardioSetResult(time(300), CardioSetStatus.FINISHED, 300, 888, DistanceSource.GPS, secondsAtEnd = 300)
        val id = repo.logCardioSession(session.copy(activity = "ROAD_RUN"), listOf(row))
        assertNull(db.cardioSetResultDao().forSession(id).single().metresAtEnd)
        assertEquals(row, history.cardioSetsFor(id).single())
    }
}
