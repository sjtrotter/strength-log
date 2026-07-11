package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.library.tracking
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TrackerRepository.addCustomExercise] / [TrackerRepository.catalogFlow] (brief
 * #13, PLAN.md A4): id-collision impossibility via the `custom_` prefix and
 * catalog-merge visibility of a saved custom exercise — the two `:data`-level
 * guarantees the custom-exercise creation form depends on. Name-blank rejection
 * is a form-layer concern (the ViewModel never calls this API with a blank
 * name) and is covered in `:app`'s CustomExerciseViewModelTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryCustomExerciseTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("custom-exercise-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `saved id is custom-prefixed and never collides with a catalog id`() = runTest {
        val id = repo.addCustomExercise(
            name = "Cable Hack Squat",
            pattern = MovementPattern.SQUAT_BILATERAL,
            equipment = listOf(Equipment.CABLE, Equipment.MACHINE),
            perHand = false,
            goalStartLb = 80.0,
        )

        assertTrue(id.startsWith(ExerciseCatalog.CUSTOM_ID_PREFIX))
        assertTrue(ExerciseLibrary.entries.none { it.id == id })
    }

    @Test
    fun `two customs never collide even with identical names`() = runTest {
        val first = repo.addCustomExercise(
            name = "Machine Row",
            pattern = MovementPattern.H_PULL,
            equipment = listOf(Equipment.MACHINE),
            perHand = false,
            goalStartLb = 90.0,
        )
        val second = repo.addCustomExercise(
            name = "Machine Row",
            pattern = MovementPattern.H_PULL,
            equipment = listOf(Equipment.MACHINE),
            perHand = false,
            goalStartLb = 90.0,
        )

        assertNotEquals(first, second)
        assertTrue(first.startsWith(ExerciseCatalog.CUSTOM_ID_PREFIX))
        assertTrue(second.startsWith(ExerciseCatalog.CUSTOM_ID_PREFIX))
    }

    @Test
    fun `a saved custom exercise is visible through the merged catalog`() = runTest {
        val id = repo.addCustomExercise(
            name = "Cable Hack Squat",
            pattern = MovementPattern.SQUAT_BILATERAL,
            equipment = listOf(Equipment.CABLE),
            perHand = true,
            goalStartLb = 65.0,
        )

        val catalog = repo.catalogFlow.first()
        val entry = catalog.get(id)
        assertEquals("Cable Hack Squat", entry.name)
        assertEquals(MovementPattern.SQUAT_BILATERAL, entry.pattern)
        assertTrue(entry.perHand)
        assertTrue(id in catalog.byPattern(MovementPattern.SQUAT_BILATERAL).map { it.id })
    }

    // --- tracking-type mapping (P4: custom-exercise form gains a type choice) --

    @Test
    fun `a REPS custom exercise stores its rep target and ignores the weight field`() = runTest {
        val id = repo.addCustomExercise(
            name = "Bodyweight Lunge",
            pattern = MovementPattern.SINGLE_LEG,
            equipment = listOf(Equipment.BODYWEIGHT),
            perHand = false,
            goalStartLb = 0.0,
            tracking = TrackingType.REPS,
            targetReps = 15,
        )

        val entry = repo.catalogFlow.first().get(id)
        assertEquals(TrackingType.REPS, entry.tracking)
        assertEquals(GoalSource.Reps(15), entry.goal)
    }

    @Test
    fun `a TIMED custom exercise stores its target seconds and optional added load`() = runTest {
        val id = repo.addCustomExercise(
            name = "Wall Sit",
            pattern = MovementPattern.SQUAT_BILATERAL,
            equipment = listOf(Equipment.BODYWEIGHT),
            perHand = false,
            goalStartLb = 10.0,
            tracking = TrackingType.TIMED,
            targetSeconds = 60,
        )

        val entry = repo.catalogFlow.first().get(id)
        assertEquals(TrackingType.TIMED, entry.tracking)
        assertEquals(GoalSource.Time(60, 10.0), entry.goal)
    }

    @Test
    fun `removing a custom exercise drops it from the catalog`() = runTest {
        val id = repo.addCustomExercise(
            name = "Temp Exercise",
            pattern = MovementPattern.BICEPS,
            equipment = emptyList(),
            perHand = false,
            goalStartLb = 20.0,
        )
        assertTrue(repo.catalogFlow.first().find(id) != null)

        repo.removeCustomExercise(id)

        assertEquals(null, repo.catalogFlow.first().find(id))
    }
}
