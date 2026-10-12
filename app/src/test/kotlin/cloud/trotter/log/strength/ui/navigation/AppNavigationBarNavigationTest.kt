package cloud.trotter.log.strength.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import cloud.trotter.log.strength.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bar's navigate policy, driven through a real graph the way Google's
 * "Test Navigation" guide does it: a [TestNavHostController] with the
 * [ComposeNavigator] hosting plain composable destinations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavigationBarNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    @Test
    fun firstRunTopLevelTapsKeepTodayAsTheSingleBackStackAnchor() {
        composeTestRule.setContent {
            navController = TestNavHostController(LocalContext.current).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            NavHost(navController = navController, startDestination = Routes.WIZARD) {
                listOf(Routes.WIZARD, Routes.TODAY, Routes.LOG, Routes.PLAN).forEach { route ->
                    composable(route) { Box {} }
                }
            }
        }

        composeTestRule.runOnUiThread {
            // The wizard's own exit: Today becomes the base of the stack.
            navController.navigate(Routes.TODAY) { popUpTo(0) { inclusive = true } }
            navController.navigateToTopLevel(TopLevelDestination.LOG)
            navController.navigateToTopLevel(TopLevelDestination.TODAY)
            navController.navigateToTopLevel(TopLevelDestination.LOG)
            navController.navigateToTopLevel(TopLevelDestination.TODAY)
            navController.navigateToTopLevel(TopLevelDestination.LOG)
        }
        composeTestRule.waitForIdle()

        val routes = navController.currentBackStack.value.mapNotNull { it.destination.route }
        assertEquals(listOf(Routes.TODAY, Routes.LOG), routes)

        composeTestRule.runOnUiThread { assertTrue(navController.popBackStack()) }
        composeTestRule.waitForIdle()
        assertEquals(Routes.TODAY, navController.currentDestination?.route)
    }
}
