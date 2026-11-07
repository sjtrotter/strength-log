package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind

/**
 * Rotation, not calendar (spec principle 1). The suggested next day advances
 * A→B→…→ and wraps to the first on completion of the last.
 */
object Rotation {

    fun upNextAfter(program: Program, suggestedDayId: String?, finishedDayId: String): String? {
        val suggested = program.days.firstOrNull { it.id == suggestedDayId }
        return when {
            suggested?.kind == ProgramDayKind.REST && trainingDayFrom(program, suggested.id) == finishedDayId ->
                nextFor(program, finishedDayId)
            suggestedDayId != finishedDayId && suggested != null -> suggestedDayId
            else -> nextFor(program, finishedDayId)
        }
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
