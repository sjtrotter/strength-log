package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.standards.TestFormat
import cloud.trotter.log.strength.domain.standards.TestPick
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TestPickSearchTest {
    private fun search(query: String = "", filter: TestPickFilter = TestPickFilter.ALL): List<TestPick> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestPickSearch.filter(query, filter) { context.getString(testLabel(it)) }
    }

    @Test fun `All joins every eligible library entry and the five runs without equipment filtering`() {
        assertEquals(ExerciseLibrary.entries.count { TestFormat.eligible(it).isNotEmpty() } + 5, search().size)
        assertEquals(FitnessTest.entries.map { TestPick.Run(it) }, search(filter = TestPickFilter.CARDIO))
        assertTrue(TestPick.Library(ExerciseLibrary.get("pullup")) in search())
        assertFalse(TestPick.Library(ExerciseLibrary.get("farmers_carry")) in search())
        assertFalse(TestPick.Library(ExerciseLibrary.get("box_jump")) in search())
    }

    @Test fun `search shares the exercise synonyms and searches run labels`() {
        assertEquals(listOf(TestPick.Library(ExerciseLibrary.get("bb_bench"))), search("bb bench"))
        assertTrue(TestPick.Library(ExerciseLibrary.get("incline_db")) in search("incline db"))
        assertEquals(listOf(TestPick.Run(FitnessTest.ONE_AND_HALF_MILE)), search("1.5-mile"))
        assertTrue(search(filter = TestPickFilter.HOLDS).all {
            it is TestPick.Library && TestFormat.MAX_HOLD in TestFormat.eligible(it.entry)
        })
        assertTrue(search(filter = TestPickFilter.STRENGTH).all {
            it is TestPick.Library && TestFormat.MAX_HOLD !in TestFormat.eligible(it.entry)
        })
    }

    @Test fun `picked entries other runs and a second lift on a taken row are disabled`() {
        val draft = SetupDraft().chooseFitnessTest().pick(TestPick.Run(FitnessTest.TWO_MILE))
            .pick(TestPick.Library(ExerciseLibrary.get("bb_bench")))
        assertEquals(R.string.setup_flow_test_one_run, TestPickSearch.disabledReason(TestPick.Run(FitnessTest.HAMR), draft))
        assertEquals(R.string.setup_flow_test_picked, TestPickSearch.disabledReason(TestPick.Run(FitnessTest.TWO_MILE), draft))
        assertEquals(R.string.setup_flow_test_one_lift,
            TestPickSearch.disabledReason(TestPick.Library(ExerciseLibrary.get("incline_db")), draft))
        assertNull(TestPickSearch.disabledReason(TestPick.Library(ExerciseLibrary.get("conv_dl")), draft))
        assertEquals(draft, draft.pick(TestPick.Run(FitnessTest.HAMR)))
        assertEquals(draft, draft.pick(TestPick.Library(ExerciseLibrary.get("incline_db"))))
        val cleared = draft.removeTestPick(FitnessTest.TWO_MILE.name).removeTestPick("bb_bench")
        assertNull(TestPickSearch.disabledReason(TestPick.Run(FitnessTest.HAMR), cleared))
    }
}
