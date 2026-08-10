package cloud.trotter.log.strength.ui.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pins the default-resource copy while producer tests pin semantic state. */
@RunWith(RobolectricTestRunner::class)
class UiTextMappingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun backup_mapping_keeps_interruption_copy() {
        assertEquals("Couldn't start the restore. Nothing changed — try again.", context.getString(UiText.BackupError(BackupErrorKind.RESTORE_NOT_STARTED).resourceId(), ""))
    }

    @Test fun today_mapping_keeps_action_copy() {
        assertEquals("CONTINUE — 4 OF 18 SETS", context.getString(UiText.TodayAction(TodayActionKind.CONTINUE, "B", 4, 18).resourceId(), 4, 18))
    }

    @Test fun log_mapping_keeps_backfill_copy() {
        assertEquals("Publish 12 past workouts", context.getString(UiText.LogBackfill(false, 12).resourceId(), 12))
    }

    @Test fun widget_default_resource_is_the_only_copy() {
        assertEquals("SET UP YOUR PROGRAM", context.getString(R.string.widget_no_program))
    }

    @Test fun day_mapping_keeps_builder_copy() {
        assertEquals("Plates: 45 + 5 a side", context.getString(UiText.DayPlate("45 + 5").resourceId(), "45 + 5"))
        assertEquals("IN PROGRESS · 4 OF 18 SETS", context.getString(UiText.DayStatus(false, 4, 18).resourceId(), 4, 18))
    }
}
