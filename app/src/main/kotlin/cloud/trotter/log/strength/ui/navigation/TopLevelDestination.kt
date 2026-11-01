package cloud.trotter.log.strength.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.Routes

enum class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    TODAY(Routes.TODAY, R.string.nav_today, Icons.Outlined.Home),
    LOG(Routes.LOG, R.string.nav_log, Icons.AutoMirrored.Outlined.List),
    PLANS(Routes.PLANS, R.string.nav_plans, Icons.Outlined.DateRange),
    ;

    companion object {
        fun showsBarOn(route: String?): Boolean = entries.any { it.route == route }
    }
}
