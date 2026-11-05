package cloud.trotter.log.strength.playtest

import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.audit.dumpSemantics
import cloud.trotter.log.strength.audit.writeShot
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.backup.BackupService
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.robolectric.Shadows.shadowOf

internal class RunArtifacts(val directory: File, val captureFrom: Int) {
    var script: Script? = null
    var stepsRun = 0
    private var failedStep: Int? = null
    private var errorClass: String? = null

    init {
        check(directory.mkdirs() || directory.isDirectory)
        listOf("summary.json", "error.json", "backup.json", "log.jsonl").forEach { File(directory, it).delete() }
        File(directory, "steps").deleteRecursively()
        File(directory, "log.jsonl").writeText("")
    }

    fun log(line: JsonObject) {
        File(directory, "log.jsonl").appendText("$line\n")
    }

    fun failure(error: PlaytestError, index: Int?, step: JsonElement? = null) {
        if (errorClass != null) return
        failedStep = index ?: (error.details["i"] as? JsonPrimitive)?.intOrNull
        errorClass = error.kind
        File(directory, "error.json").writeText(failureJson(error, index, step).toString() + "\n")
    }

    fun writeBackup(backup: BackupService) {
        File(directory, "backup.json").writeText(runBlocking(Dispatchers.IO) { backup.export() })
    }

    fun writeSummary(time: PlaytestTime?) {
        File(directory, "summary.json").writeText(buildJsonObject {
            put("seat", script?.seat)
            put("steps_total", script?.steps?.size ?: 0)
            put("steps_run", stepsRun)
            put("failed_step", failedStep?.let(::JsonPrimitive) ?: JsonNull)
            put("error_class", errorClass)
            put("clock_at_exit", time?.clock?.instant()?.toString())
            put("capture_from", captureFrom)
        }.toString() + "\n")
    }
}

internal class Replay(
    private val compose: ComposeTestRule,
    private val scenario: ActivityScenario<MainActivity>,
    private val time: PlaytestTime,
    private val cardioAlarm: PlaytestCardioAlarm,
    private val settings: SettingsStore,
    private val database: StrengthDatabase,
    private val artifacts: RunArtifacts,
) {
    private val selectors = Selectors(compose, compose.density.density)
    private var screen = "launch"
    private var lastMatches: JsonArray = JsonArray(emptyList())

    fun run(script: Script) {
        try {
            settle()
            capture("000-launch", png = true)
        } catch (error: Throwable) {
            captureFailure("000-FAILED-launch", error)
            throw error
        }
        script.steps.forEachIndexed { index, step ->
            val started = System.nanoTime()
            artifacts.stepsRun++
            lastMatches = JsonArray(emptyList())
            try {
                if (step.action != "note") {
                    perform(step)
                    settle()
                    if (index >= artifacts.captureFrom || step.action == "checkpoint") {
                        capture("%03d-%s".format(index, step.slug),
                            png = !script.checkpointsOnly || step.action == "checkpoint")
                    }
                }
                log(index, step, started, ok = true)
            } catch (error: Throwable) {
                captureFailure("%03d-FAILED-%s".format(index, step.slug), error)
                artifacts.failure(error.playtestError(), index, step.json)
                log(index, step, started, ok = false)
                throw error
            }
        }
    }

    private fun log(index: Int, step: Step, started: Long, ok: Boolean) {
        artifacts.log(buildJsonObject {
            put("i", index)
            put("action", step.action)
            put("selector", step.selector?.json ?: JsonNull)
            put("ok", ok)
            put("ms", (System.nanoTime() - started) / 1_000_000)
            put("matches", lastMatches)
            put("screen", screen)
            if (step.action == "note") put("note", step.payload)
        })
    }

    private fun perform(step: Step) {
        when (step.action) {
            "back" -> scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            "recreate" -> scenario.recreate()
            "advance" -> advance(requireNotNull(step.duration))
            "checkpoint" -> Unit
            "expect" -> expect(step)
            "scrollTo" -> scrollTo(requireNotNull(step.selector))
            else -> act(step)
        }
    }

    private fun expect(step: Step) {
        val selector = requireNotNull(step.selector)
        val matches = selectors.matches(selector)
        lastMatches = selectors.describe(matches)
        val absent = (step.payload as JsonObject)["absent"] == JsonPrimitive(true)
        if (absent) {
            val present = selector.index?.let { it < matches.size } ?: matches.isNotEmpty()
            if (present) throw PlaytestError("ambiguous", "Expected no matches for ${selector.json}", buildJsonObject {
                put("matches", lastMatches)
            })
        } else {
            selectors.resolve(selector)
        }
    }

    private fun act(step: Step) {
        val selector = requireNotNull(step.selector)
        val typing = step.action == "type"
        // Report the action-specific error when a selector finds a non-editable node.
        if (typing && selectors.matches(selector, typing = true).isEmpty() && selectors.matches(selector).isNotEmpty()) {
            throw PlaytestError("not-editable", "Use type on a text field with EditableText semantics")
        }
        lastMatches = selectors.describe(selectors.matches(selector, typing))
        val target = selectors.resolve(selector, typing)
        val node = target.fetchSemanticsNode()
        if (step.action == "tap" && node.config.contains(SemanticsProperties.EditableText)) {
            throw PlaytestError("script", "Use type instead of tap on an editable node; a second click can start the magnifier")
        }
        enabled(node)
        when (step.action) {
            "tap" -> {
                requireAction(node.config.contains(SemanticsActions.OnClick), "not-clickable", "Node has no click action")
                target.performClick()
            }
            "longPress" -> {
                requireAction(node.config.contains(SemanticsActions.OnLongClick), "not-clickable", "Node has no long-click action")
                target.performTouchInput { longClick() }
            }
            "type" -> {
                requireAction(node.config.contains(SemanticsActions.SetText) && node.config.contains(SemanticsActions.RequestFocus),
                    "not-editable", "Node has no text replacement or focus action")
                target.requestFocus()
                val payload = step.payload as JsonObject
                target.performTextReplacement(payload.string("value"))
                if ("ime" in payload) {
                    requireAction(node.config.contains(SemanticsActions.OnImeAction), "not-editable", "Node has no IME action")
                    target.performImeAction()
                }
            }
            "swipe" -> target.performTouchInput {
                when ((step.payload as JsonObject).string("direction")) {
                    "left" -> swipeLeft()
                    "right" -> swipeRight()
                    "up" -> swipeUp()
                    "down" -> swipeDown()
                }
            }
        }
    }

    private fun scrollTo(selector: Selector) {
        val matches = selectors.matches(selector)
        lastMatches = selectors.describe(matches)
        if (matches.isNotEmpty()) {
            val target = selectors.resolve(selector)
            val node = target.fetchSemanticsNode()
            enabled(node)
            requireAction(generateSequence(node.parent) { it.parent }.any { hasScrollAction().matches(it) },
                "not-scrollable", "Node has no scrollable ancestor")
            target.performScrollTo()
            return
        }
        val containers = compose.onAllNodes(hasScrollAction())
        val containerIndex = selector.index ?: 0
        requireAction(containerIndex < containers.fetchSemanticsNodes().size, "not-scrollable", "No scroll container at index $containerIndex")
        val container = containers[containerIndex]
        enabled(container.fetchSemanticsNode())
        requireAction(container.fetchSemanticsNode().config.contains(SemanticsActions.ScrollToIndex),
            "not-scrollable", "Uncomposed targets need a lazy scroll container")
        try {
            container.performScrollToNode(selectors.matcher(selector))
        } catch (error: AssertionError) {
            if (selectors.matches(selector).isEmpty()) {
                try {
                    selectors.resolve(selector)
                } catch (missing: PlaytestError) {
                    missing.addSuppressed(error)
                    throw missing
                }
            }
            throw error
        }
    }

    private fun enabled(node: SemanticsNode) {
        requireAction(!node.config.contains(SemanticsProperties.Disabled), "disabled", "Matching node is disabled")
    }

    private fun requireAction(condition: Boolean, kind: String, message: String) {
        if (!condition) throw PlaytestError(kind, message, buildJsonObject { put("matches", lastMatches) })
    }

    private fun advance(duration: Duration) {
        if (duration > Duration.ofHours(1)) {
            val safe = runBlocking(Dispatchers.IO) {
                settings.phoneRestFlow.first() == null && database.runDao().openRun() == null
            }
            scriptCheck(safe, "Advances over one hour require no open workout and no armed rest; finish the workout first")
        }
        time.advance(duration)
        shadowOf(Looper.getMainLooper()).idleFor(duration)
        compose.runOnUiThread { cardioAlarm.fireDue(SystemClock.elapsedRealtime()) }
        compose.mainClock.advanceTimeBy(duration.toMillis().coerceAtMost(5_000))
    }

    private fun settle() {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var last = ""
        var stable = 0
        while (System.nanoTime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            val now = compose.dumpSemantics(compose.density.density)
            stable = if (now == last) stable + 1 else 0
            last = now
            if (stable >= 6 && now != "[\n\n]\n") {
                screen = (Json.parseToJsonElement(now) as JsonArray).asSequence()
                    .mapNotNull { ((it as JsonObject)["text"] as? JsonPrimitive)?.content }
                    .firstOrNull { it.isNotBlank() } ?: "unknown"
                return
            }
            Thread.sleep(5)
        }
        throw PlaytestError("timeout", "Screen did not settle within 10 seconds", buildJsonObject { put("semantics", last) })
    }

    private fun capture(name: String, png: Boolean) {
        val file = File(artifacts.directory, "steps/$name.png")
        if (png) compose.writeShot(file, compose.density.density) else {
            check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
            File(file.parentFile, "$name.json").writeText(compose.dumpSemantics(compose.density.density))
        }
    }

    private fun captureFailure(name: String, error: Throwable) {
        try {
            capture(name, png = true)
        } catch (captureError: Throwable) {
            error.addSuppressed(captureError)
            try {
                capture(name, png = false)
            } catch (dumpError: Throwable) {
                error.addSuppressed(dumpError)
            }
        }
    }
}
