package cloud.trotter.log.strength.health

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles Health Connect's mandatory permissions-rationale intents (#17): the
 * provider launches this to explain why the app wants health permissions,
 * before the user grants them. It's a plain screen with no workout data or
 * network access, stating exactly what each permission is for and that
 * everything stays on-device.
 *
 * The full privacy-policy page (Play requirement for health permissions) is
 * #23's job; this activity only satisfies the manifest rationale contract that
 * has to ship with the permission declarations.
 */
@AndroidEntryPoint
class HealthRationaleActivity : ComponentActivity() {

    @Inject
    lateinit var repository: TrackerRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themePreference by repository.themePreferenceFlow.collectAsStateWithLifecycle(
                initialValue = ThemePreference.SYSTEM,
            )
            AppTheme(preference = themePreference) { RationaleScreen() }
        }
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
        Text(stringResource(R.string.health_rationale_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.health_rationale_body),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(16.dp))
        Bullet(stringResource(R.string.health_rationale_write_workouts_bullet))
        Bullet(stringResource(R.string.health_rationale_read_sessions_bullet))
        Bullet(stringResource(R.string.health_rationale_read_bodyweight_bullet))
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        "•  $text",
        color = TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
