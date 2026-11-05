package cloud.trotter.log.strength.audit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.FinishFailedScreen
import cloud.trotter.log.strength.ui.day.FinishingScreen
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRow
import cloud.trotter.log.strength.ui.day.receipt.attemptFixture
import cloud.trotter.log.strength.ui.day.receipt.cardioReceiptFixture
import cloud.trotter.log.strength.ui.day.receipt.finishingFixture
import cloud.trotter.log.strength.ui.day.receipt.partialReceiptFixture
import cloud.trotter.log.strength.ui.day.receipt.receiptFixture
import cloud.trotter.log.strength.ui.day.receipt.timeTargetFixture
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditFinishShotsTest : UiAuditTest() {

    @Test fun shot01FinishingBeforeReveal() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val show = mutableStateOf(false)
        rule.auditShot("finish/01-finishing-before-reveal", beforeCapture = {
            rule.runOnIdle { show.value = true }
            rule.mainClock.advanceTimeBy(16)
        }) {
            if (show.value) FinishingScreen(attemptFixture(), finishingFixture())
            else AuditDay(finishingFixture())
        }
    }

    @Test fun shot02FinishingAfterReveal() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/02-finishing-after-reveal") { FinishingScreen(attemptFixture(), finishingFixture()) }
    }

    @Test fun shot03FinishFailed() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/03-finish-failed") { FinishFailedScreen(attemptFixture().copy(failed = true), {}, {}) }
    }

    @Test fun shot04ReceiptStrength() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/04-receipt-strength") { AuditDay(finishingFixture(), receipt = receiptFixture()) }
    }

    @Test fun shot05ReceiptEveryRowOpen() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/05-receipt-every-row-open", beforeCapture = {
            // Receipt columns compose every row, including those below the viewport.
            val rows = receiptFixture().groups.flatMap { it.rows }.filter { it.shape == ReceiptRow.Shape.STRAIGHT }
            for (row in rows) {
                rule.onNodeWithContentDescription(row.name, substring = true).performScrollTo().performClick()
                rule.mainClock.advanceTimeBy(300)
            }
            rule.onNodeWithContentDescription("Barbell Back Squat", substring = true).performScrollTo()
        }) { AuditDay(finishingFixture(), receipt = receiptFixture()) }
    }

    @Test fun shot06ReceiptRecordsUnchecked() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/06-receipt-records-unchecked") { AuditDay(finishingFixture(), receipt = receiptFixture().let { it.copy(recordsChecked = false, groups = it.groups.map { group -> group.copy(rows = group.rows.map { row -> row.copy(record = null) }) }) }) }
    }

    @Test fun shot07ReceiptAllPlansDone() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/07-receipt-all-plans-done") { AuditDay(finishingFixture(), receipt = receiptFixture().copy(allPlansDone = true)) }
    }

    @Test fun shot08ReceiptCardioIntervals() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/08-receipt-cardio-intervals") { AuditDay(finishingFixture(), receipt = cardioReceiptFixture()) }
    }

    @Test fun shot09ReceiptCardioTimeNoDistance() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/09-receipt-cardio-time-no-distance") { AuditDay(finishingFixture(), receipt = timeTargetFixture().let { it.copy(cardioRows = it.cardioRows.map { row -> row.copy(record = null, intervals = row.intervals.map { interval -> interval.copy(metres = null) }) }) }) }
    }

    @Test fun shot10ReceiptLong() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/10-receipt-long") { AuditDay(finishingFixture(), receipt = receiptFixture().let { it.copy(planName = LONG_PLAN, dayTitle = LONG_DAY, groups = it.groups.map { group -> group.copy(rows = group.rows.map { row -> row.copy(name = longestExercise.name) }) }) }) }
    }

    @Config(fontScale = 2f)
    @Test fun shot11ReceiptStrengthFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/11-receipt-strength-font2", fontScale = 2f) { AuditDay(finishingFixture(), receipt = receiptFixture()) }
    }

    @Test fun shot12ReceiptStrengthLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/12-receipt-strength-light", theme = ThemePreference.LIGHT) { AuditDay(finishingFixture(), receipt = receiptFixture()) }
    }

    @Test fun shot13ReceiptCardioLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/13-receipt-cardio-light", theme = ThemePreference.LIGHT) { AuditDay(finishingFixture(), receipt = cardioReceiptFixture()) }
    }

    @Test fun shot14ReceiptUnknownDuration() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/14-receipt-unknown-duration") { AuditDay(finishingFixture(), receipt = receiptFixture().copy(minutes = null)) }
    }

    @Test fun shot15ReceiptPartial() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/15-receipt-partial") { AuditDay(finishingFixture(), receipt = partialReceiptFixture()) }
    }

    @Test fun shot16ReceiptWithNote() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/16-receipt-with-note") { AuditDay(finishingFixture(), receipt = receiptFixture().copy(note = "The last round felt steady. Keep the same load next time.")) }
    }

    @Test fun shot17ReceiptCardioShortened() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/17-receipt-cardio-shortened") { AuditDay(finishingFixture(), receipt = timeTargetFixture().let { it.copy(cardioRows = it.cardioRows.map { row -> row.copy(record = null, intervals = row.intervals.map { interval -> interval.copy(status = CardioSetStatus.SHORTENED, elapsedSeconds = 900) }) }) }) }
    }

    @Test fun shot18ReceiptCardioSkipped() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/18-receipt-cardio-skipped") { AuditDay(finishingFixture(), receipt = cardioReceiptFixture().let { it.copy(cardioRows = it.cardioRows.map { row -> row.copy(record = null, intervals = row.intervals.map { interval -> interval.copy(status = CardioSetStatus.SKIPPED, elapsedSeconds = 0, metres = null) }) }) }) }
    }

    @Test fun shot19FinishingLoading() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/19-finishing-loading") { FinishingScreen(attemptFixture(), DayUiState(loading = true)) }
    }

    @Test fun shot20ReceiptCardioTimeDistance() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("finish/20-receipt-cardio-time-distance") { AuditDay(finishingFixture(), receipt = timeTargetFixture()) }
    }

}
