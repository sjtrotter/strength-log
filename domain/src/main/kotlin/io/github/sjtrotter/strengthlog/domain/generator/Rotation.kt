package io.github.sjtrotter.strengthlog.domain.generator

import io.github.sjtrotter.strengthlog.domain.model.Program

/**
 * Rotation, not calendar (spec principle 1). The suggested next day advances
 * A→B→…→ and wraps to the first on completion of the last.
 */
object Rotation {

    /** The day id that follows [currentId] in [program], wrapping at the end. */
    fun next(program: Program, currentId: String): String {
        val days = program.days
        require(days.isNotEmpty()) { "program has no days" }
        val idx = days.indexOfFirst { it.id == currentId }
        require(idx >= 0) { "unknown day id: $currentId" }
        return days[(idx + 1) % days.size].id
    }
}
