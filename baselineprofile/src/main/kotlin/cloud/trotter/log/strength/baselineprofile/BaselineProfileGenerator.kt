package cloud.trotter.log.strength.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the app the way a lifter does and records what the JIT would otherwise
 * have to learn on every cold start (#156).
 *
 * The output is `app/src/release/generated/baselineProfiles/baseline-prof.txt`,
 * which AGP compiles AOT into the release APK — the day screen's scroll path is
 * exactly the kind of deep Compose call graph that runs interpreted for the
 * first few hundred milliseconds without one, which is why the phone has a jank
 * history at all.
 *
 * Generate it on a connected device or emulator:
 *
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 *
 * There is no checked-in profile until someone runs that on a real machine; a
 * hand-written one would be a lie about what the app executes.
 *
 * ## Why this reads so defensively
 *
 * A profile generator is a UI script that has to survive a *fresh install* —
 * no program, no logs, the setup wizard in the way. Every navigation step below
 * is therefore best-effort: if the wizard's shape changes, the run still
 * collects a smaller profile instead of failing CI. The two [fling] calls are
 * the point of the exercise, so they run against whatever scroller is on
 * screen, which is the day list when navigation worked and Today when it did
 * not.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupTodayAndTheDayScreen() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        completeSetupWizardIfShown(device)
        fling(device) // Today
        openTodaysWorkout(device)
        fling(device) // the day screen's card list — the jank surface
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "cloud.trotter.log.strength"
        const val WAIT_MS = 5_000L

        /** More than the wizard has steps, so a stuck step ends the loop rather
         *  than spinning until the instrumentation times out. */
        const val MAX_WIZARD_STEPS = 12

        fun completeSetupWizardIfShown(device: UiDevice) {
            repeat(MAX_WIZARD_STEPS) {
                val next = device.findObject(By.text("NEXT"))
                    ?: device.findObject(By.text("GENERATE PROGRAM"))
                    ?: return
                next.click()
                device.waitForIdle(WAIT_MS)
            }
        }

        /** Today's primary action, whatever phase the day is in. */
        fun openTodaysWorkout(device: UiDevice) {
            val start = device.wait(Until.findObject(By.textStartsWith("START DAY")), WAIT_MS)
                ?: device.findObject(By.textStartsWith("CONTINUE"))
                ?: device.findObject(By.textStartsWith("FINISH DAY"))
                ?: return
            start.click()
            device.waitForIdle(WAIT_MS)
        }

        /** Down and back up, so the profile covers composing items on the way in
         *  *and* the reuse path on the way back. */
        fun fling(device: UiDevice) {
            val scroller = device.findObject(By.scrollable(true)) ?: return
            scroller.setGestureMargin(device.displayWidth / 5)
            scroller.fling(Direction.DOWN)
            device.waitForIdle(WAIT_MS)
            scroller.fling(Direction.UP)
            device.waitForIdle(WAIT_MS)
        }
    }
}
