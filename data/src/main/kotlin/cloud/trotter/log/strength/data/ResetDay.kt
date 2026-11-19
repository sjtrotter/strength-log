package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.serialization.hasSetupRecipe
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.units.WeightUnit

/** The day Reset day to plan writes for [dayId], or null when the plan offers no reset. Only generated Lift setup
 *  plans reset, and only to a training day their recipe produces (design §4.6.3, revision 49 §1.7). */
fun PlanEntity.resetDay(dayId: String, unit: WeightUnit): ProgramDay? {
    if (origin != PlanOrigin.GENERATED.name || !hasSetupRecipe) return null
    val answers = setupAnswers(unit)?.takeIf { it.branch == PlanBranch.LIFT } ?: return null
    return runCatching { Planner.day(answers, dayId) }.getOrNull()?.day?.takeIf { it.kind != ProgramDayKind.REST }
}
