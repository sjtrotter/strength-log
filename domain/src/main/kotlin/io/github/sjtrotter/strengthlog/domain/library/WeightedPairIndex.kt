package io.github.sjtrotter.strengthlog.domain.library

/**
 * Builds the reverse index of [ExerciseEntry.weightedPairId] links: target id →
 * declaring (unloaded) id. Pairs are declared once, on the lighter side; this is
 * the SSOT for "does this loaded entry have a bodyweight counterpart" so the
 * REMOVE-WEIGHT direction is derived, never duplicated.
 *
 * Validates the whole graph at build time (fail loud on a malformed catalog):
 *  - every non-null [ExerciseEntry.weightedPairId] resolves to a real entry,
 *  - the target shares the declaring entry's [ExerciseEntry.pattern],
 *  - the mapping is injective (no target claimed by two entries),
 *  - the graph is acyclic (no pair target declares a pair itself → no chains).
 */
fun buildWeightedPairIndex(entries: List<ExerciseEntry>): Map<String, String> {
    val byId = entries.associateBy { it.id }
    val reverse = mutableMapOf<String, String>()
    for (entry in entries) {
        val targetId = entry.weightedPairId ?: continue
        val target = byId[targetId]
            ?: error("weightedPairId '$targetId' on '${entry.id}' does not resolve to an entry")
        require(target.pattern == entry.pattern) {
            "weightedPairId '$targetId' on '${entry.id}' crosses movement patterns " +
                "(${entry.pattern} → ${target.pattern})"
        }
        val prior = reverse.put(targetId, entry.id)
        require(prior == null) {
            "weightedPairId target '$targetId' claimed by both '$prior' and '${entry.id}' (not injective)"
        }
    }
    for (targetId in reverse.keys) {
        require(byId.getValue(targetId).weightedPairId == null) {
            "pair target '$targetId' declares its own weightedPairId, forming a chain/cycle"
        }
    }
    return reverse
}
