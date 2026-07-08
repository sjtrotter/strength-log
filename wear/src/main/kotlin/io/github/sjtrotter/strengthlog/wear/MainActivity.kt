package io.github.sjtrotter.strengthlog.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import io.github.sjtrotter.strengthlog.wear.data.FakeWatchClient
import io.github.sjtrotter.strengthlog.wear.ui.WearApp

/**
 * Single-activity Compose-for-Wear host (spec §9). Wired against
 * [FakeWatchClient] here — #20 swaps in the real Data Layer client without
 * touching this class or the screens.
 */
class MainActivity : ComponentActivity() {

    private var isAmbient by mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
        }

        override fun onExitAmbient() {
            isAmbient = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        val client = FakeWatchClient()
        setContent {
            WearApp(client = client, isAmbient = isAmbient)
        }
    }
}
