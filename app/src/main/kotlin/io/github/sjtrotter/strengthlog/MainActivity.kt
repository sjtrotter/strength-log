package io.github.sjtrotter.strengthlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * Single-activity Compose host. The real screens land in M3 — this shows
 * only enough to prove the module graph and build wiring work.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlaceholderScreen()
        }
    }
}

private val NearBlack = Color(0xFF0D0D0F)

@Composable
private fun PlaceholderScreen() {
    Surface(color = NearBlack) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "strength.log",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Preview
@Composable
private fun PlaceholderScreenPreview() {
    PlaceholderScreen()
}
