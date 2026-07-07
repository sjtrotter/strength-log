package io.github.sjtrotter.strengthlog.transfer.backup

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The pure (Android-free, JVM-testable) core of the A2 backup: it turns a
 * [BackupDocument] into deterministic JSON and back, and — critically — validates
 * an untrusted file completely *before* any of it is handed to the database.
 *
 * The validation order is deliberate, cheapest and most-fundamental first, so a
 * bad file is rejected as early as possible: size → JSON well-formedness →
 * schema version → structural decode → semantic sanity.
 *
 * [maxBytes] is injectable purely so tests can drive the size guard with small
 * inputs; production uses [DEFAULT_MAX_BYTES].
 */
class BackupCodec(private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    private val json = Json {
        encodeDefaults = true
        // Tolerate additive fields from a *same-version* newer build, matching the
        // rule `:data` uses for its own stored JSON. Version incompatibilities are
        // caught explicitly by the schemaVersion check, not silently swallowed.
        ignoreUnknownKeys = true
    }

    fun encode(document: BackupDocument): String =
        json.encodeToString(BackupDocument.serializer(), document)

    /** Reads a backup off a stream, enforcing the byte ceiling as it goes so an
     *  oversized (or endless) file never fully buffers. */
    fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw BackupError.TooLarge(total, maxBytes)
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Decodes and fully validates [text], throwing a typed [BackupError] on any
     * problem. On success the returned document is safe to restore as-is.
     */
    fun decode(text: String): BackupDocument {
        if (text.length.toLong() > maxBytes) throw BackupError.TooLarge(text.length.toLong(), maxBytes)

        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw BackupError.Malformed("not valid JSON", e)
        }
        val obj = root as? JsonObject
            ?: throw BackupError.Malformed("top-level value is not a JSON object")
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw BackupError.Malformed("missing or non-integer schemaVersion")

        val document = when (version) {
            CURRENT_SCHEMA_VERSION -> decodeCurrent(obj)
            else -> throw BackupError.UnsupportedSchemaVersion(version, CURRENT_SCHEMA_VERSION)
        }
        validate(document)
        return document
    }

    private fun decodeCurrent(obj: JsonObject): BackupDocument =
        try {
            json.decodeFromJsonElement(BackupDocument.serializer(), obj)
        } catch (e: SerializationException) {
            throw BackupError.Malformed("does not match schema v$CURRENT_SCHEMA_VERSION", e)
        }

    /**
     * Semantic checks the type system can't express. These reject a file that
     * would decode but then break the app: an out-of-range wizard value, a custom
     * exercise the catalog couldn't ingest, a program slot pointing at a
     * non-existent exercise, or a self-contradictory pointer/log.
     *
     * Session-set exercise ids are intentionally *not* checked against the
     * catalog: history denormalizes the exercise name precisely so a completed
     * workout survives the deletion of its exercise, so a session legitimately may
     * reference an id that no longer resolves.
     */
    private fun validate(doc: BackupDocument) {
        val days = doc.settings.daysPerWeek
        if (days !in 2..6) throw BackupError.Inconsistent("daysPerWeek out of range: $days")

        val codeIds = ExerciseLibrary.entries.mapTo(HashSet()) { it.id }
        val customIds = HashSet<String>()
        for (c in doc.customExercises) {
            if (!c.id.startsWith(ExerciseCatalog.CUSTOM_ID_PREFIX)) {
                throw BackupError.InvalidCustomExercise(
                    "id '${c.id}' lacks the '${ExerciseCatalog.CUSTOM_ID_PREFIX}' prefix",
                )
            }
            if (c.id in codeIds) throw BackupError.InvalidCustomExercise("id '${c.id}' collides with a catalog id")
            if (!customIds.add(c.id)) throw BackupError.InvalidCustomExercise("duplicate id '${c.id}'")
            if (MovementPattern.entries.none { it.name == c.pattern }) {
                throw BackupError.InvalidCustomExercise("unknown pattern '${c.pattern}' on '${c.id}'")
            }
            c.equipmentCsv.split(",").filter { it.isNotBlank() }.forEach { token ->
                if (Equipment.entries.none { it.name == token }) {
                    throw BackupError.InvalidCustomExercise("unknown equipment '$token' on '${c.id}'")
                }
            }
        }

        val resolvable = codeIds + customIds
        val dayIds = HashSet<String>()
        for (day in doc.program) {
            if (!dayIds.add(day.dayId)) throw BackupError.Inconsistent("duplicate day id '${day.dayId}'")
            for (ex in day.exercises) {
                if (ex.exerciseId !in resolvable) throw BackupError.DanglingExerciseReference(ex.exerciseId)
                val ss = ex.supersetExerciseId
                if (ss != null && ss !in resolvable) throw BackupError.DanglingExerciseReference(ss)
            }
        }

        doc.settings.suggestedDay?.let { suggested ->
            if (suggested !in dayIds) {
                throw BackupError.Inconsistent("suggestedDay '$suggested' is not a day in this backup")
            }
        }

        val slotsByDay = doc.program.associate { d -> d.dayId to d.exercises.mapTo(HashSet()) { it.id } }
        for (log in doc.liveLogs) {
            val slots = slotsByDay[log.dayId]
                ?: throw BackupError.Inconsistent("live log references unknown day '${log.dayId}'")
            if (log.programExerciseId !in slots) {
                throw BackupError.Inconsistent(
                    "live log references unknown slot ${log.programExerciseId} in day '${log.dayId}'",
                )
            }
        }
    }

    companion object {
        /**
         * 32 MiB. A full backup is normally kilobytes-to-low-megabytes; this cap
         * only exists to bound the transient memory a restore allocates (the file
         * is buffered into a string and then a full object graph) and to refuse a
         * malicious or corrupt file outright. 32 MiB comfortably holds a very
         * dense history — order-of a decade of near-daily logging, ~150k+ session
         * sets — while keeping the one-shot allocation within a modern phone's
         * heap.
         */
        const val DEFAULT_MAX_BYTES: Long = 32L * 1024 * 1024
    }
}
