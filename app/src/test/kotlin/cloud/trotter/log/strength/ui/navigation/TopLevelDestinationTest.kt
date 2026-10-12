package cloud.trotter.log.strength.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelDestinationTest {
    @Test
    fun showsBarOn_only_matches_the_three_top_level_routes() {
        assertTrue(TopLevelDestination.showsBarOn("today"))
        assertTrue(TopLevelDestination.showsBarOn("log"))
        assertTrue(TopLevelDestination.showsBarOn("plan"))
        assertFalse(TopLevelDestination.showsBarOn("day"))
        assertFalse(TopLevelDestination.showsBarOn("day/B"))
        assertFalse(TopLevelDestination.showsBarOn("setup?rest=true"))
        assertFalse(TopLevelDestination.showsBarOn(null))
    }
}
