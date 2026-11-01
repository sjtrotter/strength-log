package cloud.trotter.log.strength

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.ui.AppNavHost
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.rest.PhoneRestActions
import cloud.trotter.log.strength.rest.PhoneRestRuntime
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Single-activity Compose host. The nav graph lives in [AppNavHost].
 * The system-qualified splash draws the first frame; DataStore's explicit theme
 * preference intentionally overrides that system choice only after composition.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: TrackerRepository

    @Inject lateinit var phoneRestRuntime: PhoneRestRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePreference by repository.themePreferenceFlow.collectAsStateWithLifecycle(
                initialValue = cloud.trotter.log.strength.domain.theme.ThemePreference.SYSTEM,
            )
            AppTheme(preference = themePreference) {
                AppNavHost()
            }
        }

        // Held on the window, not per screen: a per-screen effect drops the
        // wake when the user navigates mid-workout. FLAG_KEEP_SCREEN_ON only
        // applies while this window is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.keepScreenOnFlow.collect { on ->
                    if (on) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            lifecycleScope.launch {
                PhoneRestActions.locked { phoneRestRuntime.setForeground(false, repository.phoneRestFlow.first()) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            PhoneRestActions.locked {
                val rest = repository.phoneRestFlow.first()
                phoneRestRuntime.setForeground(true, rest)
                rest?.let(phoneRestRuntime::arm)
            }
        }
    }
}
