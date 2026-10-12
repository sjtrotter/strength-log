package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Counts the production ExercisePage body through Compose tracing, including the adjacent pages. */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ExerciseCardSkipTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rebuildingTheDayRecomposesOnlyThePageThatChanged() {
        val state = mutableStateOf(pagerFixture().copy(selectedPageKey = "exercise:2"))
        var pageCompositions = 0
        val tracer = object : CompositionTracer {
            override fun isTraceInProgress(): Boolean = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.startsWith("cloud.trotter.log.strength.ui.day.ExercisePage (")) pageCompositions++
            }
            override fun traceEventEnd() = Unit
        }
        Composer.setTracer(tracer)
        try {
            rule.setContent { AppTheme { DayScreen(state.value, pagerActions(), DayEditUiState(), pagerEditActions()) } }
            rule.waitForIdle()
            assertTrue("The tracer must see the current and adjacent production pages", pageCompositions >= 3)
            rule.runOnIdle {
                pageCompositions = 0
                state.value = state.value.copy(exercises = state.value.exercises.map { card ->
                    if (card.programExerciseId != 2L) card.copy() else card.copy(
                        rows = card.rows.map { if (it.index == 1) it.copy(done = true) else it },
                        focusIndex = 2,
                    )
                })
            }
            rule.waitForIdle()
            assertEquals("An unchanged page must skip its body", 1, pageCompositions)
        } finally {
            Composer.setTracer(null)
        }
    }
}
