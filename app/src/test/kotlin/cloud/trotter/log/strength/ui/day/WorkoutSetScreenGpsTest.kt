package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WorkoutSetScreenGpsTest {
    @get:Rule val rule = createComposeRule()
    private fun show(gps: CardioGpsState?, activity: CardioActivity = CardioActivity.ROAD_RUN) {
        val base = workoutSetCardioRunningFixture()
        val cardio = requireNotNull(base.cardio)
        val block = requireNotNull(cardio.block).copy(activity = activity)
        val json = CardioBlockJson.encode(block)
        val state = base.copy(cardio = cardio.copy(gps = gps, block = block, distanceUnit = DistanceUnit.MI,
            run = cardio.run?.copy(blockJson = json, sourceBlockJson = json)))
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
    }
    @Test fun `live shows distance and pace in miles and hides typed distance`() {
        show(CardioGpsState(GpsShown.LIVE, 1996, 3.0))
        listOf("1.24 mi", "Distance", "8:56 /mi", "Pace").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithTag("workout-cardio-distance").assertDoesNotExist()
    }
    @Test fun `finding shows its status without a distance value`() {
        show(CardioGpsState(GpsShown.FINDING))
        rule.onNodeWithText("Finding GPS").assertExists()
        rule.onNodeWithText("1.24 mi").assertDoesNotExist()
    }
    @Test fun `no signal keeps distance and leaves pace blank`() {
        show(CardioGpsState(GpsShown.NO_SIGNAL, 1996))
        rule.onNodeWithText("1.24 mi").assertExists()
        rule.onNodeWithText("No GPS signal").assertExists()
        rule.onNodeWithText("Pace").assertExists()
        rule.onNodeWithText("8:56 /mi").assertDoesNotExist()
    }
    @Test fun `partial keeps the distance with the lost signal label`() {
        show(CardioGpsState(GpsShown.PARTIAL, 1996, 3.0))
        rule.onNodeWithText("1.24 mi").assertExists()
        rule.onNodeWithText("GPS lost signal").assertExists()
    }
    @Test fun `location off offers settings and typed distance`() {
        show(CardioGpsState(GpsShown.LOCATION_OFF))
        rule.onNodeWithText("Location is off").assertExists()
        rule.onNodeWithText("Turn on").assertExists()
        rule.onNodeWithTag("workout-cardio-distance").assertExists()
    }
    @Test fun `absent GPS keeps the original typed field`() {
        show(null)
        rule.onNodeWithTag("workout-cardio-distance").assertExists()
        rule.onNodeWithTag("workout-gps-status").assertDoesNotExist()
    }
    @Test fun `rides show speed in miles per hour`() {
        show(CardioGpsState(GpsShown.LIVE, 1996, 6.8), CardioActivity.OUTDOOR_RIDE)
        rule.onNodeWithText("15.2 mph").assertExists()
        rule.onNodeWithText("Speed").assertExists()
    }
}
