package cloud.trotter.log.strength.audit

import cloud.trotter.log.strength.ui.day.workoutDaySheetFixture
import cloud.trotter.log.strength.ui.day.NextTime
import cloud.trotter.log.strength.ui.preview.PreviewActions
import cloud.trotter.log.strength.ui.preview.PreviewCardio
import cloud.trotter.log.strength.ui.preview.PreviewLift
import cloud.trotter.log.strength.ui.preview.PreviewPreviousResult
import cloud.trotter.log.strength.ui.preview.PreviewScreen
import cloud.trotter.log.strength.ui.preview.PreviewUiState
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditPreviewShotsTest : UiAuditTest() {

    @Test fun shot01PreviewNormal() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/01-preview-normal") { PreviewScreen(auditPreviewFixture(), PreviewActions({}, {}, {})) }
    }

    @Test fun shot02PreviewSupersetCardio() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/02-preview-superset-cardio") { PreviewScreen(auditPreviewFixture(workoutDaySheetFixture()).copy(cardio = PreviewCardio("Road run", "5:00", cloud.trotter.log.strength.domain.model.CardioActivity.ROAD_RUN)), PreviewActions({}, {}, {})) }
    }

    @Test fun shot03PreviewEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/03-preview-empty") { PreviewScreen(auditPreviewFixture().copy(lifts = emptyList()), PreviewActions({}, {}, {})) }
    }

    @Test fun shot04PreviewLong() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/04-preview-long") { PreviewScreen(auditPreviewFixture().copy(planName = LONG_PLAN, title = LONG_DAY, lifts = listOf(PreviewLift(longestExercise.name, "35 lb × 10", listOf(PreviewPreviousResult(null))))), PreviewActions({}, {}, {})) }
    }

    @Config(fontScale = 2f)
    @Test fun shot05PreviewNormalFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/05-preview-normal-font2", fontScale = 2f) { PreviewScreen(auditPreviewFixture(), PreviewActions({}, {}, {})) }
    }

    @Test fun shot06PreviewLoading() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/06-preview-loading") { PreviewScreen(PreviewUiState(), PreviewActions({}, {}, {})) }
    }

    @Test fun shot07PreviewCardioOnly() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/07-preview-cardio-only") { PreviewScreen(auditPreviewFixture().copy(lifts = emptyList(), cardio = PreviewCardio("Road run", "20:00", cloud.trotter.log.strength.domain.model.CardioActivity.ROAD_RUN)), PreviewActions({}, {}, {})) }
    }

    @Test fun shot08PreviewNoLastResults() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("preview/08-preview-no-last-results") { PreviewScreen(auditPreviewFixture().let { it.copy(lifts = it.lifts.map { lift -> lift.copy(working = null, previous = lift.previous.map { it.copy(last = null) }) }) }, PreviewActions({}, {}, {})) }
    }

    @Config(fontScale = 2f)
    @Test fun shot09PreviewLiftNoteFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = auditPreviewFixture().let { it.copy(lifts = it.lifts.map { lift ->
            lift.copy(note = "Belt on top, straps on back-off sets", nextTime = NextTime.Offer("+5 lb", false, 240.0, 235.0))
        }) }
        rule.auditShot("preview/09-preview-lift-note-font2", fontScale = 2f) {
            PreviewScreen(state, PreviewActions({}, {}, {}))
        }
    }

}
