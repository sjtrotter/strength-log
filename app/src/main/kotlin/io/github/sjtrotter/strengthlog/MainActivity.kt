package io.github.sjtrotter.strengthlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.sjtrotter.strengthlog.ui.AppNavHost
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme

/** Single-activity Compose host. The nav graph lives in [AppNavHost]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppNavHost()
            }
        }
    }
}
