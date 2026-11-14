package cloud.trotter.log.strength.ui.log.share

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.ImportedCardioSession
import cloud.trotter.log.strength.data.ImportedSession
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import cloud.trotter.log.strength.ui.theme.DarkBackground
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ShareCardService] end to end (docs/briefs/session-share.md §3): a real
 * render onto a real Canvas, a real file under `cache/shares/`, and the
 * `ACTION_SEND` chooser intent it produces. Run under a plain [runBlocking]
 * rather than `kotlinx-coroutines-test` — the service hops onto
 * `Dispatchers.IO` internally (§3's off-main-thread render), which a virtual-
 * time `TestDispatcher` can't observe; a real blocking call sidesteps that
 * mismatch entirely and genuinely waits for the work.
 *
 * Every [FileProvider][androidx.core.content.FileProvider] assertion lives in
 * one test method ([rendersWritesAndReplacesTheShareCard]) rather than split
 * across several: `FileProvider` caches its resolved root directory in a
 * process-static map keyed by authority, and Robolectric hands out a fresh
 * `cacheDir` per test *method* — a second method calling `getUriForFile`
 * would find the *first* method's now-stale cached root and fail with
 * "no configured root", a Robolectric/JVM-static artifact that can't happen
 * on a real device (its cacheDir never moves for the app's lifetime).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareCardServiceTest {

    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: HistoryRepository
    private lateinit var service: ShareCardService
    private lateinit var context: Context
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("share-service-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = HistoryRepository(
            db = db,
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
            plans = PlanRepository(db, settings),
        )
        service = ShareCardService(context, repo, settings = settings)
    }

    @After
    fun tearDown() {
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        db.close()
        File(context.cacheDir, "shares").deleteRecursively()
    }

    private fun session(dayId: String) = WorkoutSessionEntity(
        id = 0, dayId = dayId, dayTitle = "Lower", startedAt = null, completedAt = 1_783_502_280_000L,
        bodyweightLb = 182.0,
    )

    private fun set() = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = "bb_back_squat", exerciseName = "Barbell Back Squat", slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 235.0, reps = 5, done = true,
    )

    /** importSessionHistory doesn't hand back the id it assigned, so read it
     *  back — safe here because each call inserts exactly one new session. */
    private suspend fun seedSession(dayId: String, sets: List<SessionSetEntity> = listOf(set())): Long {
        repo.importSessionHistory(listOf(ImportedSession(session(dayId), sets)), newCustomExercises = emptyList())
        return db.sessionDao().allSessions().last().id
    }

    @Test
    fun anUnknownSessionIdBuildsNoIntent() = runBlocking {
        assertNull(service.buildShareIntent(999L))
    }

    @Test
    fun rendersWritesAndReplacesTheShareCard() = runBlocking {
        val sharesDir = File(context.cacheDir, "shares")
        val firstId = seedSession("A")

        val chooser = service.buildShareIntent(firstId)

        // The ACTION_SEND intent, correctly typed and permissioned.
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val sendIntent = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
        assertEquals("image/png", sendIntent.type)
        assertTrue((sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertNotNull(sendIntent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertNotNull(sendIntent.clipData)

        // The PNG itself: 1080x1350, actually on disk.
        val firstFile = File(sharesDir, "share-$firstId.png")
        assertTrue(firstFile.exists())
        val bitmap = BitmapFactory.decodeFile(firstFile.absolutePath)
        assertEquals(1080, bitmap.width)
        assertEquals(1350, bitmap.height)
        // Robolectric bitmaps carry no real pixel data, so the dark-pinned
        // contract is asserted at the painter's color seam, which render()
        // itself consumes.
        val painterColors = ShareCardPainter.colorInput(dayIndex = 0)
        assertEquals(DarkBackground.toArgb(), painterColors.background)
        assertEquals(DayAccentColors.brightHex(0).toInt(), painterColors.accent)

        // A second render (a different session) clears the first's file —
        // one render lives in cache/shares/ at a time (§3).
        val secondId = seedSession("B", nineExerciseShareSets())
        service.buildShareIntent(secondId)
        val secondFile = File(sharesDir, "share-$secondId.png")

        assertFalse("the previous session's render should be cleared", firstFile.exists())
        assertTrue(secondFile.exists())
        val secondBitmap = BitmapFactory.decodeFile(secondFile.absolutePath)
        assertEquals(1080, secondBitmap.width)
        assertEquals(1650, secondBitmap.height)
    }

    @Test
    fun heightKeepsTheMinimumThroughSixLinesAndAddsOneRowPerExtraLine() {
        for (count in 0..6) assertEquals(1350, ShareCardPainter.heightFor(count))
        for (count in 7..20) assertEquals(1350 + (count - 6) * 100, ShareCardPainter.heightFor(count))
    }

    @Test
    fun includesEveryCardioBlockFromTheRunOrDayAndUsesDistancePreference() = runBlocking {
        settings.setDistanceUnit(DistanceUnit.KM)
        val workout = session("A").copy(planId = 1, startedAt = 1_783_500_000_000L)
        val cardio = cardioSession(workout.completedAt - 1)
        val results = listOf(ManualCardio.result(600, 2000), ManualCardio.result(900, 3000),
            ManualCardio.result(300, 9000).copy(status = CardioSetStatus.SKIPPED))
        repo.importSessionHistory(listOf(ImportedSession(workout, listOf(set()))), newCustomExercises = emptyList(),
            cardioSessions = listOf(
                ImportedCardioSession(cardio, results),
                ImportedCardioSession(cardio.copy(completedAt = workout.completedAt, seconds = 300),
                    listOf(ManualCardio.result(300, null))),
                ImportedCardioSession(cardio.copy(planId = 2), results),
                ImportedCardioSession(cardio.copy(dayId = "B"), results),
                ImportedCardioSession(cardio.copy(completedAt = requireNotNull(workout.startedAt) - 1), results),
                ImportedCardioSession(cardio.copy(completedAt = workout.completedAt - 86_400_000), results),
                ImportedCardioSession(cardio.copy(completedAt = workout.completedAt + 86_400_000), results),
            ))
        val id = db.sessionDao().allSessions().single().id
        val content = requireNotNull(service.buildContent(id))
        assertEquals(listOf(
            ShareExerciseLine("ROAD RUN", "5 KM · 25:00"),
            ShareExerciseLine("ROAD RUN", "5 KM · 25:00"),
            ShareExerciseLine("ROAD RUN", "5:00"),
        ), content.exerciseLines.drop(1))
    }

    @Test
    fun sessionsWithoutAStartMatchCardioOnTheSameCivilDayAndKnownPlan() = runBlocking {
        val workout = session("A").copy(planId = 1)
        val cardio = cardioSession(workout.completedAt)
        repo.importSessionHistory(listOf(ImportedSession(workout, listOf(set()))), newCustomExercises = emptyList(),
            cardioSessions = listOf(
                ImportedCardioSession(cardio.copy(activity = null), listOf(ManualCardio.result(1500, 5000))),
                ImportedCardioSession(cardio.copy(completedAt = workout.completedAt - 86_400_000), emptyList()),
            ))
        val id = db.sessionDao().allSessions().single().id
        assertEquals(listOf(ShareExerciseLine("ROAD RUN", "3.11 MI · 25:00")),
            requireNotNull(service.buildContent(id)).exerciseLines.drop(1))
        val unknownPlanId = seedSession("A")
        assertEquals(1, requireNotNull(service.buildContent(unknownPlanId)).exerciseLines.size)
    }

    @Test
    fun cardioWithinAnOvernightRunIsIncludedFromThePreviousCivilDay() = runBlocking {
        val completedAt = LocalDate.of(2026, 11, 13).atTime(0, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val workout = session("A").copy(planId = 1, startedAt = completedAt - 1_800_000, completedAt = completedAt)
        repo.importSessionHistory(listOf(ImportedSession(workout, listOf(set()))), newCustomExercises = emptyList(),
            cardioSessions = listOf(ImportedCardioSession(cardioSession(completedAt - 1_200_000),
                listOf(ManualCardio.result(1500, 5000)))))
        val id = db.sessionDao().allSessions().single().id
        assertEquals(listOf(ShareExerciseLine("ROAD RUN", "3.11 MI · 25:00")),
            requireNotNull(service.buildContent(id)).exerciseLines.drop(1))
    }

    private fun cardioSession(completedAt: Long) = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Easy run",
        startedAt = completedAt - 1_500_000, completedAt = completedAt, seconds = 1500,
        stepsCompleted = 1, planId = 1, activity = "ROAD_RUN",
    )
}
