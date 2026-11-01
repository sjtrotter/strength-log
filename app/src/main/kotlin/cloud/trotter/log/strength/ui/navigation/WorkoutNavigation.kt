package cloud.trotter.log.strength.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import cloud.trotter.log.strength.ui.Routes

/** Completion always clears the workout and any preview or plan above Today. */
internal fun NavHostController.finishWorkout() {
    navigate(Routes.TODAY) {
        popUpTo(Routes.TODAY)
        launchSingleTop = true
    }
}

/** Keep the old destination identity so installed apps can restore their stacks. */
internal fun NavGraphBuilder.legacyPlanDestination(navController: NavHostController) {
    composable(Routes.PLAN) {
        LaunchedEffect(Unit) {
            navController.navigate(Routes.PLANS) {
                popUpTo(Routes.PLAN) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
