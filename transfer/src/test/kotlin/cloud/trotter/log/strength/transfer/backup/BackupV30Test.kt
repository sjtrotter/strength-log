package cloud.trotter.log.strength.transfer.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.LifterProfile
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupV30Test {
    private val codec = BackupCodec()
    private fun document() = BackupDocument(
        schemaVersion = 29,
        settings = SettingsBackup(200, 40, "ADVANCED", weightUnit = "LB", wizardComplete = true),
    )
    private fun snapshot(profile: LifterProfile) = document().toSnapshot().copy(profile = profile)

    @Test fun `a v30 document carries a partial profile`() {
        // PR 7c: keep this v30 import fixture at v30 while new exports use v31
        val document = snapshot(LifterProfile(180.0, null)).toDocument().copy(schemaVersion = 30)
        val encoded = codec.encode(document)
        val settings = Json.parseToJsonElement(encoded).jsonObject.getValue("settings").jsonObject
        assertEquals(JsonPrimitive(180), settings["bodyweightLb"])
        assertEquals(JsonPrimitive(180.0), settings["bodyweightLbPrecise"])
        assertEquals(JsonNull, settings["age"])
        assertFalse("level" in settings)
        assertFalse("hasLifterAnswers" in settings)
        val decoded = codec.decode(encoded)
        assertEquals(30, decoded.schemaVersion)
        assertEquals(LifterProfile(180.0, null), decoded.toSnapshot().profile)
    }

    @Test fun `an empty profile round trips`() {
        val encoded = codec.encode(snapshot(LifterProfile()).toDocument())
        val settings = Json.parseToJsonElement(encoded).jsonObject.getValue("settings").jsonObject
        assertEquals(JsonNull, settings["bodyweightLb"])
        assertEquals(JsonNull, settings["age"])
        assertEquals(LifterProfile(), codec.decode(encoded).toSnapshot().profile)
    }

    @Test fun `older documents that carry level still restore`() {
        val old = document()
        assertEquals(LifterProfile(200.0, 40), codec.decode(codec.encode(old)).toSnapshot().profile)
        val absent = old.copy(settings = old.settings.copy(hasLifterAnswers = false))
        assertEquals(LifterProfile(), codec.decode(codec.encode(absent)).toSnapshot().profile)
    }

    @Test fun `an older document without its numbers is inconsistent`() {
        val old = document()
        for (settings in listOf(old.settings.copy(age = null), old.settings.copy(bodyweightLb = null))) {
            val error = assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(old.copy(settings = settings)))
            }
            assertEquals("Inconsistent backup: bodyweightLb and age are required before schema v30", error.message)
        }
    }

    @Test fun `a restored device has no level key`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val cardioRunPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("BackupV30Test-cardio", ".preferences_pb")
        })
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v30-restore", ".preferences_pb")
            }
            val settings = SettingsStore(store)
            store.edit { it[stringPreferencesKey("level")] = "NOVICE" }
            val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v30-journal", ".preferences_pb")
            }, settings, cardioRunPrefs = cardioRunPrefs)
            val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
            val backup = BackupRepository(db, settings, repo.plans, repo.history, cardioRunPrefs = cardioRunPrefs)
            BackupService(backup, journal).import(codec.encode(document()))
            assertNull(store.data.first()[stringPreferencesKey("level")])
            assertEquals(LifterProfile(200.0, 40), settings.lifterProfileFlow.first())
        } finally {
            db.close()
            scope.cancel()
        }
    }
}
