package cloud.trotter.log.strength.playtest

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.test.platform.app.InstrumentationRegistry
import cloud.trotter.log.strength.audit.dumpSemantics
import java.io.File

internal fun SemanticsNodeInteractionsProvider.writePlaytestShot(file: File, density: Float) {
    val roots = onAllNodes(isRoot())
    val root = roots.onLast()
    root.assertExists()
    check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
    val bitmap = if (roots.fetchSemanticsNodes().size > 1) {
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    } else {
        root.captureToImage().asAndroidBitmap()
    }
    try {
        // Encode straight to disk; Roborazzi's AWT conversion allocates copies of every pixel.
        file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
    File(file.parentFile, "${file.nameWithoutExtension}.json").writeText(dumpSemantics(density))
}
