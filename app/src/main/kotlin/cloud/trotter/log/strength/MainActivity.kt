package cloud.trotter.log.strength

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.icon.DayIconManager
import cloud.trotter.log.strength.ui.AppNavHost
import cloud.trotter.log.strength.ui.theme.AppTheme
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

        // Keep-screen-on (#125) is held here, on the window, and not by a
        // DisposableEffect on the day screen. A per-screen effect releases the
        // wake the moment that screen leaves the composition, which is exactly
        // what happened walking Day → Log mid-workout: the phone went dark
        // between sets because the user had *navigated*, which is not a reason
        // to stop waiting for them.
        //
        // Scope is the whole app while the preference is on, not "workout
        // surfaces only". Two reasons: any list of workout surfaces has to be
        // maintained forever and re-introduces the same seam at its edges; and
        // the wake is now something the user asked for out loud, so honouring it
        // everywhere is the honest reading of the switch. It costs nothing when
        // they are elsewhere — FLAG_KEEP_SCREEN_ON only applies while this
        // window is visible, so backgrounding the app releases it for free.
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
        // Rotation also passes through onStop but recreates into the same task,
        // which would reopen the #96 teardown window while the user is still
        // looking at the app — apply only on a genuine exit (a skipped apply
        // self-heals on the next one). applyDayIcon(null) is a no-op
        // (dayIndexForIcon null-guards) and an unchanged day is a no-op
        // (shouldReapplyIcon), so calling this on every real stop is safe.
        if (!isChangingConfigurations) dayIconManager.applyDayIcon(iconDayId)
    }
}
