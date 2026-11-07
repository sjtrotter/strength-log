package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.CardioActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardioEntryDraftTest {
    @Test fun `a draft is valid within limits and not in the future`() {
        val now = 1_800_000_000_000L
        val draft = CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_472_000, 2412)
        assertTrue(draft.valid(now))
        assertTrue(draft.copy(metresText = "7100").valid(now))
        assertFalse(draft.copy(seconds = 0).valid(now))
        assertFalse(draft.copy(seconds = 86_401).valid(now))
        assertFalse(draft.copy(metresText = "0").valid(now))
        assertFalse(draft.copy(metresText = "7.1").valid(now))
        assertFalse(draft.copy(metresText = "200001").valid(now))
        assertFalse(draft.copy(startedAt = now).valid(now))
        assertTrue(draft.copy(startedAt = now - 86_400_000, seconds = 86_400, metresText = "200000").valid(now))
    }
}
