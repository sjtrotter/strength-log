package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.TestGoalJson
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.log.testResultFixture
import java.time.LocalDate

internal fun testGoalPlanFixture(): PlanUiState {
    val goal = TestGoal(listOf(TestEventGoal(TestRef("aft_hrp", 1, 120), 40),
        TestEventGoal(TestRef("aft_plank", 1), 220)), LocalDate.of(2026, 11, 15).toEpochDay())
    return PlanScreenBuilder.buildUiState(
        PlanEntity(1, "Test prep", PlanOrigin.GENERATED.name, 0, 0, testGoalJson = TestGoalJson.encode(goal)),
        Program(listOf(ProgramDay("A", "Full Body", "", listOf(ProgramExercise("hand_release_pushup"))))),
        ExerciseCatalog.CODE_ONLY, WeightUnit.LB, todayEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
        testResults = listOf(requireNotNull(testResultFixture().toDomain())),
    )
}
