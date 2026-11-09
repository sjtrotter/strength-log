package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind

/**
 * Rotation, not calendar (spec principle 1). The suggested next day advances
 * A→B→…→ and wraps to the first on completion of the last.
 */
object Rotation {

    fun upNextAfter(program: Program, suggestedDayId: String?, finishedDayId: String): String? =
        if (advancesFrom(program, suggestedDayId, finishedDayId)) nextFor(program, finishedDayId) else suggestedDayId

    /** Whether finishing [finishedDayId] completes a cycle: it is the rotation's last training day and the rotation
     *  advances from it. */
    fun wraps(program: Program, suggestedDayId: String?, finishedDayId: String): Boolean =
        program.rotationDays.lastOrNull { it.kind != ProgramDayKind.REST }?.id == finishedDayId &&
            advancesFrom(program, suggestedDayId, finishedDayId)

    private fun advancesFrom(program: Program, suggestedDayId: String?, finishedDayId: String): Boolean {
        val suggested = program.days.firstOrNull { it.id == suggestedDayId } ?: return true
        return suggested.id == finishedDayId ||
            (suggested.kind == ProgramDayKind.REST && trainingDayFrom(program, suggested.id) == finishedDayId)
    }

    fun trainingDayFrom(program: Program, dayId: String): String? {
        var current = dayId
        repeat(program.rotationDays.size) {
            val day = program.rotationDays.firstOrNull { it.id == current } ?: return null
            if (day.kind != ProgramDayKind.REST) return current
            current = nextFor(program, current) ?: return null
        }
        return null
    }

    fun nextFor(program: Program, currentId: String): String? {
        val days = program.rotationDays
        val index = days.indexOfFirst { it.id == currentId }
        return if (index < 0) null else days[(index + 1) % days.size].id
    }
}
