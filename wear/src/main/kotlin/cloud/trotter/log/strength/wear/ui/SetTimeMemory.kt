package cloud.trotter.log.strength.wear.ui

/**
 * How long each set of this session actually took, remembered **on the wrist only**
 * — the peek's `TOOK 0:52` line (brief §6).
 *
 * The wire deliberately never echoes the per-set stamps back (#85: the snapshot has
 * nowhere to show them and nobody reads them), so the watch keeps its own note of
 * the durations it observed. That makes this memory honest about its limits: a
 * round logged on the phone, or one that outlived a process death, simply has no
 * entry and peeks without a `TOOK` line. It is never a source of truth for anything
 * — it is a session's own recollection, thrown away with the day.
 *
 * Kept pure (and encodable to one string) so it can ride `rememberSaveable` through
 * a process death without dragging Android into the model.
 */
class SetTimeMemory private constructor(private val seconds: Map<String, Int>) {

    fun record(programExerciseId: Long, roundIndex: Int, durationSeconds: Int): SetTimeMemory {
        if (durationSeconds <= 0) return this
        return SetTimeMemory(seconds + (key(programExerciseId, roundIndex) to durationSeconds))
    }

    /** Forgets a round's time — an untick retracts the fact that produced it. */
    fun forget(programExerciseId: Long, roundIndex: Int): SetTimeMemory {
        val key = key(programExerciseId, roundIndex)
        if (key !in seconds) return this
        return SetTimeMemory(seconds - key)
    }

    fun secondsFor(programExerciseId: Long, roundIndex: Int): Int? =
        seconds[key(programExerciseId, roundIndex)]

    /** `"7:2=52;7:3=48"` — compact enough for a saved-instance bundle. */
    fun encode(): String = seconds.entries.joinToString(ENTRY_SEPARATOR) { "${it.key}$VALUE_SEPARATOR${it.value}" }

    companion object {

        val EMPTY = SetTimeMemory(emptyMap())

        /** Tolerant by design: a garbled entry costs one `TOOK` line, never a crash. */
        fun decode(text: String): SetTimeMemory {
            if (text.isBlank()) return EMPTY
            val entries = text.split(ENTRY_SEPARATOR).mapNotNull { entry ->
                val key = entry.substringBefore(VALUE_SEPARATOR, "")
                val value = entry.substringAfter(VALUE_SEPARATOR, "").toIntOrNull()
                if (key.isBlank() || value == null) null else key to value
            }
            return SetTimeMemory(entries.toMap())
        }

        private fun key(programExerciseId: Long, roundIndex: Int) = "$programExerciseId$KEY_SEPARATOR$roundIndex"

        private const val KEY_SEPARATOR = ":"
        private const val VALUE_SEPARATOR = "="
        private const val ENTRY_SEPARATOR = ";"
    }
}
