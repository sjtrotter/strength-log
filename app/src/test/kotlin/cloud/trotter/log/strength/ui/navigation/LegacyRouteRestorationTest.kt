package cloud.trotter.log.strength.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cloud.trotter.log.strength.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyRouteRestorationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    @Test fun restoredPlainPlanDestinationRedirectsToPlansAndPopsItself() {
        val restoration = StateRestorationTester(rule)
        // Build an old-version stack first; only the restored graph has the redirect.
        var upgraded = false
        restoration.setContent {
            nav = rememberNavController()
            NavHost(nav, Routes.TODAY) {
                composable(Routes.TODAY) { Text("Today") }
                if (upgraded) legacyPlanDestination(nav) else composable(Routes.PLAN) { Text("Old Plan") }
                composable(Routes.PLANS) { Text("Plans") }
            }
        }
        rule.runOnIdle { nav.navigate(Routes.PLAN) }
        rule.runOnIdle { assertEquals(Routes.PLAN, nav.currentDestination?.route) }
        assertFalse(TopLevelDestination.showsBarOn(Routes.PLAN))
        upgraded = true
        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(5_000) { rule.runOnIdle { nav.currentDestination?.route == Routes.PLANS } }
        rule.runOnIdle {
            assertFalse(nav.currentBackStack.value.any { it.destination.route == Routes.PLAN })
            assertTrue(nav.popBackStack())
            assertEquals(Routes.TODAY, nav.currentDestination?.route)
        }
    }

    @Test fun restoredLegacyDayDestinationKeepsItsDayArgument() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            nav = rememberNavController()
            NavHost(nav, Routes.TODAY) {
                composable(Routes.TODAY) { Text("Today") }
                composable(Routes.DAY_WITH_ID, arguments = listOf(
                    navArgument(Routes.DAY_ID_ARG) { type = NavType.StringType },
                )) { Text("Workout") }
            }
        }
        rule.runOnIdle { nav.navigate(Routes.day("B")) }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertEquals(Routes.DAY_WITH_ID, nav.currentDestination?.route)
            assertEquals("B", nav.currentBackStackEntry?.arguments?.getString(Routes.DAY_ID_ARG))
        }
    }
}
