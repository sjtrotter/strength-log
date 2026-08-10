package cloud.trotter.log.strength.ui.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticBackupStateLineTest {
    @Test fun `successful run is described relative to now`() {
        val now = 5 * 86_400_000L
        assertEquals("Last backup: yesterday", automaticBackupResultLine(now - 86_400_000L, false, false, now))
    }

    @Test fun `failure is honest about retry`() {
        assertEquals("Last attempt failed — will retry", automaticBackupResultLine(null, true, false, 0L))
    }

    @Test fun `revoked grant asks for a new folder without claiming retry`() {
        assertEquals("Folder permission was lost — choose it again", automaticBackupResultLine(null, true, true, 0L))
    }
}
