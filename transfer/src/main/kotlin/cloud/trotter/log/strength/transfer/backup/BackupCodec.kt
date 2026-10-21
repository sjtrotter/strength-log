package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.RestPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
            // Every version shares one decoder, with defaulted additions: v2
            // added session-set `seconds` and custom-exercise tracking/targets,
            // v3 added the rest-timer prefs, v4
            // added keep-screen-on, v5 made a session's bodyweight nullable, v6
            // added cardio sessions, v7 added program-day kinds, and v8 added
            // plans and their live-log/selection pointers; v9 added each plan's
            // recipe and rotation; v10 added authored prescriptions. Older documents decode
            // with newer fields at their defaults; the version determines which
            // program shape is valid and how it restores. A newer version is
            // still rejected loudly rather than silently misread.
            1, 2, 3, 4 -> withLegacyUnknownBodyweight(decodeCurrent(requireLegacySessionBodyweights(obj), version))
            5, 6, 7, 8, 9, 10, 11, CURRENT_SCHEMA_VERSION -> decodeCurrent(obj, version)
            else -> throw BackupError.UnsupportedSchemaVersion(version, CURRENT_SCHEMA_VERSION)
        }
        validate(document)
        return document
    }

    /**
     * Decodes and checks the version's program shape with the shared decoder.
     * v1–v7 ignore stray `plans` and live-log `planId` as unknown fields, while
     * v8 and later reject a non-empty legacy `program`.
     */
    private fun decodeCurrent(obj: JsonObject, version: Int): BackupDocument {
        if (version < 9) requireLegacyRecipe(obj)
        val shaped = if (version < 8) {
            JsonObject(obj.toMutableMap().apply {
                remove("plans")
                val logs = get("liveLogs") as? JsonArray
                if (logs != null) {
                    put("liveLogs", JsonArray(logs.map { if (it is JsonObject) JsonObject(it - "planId") else it }))
                }
            })
        } else {
            JsonObject(obj.toMutableMap().apply {
                if (version == 8) {
                    val plans = get("plans") as? JsonArray
                    if (plans != null) {
                        put("plans", JsonArray(plans.map {
                            if (it is JsonObject) JsonObject(it - "recipeJson" - "suggestedDay") else it
                        }))
                    }
                } else {
                    val settings = get("settings") as? JsonObject
                    if (settings != null) {
                        put("settings", JsonObject(settings - LEGACY_RECIPE_FIELDS.toSet() - "suggestedDay"))
                    }
                }
            })
        }
        // Future fields stay unknown in old versions, even when they have the wrong type.
        val withDayFields = if (version < 11) withoutFutureDayFields(shaped, version) else shaped
        val versioned = if (version < 12) {
            val sessions = withDayFields["cardioSessions"] as? JsonArray
            if (sessions == null) withDayFields else JsonObject(withDayFields + ("cardioSessions" to JsonArray(
                sessions.map { if (it is JsonObject) JsonObject(it - "activity" - "sets") else it },
            )))
        } else withDayFields
        val document = try {
            json.decodeFromJsonElement(BackupDocument.serializer(), versioned)
        } catch (e: SerializationException) {
            throw BackupError.Malformed("does not match schema v$CURRENT_SCHEMA_VERSION", e)
        }
        if (version >= 8 && document.program.isNotEmpty()) {
            throw BackupError.Malformed("program must be empty in schema v$version")
        }
        return document
    }

    private fun withoutFutureDayFields(root: JsonObject, version: Int): JsonObject {
        fun stripDays(value: JsonElement?): JsonElement? =
            (value as? JsonArray)?.let { dayRows ->
                JsonArray(dayRows.map { day ->
                    if (day !is JsonObject) day else {
                        val currentDay = JsonObject(day - "cardioBlockJson")
                        val exercises = currentDay["exercises"] as? JsonArray
                        if (version >= 10 || exercises == null) currentDay else {
                            JsonObject(currentDay + ("exercises" to JsonArray(exercises.map {
                                if (it is JsonObject) JsonObject(it - "prescriptionJson") else it
                            })))
                        }
                    }
                })
            } ?: value
        return JsonObject(root.toMutableMap().apply {
            stripDays(get("program"))?.let { put("program", it) }
            val plans = get("plans") as? JsonArray
            if (plans != null) put("plans", JsonArray(plans.map { plan ->
                if (plan !is JsonObject) plan else {
                    val planDays = stripDays(plan["days"])
                    if (planDays == null) plan else JsonObject(plan + ("days" to planDays))
                }
            }))
        })
    }

    /** Pre-v9 recipe fields were required; null defaults must not hide truncation. */
    private fun requireLegacyRecipe(obj: JsonObject) {
        val settings = obj["settings"] as? JsonObject ?: return
        LEGACY_RECIPE_FIELDS.forEach { field ->
            val value = settings[field]
            if (value == null || value == JsonNull) {
                throw BackupError.Malformed("settings $field is required before schema v9")
            }
        }
    }

    /**
     * Pre-v5, a session's bodyweight was a REQUIRED number. The current
     * decoder would quietly default an absent field to null, which for a
     * legacy document means accepting truncation as data — so absence (or an
     * explicit JSON null) in a v1-v4 document stays what it always was there:
     * malformed.
     */
    private fun requireLegacySessionBodyweights(obj: JsonObject): JsonObject {
        val sessions = obj["sessions"] as? JsonArray ?: return obj
        sessions.forEachIndexed { i, el ->
            val bw = (el as? JsonObject)?.get("bodyweightLb")
            if (bw !is JsonPrimitive || bw.intOrNull == null) {
                throw BackupError.Malformed("session[$i] bodyweightLb is required before schema v5")
            }
        }
        return obj
    }

    /**
     * Pre-v5, a session's bodyweight was a required number and CSV-imported
     * history was written as 0 (#171) — the value that made those documents
     * unrestorable. 0 was never a real bodyweight, so it can only have meant
     * "none recorded", which v5 spells null.
     */
    private fun withLegacyUnknownBodyweight(doc: BackupDocument): BackupDocument =
        doc.copy(
            sessions = doc.sessions.map { if (it.bodyweightLb == 0) it.copy(bodyweightLb = null) else it },
        )

    /**
     * Semantic checks the type system can't express. These reject a file that
     * would decode but then break the app: an out-of-range or impossible value, a
     * custom exercise the catalog couldn't ingest, a program slot pointing at a
     * non-existent exercise, a duplicate primary key that would abort the restore
     * transaction with a raw SQLite error, a self-contradictory pointer/log, or —
     * critically — an embedded `setsJson`/`cardioJson`/`recipeJson` payload the app's own
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
        val slotIds = HashSet<Long>()
        if (doc.schemaVersion >= 8) {
            val slotsByPlan = LinkedHashMap<Long, Map<String, Set<Long>>>()
            for (plan in doc.plans) {
                if (plan.id <= 0) throw BackupError.Inconsistent("plan id must be positive: ${plan.id}")
                if (plan.id in slotsByPlan) throw BackupError.Inconsistent("duplicate plan id ${plan.id}")
                if (PlanOrigin.entries.none { it.name == plan.origin }) {
                    throw BackupError.Inconsistent("unknown plan origin '${plan.origin}'")
                }
                slotsByPlan[plan.id] = validateProgram(plan.days, customIds, slotIds)
                if (doc.schemaVersion >= 11) {
                    plan.days.forEach { day ->
                        day.cardioBlockJson?.let { block ->
                            decodePayload("cardioBlockJson of day '${day.dayId}'") { CardioBlockJson.validate(block) }
                        }
                    }
                }
                if (doc.schemaVersion >= 10) {
                    plan.days.flatMap { it.exercises }.forEach { ex ->
                        ex.prescriptionJson?.let { prescription ->
                            decodePayload("prescriptionJson of slot ${ex.id}") { PrescriptionJson.validate(prescription) }
                            if (ex.targetSets !in 1..99) {
                                throw BackupError.Inconsistent("authored targetSets must be 1..99 on slot ${ex.id}")
                            }
                        }
                    }
                }
                if (doc.schemaVersion >= 9) {
                    plan.recipeJson?.let { recipe ->
                        decodePayload("recipeJson of plan ${plan.id}") { PlanRecipeJson.validate(recipe) }
                        if (plan.origin == PlanOrigin.AUTHORED.name) {
                            throw BackupError.Inconsistent("AUTHORED plan ${plan.id} must have a null recipeJson")
                        }
                    }
                    validateSuggestedDay(plan.suggestedDay, plan.days)
                }
            }
            val selectedPlan = doc.settings.selectedPlanId?.let { id ->
                val plan = doc.plans.firstOrNull { it.id == id }
                    ?: throw BackupError.Inconsistent("selectedPlanId references unknown plan $id")
                if (plan.archived) throw BackupError.Inconsistent("selectedPlanId references archived plan $id")
                plan
            } ?: doc.plans.firstOrNull { !it.archived }
            if (doc.schemaVersion == 8) {
                validateSuggestedDay(doc.settings.suggestedDay, selectedPlan?.days.orEmpty())
            }
            for ((planId, logs) in doc.liveLogs.groupBy { it.planId }) {
                if (planId == null) throw BackupError.Inconsistent("live log planId is required in schema v${doc.schemaVersion}")
                val slotsByDay = slotsByPlan[planId]
                    ?: throw BackupError.Inconsistent("live log references unknown plan $planId")
                validateLiveLogs(logs, slotsByDay)
            }
        } else {
            val slotsByDay = validateProgram(doc.program, customIds, slotIds)
            validateSuggestedDay(doc.settings.suggestedDay, doc.program)
            validateLiveLogs(doc.liveLogs, slotsByDay)
        }
        validateSessions(doc.sessions)
        validateCardioSessions(doc.cardioSessions)
        if (doc.schemaVersion >= 12) validateCardioSets(doc.cardioSessions)
    }

    private fun validateSuggestedDay(suggestedDay: String?, days: List<ProgramDayBackup>) {
        suggestedDay?.let { suggested ->
            if (days.none { it.dayId == suggested }) {
                throw BackupError.Inconsistent("suggestedDay '$suggested' is not a day in this backup")
            }
        }
    }

    private fun validateSettings(settings: SettingsBackup) {
        if (settings.daysPerWeek != null && settings.daysPerWeek !in 2..6) {
            throw BackupError.Inconsistent("daysPerWeek out of range: ${settings.daysPerWeek}")
        }
        if (settings.bodyweightLb <= 0) {
            throw BackupError.Inconsistent("bodyweightLb must be positive: ${settings.bodyweightLb}")
        }
        if (settings.age < 0) throw BackupError.Inconsistent("age must not be negative: ${settings.age}")
        settings.restOverrides().forEach { (category, seconds) ->
            if (seconds !in 0..RestPolicy.MAX_REST_SECONDS) {
                throw BackupError.Inconsistent("rest override for $category out of range: $seconds")
            }
        }
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
        slotIds: MutableSet<Long>,
    ): Map<String, Set<Long>> {
        val resolvable = ExerciseLibrary.entries.mapTo(HashSet()) { it.id } + customIds
        val dayIds = HashSet<String>()
        for (day in program) {
            if (!dayIds.add(day.dayId)) throw BackupError.Inconsistent("duplicate day id '${day.dayId}'")
            if (day.kind != "STRENGTH" && day.kind != "CARDIO") {
                throw BackupError.Inconsistent("unknown program day kind '${day.kind}'")
            }
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
            // Null is "none recorded" and legitimate; any number that isn't a real
            // bodyweight is not (a legacy 0 has already been mapped to null).
            if (s.bodyweightLb != null && s.bodyweightLb <= 0) {
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

    private fun validateCardioSessions(sessions: List<CardioSessionBackup>) {
        val ids = HashSet<Long>()
        sessions.forEachIndexed { index, session ->
            if (!ids.add(session.id)) {
                throw BackupError.Inconsistent("duplicate cardio session id ${session.id}")
            }
            if (session.completedAt <= session.startedAt) {
                throw BackupError.Inconsistent("cardio session[$index] completedAt must be after startedAt")
            }
            if (session.seconds !in 60..86_400) {
                throw BackupError.Inconsistent("cardio session[$index] seconds out of range: ${session.seconds}")
            }
            if (session.label.isBlank()) {
                throw BackupError.Inconsistent("cardio session[$index] label must not be blank")
            }
            if (session.label.length > 80) {
                throw BackupError.Inconsistent("cardio session[$index] label is longer than 80 characters")
            }
            if (session.stepsCompleted < 0) {
                throw BackupError.Inconsistent("cardio session[$index] stepsCompleted must not be negative")
            }
        }
    }

    private fun validateCardioSets(sessions: List<CardioSessionBackup>) {
        val ids = HashSet<Long>()
        for (session in sessions) {
            if (session.id <= 0) {
                throw BackupError.Inconsistent("cardio session id must be positive: ${session.id}")
            }
            if (session.activity != null && CardioActivity.entries.none { it.name == session.activity }) {
                throw BackupError.Inconsistent("unknown cardio activity '${session.activity}' in session ${session.id}")
            }
            for (set in session.sets) {
                if (set.id <= 0 || !ids.add(set.id)) {
                    throw BackupError.Inconsistent("invalid or duplicate cardio set id ${set.id}")
                }
                decodePayload("cardio set ${set.id} in session ${session.id}") {
                    CardioSetResult(
                        spec = CardioBlockJson.decodeSet(set.specJson),
                        status = CardioSetStatus.valueOf(set.status),
                        elapsedSeconds = set.elapsedSeconds,
                        metres = set.metres,
                        distanceSource = set.distanceSource?.let(DistanceSource::valueOf),
                    )
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
        private val LEGACY_RECIPE_FIELDS = listOf(
            "emphasis", "cardioMode", "cardioPlacement", "fiveKGoal", "daysPerWeek",
            "split", "anchorScheme", "deadliftVariant", "equipment",
        )

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
