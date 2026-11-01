package cloud.trotter.log.strength.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1024dp-h500dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListingImagesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun launcherIcon(): AdaptiveIconDrawable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
    }

    @Test
    fun icon() {
        val icon = launcherIcon()
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor((icon.background as ColorDrawable).color)
        val foreground = requireNotNull(icon.foreground)
        foreground.setBounds(-96, -96, 608, 608)
        foreground.draw(canvas)
        bitmap.setHasAlpha(false)
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("listingImages.dir"), "icon.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        bitmap.recycle()
    }

    @Test
    fun featureGraphic() {
        val listing = File(requireNotNull(System.getProperty("listing.dir")))
        val title = File(listing, "title.txt").readText().trim()
        val description = File(listing, "short_description.txt").readText().trim()
        val sentenceEnd = description.indexOf(". ")
        val tagline = if (sentenceEnd >= 0) description.substring(0, sentenceEnd + 1) else description
        val iconBackground = Color((launcherIcon().background as ColorDrawable).color)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.DARK) {
                Box(
                    Modifier.size(1024.dp, 500.dp).background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val shape = MaterialTheme.shapes.large
                        Box(
                            Modifier.size(192.dp).clip(shape).background(iconBackground)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painterResource(R.drawable.ic_barbell_foreground),
                                contentDescription = null,
                                modifier = Modifier.requiredSize(264.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                title,
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.displayLarge.copy(fontSize = 112.sp, lineHeight = 112.sp),
                            )
                            Text(
                                tagline,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 32.sp, lineHeight = 40.sp),
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        val root = composeTestRule.onRoot()
        root.assertWidthIsEqualTo(1024.dp).assertHeightIsEqualTo(500.dp)
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("listingImages.dir"), "featureGraphic.png")
            file.parentFile.mkdirs()
            val bitmap = root.captureToImage().asAndroidBitmap()
            bitmap.setHasAlpha(false)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
