package cloud.trotter.log.strength.audit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.navigation.AppNavigationBar
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.theme.AppTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Real phone windows, including the content behind a modal's scrim. A font2 shot also carries
 * `@Config(fontScale = 2f)`: sheets and dialogs are separate windows that take their density from the
 * configuration, so the [LocalDensity] override alone never reached them.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun ComposeContentTestRule.auditShot(
    name: String,
    theme: ThemePreference = ThemePreference.DARK,
    fontScale: Float = 1f,
    beforeCapture: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            AppTheme(preference = theme, content = content)
        }
    }
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    beforeCapture()
    waitForIdle()
    val roots = onAllNodes(isRoot())
    val root = roots.onLast()
    root.assertExists()
    val file = File(requireNotNull(System.getProperty("uiAudit.dir")), "$name.png")
    check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
    if (roots.fetchSemanticsNodes().size > 1) {
        captureScreenRoboImage(file.absolutePath)
    } else {
        root.captureRoboImage(file)
    }
    val nodes = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { it.auditDescendants() }
        .filter { node ->
            val config = node.config
            config.contains(SemanticsProperties.Text) || config.contains(SemanticsProperties.EditableText) ||
                config.contains(SemanticsProperties.ContentDescription) ||
                config.contains(SemanticsActions.OnClick) || config.contains(SemanticsActions.OnLongClick)
        }
    File(file.parentFile, "${file.nameWithoutExtension}.json").writeText(
        nodes.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]\n") { it.auditJson(density.density) },
    )
}

private fun SemanticsNode.auditDescendants(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.auditDescendants() }

private fun SemanticsNode.auditJson(density: Float): String {
    val text = listOfNotNull(
        config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text },
        config.getOrNull(SemanticsProperties.EditableText)?.text,
    ).joinToString("\n")
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("\n").orEmpty()
    val role = config.getOrNull(SemanticsProperties.Role)?.toString()
    val clickable = config.contains(SemanticsActions.OnClick) || config.contains(SemanticsActions.OnLongClick)
    val bounds = boundsInRoot
    val touch = touchBounds()
    return listOf(
        "\"text\":${text.jsonString()}",
        "\"contentDescription\":${description.jsonString()}",
        "\"role\":${role?.jsonString() ?: "null"}",
        "\"clickable\":$clickable",
        "\"left\":${bounds.left / density}",
        "\"top\":${bounds.top / density}",
        "\"width\":${bounds.width / density}",
        "\"height\":${bounds.height / density}",
        "\"touchWidth\":${touch?.let { it.width / density }}",
        "\"touchHeight\":${touch?.let { it.height / density }}",
        "\"disabled\":${config.contains(SemanticsProperties.Disabled)}",
    ).joinToString(prefix = "  {", postfix = "}")
}

/**
 * The area Compose accepts taps in: a clickable node smaller than the 48 dp minimum touch target gets its
 * hit area widened to it. Compose keeps this as an internal property, so it is read reflectively; null if
 * a Compose update renames it, and the JSON then says so rather than guessing.
 */
private fun SemanticsNode.touchBounds(): Rect? =
    runCatching { touchBoundsGetter?.invoke(this) as? Rect }.getOrNull()

private val touchBoundsGetter = SemanticsNode::class.java.methods.firstOrNull { it.name.startsWith("getTouchBoundsInRoot") }

// No serialization runtime is exposed on the app test compile classpath.
private fun String.jsonString(): String = buildString {
    append('"')
    for (character in this@jsonString) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else append(character)
        }
    }
    append('"')
}

/** JUnit creates a fresh rule and composition for each independently selectable shot. */
abstract class UiAuditTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before fun fixLocaleAndTimeZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }
}

@Composable
internal fun AuditDestination(destination: TopLevelDestination, content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = { AppNavigationBar(selectedRoute = destination.route, onSelect = {}) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) { content() }
    }
}
