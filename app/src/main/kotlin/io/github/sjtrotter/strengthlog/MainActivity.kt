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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppNavHost()
            }
        }

        // Keeps the home-screen launcher icon in sync with the rotation day
        // (#22) — swaps the enabled activity-alias whenever the suggested
        // day changes, which covers rotation advance automatically.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.suggestedDayFlow.collect { dayIconManager.applyDayIcon(it) }
            }
        }
    }
}
