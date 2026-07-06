package io.github.sjtrotter.strengthlog.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * Single-activity Compose-for-Wear host. Real watch UI lands in M5 — this
 * shows only enough to prove the module graph and build wiring work.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlaceholderScreen()
        }
    }
}

private val NearBlack = Color(0xFF0D0D0F)

@Composable
private fun PlaceholderScreen() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "strength.log", color = Color.White)
        }
    }
}

@Preview(device = "id:wearos_small_round")
@Composable
private fun PlaceholderScreenPreview() {
    PlaceholderScreen()
}
