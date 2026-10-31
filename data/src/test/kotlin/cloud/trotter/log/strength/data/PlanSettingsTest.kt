package cloud.trotter.log.strength.data

import android.content.Context
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
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `fixup carries legacy facts once and leaves authored and existing recipes alone`() = runTest {
        val legacy = strengthAnswers()
        val existing = plan(4, physiqueAnswers(), "D")
        val authored = plan(2).copy(origin = PlanOrigin.AUTHORED.name)
        db.planDao().insertPlans(listOf(plan(1), authored, plan(3), existing))
        writeLegacy(legacy, "B")
        val before = repo.configFlow.first()

        repo.runPlanSettingsFixupIfNeeded()

        assertEquals(legacy, answersFor(1))
        assertEquals(legacy, answersFor(3))
        assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        assertNull(db.planDao().plan(3)!!.suggestedDayId)
        assertEquals(authored, db.planDao().plan(2))
        assertEquals(existing, db.planDao().plan(4))
        assertEquals(before, repo.configFlow.first())
        assertLegacyKeysGone()
        assertTrue(settings.planSettingsFixupDoneFlow.first())
        val plans = db.planDao().allPlans()
        val prefs = store.data.first()

        repo.runPlanSettingsFixupIfNeeded()

        assertEquals(plans, db.planDao().allPlans())
        assertEquals(prefs, store.data.first())
    }

    @Test
    fun `fixup retries after Room commit without overwriting written recipes or position`() = runTest {
        db.planDao().insertPlan(plan(1))
        writeLegacy(strengthAnswers(), "B")
        store.failOnUpdate = 2

        val failure = runCatching { repo.runPlanSettingsFixupIfNeeded() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(strengthAnswers(), answersFor(1))
        assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        assertFalse(settings.planSettingsFixupDoneFlow.first())
        assertEquals(strengthAnswers(), settings.legacyAnswersFlow.first())
        db.planDao().updateRecipe(1, PlanRecipeJson.encode(physiqueAnswers()))
        db.planDao().updateSuggestedDay(1, "C")

        repo.runPlanSettingsFixupIfNeeded()

        assertEquals(physiqueAnswers(), answersFor(1))
        assertEquals("C", repo.suggestedDayFlow.first())
        assertLegacyKeysGone()
        assertTrue(settings.planSettingsFixupDoneFlow.first())
    }

    @Test
    fun `empty legacy store is retired without manufacturing recipes`() = runTest {
        db.planDao().insertPlan(plan(1))

        repo.runPlanSettingsFixupIfNeeded()

        assertNull(db.planDao().plan(1)!!.recipeJson)
        assertEquals(WizardAnswers(), repo.wizardAnswersFlow.first())
        assertTrue(settings.planSettingsFixupDoneFlow.first())
    }

    @Test
    fun `edit before startup fixup preserves the new emphasis`() = runTest {
        db.planDao().insertPlan(plan(1))
        writeLegacy(strengthAnswers(), "B")

        repo.setConfig(strengthAnswers().config.copy(emphasis = GoalEmphasis.PHYSIQUE))

        assertEquals(GoalEmphasis.PHYSIQUE, repo.configFlow.first().emphasis)
        repo.runPlanSettingsFixupIfNeeded()
        assertEquals(GoalEmphasis.PHYSIQUE, repo.configFlow.first().emphasis)
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
            repo.configFlow.collect {
                emissions += it.emphasis
                observed.send(it.emphasis)
            }
        }
        val startup = launch { repo.runPlanSettingsFixupIfNeeded() }
        store.gateReached.await()
        assertTrue(emissions.isEmpty())

        open.complete(Unit)

        assertEquals(GoalEmphasis.STRENGTH, observed.receive())
        startup.join()
        // A later plan-row update makes the same live collector observe Room
        // again after key retirement, rather than stopping at its first value.
        repo.setConfig(strengthAnswers().config.copy(age = 41))
        observed.receive()
        collector.cancelAndJoin()
        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.all { it == GoalEmphasis.STRENGTH })
    }

    @Test
    fun `first wizard write seeds a recipe for either origin`() = runTest {
        db.planDao().insertPlans(listOf(plan(1), plan(2).copy(origin = PlanOrigin.AUTHORED.name)))
        val global = GlobalLifter(bodyweightLb = 205, age = 41, level = ExperienceLevel.ADVANCED)
        settings.setGlobalLifter(global)
        val prefs = CardioPrefs(CardioMode.LOW_IMPACT, CardioPlacement.BOTH, false)

        repo.setWizardAnswers(repo.wizardAnswersFlow.first().copy(cardio = prefs))

        assertEquals(WizardAnswers(config = global.toConfig(), cardio = prefs), answersFor(1))
        settings.setSelectedPlanId(2)
        repo.setWizardAnswers(repo.wizardAnswersFlow.first().copy(cardio = prefs))
        assertEquals(WizardAnswers(config = global.toConfig(), cardio = prefs), answersFor(2))
        assertEquals(WizardAnswers(config = global.toConfig(), cardio = prefs), repo.wizardAnswersFlow.first())
    }

    @Test
    fun `selection switches every plan setting including a live config collector`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one, "B"), plan(2, two, "D")))
        val emissions = Channel<LifterConfig>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.configFlow.collect { emissions.send(it) }
        }
        assertEquals(one.config, emissions.receive())
        assertSelected(one, "B")

        settings.setSelectedPlanId(2)

        assertEquals(two.config, emissions.receive())
        assertSelected(two, "D")
        collector.cancelAndJoin()
    }

    @Test
    fun `setConfig shares global lifter while updating only selected emphasis`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one), plan(2, two)))
        val changed = one.config.copy(bodyweightLb = 210, age = 31, level = ExperienceLevel.ADVANCED, emphasis = GoalEmphasis.BALANCED)

        repo.setConfig(changed)

        assertEquals(changed, repo.configFlow.first())
        assertEquals(one.copy(config = changed), repo.wizardAnswersFlow.first())
        settings.setSelectedPlanId(2)
        assertEquals(changed.copy(emphasis = GoalEmphasis.PHYSIQUE), repo.configFlow.first())
        assertEquals(PlanRecipeJson.encode(two), db.planDao().plan(2)!!.recipeJson)
        assertLegacyKeysGone()
    }

    @Test
    fun `wizard writes update the selected recipe for either origin`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one), plan(2, two), plan(3).copy(origin = PlanOrigin.AUTHORED.name)))
        val prefs = CardioPrefs(CardioMode.LOW_IMPACT, CardioPlacement.BOTH, false)

        repo.setWizardAnswers(repo.wizardAnswersFlow.first().copy(cardio = prefs))

        assertEquals(one.copy(cardio = prefs), repo.wizardAnswersFlow.first())
        assertEquals(PlanRecipeJson.encode(two), db.planDao().plan(2)!!.recipeJson)
        settings.setSelectedPlanId(3)
        repo.setConfig(one.config.copy(bodyweightLb = 205))
        repo.setWizardAnswers(repo.wizardAnswersFlow.first().copy(cardio = prefs))
        assertEquals(one.config.copy(bodyweightLb = 205), repo.configFlow.first())
        assertEquals(prefs, answersFor(3).cardio)
        repo.setWizardAnswers(two.copy(config = two.config.copy(bodyweightLb = 215)))
        assertEquals(two.copy(config = two.config.copy(bodyweightLb = 215)), answersFor(3))
        assertEquals(two.copy(config = two.config.copy(bodyweightLb = 215)), repo.wizardAnswersFlow.first())
        settings.setSelectedPlanId(2)
        repo.setWizardAnswers(two.copy(config = two.config.copy(bodyweightLb = 215)))
        assertEquals(two.copy(config = two.config.copy(bodyweightLb = 215)), repo.wizardAnswersFlow.first())
        assertLegacyKeysGone()
    }

    @Test
    fun `authored emphasis creates a recipe sticks and changes standard lift goals`() = runTest {
        val plan = repo.createAuthoredPlan("Strength", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
        val original = repo.configFlow.first()
        assertEquals(settings.globalLifterFlow.first().toConfig(), original)
        val entry = repo.catalogFlow.first().get("bb_back_squat")
        val before = GoalCalculator.targetFor(entry, original)
        val changed = original.copy(emphasis = GoalEmphasis.STRENGTH)
        repo.setConfig(changed)
        assertEquals(changed, repo.configFlow.first())
        assertEquals(changed, repo.wizardAnswersFor(plan).first().config)
        assertEquals(changed, answersFor(plan).config)
        assertFalse(before == GoalCalculator.targetFor(entry, repo.configFlow.first()))
        val other = repo.createAuthoredPlan("Other", "Day A")
        repo.publishCreatedAuthoredPlan(other)
        assertEquals(original, repo.configFlow.first())
        repo.selectPlan(plan)
        assertEquals(changed, repo.configFlow.first())
    }

    @Test
    fun `wizard can persist a recipe before the first program exists`() = runTest {
        repo.setWizardAnswers(strengthAnswers())

        assertEquals(strengthAnswers(), repo.wizardAnswersFlow.first())
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
        assertEquals("A", repo.suggestedDayFlow.first())
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
            assertEquals(oldConfig, repo.configFlow.first())

            repo.runPlanSettingsFixupIfNeeded()

            assertEquals(oldConfig, repo.configFlow.first())
            assertEquals(expected, GoalCalculator.goalForMain(StandardLift.SQUAT, false, repo.configFlow.first()), 0.0)
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
        val snapshot = repo.exportSnapshot()
        assertEquals(two, snapshot.answers)
        assertEquals("D", snapshot.suggestedDay)
        writeLegacy(one, "old")
        settings.enableAutoBackup("content://tree/folder", "Backups")
        settings.recordAutoBackupSuccess(1_234)
        val autoBackup = settings.autoBackupSettingsFlow.first()

        repo.importSnapshot(snapshot, journal)

        assertEquals(snapshot, repo.exportSnapshot())
        assertEquals(autoBackup, settings.autoBackupSettingsFlow.first())
        assertLegacyKeysGone()
        val authored = plan(3).copy(origin = PlanOrigin.AUTHORED.name)
        val legacy = snapshot.copy(
            answers = one,
            plans = snapshot.plans.map { it.copy(recipeJson = null, suggestedDayId = null) } + authored,
            suggestedDay = "B",
            answersCarryRecipe = true,
        )
        repo.importSnapshot(legacy, journal)

        assertEquals(one, answersFor(1))
        assertEquals(one, answersFor(2))
        assertEquals(authored, db.planDao().plan(3))
        assertEquals("B", db.planDao().plan(2)!!.suggestedDayId)
        assertNull(db.planDao().plan(1)!!.suggestedDayId)
        assertLegacyKeysGone()
        repo.importSnapshot(legacy.copy(plans = emptyList(), selectedPlanId = null, days = listOf(day(1, "A", 0))), journal)
        assertEquals(one, answersFor(PlanEntity.LEGACY_ID))
        assertEquals("B", repo.suggestedDayFlow.first())
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

        repo.importSnapshot(snapshot, journal)

        assertEquals(PlanEntity.LEGACY_ID, db.planDao().allPlans().single().id)
        assertEquals(answers, answersFor(PlanEntity.LEGACY_ID))
        assertEquals(answers, repo.wizardAnswersFlow.first())
        assertTrue(repo.programFlow.first().days.isEmpty())
        assertFalse(repo.wizardCompleteFlow.first())
        val generated = ProgramGenerator.generate(answers)
        repo.replaceProgram(generated)
        assertEquals(1, db.planDao().allPlans().size)
        assertEquals(generated, repo.programFlow.first())
        assertEquals(answers, repo.wizardAnswersFlow.first())
    }

    @Test
    fun `restore retires the legacy store before returning`() = runTest {
        db.planDao().insertPlan(plan(1, strengthAnswers(), "B"))
        repo.runPlanSettingsFixupIfNeeded()
        val snapshot = repo.exportSnapshot()
        assertTrue(settings.planSettingsFixupDoneFlow.first())

        repo.importSnapshot(snapshot, journal)

        assertTrue(settings.planSettingsFixupDoneFlow.first())
        assertLegacyKeysGone()
        assertEquals(strengthAnswers(), repo.wizardAnswersFlow.first())
        assertEquals(snapshot.plans, db.planDao().allPlans())
    }

    private suspend fun assertSelected(answers: WizardAnswers, suggestedDay: String) {
        assertEquals(answers.config, repo.configFlow.first())
        assertEquals(answers.cardio, repo.wizardAnswersFlow.first().cardio)
        assertEquals(answers, repo.wizardAnswersFlow.first())
        assertEquals(suggestedDay, repo.suggestedDayFlow.first())
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
        prefs[intPreferencesKey("bodyweight")] = answers.config.bodyweightLb
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
