package io.github.sjtrotter.strengthlog.transfer.backup

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.serialization.CardioDto
import io.github.sjtrotter.strengthlog.data.serialization.SetJson
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.SetKind
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
            // v1 and v2 share one decoder: v2 only *added* defaulted fields
            // (session-set `seconds`, custom-exercise tracking/targets), so a v1
            // document decodes byte-for-byte into the same shape with those fields
            // at their defaults — a v1 backup must always restore. A newer version
            // is still rejected loudly rather than silently misread.
            1, CURRENT_SCHEMA_VERSION -> decodeCurrent(obj)
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
     * would decode but then break the app: an out-of-range or impossible value, a
     * custom exercise the catalog couldn't ingest, a program slot pointing at a
     * non-existent exercise, a duplicate primary key that would abort the restore
     * transaction with a raw SQLite error, a self-contradictory pointer/log, or —
     * critically — an embedded `setsJson`/`cardioJson` payload the app's own
     * codecs can't read (those strings go into Room verbatim and are decoded on
     * every program/log read, so a poisoned one would crash every collect).
     *
     * Session-set exercise ids are intentionally *not* checked against the
     * catalog: history denormalizes the exercise name precisely so a completed
     * workout survives the deletion of its exercise, so a session legitimately may
     * reference an id that no longer resolves.
     */
    private fun validate(doc: BackupDocument) {
        validateSettings(doc.settings)
        val customIds = validateCustomExercises(doc.customExercises)
        val slotsByDay = validateProgram(doc.program, customIds)
        doc.settings.suggestedDay?.let { suggested ->
            if (doc.program.none { it.dayId == suggested }) {
                throw BackupError.Inconsistent("suggestedDay '$suggested' is not a day in this backup")
            }
        }
        validateLiveLogs(doc.liveLogs, slotsByDay)
        validateSessions(doc.sessions)
    }

    private fun validateSettings(settings: SettingsBackup) {
        if (settings.daysPerWeek !in 2..6) {
            throw BackupError.Inconsistent("daysPerWeek out of range: ${settings.daysPerWeek}")
        }
        if (settings.bodyweightLb <= 0) {
            throw BackupError.Inconsistent("bodyweightLb must be positive: ${settings.bodyweightLb}")
        }
        if (settings.age < 0) throw BackupError.Inconsistent("age must not be negative: ${settings.age}")
    }

    private fun validateCustomExercises(customs: List<CustomExerciseBackup>): Set<String> {
        val codeIds = ExerciseLibrary.entries.mapTo(HashSet()) { it.id }
        val customIds = HashSet<String>()
        for (c in customs) {
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
            if (c.goalStartLb < 0) {
                throw BackupError.Inconsistent("negative goalStartLb on '${c.id}': ${c.goalStartLb}")
            }
            if (TrackingType.entries.none { it.name == c.tracking }) {
                throw BackupError.InvalidCustomExercise("unknown tracking '${c.tracking}' on '${c.id}'")
            }
            if ((c.targetReps ?: 0) < 0 || (c.targetSeconds ?: 0) < 0) {
                throw BackupError.Inconsistent("negative rep/time target on '${c.id}'")
            }
        }
        return customIds
    }

    /** Returns each day's set of slot ids for the live-log checks. */
    private fun validateProgram(
        program: List<ProgramDayBackup>,
        customIds: Set<String>,
    ): Map<String, Set<Long>> {
        val resolvable = ExerciseLibrary.entries.mapTo(HashSet()) { it.id } + customIds
        val dayIds = HashSet<String>()
        val slotIds = HashSet<Long>()
        for (day in program) {
            if (!dayIds.add(day.dayId)) throw BackupError.Inconsistent("duplicate day id '${day.dayId}'")
            decodePayload("cardioJson of day '${day.dayId}'") { CardioDto.decode(day.cardioJson) }
            for (ex in day.exercises) {
                if (!slotIds.add(ex.id)) throw BackupError.Inconsistent("duplicate program exercise id ${ex.id}")
                if (ex.exerciseId !in resolvable) throw BackupError.DanglingExerciseReference(ex.exerciseId)
                val ss = ex.supersetExerciseId
                if (ss != null && ss !in resolvable) throw BackupError.DanglingExerciseReference(ss)
                if (ex.targetSets < 1) {
                    throw BackupError.Inconsistent("targetSets must be at least 1 on slot ${ex.id}: ${ex.targetSets}")
                }
            }
        }
        return program.associate { d -> d.dayId to d.exercises.mapTo(HashSet()) { it.id } }
    }

    private fun validateLiveLogs(logs: List<LiveLogBackup>, slotsByDay: Map<String, Set<Long>>) {
        val logKeys = HashSet<Triple<String, Long, String>>()
        for (log in logs) {
            val slots = slotsByDay[log.dayId]
                ?: throw BackupError.Inconsistent("live log references unknown day '${log.dayId}'")
            if (log.programExerciseId !in slots) {
                throw BackupError.Inconsistent(
                    "live log references unknown slot ${log.programExerciseId} in day '${log.dayId}'",
                )
            }
            if (log.slot != Slot.MAIN && log.slot != Slot.SS) {
                throw BackupError.Inconsistent("unknown live-log slot kind '${log.slot}'")
            }
            if (!logKeys.add(Triple(log.dayId, log.programExerciseId, log.slot))) {
                throw BackupError.Inconsistent(
                    "duplicate live log for (${log.dayId}, ${log.programExerciseId}, ${log.slot})",
                )
            }
            val sets = decodePayload("setsJson of slot ${log.programExerciseId} in day '${log.dayId}'") {
                SetJson.decodeSets(log.setsJson)
            }
            sets.forEach { s ->
                if (s.weightLb < 0 || s.reps < 0 || s.seconds < 0) {
                    throw BackupError.Inconsistent(
                        "negative weight/reps/seconds in live log for slot ${log.programExerciseId} in day '${log.dayId}'",
                    )
                }
            }
        }
    }

    private fun validateSessions(sessions: List<SessionBackup>) {
        val sessionIds = HashSet<Long>()
        val setIds = HashSet<Long>()
        for (s in sessions) {
            if (!sessionIds.add(s.id)) throw BackupError.Inconsistent("duplicate session id ${s.id}")
            if (s.bodyweightLb <= 0) {
                throw BackupError.Inconsistent("session ${s.id} bodyweightLb must be positive: ${s.bodyweightLb}")
            }
            for (set in s.sets) {
                if (!setIds.add(set.id)) throw BackupError.Inconsistent("duplicate session set id ${set.id}")
                if (set.slot != Slot.MAIN && set.slot != Slot.SS) {
                    throw BackupError.Inconsistent("unknown session-set slot kind '${set.slot}' in session ${s.id}")
                }
                if (SetKind.entries.none { it.name == set.kind }) {
                    throw BackupError.Inconsistent("unknown set kind '${set.kind}' on set ${set.id} in session ${s.id}")
                }
                if (set.weightLb < 0 || set.reps < 0 || set.setIndex < 0 || set.seconds < 0) {
                    throw BackupError.Inconsistent("negative weight/reps/setIndex/seconds on set ${set.id} in session ${s.id}")
                }
            }
        }
    }

    /** Runs one of `:data`'s own payload codecs (SSOT — the exact decoders the app
     *  uses at read time) and converts any failure to the typed error. */
    private fun <T> decodePayload(what: String, decode: () -> T): T =
        try {
            decode()
        } catch (e: Exception) {
            throw BackupError.InvalidPayload("$what: ${e.message}", e)
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
