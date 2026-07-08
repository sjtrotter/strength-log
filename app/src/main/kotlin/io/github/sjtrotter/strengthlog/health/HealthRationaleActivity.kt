package io.github.sjtrotter.strengthlog.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary

/**
 * Handles Health Connect's mandatory permissions-rationale intents (#17): the
 * provider launches this to explain why the app wants health permissions,
 * before the user grants them. It's a plain, self-contained screen — no data,
 * no network — stating exactly what each permission is for and that everything
 * stays on-device.
 *
 * The full privacy-policy page (Play requirement for health permissions) is
 * #23's job; this activity only satisfies the manifest rationale contract that
 * has to ship with the permission declarations.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { RationaleScreen() } }
    }
}

@Composable
private fun RationaleScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Text("Health Connect", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Text(
            "strength.log uses Health Connect only on this device — it needs no internet access. " +
                "You choose which of these to allow, and the app works fully without any of them:",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(16.dp))
        Bullet("Write your completed workouts, so other fitness apps can see them.")
        Bullet("Read strength sessions from other apps, to list them in your Log.")
        Bullet("Read your latest bodyweight, to offer to update your training GOALs.")
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
}
