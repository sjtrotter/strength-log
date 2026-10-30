package cloud.trotter.log.strength.wear.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the whole module against reintroducing a screen lock. Scanning every
 * source catches routes to the flag beyond any single composable or View,
 * including active cardio and rep windows.
 */
class WearAppPowerPolicyTest {

    private val forbidden = listOf("keepScreenOn", "setKeepScreenOn", "FLAG_KEEP_SCREEN_ON")

    @Test
    fun `no wear code keeps the screen on`() {
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
        // Kotlin sources only: the MANIFEST must keep declaring WAKE_LOCK —
        // AmbientLifecycleObserver holds a lock internally and ambient mode
        // never arms without the permission. App code must not hold one.
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
