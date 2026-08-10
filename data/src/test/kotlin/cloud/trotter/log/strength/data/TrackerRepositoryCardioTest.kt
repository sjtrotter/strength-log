package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryCardioTest {
    @Test fun `unknown mode stays unknown for typed consumers`() {
        assertNull(cardio("Future", 2_000).copy(mode = "FUTURE_MODE").decodedCardioMode())
    }

    @Test fun `cardio write commits and flow reads newest first`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-repo", ".preferences_pb")
        })
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        try {
            repo.logCardioSession(cardio("Easy Zone 2", 2_000))
            val id = repo.logCardioSession(cardio("Tempo", 3_000))
            repo.logCardioSession(cardio("Later id at same time", 3_000))
            assertEquals(
                listOf("Later id at same time", "Tempo", "Easy Zone 2"),
                repo.cardioSessionsFlow.first().map { it.label },
            )
            assertEquals("Tempo", repo.cardioSession(id)?.label)
        } finally { db.close(); scope.cancel() }
    }

    private fun cardio(label: String, completedAt: Long) = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = label,
        startedAt = completedAt - 60_000, completedAt = completedAt, seconds = 60, stepsCompleted = 1,
    )
}
