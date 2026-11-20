package cloud.trotter.log.strength.playtest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenListingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listsControlsInReadingOrderWithKindsAndFlags() {
        compose.setContent {
            AppTheme {
                Column {
                    Text("Today", Modifier.semantics { heading() })
                    Button(onClick = {}, modifier = Modifier.testTag("today-start")) { Text("Start day A") }
                    Button(onClick = {}, enabled = false) { Text("Finish") }
                    Column(Modifier.selectable(selected = true, role = Role.RadioButton, onClick = {})) {
                        Text("Lift")
                        Text("Strength, muscle, or fitness")
                    }
                    Row(Modifier.toggleable(value = true, role = Role.Switch, onValueChange = {})) {
                        Text("GPS distance")
                        Switch(checked = true, onCheckedChange = null)
                    }
                    OutlinedTextField(value = "135", onValueChange = {}, label = { Text("Weight (lb)") })
                    IconButton(onClick = {}, modifier = Modifier.semantics { contentDescription = "More" }) {
                        Box(Modifier.size(24.dp))
                    }
                }
            }
        }
        assertEquals(listOf(
            "## Controls", "[screen]",
            "  heading \"Today\"",
            "  button \"Start day A\" tag today-start",
            "  button \"Finish\" (disabled)",
            "  radio \"Lift / Strength, muscle, or fitness\" (selected)",
            "  switch \"GPS distance\" (checked)",
            "  field \"Weight (lb)\" = \"135\"",
            "  button desc \"More\"",
        ), compose.screenListing("Controls", emptyList()).lines().dropLast(1))
    }

    @Test fun dialogLayerComesBeforeTheScreen() {
        val property = "robolectric.windowManager.useRequestedSize"
        val previous = System.getProperty(property)
        System.setProperty(property, "true")
        try {
            compose.setContent {
                AppTheme {
                    Text("Today")
                    AlertDialog(onDismissRequest = {}, title = { Text("Finish workout?") },
                        confirmButton = { Button(onClick = {}) { Text("Finish") } })
                }
            }
            val listing = compose.screenListing("Dialog", emptyList())
            assertTrue(listing.indexOf("[dialog]") < listing.indexOf("[screen]"))
            assertTrue(listing.substringBefore("[screen]").contains("button \"Finish\""))
            assertFalse(listing.substringAfter("[screen]").contains("Finish"))
            assertTrue(listing.substringAfter("[screen]").contains("text \"Today\""))
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test fun offscreenScrollContentIsMarkedAndHiddenNodesAreDropped() {
        compose.setContent {
            AppTheme {
                Column {
                    Column(Modifier.height(100.dp).verticalScroll(rememberScrollState())) {
                        Text("Top")
                        Spacer(Modifier.height(300.dp))
                        Text("Far below")
                    }
                    Box(Modifier.size(0.dp)) { Text("Hidden") }
                }
            }
        }
        val listing = compose.screenListing("Scroll", emptyList())
        assertTrue(listing.contains("  text \"Far below\" (offscreen)\n"))
        assertTrue(listing.contains("  scroll area (more below)\n"))
        assertFalse(listing.contains("Hidden"))
    }

    @Test fun headerAndAfterLineAndEscaping() {
        compose.setContent { AppTheme { Column { Text("A \"quote\" and \\"); Text("x".repeat(200)) } } }
        val listing = compose.screenListing("Example", listOf("tap \"Next\"", "back"))
        assertTrue(listing.startsWith("## Example\nafter: tap \"Next\"; back\n"))
        assertTrue(listing.contains("  text \"A \\\"quote\\\" and \\\\\"\n"))
        assertTrue(listing.contains("  text \"${"x".repeat(157)}…\"\n"))
        assertTrue(listing.endsWith("\n"))
    }
}
