package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.StandardLift
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
class PlanSettingsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var store: FlakyDataStore
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var backupRepository: BackupRepository
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("plan-settings", ".preferences_pb")
            },
        )
        settings = SettingsStore(store)
        journal = RestoreJournal(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("plan-settings-journal", ".preferences_pb")
            },
            settings,
        )
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        backupRepository = BackupRepository(db, settings, repo.plans, repo.history)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `membership fixup rechecks the marker after an import commits Room`() = runTest {
        db.planDao().insertPlan(plan(1))
        settings.setPlanSettingsFixupDone()
        val readReached = CompletableDeferred<Unit>()
        val readOpen = CompletableDeferred<Unit>()
        var pauseNextRead = true
        val gatedSettings = SettingsStore(object : DataStore<Preferences> {
            override val data = store.data.onEach {
                if (pauseNextRead) {
                    pauseNextRead = false
                    readReached.complete(Unit)
                    readOpen.await()
                }
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                store.updateData(transform)
        })
        val repository = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), gatedSettings)
        val importingBackup = BackupRepository(db, gatedSettings, repository.plans, repository.history)
        val snapshot = FullSnapshot(
            answers = WizardAnswers(), unit = WeightUnit.LB, wizardComplete = true,
            suggestedDay = null, selectedPlanId = 2, restSettings = RestSettings(),
            plans = listOf(plan(2)), answersCarryRecipe = false,
            customExercises = emptyList(), days = emptyList(), exercises = emptyList(),
            logs = emptyList(), sessions = emptyList(), sessionSets = emptyList(),
        )
        val writeOpen = CompletableDeferred<Unit>()
        store.gateOnUpdate = 2
        store.gate = writeOpen
        val fixup = launch { repository.plans.runPlanSettingsFixupIfNeeded() }
        readReached.await()
        val importing = launch { importingBackup.importSnapshot(snapshot, journal) }
        try {
            store.gateReached.await()
            assertTrue(db.restoreMarkerDao().nonce() != null)
            readOpen.complete(Unit)
            fixup.join()
            assertEquals(snapshot.plans, db.planDao().allPlans())
            assertFalse(settings.onTodayFixupDoneFlow.first())
        } finally {
            readOpen.complete(Unit)
            writeOpen.complete(Unit)
        }
        importing.join()
        assertEquals(snapshot.plans, db.planDao().allPlans())
        assertTrue(settings.onTodayFixupDoneFlow.first())
    }

    @Test
    fun `fixup carries legacy facts once and leaves authored and existing recipes alone`() = runTest {
        val legacy = strengthAnswers()
        val existing = plan(4, physiqueAnswers(), "D")
        val authored = plan(2).copy(origin = PlanOrigin.AUTHORED.name)
        db.planDao().insertPlans(listOf(plan(1), authored, plan(3), existing))
        writeLegacy(legacy, "B")
        val before = repo.plans.configFlow.first()

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(legacy, answersFor(1))
        assertEquals(legacy, answersFor(3))
        assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        assertNull(db.planDao().plan(3)!!.suggestedDayId)
        assertEquals(authored, db.planDao().plan(2))
        assertEquals(existing, db.planDao().plan(4))
        assertEquals(before, repo.plans.configFlow.first())
        assertLegacyKeysGone()
        assertTrue(settings.planSettingsFixupDoneFlow.first())
        val plans = db.planDao().allPlans()
        val prefs = store.data.first()

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(plans, db.planDao().allPlans())
        assertEquals(prefs, store.data.first())
    }

    @Test
    fun `fixup retries after Room commit without overwriting written recipes or position`() = runTest {
        db.planDao().insertPlan(plan(1))
        writeLegacy(strengthAnswers(), "B")
        store.failOnUpdate = 2

        val failure = runCatching { repo.plans.runPlanSettingsFixupIfNeeded() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(strengthAnswers(), answersFor(1))
        assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        assertFalse(settings.planSettingsFixupDoneFlow.first())
        assertEquals(strengthAnswers(), settings.legacyAnswersFlow.first())
        db.planDao().updateRecipe(1, PlanRecipeJson.encode(physiqueAnswers()))
        db.planDao().updateSuggestedDay(1, "C")

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(physiqueAnswers(), answersFor(1))
        assertEquals("C", repo.plans.suggestedDayFlow.first())
        assertLegacyKeysGone()
        assertTrue(settings.planSettingsFixupDoneFlow.first())
    }

    @Test
    fun `empty legacy store is retired without manufacturing recipes`() = runTest {
        db.planDao().insertPlan(plan(1))

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertNull(db.planDao().plan(1)!!.recipeJson)
        assertEquals(WizardAnswers(), repo.plans.wizardAnswersFlow.first())
        assertTrue(settings.planSettingsFixupDoneFlow.first())
    }

    @Test
    fun `edit before startup fixup preserves the new emphasis`() = runTest {
        db.planDao().insertPlan(plan(1))
        writeLegacy(strengthAnswers(), "B")

        repo.plans.setPlanEmphasis(1, GoalEmphasis.PHYSIQUE)

        assertEquals(GoalEmphasis.PHYSIQUE, repo.plans.configFlow.first().emphasis)
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(GoalEmphasis.PHYSIQUE, repo.plans.configFlow.first().emphasis)
        assertEquals(GoalEmphasis.PHYSIQUE, answersFor(1).config.emphasis)
    }

    @Test
    fun `live config collector never emits defaults across the migration gate`() = runTest {
        db.planDao().insertPlan(plan(1))
        writeLegacy(strengthAnswers(), "B")
        val open = CompletableDeferred<Unit>()
        store.gateOnUpdate = 2
        store.gate = open
        val emissions = mutableListOf<GoalEmphasis>()
        val observed = Channel<GoalEmphasis>(Channel.UNLIMITED)
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.plans.configFlow.collect {
                emissions += it.emphasis
                observed.send(it.emphasis)
            }
        }
        val startup = launch { repo.plans.runPlanSettingsFixupIfNeeded() }
        store.gateReached.await()
        assertTrue(emissions.isEmpty())

        open.complete(Unit)

        assertEquals(GoalEmphasis.STRENGTH, observed.receive())
        startup.join()
        // A later lifter edit makes the same live collector emit again after
        // key retirement, rather than stopping at its first value.
        repo.plans.updateLifter { it.copy(age = 41) }
        observed.receive()
        collector.cancelAndJoin()
        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.all { it == GoalEmphasis.STRENGTH })
    }

    @Test
    fun `first wizard write seeds a recipe for either origin`() = runTest {
        db.planDao().insertPlans(listOf(plan(1), plan(2).copy(origin = PlanOrigin.AUTHORED.name)))
        val global = GlobalLifter(bodyweightLb = 205.0, age = 41, level = ExperienceLevel.ADVANCED)
        settings.setGlobalLifter(global)
        val prefs = CardioPrefs(CardioMode.LOW_IMPACT, CardioPlacement.BOTH, false)

        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first().copy(cardio = prefs))

        assertEquals(WizardAnswers(config = global.toConfig(), cardio = prefs), answersFor(1))
        settings.setSelectedPlanId(2)
        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first().copy(cardio = prefs))
        assertEquals(WizardAnswers(config = global.toConfig(), cardio = prefs), answersFor(2))
        assertEquals(WizardAnswers(config = global.toConfig(), cardio = prefs), repo.plans.wizardAnswersFlow.first())
    }

    @Test
    fun `selection switches every plan setting including a live config collector`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one, "B"), plan(2, two, "D")))
        val emissions = Channel<LifterConfig>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.plans.configFlow.collect { emissions.send(it) }
        }
        assertEquals(one.config, emissions.receive())
        assertSelected(one, "B")

        settings.setSelectedPlanId(2)

        assertEquals(two.config, emissions.receive())
        assertSelected(two, "D")
        collector.cancelAndJoin()
    }

    @Test
    fun `lifter edits are shared while emphasis updates affect only the selected plan`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one), plan(2, two)))
        val changed = one.config.copy(bodyweightLb = 210.0, age = 31, level = ExperienceLevel.ADVANCED, emphasis = GoalEmphasis.BALANCED)

        repo.plans.updateLifter { it.copy(bodyweightLb = changed.bodyweightLb, age = changed.age, level = changed.level) }
        repo.plans.setPlanEmphasis(repo.plans.selectedPlanFlow.first()!!.id, changed.emphasis)

        assertEquals(changed, repo.plans.configFlow.first())
        assertEquals(one.copy(config = changed), repo.plans.wizardAnswersFlow.first())
        settings.setSelectedPlanId(2)
        assertEquals(changed.copy(emphasis = GoalEmphasis.PHYSIQUE), repo.plans.configFlow.first())
        assertEquals(PlanRecipeJson.encode(two), db.planDao().plan(2)!!.recipeJson)
        assertLegacyKeysGone()
    }

    @Test
    fun `lifter edit preserves the latest emphasis and leaves the recipe byte identical`() = runTest {
        db.planDao().insertPlan(plan(1, WizardAnswers()))
        settings.setSelectedPlanId(1)
        assertEquals(GoalEmphasis.BALANCED, repo.plans.configFlow.first().emphasis)
        repo.plans.setPlanEmphasis(1, GoalEmphasis.STRENGTH)
        val recipeBefore = db.planDao().plan(1)!!.recipeJson!!

        repo.plans.updateLifter { it.copy(bodyweightLb = 205.0) }

        assertEquals(GoalEmphasis.STRENGTH, repo.plans.wizardAnswersFor(1).first().config.emphasis)
        assertEquals(205.0, repo.plans.configFlow.first().bodyweightLb, 0.0)
        assertEquals(recipeBefore, db.planDao().plan(1)!!.recipeJson)
    }

    @Test
    fun `wizard writes update the selected recipe for either origin`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one), plan(2, two), plan(3).copy(origin = PlanOrigin.AUTHORED.name)))
        val prefs = CardioPrefs(CardioMode.LOW_IMPACT, CardioPlacement.BOTH, false)

        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first().copy(cardio = prefs))

        assertEquals(one.copy(cardio = prefs), repo.plans.wizardAnswersFlow.first())
        assertEquals(PlanRecipeJson.encode(two), db.planDao().plan(2)!!.recipeJson)
        settings.setSelectedPlanId(3)
        repo.plans.updateLifter { it.copy(bodyweightLb = 205.0) }
        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first().copy(cardio = prefs))
        repo.plans.setPlanEmphasis(3, one.config.emphasis)
        assertEquals(one.config.copy(bodyweightLb = 205.0), repo.plans.configFlow.first())
        assertEquals(prefs, answersFor(3).cardio)
        repo.plans.setWizardAnswers(two.copy(config = two.config.copy(bodyweightLb = 215.0)))
        assertEquals(two.copy(config = two.config.copy(bodyweightLb = 215.0)), answersFor(3))
        assertEquals(two.copy(config = two.config.copy(bodyweightLb = 215.0)), repo.plans.wizardAnswersFlow.first())
        settings.setSelectedPlanId(2)
        repo.plans.setWizardAnswers(two.copy(config = two.config.copy(bodyweightLb = 215.0)))
        assertEquals(two.copy(config = two.config.copy(bodyweightLb = 215.0)), repo.plans.wizardAnswersFlow.first())
        assertLegacyKeysGone()
    }

    @Test
    fun `authored emphasis sticks in a seeded recipe and changes standard lift goals`() = runTest {
        val plan = repo.plans.createAuthoredPlan("Strength", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val original = repo.plans.configFlow.first()
        assertEquals(settings.globalLifterFlow.first().toConfig(), original)
        val entry = repo.catalog.catalogFlow.first().get("bb_back_squat")
        val before = GoalCalculator.targetFor(entry, original)
        val changed = original.copy(emphasis = GoalEmphasis.STRENGTH)
        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first())
        repo.plans.setPlanEmphasis(plan, changed.emphasis)
        assertEquals(changed, repo.plans.configFlow.first())
        assertEquals(changed, repo.plans.wizardAnswersFor(plan).first().config)
        assertEquals(changed, answersFor(plan).config)
        assertFalse(before == GoalCalculator.targetFor(entry, repo.plans.configFlow.first()))
        val other = repo.plans.createAuthoredPlan("Other", "Day A")
        repo.plans.publishCreatedAuthoredPlan(other)
        assertEquals(original, repo.plans.configFlow.first())
        repo.plans.selectPlan(plan)
        assertEquals(changed, repo.plans.configFlow.first())
    }

    @Test
    fun `wizard can persist a recipe before the first program exists`() = runTest {
        repo.plans.setWizardAnswers(strengthAnswers())

        assertEquals(strengthAnswers(), repo.plans.wizardAnswersFlow.first())
        assertEquals(PlanOrigin.GENERATED.name, db.planDao().allPlans().single().origin)
        assertEquals(db.planDao().allPlans().single().id, settings.selectedPlanIdFlow.first())
        assertLegacyKeysGone()
    }

    @Test
    fun `advance moves only the selected rotation inside the session transaction`() = runTest {
        db.planDao().insertPlans(listOf(plan(1, strengthAnswers(), "A"), plan(2, physiqueAnswers(), "C")))
        db.programDao().upsertDays(listOf(day(1, "A", 0), day(1, "B", 1)))
        db.programDao().upsertDay(db.programDao().day(1, "A")!!.copy(nextRunId = "run-A"))
        db.runDao().insertRun(cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity(
            "run-A", 1, "A", "Day A", "My plan", 1_000))

        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        assertEquals("C", db.planDao().plan(2)!!.suggestedDayId)
        assertEquals(1L, db.sessionDao().sessionById(sessionId)!!.planId)
        assertEquals(1_000L, db.sessionDao().sessionById(sessionId)!!.startedAt)
        assertNull(repo.openRunFlow.first())
        assertLegacyKeysGone()
    }

    @Test
    fun `failed rotation write rolls back the session and keeps the open run`() = runTest {
        db.planDao().insertPlan(plan(1, strengthAnswers(), "A"))
        db.programDao().upsertDays(listOf(day(1, "A", 0), day(1, "B", 1)))
        db.programDao().upsertDay(db.programDao().day(1, "A")!!.copy(nextRunId = "run-A"))
        db.runDao().insertRun(cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity(
            "run-A", 1, "A", "Day A", "My plan", 1_000))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_rotation BEFORE UPDATE OF suggestedDayId ON plan BEGIN SELECT RAISE(ABORT, 'rotation failed'); END",
        )

        assertTrue(runCatching { repo.finishRun(repo.openRunFlow.first()!!.id)!! }.isFailure)

        assertTrue(db.sessionDao().allSessions().isEmpty())
        assertEquals("A", repo.plans.suggestedDayFlow.first())
        assertEquals(1_000L, repo.openRunFlow.first()!!.startedAt)
    }

    @Test
    fun `GOAL config is identical before and after migration for default and custom answers`() = runTest {
        db.planDao().insertPlan(plan(1))
        val custom = strengthAnswers().copy(
            config = LifterConfig(bodyweightLb = 210, age = 56, level = ExperienceLevel.ADVANCED, emphasis = GoalEmphasis.STRENGTH),
        )
        for (answers in listOf(WizardAnswers(), custom)) {
            writeLegacy(answers, "B")
            val oldConfig = settings.legacyAnswersFlow.first()!!.config
            val expected = GoalCalculator.goalForMain(StandardLift.SQUAT, false, oldConfig)
            // Recreate the pre-fixup plan and flag for each legacy fixture.
            db.planDao().deleteAllPlans()
            db.planDao().insertPlan(plan(1))
            store.edit { it.remove(booleanPreferencesKey("plan_settings_fixup_done")) }
            assertEquals(oldConfig, repo.plans.configFlow.first())

            repo.plans.runPlanSettingsFixupIfNeeded()

            assertEquals(oldConfig, repo.plans.configFlow.first())
            assertEquals(expected, GoalCalculator.goalForMain(StandardLift.SQUAT, false, repo.plans.configFlow.first()), 0.0)
            if (answers == WizardAnswers()) assertEquals(235.0, expected, 0.0)
        }
    }

    @Test
    fun `snapshot keeps distinct recipes and rotation and legacy snapshots spread settings`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one, "B"), plan(2, two, "D")))
        db.programDao().upsertDays(listOf(
            day(1, "B", 0).copy(nextRunId = "plan-1-B"),
            day(2, "D", 0).copy(nextRunId = "plan-2-D"),
        ))
        settings.setSelectedPlanId(2)
        val snapshot = backupRepository.exportSnapshot()
        assertEquals(two, snapshot.answers)
        assertEquals("D", snapshot.suggestedDay)
        writeLegacy(one, "old")
        settings.enableAutoBackup("content://tree/folder", "Backups")
        settings.recordAutoBackupSuccess(1_234)
        val autoBackup = settings.autoBackupSettingsFlow.first()

        backupRepository.importSnapshot(snapshot, journal)

        assertEquals(snapshot, backupRepository.exportSnapshot())
        assertEquals(autoBackup, settings.autoBackupSettingsFlow.first())
        assertLegacyKeysGone()
        val authored = plan(3).copy(origin = PlanOrigin.AUTHORED.name)
        val legacy = snapshot.copy(
            answers = one,
            plans = snapshot.plans.map { it.copy(recipeJson = null, suggestedDayId = null) } + authored,
            suggestedDay = "B",
            answersCarryRecipe = true,
        )
        backupRepository.importSnapshot(legacy, journal)

        assertEquals(one, answersFor(1))
        assertEquals(one, answersFor(2))
        assertEquals(authored, db.planDao().plan(3))
        assertEquals("B", db.planDao().plan(2)!!.suggestedDayId)
        assertNull(db.planDao().plan(1)!!.suggestedDayId)
        assertLegacyKeysGone()
        backupRepository.importSnapshot(legacy.copy(plans = emptyList(), selectedPlanId = null, days = listOf(day(1, "A", 0))), journal)
        assertEquals(one, answersFor(PlanEntity.LEGACY_ID))
        assertEquals("B", repo.plans.suggestedDayFlow.first())
    }

    @Test
    fun `mid first run backup gives answers an empty plan that the wizard can fill`() = runTest {
        val answers = strengthAnswers()
        val snapshot = FullSnapshot(
            answers = answers,
            unit = WeightUnit.LB,
            wizardComplete = false,
            suggestedDay = null,
            restSettings = RestSettings(),
            customExercises = emptyList(),
            days = emptyList(),
            exercises = emptyList(),
            logs = emptyList(),
            sessions = emptyList(),
            sessionSets = emptyList(),
        )

        backupRepository.importSnapshot(snapshot, journal)

        assertEquals(PlanEntity.LEGACY_ID, db.planDao().allPlans().single().id)
        assertEquals(answers, answersFor(PlanEntity.LEGACY_ID))
        assertEquals(answers, repo.plans.wizardAnswersFlow.first())
        assertTrue(repo.plans.programFlow.first().days.isEmpty())
        assertFalse(settings.wizardCompleteFlow.first())
        val generated = ProgramGenerator.generate(answers)
        repo.replaceProgram(generated)
        assertEquals(1, db.planDao().allPlans().size)
        assertEquals(generated, repo.plans.programFlow.first())
        assertEquals(answers, repo.plans.wizardAnswersFlow.first())
    }

    @Test
    fun `restore retires the legacy store before returning`() = runTest {
        db.planDao().insertPlan(plan(1, strengthAnswers(), "B"))
        repo.plans.runPlanSettingsFixupIfNeeded()
        val snapshot = backupRepository.exportSnapshot()
        assertTrue(settings.planSettingsFixupDoneFlow.first())

        backupRepository.importSnapshot(snapshot, journal)

        assertTrue(settings.planSettingsFixupDoneFlow.first())
        assertLegacyKeysGone()
        assertEquals(strengthAnswers(), repo.plans.wizardAnswersFlow.first())
        assertEquals(snapshot.plans, db.planDao().allPlans())
    }

    @Test
    fun `setPlanEmphasis changes only the viewed recipe and preserves global lifter`() = runTest {
        db.planDao().insertPlans(listOf(plan(1, WizardAnswers()), plan(2, WizardAnswers())))
        settings.setSelectedPlanId(1)
        val global = GlobalLifter(210.0, 42, ExperienceLevel.ADVANCED)
        settings.setGlobalLifter(global)
        repo.plans.runPlanSettingsFixupIfNeeded()
        val one = db.planDao().plan(1)
        val two = db.planDao().plan(2)!!
        val prefs = store.data.first()

        repo.plans.setPlanEmphasis(2, GoalEmphasis.STRENGTH)

        assertEquals(GoalEmphasis.STRENGTH, repo.plans.wizardAnswersFor(2).first().config.emphasis)
        assertEquals(GoalEmphasis.BALANCED, repo.plans.wizardAnswersFor(1).first().config.emphasis)
        assertEquals(one, db.planDao().plan(1))
        assertEquals(two.copy(recipeJson = PlanRecipeJson.encode(WizardAnswers(
            config = global.toConfig().copy(emphasis = GoalEmphasis.STRENGTH),
        ))), db.planDao().plan(2))
        assertEquals(global, settings.globalLifterFlow.first())
        assertEquals(prefs, store.data.first())
    }

    @Test
    fun `setPlanEmphasis ignores missing plans and null recipes`() = runTest {
        db.planDao().insertPlan(plan(1, WizardAnswers()))
        repo.plans.runPlanSettingsFixupIfNeeded()
        val before = db.planDao().allPlans()
        val prefs = store.data.first()

        repo.plans.setPlanEmphasis(3, GoalEmphasis.STRENGTH)

        assertEquals(before, db.planDao().allPlans())
        val authored = plan(3).copy(origin = PlanOrigin.AUTHORED.name)
        db.planDao().insertPlan(authored)
        repo.plans.setPlanEmphasis(3, GoalEmphasis.STRENGTH)

        assertEquals(before + authored, db.planDao().allPlans())
        assertEquals(prefs, store.data.first())
    }

    private suspend fun assertSelected(answers: WizardAnswers, suggestedDay: String) {
        assertEquals(answers.config, repo.plans.configFlow.first())
        assertEquals(answers.cardio, repo.plans.wizardAnswersFlow.first().cardio)
        assertEquals(answers, repo.plans.wizardAnswersFlow.first())
        assertEquals(suggestedDay, repo.plans.suggestedDayFlow.first())
    }

    private suspend fun answersFor(id: Long): WizardAnswers =
        PlanRecipeJson.decode(db.planDao().plan(id)!!.recipeJson!!, settings.globalLifterFlow.first())

    private suspend fun assertLegacyKeysGone() {
        val names = store.data.first().asMap().keys.map { it.name }.toSet()
        assertTrue(names.intersect(LEGACY_KEYS).isEmpty())
        assertNull(settings.legacyAnswersFlow.first())
        assertNull(settings.legacySuggestedDayFlow.first())
    }

    private suspend fun writeLegacy(answers: WizardAnswers, suggestedDay: String) = store.edit { prefs ->
        prefs[intPreferencesKey("bodyweight")] = answers.config.bodyweightLb.toInt()
        prefs[intPreferencesKey("age")] = answers.config.age
        prefs[stringPreferencesKey("level")] = answers.config.level.name
        prefs[stringPreferencesKey("emphasis")] = answers.config.emphasis.name
        prefs[stringPreferencesKey("cardio_mode")] = answers.cardio.mode.name
        prefs[stringPreferencesKey("cardio_placement")] = answers.cardio.placement.name
        prefs[booleanPreferencesKey("five_k")] = answers.cardio.fiveKGoal
        prefs[intPreferencesKey("days_per_week")] = answers.daysPerWeek
        prefs[stringPreferencesKey("split")] = answers.split.name
        prefs[stringPreferencesKey("anchor_scheme")] = answers.anchorScheme.name
        prefs[stringPreferencesKey("deadlift_variant")] = answers.deadliftVariant.name
        prefs[stringSetPreferencesKey("equipment")] = answers.equipment.map { it.name }.toSet()
        prefs[stringPreferencesKey("suggested_day")] = suggestedDay
    }

    private fun plan(id: Long, answers: WizardAnswers? = null, suggestedDay: String? = null) = PlanEntity(
        id, "Plan $id", PlanOrigin.GENERATED.name, id.toInt(), 1_000,
        recipeJson = answers?.let(PlanRecipeJson::encode), suggestedDayId = suggestedDay,
    )

    private fun day(planId: Long, id: String, position: Int) = ProgramDayEntity(planId, id, position, id, "", null)

    private fun strengthAnswers() = WizardAnswers(
        daysPerWeek = 3,
        anchorScheme = AnchorScheme.BIG_4,
        deadliftVariant = DeadliftVariant.SUMO,
        config = LifterConfig(emphasis = GoalEmphasis.STRENGTH),
        cardio = CardioPrefs(CardioMode.NONE, CardioPlacement.NONE, false),
        equipment = setOf(Equipment.BARBELL),
    )

    private fun physiqueAnswers() = WizardAnswers(
        daysPerWeek = 6,
        split = SplitTemplate.PPL,
        config = LifterConfig(emphasis = GoalEmphasis.PHYSIQUE),
        cardio = CardioPrefs(CardioMode.TREADMILL, CardioPlacement.SEPARATE_DAYS, true),
        equipment = setOf(Equipment.DUMBBELL),
    )

    private companion object {
        val LEGACY_KEYS = setOf(
            "emphasis", "cardio_mode", "cardio_placement", "five_k", "days_per_week",
            "split", "anchor_scheme", "deadlift_variant", "equipment", "suggested_day",
        )
    }
}
