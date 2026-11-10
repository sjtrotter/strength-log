package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupDraftRunTest {
    private val codec = BackupCodec()
    private fun document(done: Boolean = false): BackupDocument {
        val planned = SetJson.encodeSets(listOf(LoggedSet(120.0, 10, SetKind.WORK)))
        val actual = SetJson.encodeSets(listOf(LoggedSet(120.0, 10, SetKind.WORK, done = done, plannedIndex = 0)))
        return BackupDocument(
            schemaVersion = 27,
            settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
                selectedPlanId = 1, bodyweightLbPrecise = 200.0),
            plans = listOf(PlanBackup(1, "Plan", "AUTHORED", 1000, onToday = true,
                days = listOf(ProgramDayBackup("A", "Day A", "", nextRunId = "open-run", exercises = listOf(
                    ProgramExerciseBackup(7, "bb_bench", "STRAIGHT", 3, note = "", keepToPlan = false),
                ))))),
            runs = listOf(RunBackup("open-run", 1, "A", "Day A", "Plan", 1000, tracks = listOf(
                RunTrackBackup(11, 0, 7, "main", "bb_bench", plannedJson = planned, setsJson = actual, updatedAt = 1000),
            ), draft = true)),
        )
    }

    @Test fun `a v27 draft round trips through the snapshot`() {
        val source = document()
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source, decoded)
        val snapshot = decoded.toSnapshot()
        assertTrue(snapshot.runs.single().draft)
        assertTrue(codec.decode(codec.encode(snapshot.toDocument())).runs.single().draft)
    }

    @Test fun `a v26 run carrying draft true restores as started`() {
        val decoded = codec.decode(codec.encode(document().copy(schemaVersion = 26)))
        assertFalse(decoded.runs.single().draft)
        assertFalse(decoded.toSnapshot().runs.single().draft)
    }

    @Test fun `a v27 draft with a done row is Inconsistent`() {
        val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(done = true))) }
        assertEquals("Inconsistent backup: a draft run cannot hold done sets", error.message)
    }
}
