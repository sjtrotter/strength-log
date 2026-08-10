package cloud.trotter.log.strength.ui.log.share

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.ImportedSession
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import cloud.trotter.log.strength.ui.theme.DarkBackground
import java.io.File
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
 * [ShareCardService] end to end (#103, docs/briefs/session-share.md §3): a
 * real render onto a real Canvas, a real file under `cache/shares/`, and the
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
    private lateinit var repo: TrackerRepository
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
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
        service = ShareCardService(context, repo)
    }

    @After
    fun tearDown() {
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        db.close()
        File(context.cacheDir, "shares").deleteRecursively()
    }

    private fun session(dayId: String) = WorkoutSessionEntity(
        id = 0, dayId = dayId, dayTitle = "Lower", startedAt = null, completedAt = 1_783_502_280_000L,
        bodyweightLb = 182,
    )

    private fun set() = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = "bb_back_squat", exerciseName = "Barbell Back Squat", slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 235.0, reps = 5, done = true,
    )

    /** importSessionHistory doesn't hand back the id it assigned, so read it
     *  back — safe here because each call inserts exactly one new session. */
    private suspend fun seedSession(dayId: String): Long {
        repo.importSessionHistory(listOf(ImportedSession(session(dayId), listOf(set()))), newCustomExercises = emptyList())
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
        assertEquals(
            "share cards must keep their dark background",
            DarkBackground.toArgb(),
            bitmap.getPixel(0, 0),
        )
        val painterColors = ShareCardPainter.colorInput(dayIndex = 0)
        assertEquals(DarkBackground.toArgb(), painterColors.background)
        assertEquals(DayAccentColors.brightHex(0).toInt(), painterColors.accent)

        // A second render (a different session) clears the first's file —
        // one render lives in cache/shares/ at a time (§3).
        val secondId = seedSession("B")
        service.buildShareIntent(secondId)
        val secondFile = File(sharesDir, "share-$secondId.png")

        assertFalse("the previous session's render should be cleared", firstFile.exists())
        assertTrue(secondFile.exists())
    }
}
