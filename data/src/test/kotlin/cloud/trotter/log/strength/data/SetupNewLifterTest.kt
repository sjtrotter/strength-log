package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupNewLifterTest : OpeningFixture() {
    private val new = SetupRecipeJson.encode(SetupAnswers(experience = LiftExperience.NEW))

    private suspend fun prepare(recipe: String?, firstDaysAgo: Long? = null): Long {
        val plan = converted(day("bb_back_squat"), day("bb_bench", id = "B"))
        lifter(plan, id(plan, "bb_back_squat"), squat(), squatRule())
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(recipeJson = recipe))
        history(plan, 22, eight)
        settings.setLifterProfile(LifterProfile(age = 30))
        if (firstDaysAgo != null) {
            val time = clock.millis() - Duration.ofDays(firstDaysAgo).toMillis()
            db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Day A", time, time, null, planId = null))
        }
        return plan
    }

    private suspend fun opening(recipe: String?, firstDaysAgo: Long? = null): OpeningDto? =
        OpeningJson.decode(opened(prepare(recipe, firstDaysAgo)).openingJson)

    @Test fun `a New answer recalibrates past fourteen days in the first six months`() = runTest {
        assertEquals(OpeningDto("RECALIBRATE"), opening(new))
    }

    @Test fun `a New answer with a log past six months uses the adult rule`() = runTest {
        assertEquals(OpeningDto("SCALED", 0.85), opening(new, 182))
    }

    @Test fun `a log one day short of six months still counts as new`() = runTest {
        assertEquals(OpeningDto("RECALIBRATE"), opening(new, 181))
    }

    @Test fun `trained answers use the adult rule`() = runTest {
        for (experience in listOf(LiftExperience.UNDER_A_YEAR, LiftExperience.A_YEAR_OR_MORE)) {
            assertEquals(OpeningDto("SCALED", 0.85), opening(SetupRecipeJson.encode(SetupAnswers(experience = experience))))
            repo.openRunFlow.first()?.let { repo.discardRun(it.id) }
        }
    }

    @Test fun `plans without a setup recipe never take the New rule`() = runTest {
        for (recipe in listOf(null, PlanRecipeJson.encode(WizardAnswers()))) {
            assertEquals(OpeningDto("SCALED", 0.85), opening(recipe))
            repo.openRunFlow.first()?.let { repo.discardRun(it.id) }
        }
    }

    @Test fun `the day preview opens as Start does`() = runTest {
        val plan = prepare(new)
        val preview = repo.observeDay(plan, "A").first().slots.first {
            it.main.performed.exerciseId == "bb_back_squat"
        }.main.openingInfo
        assertEquals(OpeningDto("RECALIBRATE"), preview)
        assertEquals(preview, OpeningJson.decode(opened(plan).openingJson))
    }
}
