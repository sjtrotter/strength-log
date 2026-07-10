package io.github.sjtrotter.strengthlog.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import io.github.sjtrotter.strengthlog.wear.ui.WearApp

/**
 * Single-activity Compose-for-Wear host (spec §9). Reads the one process-wide
 * [io.github.sjtrotter.strengthlog.wear.data.WatchTrackerClient] from
 * [StrengthLogWearApp] — the real Data Layer client (#20) — so Activity recreation
 * (rotation, ambient exit) never re-registers listeners or drops the edit queue.
 */
class MainActivity : ComponentActivity() {

    private var isAmbient by mutableStateOf(false)

    // Bumped on the system's own once-a-minute ambient refresh signal
    // (onUpdateAmbient) rather than driving the ambient clock off a
    // free-running coroutine, which isn't guaranteed to fire while the CPU is
    // suspended in ambient mode.
    private var ambientTick by mutableIntStateOf(0)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
            ambientTick++
        }

        override fun onExitAmbient() {
            isAmbient = false
        }

        override fun onUpdateAmbient() {
            ambientTick++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        val client = (application as StrengthLogWearApp).watchClient
        setContent {
            WearApp(client = client, isAmbient = isAmbient, ambientTick = ambientTick)
        }
    }
}
