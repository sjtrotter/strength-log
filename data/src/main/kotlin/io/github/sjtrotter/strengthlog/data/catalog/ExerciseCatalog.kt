package io.github.sjtrotter.strengthlog.data.catalog

import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern

/**
 * The single exercise lookup for the whole app (PLAN.md A4): the in-code
 * [ExerciseLibrary] (seed truth) merged with the user's custom-exercise overlay.
 *
 * SSOT holds because custom ids are prefixed `custom_` and can never collide with
 * a catalog id, so the merge is a plain union — no entry ever shadows another.
 * Customs sort *after* catalog entries within a pattern (they carry
 * [CUSTOM_SUBRANK]) so the ranked substitution order the spec defines is
 * preserved and user additions land at the end.
 *
 * This mirrors [ExerciseLibrary]'s query surface so callers depend only on the
 * catalog and never reach past it to the code library directly.
 */
class ExerciseCatalog(customEntries: List<ExerciseEntry>) {

    val entries: List<ExerciseEntry> = ExerciseLibrary.entries + customEntries

    private val byId: Map<String, ExerciseEntry> = entries.associateBy { it.id }

    fun find(id: String): ExerciseEntry? = byId[id]

    fun get(id: String): ExerciseEntry =
        byId[id] ?: error("Unknown exercise id: $id")

    /** The unloaded (REMOVE-WEIGHT) counterpart of a loaded entry, if any (§4.2)
     *  — mirrors [ExerciseLibrary.bodyweightPairFor] so callers depend only on
     *  the catalog. Custom exercises never declare [ExerciseEntry.weightedPairId],
     *  so the code library's reverse index already covers the whole merged set. */
    fun bodyweightPairFor(id: String): String? = ExerciseLibrary.bodyweightPairFor(id)

    fun byPattern(pattern: MovementPattern): List<ExerciseEntry> =
        entries.filter { it.pattern == pattern }.sortedBy { it.subRank }

    /** Ranked same-pattern replacements for [id], excluding itself (spec §5). */
    fun substitutionsFor(id: String): List<ExerciseEntry> {
        val target = get(id)
        val samePattern = byPattern(target.pattern).filter { it.id != id }
        if (samePattern.isNotEmpty()) return samePattern
        val fallback = target.fallbackPattern ?: return emptyList()
        return byPattern(fallback).filter { it.id != id }
    }

    companion object {
        /** subRank given to every custom entry so it sorts behind catalog entries. */
        const val CUSTOM_SUBRANK: Int = 1_000

        /** Prefix that guarantees custom ids never collide with catalog ids. */
        const val CUSTOM_ID_PREFIX: String = "custom_"

        /** Catalog with no user overlay — the pre-onboarding default. */
        val CODE_ONLY: ExerciseCatalog = ExerciseCatalog(emptyList())
    }
}
