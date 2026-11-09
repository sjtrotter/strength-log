package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SetKind
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupV27PayloadTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: TrackerRepository
    private lateinit var backupRepository: BackupRepository
    private lateinit var service: BackupService
    private val codec = BackupCodec()
    private val rows = listOf(
        LoggedSet(235.0, 5, SetKind.TOP, done = true, rir = 1),
        LoggedSet(175.0, 8, SetKind.BACKOFF, done = true, rir = 0),
        LoggedSet(175.0, 8, SetKind.BACKOFF, done = true, rir = 5),
        LoggedSet(175.0, 8, SetKind.BACKOFF, done = true),
    )

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("v27-payload", ".preferences_pb")
        })
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("v27-payload-journal", ".preferences_pb")
        }, settings)
        repository = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        backupRepository = BackupRepository(db, settings, repository.plans, repository.history)
        service = BackupService(backupRepository, journal)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun sessionPlan(sets: List<LoggedSet>) = SessionPlanJson.encode(listOf(
        PlannedTrackDto(0, "bb_back_squat", "main", sets = sets.map(SetDto::of)),
    ))

    private fun document(): BackupDocument {
        val sets = SetJson.encodeSets(rows)
        val planned = SetJson.encodeSets(rows.map { it.copy(done = false) })
        val spec = CardioBlockJson.encodeSet(CardioSetSpec(CardioRole.WORK, Measure.Time(900)))
        return BackupDocument(
            schemaVersion = 27,
            settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
                selectedPlanId = 1, bodyweightLbPrecise = 200.0),
            plans = listOf(PlanBackup(1, "Plan", "AUTHORED", 1000, onToday = true, suggestedDay = "A",
                days = listOf(ProgramDayBackup("A", "Day A", "", nextRunId = "open-run", exercises = listOf(
                    ProgramExerciseBackup(7, "bb_back_squat", "RAMP_TOP_BACKOFF", 4, note = "",
                        keepToPlan = false, parkedLogsJson = ParkedLogsJson.encode(listOf(
                            ParkedTrackDto("main", "bb_back_squat", planned),
                        ))),
                ))))),
            liveLogs = listOf(LiveLogBackup(1, "A", 7, "main", planned, updatedAt = 1000)),
            runs = listOf(RunBackup("open-run", 1, "A", "Day A", "Plan", 1000, tracks = listOf(
                RunTrackBackup(11, 0, 7, "main", "bb_back_squat", plannedJson = planned, setsJson = sets,
                    updatedAt = 3000),
            ))),
            sessions = listOf(SessionBackup(9, "A", "Day A", startedAt = 100, completedAt = 500,
                bodyweightLb = 200, bodyweightLbPrecise = 200.0, planId = 1, planName = "Plan",
                runId = "finished-run", plannedJson = sessionPlan(rows))),
            cardioSessions = listOf(CardioSessionBackup(12, "C", "OUTDOOR_RUN", false, "Easy", 1000, 901000, 900, 1,
                activity = "ROAD_RUN", sets = listOf(CardioSetResultBackup(13, spec, "FINISHED", 900,
                    metresAtEnd = 3470, secondsAtEnd = 900)))),
        )
    }

    private fun BackupDocument.withSlot(transform: (ProgramExerciseBackup) -> ProgramExerciseBackup) = copy(
        plans = plans.map { plan -> plan.copy(days = plan.days.map { day ->
            day.copy(exercises = day.exercises.map(transform))
        }) },
    )

    private fun BackupDocument.withRunTrack(transform: (RunTrackBackup) -> RunTrackBackup) = copy(
        runs = runs.map { it.copy(tracks = it.tracks.map(transform)) },
    )

    @Test fun `v27 open run rir 6 is Inconsistent and leaves the database untouched`() = runTest {
        val source = document()
        service.import(codec.encode(source.copy(plans = source.plans.map { it.copy(name = "Existing") })))
        val before = backupRepository.exportSnapshot()
        for (rir in listOf(6, -1)) {
            val invalid = source.withRunTrack { track ->
                track.copy(setsJson = SetJson.encodeSets(rows.mapIndexed { index, set ->
                    if (index == 0) set.copy(rir = rir) else set
                }))
            }
            assertFailsWith<BackupError.Inconsistent> { service.import(codec.encode(invalid)) }
            assertEquals(before, backupRepository.exportSnapshot())
            assertNull(db.restoreMarkerDao().nonce())
        }
    }

    @Test fun `valid v27 open run RIR restores finishes re-exports and restores again`() = runTest {
        val source = document()
        service.import(codec.encode(source))
        assertEquals(source.runs.single().tracks.single().setsJson, db.runDao().allTracks().single().setsJson)

        val sessionId = assertNotNull(repository.finishRun("open-run"))
        assertEquals(listOf(1, 0, 5, null), db.sessionDao().setsForSession(sessionId).map { it.rir })
        val exported = service.export()
        val decoded = codec.decode(exported)
        assertEquals(27, decoded.schemaVersion)
        assertEquals(listOf(1, 0, 5, null), decoded.sessions.single { it.id == sessionId }.sets.map { it.rir })

        service.import(exported)
        assertEquals(exported, service.export())
    }

    @Test fun `v27 live log RIR outside zero through five is Inconsistent`() {
        for (rir in listOf(-1, 6)) {
            val source = document()
            val invalid = source.copy(liveLogs = source.liveLogs.map {
                it.copy(setsJson = SetJson.encodeSets(listOf(rows.first().copy(rir = rir))))
            })
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(invalid)) }
        }
    }

    @Test fun `v27 parked row RIR outside zero through five is Inconsistent`() {
        for (rir in listOf(-1, 6)) {
            val invalid = document().withSlot { it.copy(parkedLogsJson = ParkedLogsJson.encode(listOf(
                ParkedTrackDto("main", "bb_back_squat", SetJson.encodeSets(listOf(rows.first().copy(rir = rir)))),
            ))) }
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(invalid)) }
        }
    }

    @Test fun `v27 run planned row RIR outside zero through five is Inconsistent`() {
        for (rir in listOf(-1, 6)) {
            val invalid = document().withRunTrack {
                it.copy(plannedJson = SetJson.encodeSets(listOf(rows.first().copy(rir = rir))))
            }
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(invalid)) }
        }
    }

    @Test fun `v27 archived planned row RIR outside zero through five is Inconsistent`() {
        for (rir in listOf(-1, 6)) {
            val source = document()
            val invalid = source.copy(sessions = source.sessions.map {
                it.copy(plannedJson = sessionPlan(listOf(rows.first().copy(rir = rir))))
            })
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(invalid)) }
        }
    }

    @Test fun `v27 cardio payload errors stay InvalidPayload and negative at-end values are Inconsistent`() {
        val source = document()
        val session = source.cardioSessions.single()
        val set = session.sets.single()
        val malformed = source.copy(cardioSessions = listOf(session.copy(sets = listOf(set.copy(specJson = "junk")))))
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(malformed)) }

        for (negative in listOf(set.copy(metresAtEnd = -1), set.copy(secondsAtEnd = -1))) {
            val invalid = source.copy(cardioSessions = listOf(session.copy(sets = listOf(negative))))
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(invalid)) }
        }
    }
    @Test fun `v27 archived offer with a negative weight is InvalidPayload`() {
        val source = document()
        val invalid = source.copy(sessions = source.sessions.map { it.copy(plannedJson =
            """[{"position":0,"exerciseId":"bb_back_squat","slot":"main","sets":[],"offer":{"fromLb":-5.0,"toLb":190.0}}]""") })
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(invalid)) }
    }

    @Test fun `v27 run track opening round trips and a bad opening is malformed`() {
        val opening = """{"layoff":"SCALED","factor":0.95}"""
        val source = document().withRunTrack { it.copy(openingJson = opening) }
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source, decoded)
        assertEquals(opening, decoded.toSnapshot().runTracks.single().openingJson)
        val invalid = source.withRunTrack { it.copy(openingJson = """{"layoff":"SCALED"}""") }
        assertFailsWith<BackupError.Malformed> { codec.decode(codec.encode(invalid)) }
    }

    @Test fun `a v26 restore rules the partner from its stored rows and a v27 restore keeps its rule`() = runTest {
        val rule = SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(12), 2, 0.0125, 1, 0.05, Scheme.STRENGTH)
        val source = document().withSlot { it.copy(supersetExerciseId = "cable_row") }
        val partner = LiveLogBackup(1, "A", 7, "ss",
            SetJson.encodeSets(List(3) { LoggedSet(120.0, 12, SetKind.WORK) }), updatedAt = 1000)
        val legacy = source.copy(schemaVersion = 26, liveLogs = source.liveLogs + partner)
        service.import(codec.encode(legacy))
        repository.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(rule, SlotRuleJson.decode(db.programDao().exercise(7)!!.partnerRuleJson))
        assertEquals(partner.setsJson, db.programDao().logForSlot(1, "A", 7, "ss")!!.setsJson)
        val kept = SlotRuleJson.encode(rule.copy(target = RepTarget.Fixed(8)))
        val current = legacy.copy(schemaVersion = 27).withSlot { it.copy(partnerRuleJson = kept) }
        service.import(codec.encode(current))
        repository.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(kept, db.programDao().exercise(7)!!.partnerRuleJson)
    }

}
