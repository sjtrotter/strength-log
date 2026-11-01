package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.PlanEntity

fun preferredPlan(plans: List<PlanEntity>, storedId: Long?): PlanEntity? =
    plans.firstOrNull { it.id == storedId && !it.archived }
        ?: plans.filterNot { it.archived }.minWithOrNull(compareBy({ it.position }, { it.id }))
