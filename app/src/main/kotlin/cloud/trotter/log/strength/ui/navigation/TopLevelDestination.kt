package cloud.trotter.log.strength.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.Routes

enum class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    TODAY(Routes.TODAY, R.string.nav_today, R.drawable.ic_nav_home_outlined, R.drawable.ic_nav_home_filled),
    LOG(Routes.LOG, R.string.nav_log, R.drawable.ic_nav_history_outlined, R.drawable.ic_nav_history_filled),
    PLANS(Routes.PLANS, R.string.nav_plans, R.drawable.ic_nav_event_repeat_outlined, R.drawable.ic_nav_event_repeat_filled),
    ;

    companion object {
        fun showsBarOn(route: String?): Boolean = entries.any { it.route == route }
    }
}
