package cloud.trotter.log.strength.transfer.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A v4 backup — written while a session's bodyweight was still a required number
 * — must still restore, and the CSV-imported sessions it carries as `0` must come
 * back as "none recorded" (#171). Those files are in the wild: any user who
 * imported a CSV before v5 has a backup full of zeroes, and the pre-fix validator
 * rejected the whole file on sight.
 */
class BackupV4RestoreTest {

    private val codec = BackupCodec()

    private fun v4Json(): String =
        checkNotNull(javaClass.getResourceAsStream("/backup/v4_backup.json")) { "missing v4 fixture" }
            .readBytes()
            .toString(Charsets.UTF_8)

    @Test
    fun `a v4 document that a CSV import poisoned still decodes`() {
        val sessions = codec.decode(v4Json()).sessions
        assertEquals(2, sessions.size)
        assertEquals(235, sessions.first { it.id == 1L }.bodyweightLb)
        assertNull(sessions.first { it.id == 2L }.bodyweightLb)
    }

    @Test
    fun `it restores into a snapshot carrying the absence, not a zero`() {
        val snapshot = codec.decode(v4Json()).toSnapshot()
        assertNull(snapshot.sessions.first { it.id == 2L }.bodyweightLb)
        assertEquals(235, snapshot.sessions.first { it.id == 1L }.bodyweightLb)
    }

    @Test
    fun `everything else in the v4 document is preserved`() {
        val snapshot = codec.decode(v4Json()).toSnapshot()
        assertEquals(235, snapshot.answers.config.bodyweightLb)
        assertEquals("A", snapshot.suggestedDay)
        assertTrue(snapshot.keepScreenOn)
        assertEquals(1500L, snapshot.sessions.first { it.id == 1L }.startedAt)
        assertEquals(2, snapshot.sessionSets.size)
    }

    @Test
    fun `re-exporting the restored document writes it at the current version`() {
        val reEncoded = codec.encode(codec.decode(v4Json()).copy(schemaVersion = CURRENT_SCHEMA_VERSION))
        // The unknown bodyweight survives a v5 write/read; before #171 this file
        // could not be restored at all.
        assertNull(codec.decode(reEncoded).sessions.first { it.id == 2L }.bodyweightLb)
    }

    @Test
    fun `a legacy document missing a session bodyweight is malformed, not unknown`() {
        // Pre-v5 the field was required; absence means truncation or hand
        // editing, and must not be quietly read as "none recorded".
        val doc = v4Json().replace(""""bodyweightLb": 0,""", "")
        val error = assertFailsWith<BackupError.Malformed> { codec.decode(doc) }
        assertTrue("bodyweightLb" in error.message.orEmpty())
    }

    @Test
    fun `a legacy document with an explicit null bodyweight is malformed too`() {
        val doc = v4Json().replace(""""bodyweightLb": 0,""", """"bodyweightLb": null,""")
        assertFailsWith<BackupError.Malformed> { codec.decode(doc) }
    }
}
