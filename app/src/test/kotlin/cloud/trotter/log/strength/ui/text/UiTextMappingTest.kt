package cloud.trotter.log.strength.ui.text

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pins every semantic choice to its resolved default-resource copy. */
@RunWith(RobolectricTestRunner::class)
class UiTextMappingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertAllResolve(expected: List<Pair<UiText, String>>) {
        lateinit var actual: List<String>
        composeTestRule.setContent { actual = expected.map { (text) -> text.resolve() } }
        composeTestRule.waitForIdle()
        assertEquals(expected.map { it.second }, actual)
    }

    @Test
    fun backup_errors_resolve_every_interruption_and_validation_kind() {
        val detail = "detail"
        val expected = mapOf(
            BackupErrorKind.RESTORE_NOT_STARTED to "Couldn't start the restore. Nothing changed — try again.",
            BackupErrorKind.SETTINGS_PENDING to "Your data restored, but your settings didn't. Reopen the app and it'll finish.",
            BackupErrorKind.CLEANUP_PENDING to "Backup restored. A bit of tidying up is left; the app will finish it.",
            BackupErrorKind.BACKUP_TOO_LARGE to "That file is too large to be a strength-log backup (detail bytes).",
            BackupErrorKind.BACKUP_MALFORMED to "That file isn't a strength-log backup (not valid JSON, or the wrong shape).",
            BackupErrorKind.UNSUPPORTED_SCHEMA to "That backup was written by a version of the app this build can't read.",
            BackupErrorKind.INVALID_PAYLOAD to "That backup contains data this build can't decode — it may be corrupt.",
            BackupErrorKind.DANGLING_EXERCISE to "That backup references an exercise (detail) it doesn't define.",
            BackupErrorKind.INVALID_CUSTOM_EXERCISE to "That backup has an invalid custom exercise and can't be restored.",
            BackupErrorKind.INCONSISTENT to "That backup's data is inconsistent and can't be safely restored.",
            BackupErrorKind.CSV_TOO_LARGE to "That file is too large to import (detail bytes).",
            BackupErrorKind.CSV_EMPTY to "That CSV file has no data rows to import.",
            BackupErrorKind.CSV_MALFORMED to "That file isn't valid CSV — it looks truncated or corrupt.",
            BackupErrorKind.CSV_MISSING_COLUMNS to "That CSV is missing required column(s): detail.",
            BackupErrorKind.CSV_AMBIGUOUS_UNIT to "Can't tell whether 'detail' is lb or kg — add a Weight Unit column.",
            BackupErrorKind.CSV_MALFORMED_ROW to detail,
            BackupErrorKind.CSV_MISSING_APPROVAL to "Pick a movement pattern for: detail.",
        )

        assertEquals(BackupErrorKind.entries.toSet(), expected.keys)
        assertAllResolve(expected.map { (kind, copy) -> UiText.BackupError(kind, detail) to copy })
    }

    @Test
    fun file_access_errors_resolve_both_messages() {
        assertAllResolve(listOf(
            UiText.FileAccessFailure("disk offline") to "Couldn't access that file: disk offline",
            UiText.FilePermissionLost to "No permission to access that file anymore.",
        ))
    }

    @Test
    fun backup_statuses_resolve_every_kind() {
        val expected = mapOf(
            BackupStatusKind.BACKUP_EXPORTED to "Backup exported.",
            BackupStatusKind.BACKUP_RESTORED to "Backup restored.",
            BackupStatusKind.HISTORY_EXPORTED to "History exported.",
            BackupStatusKind.HISTORY_IMPORTED to "Imported 2 session(s), 9 set(s).",
        )

        assertEquals(BackupStatusKind.entries.toSet(), expected.keys)
        assertAllResolve(expected.map { (kind, copy) -> UiText.BackupStatus(kind, 2, 9) to copy })
    }

    @Test
    fun today_actions_resolve_every_kind_and_uppercase_day_ids_at_the_resource_boundary() {
        val expected = mapOf(
            TodayActionKind.START to "START DAY B",
            TodayActionKind.CONTINUE to "CONTINUE — 4 OF 18 SETS",
            TodayActionKind.FINISH to "FINISH DAY B",
        )

        assertEquals(TodayActionKind.entries.toSet(), expected.keys)
        assertAllResolve(expected.map { (kind, copy) -> UiText.TodayAction(kind, "b", 4, 18) to copy })
    }

    @Test
    fun today_cardio_resolves_both_variants() {
        assertAllResolve(listOf(
            UiText.TodayCardio(false, "Easy Zone 2") to "EASY · Zone 2",
            UiText.TodayCardio(true, "Hard cardio — intervals") to "HARD · Cardio — intervals",
        ))
    }

    @Test
    fun log_backfill_resolves_publishing_singular_and_plural() {
        assertAllResolve(listOf(
            UiText.LogBackfill(true, 12) to "Publishing…",
            UiText.LogBackfill(false, 1) to "Publish 1 past workout",
            UiText.LogBackfill(false, 12) to "Publish 12 past workouts",
        ))
    }

    @Test
    fun day_copy_resolves_both_plate_and_status_branches_and_both_helpers() {
        assertAllResolve(listOf(
            UiText.DayPlate(null) to "Plates: empty bar",
            UiText.DayPlate("45 + 5") to "Plates: 45 + 5 a side",
            UiText.DayStatus(false, 4, 18) to "IN PROGRESS · 4 OF 18 SETS",
            UiText.DayStatus(true, 18, 18) to "READY TO FINISH · 18 OF 18 SETS",
        ))
        assertEquals("Change the TOP set — ramp & back-off recalculate.", context.getString(R.string.day_main_helper))
        assertEquals("One tick checks the whole round — both moves, back-to-back.", context.getString(R.string.day_superset_helper))
    }

    @Test
    fun widget_default_resource_is_the_only_copy() {
        assertEquals("SET UP YOUR PROGRAM", context.getString(R.string.widget_no_program))
    }
}
