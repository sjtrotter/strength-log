package io.github.sjtrotter.strengthlog.data.serialization

import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistence representation of a [LoggedSet]. The domain types stay pure Kotlin
 * with no serialization annotations (`:domain` owns no framework deps), so `:data`
 * carries its own on-disk shape and maps at the boundary.
 *
 * [kind] is stored as the enum *name* rather than its ordinal so that reordering
 * or inserting a [SetKind] later can't silently reinterpret old rows.
 */
@Serializable
data class SetDto(
    val weightLb: Double,
    val reps: Int,
    val kind: String,
    val done: Boolean,
    // Defaulted so a pre-tracking-types row (no `seconds` key) decodes to 0, and —
    // with the codec's encodeDefaults off — a WEIGHTED/REPS set (seconds 0) still
    // serializes byte-identically to before. TIMED sets carry a non-zero value.
    val seconds: Int = 0,
) {
    fun toDomain(): LoggedSet =
        LoggedSet(weightLb = weightLb, reps = reps, kind = SetKind.valueOf(kind), done = done, seconds = seconds)

    companion object {
        fun of(set: LoggedSet): SetDto =
            SetDto(
                weightLb = set.weightLb,
                reps = set.reps,
                kind = set.kind.name,
                done = set.done,
                seconds = set.seconds,
            )
    }
}

/**
 * The single JSON codec for everything `:data` stores in a text column
 * (`exercise_log.setsJson`, `program_day.cardioJson`). Centralised so encode and
 * decode can never drift apart.
 */
object SetJson {
    // Tolerant on read so a column written by a newer build (extra fields) still
    // loads; strict enough to fail loudly on genuinely malformed data.
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeSets(sets: List<LoggedSet>): String =
        json.encodeToString(sets.map(SetDto::of))

    fun decodeSets(text: String): List<LoggedSet> =
        json.decodeFromString<List<SetDto>>(text).map(SetDto::toDomain)

    fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T =
        json.decodeFromString(serializer, text)
}
