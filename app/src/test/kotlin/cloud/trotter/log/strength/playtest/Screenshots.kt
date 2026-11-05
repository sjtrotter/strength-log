package cloud.trotter.log.strength.playtest

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.test.espresso.Espresso
import cloud.trotter.log.strength.audit.dumpSemantics
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.RoboComponent
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.fetchImage
import com.github.takahirom.roborazzi.fetchRobolectricWindowRoots
import java.io.File
import org.robolectric.Shadows.shadowOf

@OptIn(InternalRoborazziApi::class)
internal fun SemanticsNodeInteractionsProvider.writePlaytestShot(file: File, density: Float) {
    val roots = onAllNodes(isRoot())
    val root = roots.onLast()
    root.assertExists()
    check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
    shadowOf(Looper.getMainLooper()).idle()
    val windows = fetchRobolectricWindowRoots()
    val options = RoborazziOptions()
    val bitmap = checkNotNull(if (roots.fetchSemanticsNodes().size > 1 || windows.size > 1) {
        Espresso.onIdle()
        RoboComponent.Screen(fetchRobolectricWindowRoots(), options).image
    } else {
        root.fetchSemanticsNode().fetchImage(options.recordOptions)
    })
    try {
        // Encode straight to disk; Roborazzi's AWT conversion allocates copies of every pixel.
        file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
    File(file.parentFile, "${file.nameWithoutExtension}.json").writeText(dumpSemantics(density))
}
