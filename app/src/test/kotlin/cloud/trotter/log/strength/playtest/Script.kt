package cloud.trotter.log.strength.playtest

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class PlaytestError(
    val kind: String,
    message: String,
    val details: JsonObject = JsonObject(emptyMap()),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun scriptCheck(condition: Boolean, message: String) {
    if (!condition) throw PlaytestError("script", message)
}

internal data class Start(
    val date: LocalDate,
    val time: LocalTime,
    val zone: ZoneId,
    val restore: File?,
    val restoreInstant: Instant?,
) {
    fun instant(): Instant = restoreInstant ?: date.atTime(time).atZone(zone).toInstant()
}

internal data class Selector(val json: JsonObject) {
    val index: Int? get() = (json["index"] as? JsonPrimitive)?.intOrNull
    fun text(key: String): String? = (json[key] as? JsonPrimitive)?.content
    fun flag(key: String): Boolean? = (json[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        private val strings = setOf("text", "textContains", "desc", "tag", "role")
        private val flags = setOf("editable", "inDialog")
        private val roles = setOf("Button", "Checkbox", "Switch", "Tab", "RadioButton", "DropdownList", "Image")

        fun parse(value: JsonObject): Selector {
            value.keysOnly(strings + flags + "index", "selector")
            scriptCheck(value.keys.any { it in strings || it in flags }, "A selector needs a matching key")
            strings.filter { it in value }.forEach { value.string(it) }
            flags.filter { it in value }.forEach { value.boolean(it) }
            value["role"]?.let { scriptCheck(value.string("role") in roles, "Unknown selector role") }
            value["index"]?.let {
                val index = (it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.intOrNull
                scriptCheck(index != null && index >= 0, "Selector index must be a nonnegative integer")
            }
            return Selector(value)
        }
    }
}

internal data class Step(
    val action: String,
    val payload: JsonElement,
    val selector: Selector?,
    val duration: Duration?,
    val timeoutMs: Long,
    val json: JsonObject,
) {
    val slug: String get() {
        val target = when (action) {
            "checkpoint" -> (payload as JsonPrimitive).content
            else -> selector?.let { it.text("text") ?: it.text("tag") ?: it.text("desc") }
        }
        return listOfNotNull(action, target).joinToString("-")
            .lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(100)
    }

    companion object {
        private val selectorActions = setOf("tap", "longPress", "type", "scrollTo", "swipe", "expect")
        private val actions = selectorActions + setOf("back", "advance", "recreate", "checkpoint", "note")

        fun parse(value: JsonElement): Step {
            val step = value.objectValue("step")
            val actionKeys = step.keys - "timeoutMs"
            scriptCheck(actionKeys.size == 1 && actionKeys.single() in actions, "Each step must contain exactly one known action and optional timeoutMs")
            val action = actionKeys.single()
            val timeoutMs = if ("timeoutMs" in step) {
                (step["timeoutMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1_000_000 }
                    ?: throw PlaytestError("script", "timeoutMs must be a positive integer in milliseconds")
            } else 10_000L
            val payload = step.getValue(action)
            var selector: Selector? = null
            var duration: Duration? = null
            when (action) {
                in selectorActions -> {
                    val objectValue = payload.objectValue(action)
                    val extras = when (action) {
                        "type" -> setOf("value", "ime")
                        "swipe" -> setOf("direction")
                        "expect" -> setOf("absent")
                        else -> emptySet()
                    }
                    selector = Selector.parse(JsonObject(objectValue.filterKeys { it !in extras }))
                    when (action) {
                        "type" -> {
                            objectValue.string("value")
                            if ("ime" in objectValue) scriptCheck(objectValue.string("ime") == "done", "ime must be done")
                        }
                        "swipe" -> scriptCheck(objectValue.string("direction") in setOf("left", "right", "up", "down"), "Unknown swipe direction")
                        "expect" -> if ("absent" in objectValue) objectValue.boolean("absent")
                    }
                }
                "back", "recreate" -> scriptCheck(payload == JsonPrimitive(true), "$action must be true")
                "advance" -> duration = parseDuration(step.string(action))
                "checkpoint" -> scriptCheck(step.string(action).isNotBlank(), "Checkpoint name must not be blank")
                "note" -> step.string(action)
            }
            return Step(action, payload, selector, duration, timeoutMs, step)
        }

        private fun parseDuration(text: String): Duration {
            val match = Regex("(?:([0-9]+)d)?(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9]+)s)?").matchEntire(text)
                ?.takeIf { text.isNotEmpty() }
                ?: throw PlaytestError("script", DURATION_HELP)
            val (days, hours, minutes, seconds) = match.destructured
            val duration = try {
                Duration.ZERO.plusDays(days.toLongOrZero()).plusHours(hours.toLongOrZero())
                    .plusMinutes(minutes.toLongOrZero()).plusSeconds(seconds.toLongOrZero())
                    .also { it.toMillis() } // Rejects overflow before launch.
            } catch (error: ArithmeticException) {
                throw PlaytestError("script", "advance duration is too long", cause = error)
            }
            scriptCheck(!duration.isZero, DURATION_HELP)
            return duration
        }
    }
}

private const val DURATION_HELP = "advance needs a positive duration such as 90s, 15m, 2h, 2d, or 23h8m"

private fun String.toLongOrZero(): Long =
    if (isEmpty()) 0 else toLongOrNull() ?: throw PlaytestError("script", "advance duration is too long")

internal data class Script(
    val seat: String,
    val device: String,
    val start: Start,
    val checkpointsOnly: Boolean,
    val steps: List<Step>,
) {
    companion object {
        fun parse(file: File): Script = try {
            val root = Json.parseToJsonElement(file.readText()).objectValue("script")
            root.keysOnly(setOf("seat", "device", "start", "capture", "steps"), "script")
            val seat = root.string("seat")
            scriptCheck(seat.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")), "seat must use letters, numbers, underscores, or hyphens")
            val start = root["start"]?.objectValue("start") ?: JsonObject(emptyMap())
            start.keysOnly(setOf("date", "time", "zone", "restore"), "start")
            val capture = root.optionalString("capture")
            scriptCheck(capture == null || capture == "checkpoints", "capture must be checkpoints when supplied")
            val device = root.optionalString("device") ?: "w360dp-h720dp-night-xxhdpi"
            scriptCheck(device.isNotBlank(), "device must not be blank")
            val steps = root["steps"] as? JsonArray ?: throw PlaytestError("script", "steps must be an array")
            val restore = start.optionalString("restore")?.let { path ->
                File(path).let { if (it.isAbsolute) it else File(file.absoluteFile.parentFile, path) }.also {
                    scriptCheck(it.isFile && it.canRead(), "Cannot read restore file: $it")
                }
            }
            val restoreInstant = if (restore != null && !("date" in start && "time" in start)) {
                val summary = File(restore.parentFile, "summary.json")
                scriptCheck(summary.isFile, "A restore needs its sibling summary.json or explicit start.date and start.time; backups have no export timestamp")
                val summaryJson = Json.parseToJsonElement(summary.readText()).objectValue("restore summary")
                Instant.parse(summaryJson.string("clock_at_exit"))
            } else null
            Script(
                seat, device,
                Start(
                    LocalDate.parse(start.optionalString("date") ?: "2026-11-09"),
                    LocalTime.parse(start.optionalString("time") ?: "17:30"),
                    ZoneId.of(start.optionalString("zone") ?: "America/Chicago"),
                    restore,
                    restoreInstant,
                ),
                capture == "checkpoints",
                steps.mapIndexed { index, value ->
                    try {
                        Step.parse(value)
                    } catch (error: Exception) {
                        throw PlaytestError("script", "Step $index: ${error.message}", buildJsonObject {
                            put("i", index)
                            put("step", value)
                        }, error)
                    }
                },
            )
        } catch (error: PlaytestError) {
            throw error
        } catch (error: Exception) {
            throw PlaytestError("script", error.message ?: "Cannot read script", cause = error)
        }
    }
}

internal fun JsonElement.objectValue(label: String): JsonObject =
    this as? JsonObject ?: throw PlaytestError("script", "$label must be an object")

private fun JsonObject.keysOnly(allowed: Set<String>, label: String) {
    scriptCheck(keys.all { it in allowed }, "Unknown $label keys: ${keys - allowed}")
}

internal fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw PlaytestError("script", "$key must be a string")

private fun JsonObject.optionalString(key: String): String? = if (key in this) string(key) else null

internal fun JsonObject.boolean(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw PlaytestError("script", "$key must be a boolean")

internal fun Throwable.playtestError(): PlaytestError =
    generateSequence(this) { it.cause }.filterIsInstance<PlaytestError>().firstOrNull()
        ?: PlaytestError(if (isWaitTimeout()) "timeout" else "crash", message ?: javaClass.name, cause = this)

internal fun failureJson(error: PlaytestError, index: Int?, step: JsonElement? = null): JsonObject = buildJsonObject {
    put("i", index?.let(::JsonPrimitive) ?: JsonNull)
    put("step", step ?: JsonNull)
    put("class", error.kind)
    put("message", error.message)
    error.details.forEach { (key, value) -> put(key, value) }
    put("stack", error.stackTraceToString())
}
