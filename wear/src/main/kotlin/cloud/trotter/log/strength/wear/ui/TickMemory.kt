package cloud.trotter.log.strength.wear.ui

/**
 * The ticks **this watch** made today, in the order it made them, and how long each
 * one took where it saw a start (brief §6's `TOOK 0:52` line).
 *
 * The wire deliberately never echoes the per-set stamps back (#85: the snapshot has
 * nowhere to show them and nobody reads them), so the watch keeps its own note. Two
 * things read it: the peek's `TOOK` line, and the undo, which needs to know which
 * round was ticked *last* — a question the snapshot cannot answer, because done
 * flags carry no chronology (ticking round A then round C looks identical to C then
 * A).
 *
 * It is honest about its limits and is never a source of truth: a round logged on
 * the phone, or one that outlived a process death, simply has no entry. The undo
 * treats a missing entry as "unknown, fall back to position" (see [UndoTarget]),
 * and a peek shows no `TOOK` line. It is a session's own recollection, thrown away
 * with the day.
 *
 * Kept pure (and encodable to one string) so it can ride `rememberSaveable` through
 * a process death without dragging Android into the model.
 */
class TickMemory private constructor(private val ticks: Map<String, Tick>) {

    /** One tick: how long it took (0 when no start was observed) and where it falls
     *  in this session's order. */
    private data class Tick(val seconds: Int, val order: Long)

    /**
     * Records a tick. [durationSeconds] may be 0 — a set the watch never saw start
     * still *happened*, and the undo has to be able to find it; only the `TOOK`
     * line cares whether it was timed. Re-recording a round moves it to the front:
     * the ledger says when a round was **last** ticked, not when it was first.
     */
    fun record(programExerciseId: Long, roundIndex: Int, durationSeconds: Int): TickMemory =
        TickMemory(
            ticks + (
                key(programExerciseId, roundIndex) to
                    Tick(durationSeconds.coerceAtLeast(0), nextOrder())
                ),
        )

    /** Forgets a tick — an untick retracts the fact that produced it. */
    fun forget(programExerciseId: Long, roundIndex: Int): TickMemory {
        val key = key(programExerciseId, roundIndex)
        if (key !in ticks) return this
        return TickMemory(ticks - key)
    }

    /**
     * Forgets every tick against one slot — what a swap does to this ledger (#90).
     * The phone's swap clears the slot's live log (§8.3), so rounds this watch
     * remembers ticking there are rounds that no longer exist; keeping them would
     * leave the undo pointing at work the lifter cannot see. [UndoTarget] would skip
     * them anyway (a remembered tick is only trusted while the snapshot still calls
     * it logged), but agreeing with the phone outright beats relying on that.
     */
    fun forgetExercise(programExerciseId: Long): TickMemory {
        val prefix = "$programExerciseId$KEY_SEPARATOR"
        val kept = ticks.filterKeys { !it.startsWith(prefix) }
        return if (kept.size == ticks.size) this else TickMemory(kept)
    }

    /** How long a round took, or null when this watch never timed it. */
    fun secondsFor(programExerciseId: Long, roundIndex: Int): Int? =
        ticks[key(programExerciseId, roundIndex)]?.seconds?.takeIf { it > 0 }

    /**
     * Every round this watch ticked, most recent first — the undo's search order.
     * Whether each is still logged is the *snapshot's* business, not this memory's:
     * a phone-side untick can strip a round out from under an entry here.
     */
    fun newestFirst(): List<TickRef> = ticks.entries
        .sortedByDescending { it.value.order }
        .mapNotNull { entry -> entry.key.toRef() }

    /** `"7:2=52@1;7:3=48@2"` — compact enough for a saved-instance bundle. */
    fun encode(): String = ticks.entries.joinToString(ENTRY_SEPARATOR) { (key, tick) ->
        "$key$VALUE_SEPARATOR${tick.seconds}$ORDER_SEPARATOR${tick.order}"
    }

    private fun nextOrder(): Long = (ticks.values.maxOfOrNull { it.order } ?: 0L) + 1L

    companion object {

        val EMPTY = TickMemory(emptyMap())

        /** Tolerant by design: a garbled entry costs one `TOOK` line, never a crash.
         *  An entry with no order — an encoding from before the undo needed one —
         *  keeps its position in the string as its order. */
        fun decode(text: String): TickMemory {
            if (text.isBlank()) return EMPTY
            val entries = text.split(ENTRY_SEPARATOR).mapIndexedNotNull { position, entry ->
                val key = entry.substringBefore(VALUE_SEPARATOR, "")
                val value = entry.substringAfter(VALUE_SEPARATOR, "")
                val seconds = value.substringBefore(ORDER_SEPARATOR).toIntOrNull()
                val order = value.substringAfter(ORDER_SEPARATOR, "").toLongOrNull()
                if (key.isBlank() || seconds == null || key.toRef() == null) {
                    null
                } else {
                    key to Tick(seconds.coerceAtLeast(0), order ?: (position + 1).toLong())
                }
            }
            return TickMemory(entries.toMap())
        }

        private fun key(programExerciseId: Long, roundIndex: Int) = "$programExerciseId$KEY_SEPARATOR$roundIndex"

        private fun String.toRef(): TickRef? {
            val id = substringBefore(KEY_SEPARATOR).toLongOrNull() ?: return null
            val round = substringAfter(KEY_SEPARATOR, "").toIntOrNull() ?: return null
            return TickRef(id, round)
        }

        private const val KEY_SEPARATOR = ":"
        private const val VALUE_SEPARATOR = "="
        private const val ORDER_SEPARATOR = "@"
        private const val ENTRY_SEPARATOR = ";"
    }
}

/** A round this watch ticked, named the way the wire names one. */
data class TickRef(val programExerciseId: Long, val roundIndex: Int)
