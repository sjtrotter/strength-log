package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.Program

/**
 * Rotation, not calendar (spec principle 1). The suggested next day advances
 * A→B→…→ and wraps to the first on completion of the last.
 */
object Rotation {

    /** Authored plans include every day; generated CARDIO days do not advance rotation. */
    fun nextFor(program: Program, currentId: String, authored: Boolean): String? {
        val days = if (authored) program.days else program.strengthDays
        val index = days.indexOfFirst { it.id == currentId }
        return if (index < 0) null else days[(index + 1) % days.size].id
    }

    /** The day id that follows [currentId] in [program], wrapping at the end. */
    fun next(program: Program, currentId: String): String {
        val days = program.strengthDays
        require(days.isNotEmpty()) { "program has no days" }
        val idx = days.indexOfFirst { it.id == currentId }
        require(idx >= 0) { "unknown day id: $currentId" }
        return days[(idx + 1) % days.size].id
    }
}
