package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.Program

/**
 * Rotation, not calendar (spec principle 1). The suggested next day advances
 * A→B→…→ and wraps to the first on completion of the last.
 */
object Rotation {

    fun upNextAfter(program: Program, suggestedDayId: String?, finishedDayId: String): String? =
        if (suggestedDayId != finishedDayId && program.days.any { it.id == suggestedDayId }) {
            suggestedDayId
        } else {
            nextFor(program, finishedDayId)
        }

    fun nextFor(program: Program, currentId: String): String? {
        val days = program.rotationDays
        val index = days.indexOfFirst { it.id == currentId }
        return if (index < 0) null else days[(index + 1) % days.size].id
    }
}
