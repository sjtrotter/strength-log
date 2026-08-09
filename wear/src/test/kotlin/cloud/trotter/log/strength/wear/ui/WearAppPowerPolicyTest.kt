package cloud.trotter.log.strength.wear.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the whole module against reintroducing a screen lock (#161). A source
 * scan, not a behavioral test, on purpose: :wear's suite is pure JVM (no
 * Robolectric), and wiring an Android test harness to assert one View flag
 * would cost more than the policy it protects. The scan covers every spelling
 * that reaches the flag; if a future change legitimately needs a *scoped*
 * screen-on (an explicit active-effort phase), it updates this test in the
 * same commit and says why.
 */
class WearAppPowerPolicyTest {

    private val forbidden = listOf("keepScreenOn", "setKeepScreenOn", "FLAG_KEEP_SCREEN_ON")

    @Test
    fun `no wear source keeps the screen on`() {
        val mainSources = sequenceOf(File("src/main"), File("wear/src/main"))
            .first(File::isDirectory)
        val offenders = mainSources.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .flatMap { file ->
                val text = file.readText()
                forbidden.filter { text.contains(it, ignoreCase = true) }
                    .map { "${file.path}: $it" }
            }
            .toList()
        assertTrue(offenders.isEmpty(), "screen-lock spellings found: $offenders")
    }

    @Test
    fun `no wear code acquires a wake lock`() {
        // Kotlin sources only, deliberately: the MANIFEST must keep declaring
        // WAKE_LOCK — AmbientLifecycleObserver holds a lock internally and
        // ambient mode never arms without the permission. What #164 forbids is
        // app code holding one.
        val forbiddenWakeLock = listOf("PowerManager", "WakeLock", "newWakeLock")
        val mainSources = sequenceOf(File("src/main"), File("wear/src/main"))
            .first(File::isDirectory)
        val offenders = mainSources.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file ->
                val text = file.readText()
                forbiddenWakeLock.filter { text.contains(it, ignoreCase = true) }
                    .map { "${file.path}: $it" }
            }
            .toList()
        assertTrue(offenders.isEmpty(), "wake-lock spellings found: $offenders")
    }
}
