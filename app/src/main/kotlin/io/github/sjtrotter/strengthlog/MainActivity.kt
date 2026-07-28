package io.github.sjtrotter.strengthlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.icon.DayIconManager
import io.github.sjtrotter.strengthlog.ui.AppNavHost
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Single-activity Compose host. The nav graph lives in [AppNavHost]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: TrackerRepository

    @Inject
    lateinit var dayIconManager: DayIconManager

    // Keeps the home-screen launcher icon in sync with the rotation day
    // (#22). Only recorded here, not applied — swapping the enabled
    // activity-alias while this activity is resumed can tear down the
    // running task on some launchers, making the app vanish the moment
    // DONE is pressed (#96). The icon is invisible while the user is
    // inside the app anyway, so the swap happens in onStop instead.
    private var iconDayId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppNavHost()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.suggestedDayFlow.collect { iconDayId = it }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Rotation also passes through onStop but recreates into the same task,
        // which would reopen the #96 teardown window while the user is still
        // looking at the app — apply only on a genuine exit (a skipped apply
        // self-heals on the next one). applyDayIcon(null) is a no-op
        // (dayIndexForIcon null-guards) and an unchanged day is a no-op
        // (shouldReapplyIcon), so calling this on every real stop is safe.
        if (!isChangingConfigurations) dayIconManager.applyDayIcon(iconDayId)
    }
}
