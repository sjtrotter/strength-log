package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
class TypedCardioMigrationTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var store: DataStore<Preferences>
    private lateinit var backupRepository: BackupRepository
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val suggestion = CardioSuggestion("Intervals", "Run hard, recover easy.", true)
    private val prose = requireNotNull(CardioDto.encode(suggestion))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio", ".preferences_pb")
        }
        settings = SettingsStore(store)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        backupRepository = BackupRepository(db, settings, repo.plans, repo.history)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun seed(recipe: String? = null) {
        db.planDao().insertPlan(PlanEntity(1, "Running", "GENERATED", 0, 1_000,
            recipeJson = recipe, suggestedDayId = "A"))
        db.programDao().upsertDay(ProgramDayEntity(1, "A", 0, "Run", "", prose, nextRunId = "open-run"))
        settings.setSelectedPlanId(1)
    }

    @Test
    fun `fixup converts all prose atomically keeps existing blocks and is a no-op on retry`() = runTest {
        val answers = v1RecipeJson(cardioMode = "TREADMILL", fiveKGoal = false)
        seed(answers)
        val expected = CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN)
        val stored = CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROWER)
        val original = requireNotNull(db.programDao().day(1, "A"))
        db.programDao().upsertDay(original.copy(dayId = "B", position = 1, cardioBlockJson = CardioBlockJson.encode(stored)))
        db.planDao().insertPlan(PlanEntity(2, "Disabled", "GENERATED", 1, 1_000,
            recipeJson = v1RecipeJson(cardioMode = "NONE")))
        db.programDao().upsertDay(original.copy(planId = 2))
        db.programDao().upsertDay(original.copy(planId = 2, dayId = "B", cardioBlockJson = CardioBlockJson.encode(stored)))
        settings.removeLegacyPlanKeys()
        assertTrue(settings.planSettingsFixupDoneFlow.first())

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(original.copy(cardioJson = null, cardioBlockJson = CardioBlockJson.encode(expected)), db.programDao().day(1, "A"))
        assertEquals(CardioBlockJson.encode(stored), db.programDao().day(1, "B")!!.cardioBlockJson)
        assertNull(db.programDao().day(2, "A")!!.cardioBlockJson)
        assertEquals(CardioBlockJson.encode(stored), db.programDao().day(2, "B")!!.cardioBlockJson)
        assertTrue(db.programDao().daysWithLegacyCardio().isEmpty())
        val converted = db.programDao().allDays()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_cardio_retry BEFORE UPDATE ON program_day BEGIN SELECT RAISE(ABORT, 'unexpected rewrite'); END",
        )
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(converted, db.programDao().allDays())
    }

    @Test
    fun `stored blocks survive without a recipe with or without prose`() = runTest {
        seed()
        val stored = CardioBlockJson.encode(CardioBlocks.fromLegacy(suggestion, false, CardioActivity.ROWER))
        val day = requireNotNull(db.programDao().day(1, "A"))
        db.programDao().upsertDay(day.copy(cardioBlockJson = stored))
        db.programDao().upsertDay(day.copy(dayId = "B", cardioJson = null, cardioBlockJson = stored))

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(listOf(stored, stored), db.programDao().allDays().map { it.cardioBlockJson })
        assertTrue(db.programDao().allDays().all { it.cardioJson == null })
        assertNull(db.planDao().plan(1)!!.recipeJson)
    }

    @Test
    fun `programFor converts a recipe-less plan with outdoor fiveK defaults on its first collection`() = runTest {
        seed()
        settings.setLifterProfile(LifterProfile(bodyweightLb = 185.0, age = 52))
        val expected = CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROAD_RUN)

        assertEquals(expected, repo.plans.programFor(1).first().days.single().cardioBlock)

        assertNull(db.planDao().plan(1)!!.recipeJson)
        assertNull(db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlockJson.encode(expected), db.programDao().day(1, "A")!!.cardioBlockJson)
    }

    @Test
    fun `programFlow waits for legacy settings before converting on first collection`() = runTest {
        seed()
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio-legacy-settings", ".preferences_pb")
        }
        store.edit {
            it[stringPreferencesKey("cardio_mode")] = "TREADMILL"
            it[booleanPreferencesKey("five_k")] = false
        }
        val legacySettings = SettingsStore(store)
        legacySettings.setSelectedPlanId(1)
        val fresh = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), legacySettings)
        val expected = CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN)

        assertEquals(expected, fresh.plans.programFlow.first().days.single().cardioBlock)

        assertNull(db.planDao().plan(1)!!.recipeJson)
        assertNull(db.programDao().day(1, "A")!!.cardioJson)
    }

    @Test
    fun `restore marker skips both settings and cardio fixups`() = runTest {
        seed()
        val original = db.programDao().allDays()
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "pending"))

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(original, db.programDao().allDays())
        assertFalse(settings.planSettingsFixupDoneFlow.first())
        assertNull(db.planDao().plan(1)!!.recipeJson)
        db.restoreMarkerDao().clear()
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertTrue(db.programDao().daysWithLegacyCardio().isEmpty())
    }

    @Test
    fun `failed conversion write rolls back every day`() = runTest {
        seed()
        val original = requireNotNull(db.programDao().day(1, "A"))
        db.programDao().upsertDay(original.copy(dayId = "B", position = 1))
        val before = db.programDao().allDays()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_cardio BEFORE UPDATE ON program_day WHEN NEW.dayId = 'B' BEGIN SELECT RAISE(ABORT, 'cardio failed'); END",
        )

        assertTrue(runCatching { repo.plans.runPlanSettingsFixupIfNeeded() }.isFailure)

        assertEquals(before, db.programDao().allDays())
    }

    @Test
    fun `export called first waits for cardio conversion`() = runTest {
        seed()

        val snapshot = backupRepository.exportSnapshot()

        assertNull(snapshot.days.single().cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROAD_RUN),
            CardioBlockJson.decode(snapshot.days.single().cardioBlockJson))
    }

    @Test
    fun `restore converts prose again after the cardio gate was already completed`() = runTest {
        val answers = v1RecipeJson(cardioMode = "TREADMILL", fiveKGoal = false)
        seed(answers)
        val legacyDay = requireNotNull(db.programDao().day(1, "A"))
        val snapshot = backupRepository.exportSnapshot().let { snapshot -> snapshot.copy(days = listOf(legacyDay),
            plans = snapshot.plans.map { it.copy(recipeJson = answers) }) }
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio-journal", ".preferences_pb")
        }, settings)
        assertFalse(snapshot.profile.bodyweightLb != null)

        backupRepository.importSnapshot(snapshot, journal)

        assertFalse(settings.lifterProfileFlow.first().bodyweightLb != null)
        assertNull(db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN),
            repo.plans.programFor(1).first().days.single().cardioBlock)
    }

    @Test
    fun `reconcile converts restored prose after the cardio gate was already completed`() = runTest {
        seed()
        val legacyDay = requireNotNull(db.programDao().day(1, "A"))
        val snapshot = backupRepository.exportSnapshot().copy(days = listOf(legacyDay))
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio-reconcile-journal", ".preferences_pb")
        }, settings)
        journal.stage(snapshot, "restored-prose")
        db.withTransaction {
            db.programDao().upsertDay(legacyDay)
            db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "restored-prose"))
        }

        assertTrue(backupRepository.reconcilePendingRestore(journal))

        assertNull(db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROAD_RUN),
            repo.plans.programFor(1).first().days.single().cardioBlock)
    }

    @Test
    fun `post restore conversion settings read failure reports cleanup pending and next reader retries`() = runTest {
        seed(v1RecipeJson(cardioMode = "TREADMILL", fiveKGoal = false))
        val legacyDay = requireNotNull(db.programDao().day(1, "A"))
        var armOnRestore = false
        var failConversionRead = false
        val readFailure = IOException("conversion settings read failed")
        val failingSettings = SettingsStore(object : DataStore<Preferences> {
            override val data = store.data.map { prefs ->
                if (failConversionRead && prefs[booleanPreferencesKey("plan_settings_fixup_done")] == true) {
                    failConversionRead = false
                    throw readFailure
                }
                prefs
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                store.updateData(transform).also {
                    if (armOnRestore) {
                        armOnRestore = false
                        failConversionRead = true
                    }
                }
        })
        val failingRepo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), failingSettings)
        val failingBackup = BackupRepository(db, failingSettings, failingRepo.plans, failingRepo.history)
        val exported = failingBackup.exportSnapshot()
        assertFalse(exported.profile.bodyweightLb != null)
        val snapshot = exported.copy(
            days = listOf(legacyDay),
            plans = exported.plans.map { it.copy(recipeJson = v1RecipeJson(cardioMode = "TREADMILL", fiveKGoal = false)) },
            profile = exported.profile.copy(bodyweightLb = 190.0, age = 28),
        )
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio-failure-journal", ".preferences_pb")
        }, failingSettings)
        armOnRestore = true

        val failure = runCatching { failingBackup.importSnapshot(snapshot, journal) }.exceptionOrNull()

        assertTrue(failure is RestoreInterruption.CleanupPending)
        // Coroutine stack-trace recovery may copy the exception, nesting the original under it.
        assertTrue(generateSequence(failure) { it.cause }.any { it === readFailure })
        assertNull(db.restoreMarkerDao().nonce())
        assertTrue(settings.lifterProfileFlow.first().bodyweightLb != null)
        assertEquals(snapshot.profile, settings.lifterProfileFlow.first())
        assertEquals(prose, db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN),
            failingRepo.plans.programFor(1).first().days.single().cardioBlock)
        assertNull(db.programDao().day(1, "A")!!.cardioJson)
    }


}
