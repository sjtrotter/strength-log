package cloud.trotter.log.strength.wear.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/** Guards the root against reintroducing an unconditional screen lock. */
class WearAppPowerPolicyTest {
    @Test
    fun `root composition never keeps the screen on`() {
        val relative = "src/main/kotlin/cloud/trotter/log/strength/wear/ui/WearApp.kt"
        val sourceFile = sequenceOf(File(relative), File("wear/$relative"))
            .first(File::isFile)
        val source = sourceFile.readText()
        assertFalse(source.contains("keepScreenOn", ignoreCase = true))
    }
}
