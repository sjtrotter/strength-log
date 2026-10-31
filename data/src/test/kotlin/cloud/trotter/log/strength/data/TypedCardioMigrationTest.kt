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
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
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
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun seed(answers: WizardAnswers? = null) {
        db.planDao().insertPlan(PlanEntity(1, "Running", "GENERATED", 0, 1_000,
            recipeJson = answers?.let(PlanRecipeJson::encode), suggestedDayId = "A"))
        db.programDao().upsertDay(ProgramDayEntity(1, "A", 0, "Run", "", prose, nextRunId = "open-run"))
        settings.setSelectedPlanId(1)
    }

    @Test
    fun `fixup converts all prose atomically keeps existing blocks and is a no-op on retry`() = runTest {
        val answers = WizardAnswers(cardio = CardioPrefs(mode = CardioMode.TREADMILL, fiveKGoal = false))
        seed(answers)
        val expected = CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN)
        val stored = CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROWER)
        val original = requireNotNull(db.programDao().day(1, "A"))
        db.programDao().upsertDay(original.copy(dayId = "B", position = 1, cardioBlockJson = CardioBlockJson.encode(stored)))
        db.planDao().insertPlan(PlanEntity(2, "Disabled", "GENERATED", 1, 1_000,
            recipeJson = PlanRecipeJson.encode(WizardAnswers(cardio = CardioPrefs(mode = CardioMode.NONE)))))
        db.programDao().upsertDay(original.copy(planId = 2))
        db.programDao().upsertDay(original.copy(planId = 2, dayId = "B", cardioBlockJson = CardioBlockJson.encode(stored)))
        settings.removeLegacyPlanKeys()
        assertTrue(settings.planSettingsFixupDoneFlow.first())

        repo.runPlanSettingsFixupIfNeeded()

        assertEquals(original.copy(cardioJson = null, cardioBlockJson = CardioBlockJson.encode(expected)), db.programDao().day(1, "A"))
        assertEquals(CardioBlockJson.encode(stored), db.programDao().day(1, "B")!!.cardioBlockJson)
        assertNull(db.programDao().day(2, "A")!!.cardioBlockJson)
        assertEquals(CardioBlockJson.encode(stored), db.programDao().day(2, "B")!!.cardioBlockJson)
        assertTrue(db.programDao().daysWithLegacyCardio().isEmpty())
        val converted = db.programDao().allDays()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_cardio_retry BEFORE UPDATE ON program_day BEGIN SELECT RAISE(ABORT, 'unexpected rewrite'); END",
        )
        repo.runPlanSettingsFixupIfNeeded()
        assertEquals(converted, db.programDao().allDays())
    }

    @Test
    fun `stored blocks survive without a recipe with or without prose`() = runTest {
        seed()
        val stored = CardioBlockJson.encode(CardioBlocks.fromLegacy(suggestion, false, CardioActivity.ROWER))
        val day = requireNotNull(db.programDao().day(1, "A"))
        db.programDao().upsertDay(day.copy(cardioBlockJson = stored))
        db.programDao().upsertDay(day.copy(dayId = "B", cardioJson = null, cardioBlockJson = stored))

        repo.runPlanSettingsFixupIfNeeded()

        assertEquals(listOf(stored, stored), db.programDao().allDays().map { it.cardioBlockJson })
        assertTrue(db.programDao().allDays().all { it.cardioJson == null })
        assertNull(db.planDao().plan(1)!!.recipeJson)
    }

    @Test
    fun `programFor converts a recipe-less plan with outdoor fiveK defaults on its first collection`() = runTest {
        seed()
        settings.setGlobalLifter(GlobalLifter(bodyweightLb = 185, age = 52))
        val expected = CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROAD_RUN)

        assertEquals(expected, repo.programFor(1).first().days.single().cardioBlock)

        assertNull(db.planDao().plan(1)!!.recipeJson)
        assertNull(db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlockJson.encode(expected), db.programDao().day(1, "A")!!.cardioBlockJson)
    }

    @Test
    fun `programFlow waits for legacy settings to reach the recipe before converting on first collection`() = runTest {
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

        assertEquals(expected, fresh.programFlow.first().days.single().cardioBlock)

        assertEquals(CardioMode.TREADMILL, fresh.wizardAnswersFor(1).first().cardio.mode)
        assertNull(db.programDao().day(1, "A")!!.cardioJson)
    }

    @Test
    fun `restore marker skips both settings and cardio fixups`() = runTest {
        seed()
        val original = db.programDao().allDays()
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "pending"))

        repo.runPlanSettingsFixupIfNeeded()

        assertEquals(original, db.programDao().allDays())
        assertFalse(settings.planSettingsFixupDoneFlow.first())
        assertNull(db.planDao().plan(1)!!.recipeJson)
        db.restoreMarkerDao().clear()
        repo.runPlanSettingsFixupIfNeeded()
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

        assertTrue(runCatching { repo.runPlanSettingsFixupIfNeeded() }.isFailure)

        assertEquals(before, db.programDao().allDays())
    }

    @Test
    fun `export called first waits for cardio conversion`() = runTest {
        seed()

        val snapshot = repo.exportSnapshot()

        assertNull(snapshot.days.single().cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROAD_RUN),
            CardioBlockJson.decode(snapshot.days.single().cardioBlockJson))
    }

    @Test
    fun `restore converts prose again after the cardio gate was already completed`() = runTest {
        val answers = WizardAnswers(cardio = CardioPrefs(mode = CardioMode.TREADMILL, fiveKGoal = false))
        seed(answers)
        val legacyDay = requireNotNull(db.programDao().day(1, "A"))
        val snapshot = repo.exportSnapshot().copy(days = listOf(legacyDay))
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio-journal", ".preferences_pb")
        }, settings)

        repo.importSnapshot(snapshot, journal)

        assertNull(db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN),
            repo.programFor(1).first().days.single().cardioBlock)
    }

    @Test
    fun `reconcile converts restored prose after the cardio gate was already completed`() = runTest {
        seed()
        val legacyDay = requireNotNull(db.programDao().day(1, "A"))
        val snapshot = repo.exportSnapshot().copy(days = listOf(legacyDay))
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio-reconcile-journal", ".preferences_pb")
        }, settings)
        journal.stage(snapshot, "restored-prose")
        db.withTransaction {
            db.programDao().upsertDay(legacyDay)
            db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "restored-prose"))
        }

        assertTrue(repo.reconcilePendingRestore(journal))

        assertNull(db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, true, CardioActivity.ROAD_RUN),
            repo.programFor(1).first().days.single().cardioBlock)
    }

    @Test
    fun `post restore conversion settings read failure reports cleanup pending and next reader retries`() = runTest {
        seed(WizardAnswers(cardio = CardioPrefs(mode = CardioMode.TREADMILL, fiveKGoal = false)))
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
        val exported = failingRepo.exportSnapshot()
        val snapshot = exported.copy(days = listOf(legacyDay), answers = exported.answers.copy(
            config = exported.answers.config.copy(bodyweightLb = 190, age = 28),
        ))
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("typed-cardio-failure-journal", ".preferences_pb")
        }, failingSettings)
        armOnRestore = true

        val failure = runCatching { failingRepo.importSnapshot(snapshot, journal) }.exceptionOrNull()

        assertTrue(failure is RestoreInterruption.CleanupPending)
        assertEquals(readFailure, failure?.cause)
        assertNull(db.restoreMarkerDao().nonce())
        assertEquals(GlobalLifter.of(snapshot.answers.config), settings.globalLifterFlow.first())
        assertEquals(prose, db.programDao().day(1, "A")!!.cardioJson)
        assertEquals(CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN),
            failingRepo.programFor(1).first().days.single().cardioBlock)
        assertNull(db.programDao().day(1, "A")!!.cardioJson)
    }

    @Test
    fun `reset C1 regenerates its block core emphasis and kind from the owning recipe`() = runTest {
        val answers = WizardAnswers(cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS))
        repo.setWizardAnswers(answers)
        val program = ProgramGenerator.generate(answers)
        repo.replaceProgram(program)
        val planId = requireNotNull(settings.selectedPlanIdFlow.first())
        val expected = program.cardioDays.first()
        assertTrue(repo.setCardioBlock(planId, "C1", expected.cardioBlock!!.copy(activity = CardioActivity.ROWER)))

        repo.resetDayToTemplate("C1")

        assertEquals(expected, repo.programFor(planId).first().cardioDays.first())
        assertEquals("A", repo.suggestedDayFlow.first())
    }
}
