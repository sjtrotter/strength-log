package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupOlderAdultTest : OpeningFixture() {
    private suspend fun opening(recipe: String?, age: Int): OpeningDto? {
        val plan = converted(day("bb_back_squat"), day("bb_bench", id = "B"))
        lifter(plan, id(plan, "bb_back_squat"), squat(), squatRule())
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(recipeJson = recipe))
        history(plan, 22, eight)
        settings.setLifterProfile(LifterProfile(age = age))
        return OpeningJson.decode(opened(plan).openingJson)
    }

    @Test fun `a v2 older answer opens with the older rule over a younger profile`() = runTest {
        assertEquals(OpeningDto("RECALIBRATE"), opening(SetupRecipeJson.encode(SetupAnswers(olderAdult = true)), 30))
    }
    @Test fun `a v2 adult answer opens with the adult rule over an older profile`() = runTest {
        assertEquals(OpeningDto("SCALED", 0.85), opening(SetupRecipeJson.encode(SetupAnswers(olderAdult = false, experience = LiftExperience.A_YEAR_OR_MORE)), 70))
    }
    @Test fun `a v1 plan reads profile age`() = runTest {
        assertEquals(OpeningDto("RECALIBRATE"), opening(PlanRecipeJson.encode(WizardAnswers()), 70))
    }
    @Test fun `a plan without a recipe reads profile age`() = runTest {
        assertEquals(OpeningDto("RECALIBRATE"), opening(null, 70))
    }
}
