package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.V1Recipe
import cloud.trotter.log.strength.data.serialization.PlanRecipeDto
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
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
            profile = LifterProfile(), unit = WeightUnit.LB, wizardComplete = true,
            suggestedDay = null, selectedPlanId = 2, restSettings = RestSettings(),
            plans = listOf(plan(2)), legacyRecipe = null,
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
    fun `fixup carries legacy facts once and converts existing recipes from their own facts`() = runTest {
        val legacy = strengthAnswers()
        val existing = plan(4, physiqueAnswers(), "D")
        val authored = plan(2).copy(origin = PlanOrigin.AUTHORED.name)
        db.planDao().insertPlans(listOf(plan(1), authored, plan(3), existing))
        writeLegacy(legacy, "B")
        val before = settings.lifterProfileFlow.first()

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(facts(legacy), answersFor(1))
        assertEquals(facts(legacy), answersFor(3))
        assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        assertNull(db.planDao().plan(3)!!.suggestedDayId)
        assertEquals(authored, db.planDao().plan(2))
        assertEquals(existing.copy(recipeJson = null, settingsJson =
            PlanSettingsJson.encode(facts(physiqueAnswers()))), db.planDao().plan(4))
        assertEquals(before, settings.lifterProfileFlow.first())
        assertLegacyKeysGone()
        assertTrue(settings.planSettingsFixupDoneFlow.first())
        val plans = db.planDao().allPlans()
        val prefs = store.data.first()

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(plans, db.planDao().allPlans())
        assertEquals(prefs, store.data.first())
    }


    @Test
    fun `fixup retries after Room commit without overwriting written goals or position`() = runTest {
        db.planDao().insertPlan(plan(1))
        writeLegacy(strengthAnswers(), "B")
        store.failOnUpdate = 2

        val failure = runCatching { repo.plans.runPlanSettingsFixupIfNeeded() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(facts(strengthAnswers()), db.planDao().plan(1)!!.planSettings)
        assertNull(db.planDao().plan(1)!!.recipeJson)
        assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        assertFalse(settings.planSettingsFixupDoneFlow.first())
        assertEquals(PlanRecipeJson.read(strengthAnswers()), settings.legacyRecipeFlow.first())
        db.planDao().updateRecipe(1, physiqueAnswers())
        db.planDao().updateSuggestedDay(1, "C")

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertEquals(facts(physiqueAnswers()), answersFor(1))
        assertEquals("C", repo.plans.suggestedDayFlow.first())
        assertLegacyKeysGone()
        assertTrue(settings.planSettingsFixupDoneFlow.first())
    }


    @Test
    fun `empty legacy store is retired without manufacturing recipes`() = runTest {
        db.planDao().insertPlan(plan(1))

        repo.plans.runPlanSettingsFixupIfNeeded()

        assertNull(db.planDao().plan(1)!!.recipeJson)
        assertEquals(PlanSettings(), repo.plans.planSettingsFor(1).first())
        assertTrue(settings.planSettingsFixupDoneFlow.first())
    }


    @Test
    fun `selection switches plan settings while the profile stays shared`() = runTest {
        settings.setLifterProfile(LifterProfile(193.0, 47))
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one, "B"), plan(2, two, "D")))
        val emissions = Channel<PlanSettings>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.plans.selectedPlanFlow.collect { emissions.send(it?.planSettings ?: PlanSettings()) }
        }
        assertEquals(facts(one), emissions.receive())
        assertSelected(one, "B")

        settings.setSelectedPlanId(2)

        assertEquals(facts(two), emissions.receive())
        assertSelected(two, "D")
        collector.cancelAndJoin()
    }


    @Test
    fun `stored recipes update the selected facts for either origin`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one), plan(2, two), plan(3).copy(origin = PlanOrigin.AUTHORED.name)))

        repo.plans.ensurePlanSettingsMigrated()
        settings.setLifterProfile(LifterProfile(235.0, 40))
        db.planDao().updateRecipe(1, one.replace("\"cardioMode\":\"NONE\"", "\"cardioMode\":\"LOW_IMPACT\"")
            .replace("\"cardioPlacement\":\"NONE\"", "\"cardioPlacement\":\"BOTH\""))
        repo.plans.invalidatePlanFixups()

        assertEquals(facts(one), repo.plans.planSettingsFor(1).first())
        assertNull(db.planDao().plan(2)!!.recipeJson)
        settings.setSelectedPlanId(3)
        settings.updateLifterProfile { it.copy(bodyweightLb = 205.0) }
        settings.setLifterProfile(LifterProfile(205.0, 40))
        db.planDao().updateRecipe(3, v1RecipeJson(cardioMode = "LOW_IMPACT", cardioPlacement = "BOTH", fiveKGoal = false))
        repo.plans.invalidatePlanFixups()
        assertEquals(LifterProfile(235.0, 40).copy(bodyweightLb = 205.0), settings.lifterProfileFlow.first())
        assertEquals(facts(v1RecipeJson()), answersFor(3))
        db.planDao().updateRecipe(settings.selectedPlanIdFlow.first()!!, two)
        repo.plans.invalidatePlanFixups()
        assertEquals(facts(two), answersFor(3))
        assertEquals(facts(two), repo.plans.selectedPlanFlow.first()!!.planSettings)
        settings.setSelectedPlanId(2)
        db.planDao().updateRecipe(settings.selectedPlanIdFlow.first()!!, two)
        repo.plans.invalidatePlanFixups()
        assertEquals(facts(two), repo.plans.selectedPlanFlow.first()!!.planSettings)
        assertLegacyKeysGone()
    }


    @Test
    fun `a recipe can persist before the first program exists`() = runTest {
        val selected = repo.plans.ensureSelectedPlan()
        db.planDao().updateRecipe(selected, strengthAnswers())
        repo.plans.invalidatePlanFixups()

        assertEquals(facts(strengthAnswers()), repo.plans.selectedPlanFlow.first()!!.planSettings)
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
        db.planDao().insertPlan(plan(1, suggestedDay = "A").copy(
            settingsJson = PlanSettingsJson.encode(facts(strengthAnswers())),
        ))
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
    fun `profile is identical before and after migration for default and custom answers`() = runTest {
        db.planDao().insertPlan(plan(1))
        val custom = LifterProfile(210.0, 56)
        for ((recipe, profile, level) in listOf(
            Triple(v1RecipeJson(), LifterProfile(235.0, 40), "INTERMEDIATE"),
            Triple(strengthAnswers(), custom, "ADVANCED"),
        )) {
            writeLegacy(recipe, "B", profile, level)
            val oldProfile = settings.lifterProfileFlow.first()
            // Recreate the pre-fixup plan and flag for each legacy fixture.
            db.planDao().deleteAllPlans()
            db.planDao().insertPlan(plan(1))
            store.edit { it.remove(booleanPreferencesKey("plan_settings_fixup_done")) }
            assertEquals(oldProfile, settings.lifterProfileFlow.first())

            repo.plans.runPlanSettingsFixupIfNeeded()

            assertEquals(oldProfile, settings.lifterProfileFlow.first())
            assertEquals(profile, settings.lifterProfileFlow.first())
        }
    }


    @Test
    fun `snapshot keeps distinct plan facts and rotation and legacy snapshots spread settings`() = runTest {
        val one = strengthAnswers()
        val two = physiqueAnswers()
        db.planDao().insertPlans(listOf(plan(1, one, "B"), plan(2, two, "D")))
        db.programDao().upsertDays(listOf(
            day(1, "B", 0).copy(nextRunId = "plan-1-B"),
            day(2, "D", 0).copy(nextRunId = "plan-2-D"),
        ))
        settings.setSelectedPlanId(2)
        val snapshot = backupRepository.exportSnapshot()
        assertEquals(settings.lifterProfileFlow.first(), snapshot.profile)
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
            profile = LifterProfile(235.0, 40),
            plans = snapshot.plans.map { it.copy(recipeJson = null, suggestedDayId = null) } + authored,
            suggestedDay = "B",
            legacyRecipe = PlanRecipeJson.read(one),
        )
        backupRepository.importSnapshot(legacy, journal)

        assertEquals(facts(one), answersFor(1))
        assertEquals(facts(one), answersFor(2))
        assertEquals(authored, db.planDao().plan(3))
        assertEquals("B", db.planDao().plan(2)!!.suggestedDayId)
        assertNull(db.planDao().plan(1)!!.suggestedDayId)
        assertLegacyKeysGone()
        backupRepository.importSnapshot(legacy.copy(plans = emptyList(), selectedPlanId = null, days = listOf(day(1, "A", 0))), journal)
        assertEquals(facts(one), answersFor(PlanEntity.LEGACY_ID))
        assertEquals("B", repo.plans.suggestedDayFlow.first())
    }


    @Test
    fun `mid first run backup gives answers an empty plan that the wizard can fill`() = runTest {
        val answers = strengthAnswers()
        val snapshot = FullSnapshot(
            profile = LifterProfile(235.0, 40),
            legacyRecipe = PlanRecipeJson.read(answers),
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
        assertEquals(facts(answers), answersFor(PlanEntity.LEGACY_ID))
        assertEquals(facts(answers), repo.plans.selectedPlanFlow.first()!!.planSettings)
        assertTrue(repo.plans.programFlow.first().days.isEmpty())
        assertFalse(settings.wizardCompleteFlow.first())
        val generated = Program(listOf("A", "B", "C").map { ProgramDay(it, "Full Body", "", emptyList()) })
        repo.replaceProgram(generated)
        assertEquals(1, db.planDao().allPlans().size)
        assertEquals(generated, repo.plans.programFlow.first())
        assertEquals(facts(answers), repo.plans.selectedPlanFlow.first()!!.planSettings)
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
        assertEquals(facts(strengthAnswers()), repo.plans.selectedPlanFlow.first()!!.planSettings)
        assertEquals(snapshot.plans, db.planDao().allPlans())
    }


    @Test
    fun `setting a plan goal stores it on that plan only`() = runTest {
        db.planDao().insertPlans(listOf(plan(1, v1RecipeJson()), plan(2, v1RecipeJson())))
        settings.setSelectedPlanId(2)
        repo.plans.runPlanSettingsFixupIfNeeded()
        val other = db.planDao().plan(2)
        val prefs = store.data.first()

        repo.plans.setPlanGoal(1, "bb_back_squat", 200.0)

        assertEquals(mapOf("bb_back_squat" to 200.0), repo.plans.planSettingsFor(1).first().goalsLb)
        assertTrue(repo.plans.planSettingsFor(2).first().goalsLb.isEmpty())
        assertEquals(other, db.planDao().plan(2))
        assertEquals(mapOf("bb_back_squat" to 200.0), repo.plans.planSettingsFor(1).first().goalsLb)
        repo.plans.setPlanGoal(1, "bb_back_squat", null)
        assertTrue(repo.plans.planSettingsFor(1).first().goalsLb.isEmpty())
        assertEquals(prefs, store.data.first())
    }


    @Test
    fun `a goal edit leaves stored working values alone`() = runTest {
        val exercise = ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF, 6)
        val program = Program(listOf("A", "B").map { ProgramDay(it, "Day $it", "", listOf(exercise)) })
        val planId = db.insertGeneratedPlan("Goals", program)
        repo.plans.invalidatePlanFixups()
        repo.ensureSeeded(planId, "A")
        val original = db.programDao().logsForPlan(planId).single { it.dayId == "A" }
        val working = original.copy(setsJson = SetJson.encodeSets(SetJson.decodeSets(original.setsJson).map {
            if (it.kind == SetKind.TOP) it.copy(weightLb = 245.0) else it
        }))
        db.programDao().upsertLog(working)
        repo.startRun(planId, "A")
        val tracks = db.runDao().allTracks()
        assertTrue(tracks.isNotEmpty())

        repo.plans.setPlanGoal(planId, exercise.exerciseId, 200.0)
        repo.ensureSeeded(planId, "A")

        assertEquals(working, db.programDao().logsForPlan(planId).single { it.dayId == "A" })
        assertEquals(tracks, db.runDao().allTracks())
        repo.ensureSeeded(planId, "B")
        val later = db.programDao().logsForPlan(planId).single { it.dayId == "B" }
        assertEquals(45.0, SetJson.decodeSets(later.setsJson).single { it.kind == SetKind.TOP }.weightLb, 0.0)
        assertEquals(working, db.programDao().logsForPlan(planId).single { it.dayId == "A" })
        assertEquals(tracks, db.runDao().allTracks())
    }


    @Test
    fun `setPlanGoal stores recipe-less goals ignores missing plans and rejects invalid weights`() = runTest {
        db.planDao().insertPlan(plan(1).copy(origin = PlanOrigin.AUTHORED.name))
        repo.plans.runPlanSettingsFixupIfNeeded()
        repo.plans.setPlanGoal(1, "bb_back_squat", 200.0)
        repo.plans.setPlanGoal(2, "bb_back_squat", 200.0)
        for (invalid in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY,
            PlanRecipeJson.MAX_GOAL_LB + 0.1, 2500.0, WeightUnit.KG.toLb(907.2))) {
            assertTrue(runCatching { repo.plans.setPlanGoal(1, "bb_back_squat", invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        val plan = db.planDao().allPlans().single()
        assertNull(plan.recipeJson)
        assertEquals(mapOf("bb_back_squat" to 200.0), plan.planSettings.goalsLb)
        assertEquals(plan.planSettings.goalsLb, repo.plans.planSettingsFor(1).first().goalsLb)
        repo.plans.setPlanGoal(1, "bb_back_squat", null)
        assertTrue(db.planDao().plan(1)!!.planSettings.goalsLb.isEmpty())
    }

    private suspend fun assertSelected(answers: String, suggestedDay: String) {
        assertEquals(LifterProfile(193.0, 47), settings.lifterProfileFlow.first())
        assertEquals(facts(answers), repo.plans.selectedPlanFlow.first()!!.planSettings)
        assertEquals(suggestedDay, repo.plans.suggestedDayFlow.first())
    }

    private fun facts(recipe: V1Recipe) = PlanSettings(equipment = recipe.equipment, goalsLb = recipe.goalsLb)

    private fun facts(json: String) = facts(requireNotNull(PlanRecipeJson.read(json)))

    private suspend fun answersFor(id: Long): PlanSettings {
        val result = repo.plans.planSettingsFor(id).first()
        assertNull(db.planDao().plan(id)!!.recipeJson)
        return result
    }

    private suspend fun assertLegacyKeysGone() {
        val names = store.data.first().asMap().keys.map { it.name }.toSet()
        assertTrue(names.intersect(LEGACY_KEYS).isEmpty())
        assertNull(settings.legacyRecipeFlow.first())
        assertNull(settings.legacySuggestedDayFlow.first())
    }

    private suspend fun writeLegacy(
        json: String,
        suggestedDay: String,
        profile: LifterProfile = LifterProfile(235.0, 40),
        level: String = "INTERMEDIATE",
    ) = store.edit { prefs ->
        val recipe = SetJson.decode(PlanRecipeDto.serializer(), json)
        prefs[intPreferencesKey("bodyweight")] = requireNotNull(profile.bodyweightLb).toInt()
        prefs[intPreferencesKey("age")] = requireNotNull(profile.age)
        prefs[stringPreferencesKey("level")] = level
        prefs[stringPreferencesKey("emphasis")] = recipe.emphasis
        prefs[stringPreferencesKey("cardio_mode")] = recipe.cardioMode
        prefs[stringPreferencesKey("cardio_placement")] = recipe.cardioPlacement
        prefs[booleanPreferencesKey("five_k")] = recipe.fiveKGoal
        prefs[intPreferencesKey("days_per_week")] = recipe.daysPerWeek
        prefs[stringPreferencesKey("split")] = recipe.split
        prefs[stringPreferencesKey("anchor_scheme")] = recipe.anchorScheme
        prefs[stringPreferencesKey("deadlift_variant")] = recipe.deadliftVariant
        prefs[stringSetPreferencesKey("equipment")] = recipe.equipment.toSet()
        prefs[stringPreferencesKey("suggested_day")] = suggestedDay
    }

    private fun plan(id: Long, recipe: String? = null, suggestedDay: String? = null) = PlanEntity(
        id, "Plan $id", PlanOrigin.GENERATED.name, id.toInt(), 1_000,
        recipeJson = recipe, suggestedDayId = suggestedDay,
    )

    private fun day(planId: Long, id: String, position: Int) = ProgramDayEntity(planId, id, position, id, "", null)

    private fun strengthAnswers() = v1RecipeJson(daysPerWeek = 3, anchorScheme = "BIG_4", deadliftVariant = "SUMO",
        emphasis = "STRENGTH", cardioMode = "NONE", cardioPlacement = "NONE", fiveKGoal = false,
        equipment = listOf(Equipment.BARBELL))

    private fun physiqueAnswers() = v1RecipeJson(daysPerWeek = 6, split = "PPL", emphasis = "PHYSIQUE",
        cardioMode = "TREADMILL", cardioPlacement = "SEPARATE_DAYS", equipment = listOf(Equipment.DUMBBELL))

    private companion object {
        val LEGACY_KEYS = setOf(
            "emphasis", "cardio_mode", "cardio_placement", "five_k", "days_per_week",
            "split", "anchor_scheme", "deadlift_variant", "equipment", "suggested_day",
        )
    }
}
