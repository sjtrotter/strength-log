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

    @Synchronized
    fun failure(error: PlaytestError, index: Int?, step: JsonElement? = null, replace: Boolean = false) {
        if (errorClass != null && !replace) return
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
    private val lastMatches: JsonArray get() = selectors.lastMatches

    fun run(script: Script) {
        try {
            val deadline = StepDeadline(10_000)
            deadline.run(compose) {
                settle(deadline)
                deadline.call { capture("000-launch", png = true) }
            }
        } catch (error: Throwable) {
            captureFailure("000-FAILED-launch", error)
            throw error
        }
        script.steps.forEachIndexed { index, step ->
            val started = System.nanoTime()
            artifacts.stepsRun++
            selectors.clear()
            val deadline = StepDeadline(step.timeoutMs, index)
            deadline.withWatchdog(onTimeout = { error ->
                try {
                    artifacts.failure(error, index, step.json, replace = true)
                } finally {
                    artifacts.writeSummary(time)
                }
            }) {
                try {
                    if (step.action != "note") {
                        deadline.run(compose) {
                            perform(step, deadline)
                            settle(deadline)
                            if (index >= artifacts.captureFrom || step.action == "checkpoint") {
                                deadline.call {
                                    capture("%03d-%s".format(index, step.slug),
                                        png = !script.checkpointsOnly || step.action == "checkpoint")
                                }
                            }
                        }
                    }
                    log(index, step, started, ok = true)
                } catch (error: Throwable) {
                    artifacts.failure(error.playtestError(), index, step.json)
                    captureFailure("%03d-FAILED-%s".format(index, step.slug), error)
                    log(index, step, started, ok = false)
                    throw error
                }
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

    private fun perform(step: Step, deadline: StepDeadline) {
        when (step.action) {
            "back" -> deadline.call { scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
            "recreate" -> deadline.call { scenario.recreate() }
            "advance" -> advance(requireNotNull(step.duration), deadline)
            "checkpoint" -> Unit
            "expect" -> expect(step, deadline)
            "scrollTo" -> scrollTo(requireNotNull(step.selector), deadline)
            else -> act(step, deadline)
        }
    }

    private fun expect(step: Step, deadline: StepDeadline) {
        val selector = requireNotNull(step.selector)
        val absent = (step.payload as JsonObject)["absent"] == JsonPrimitive(true)
        selectors.await(selector, deadline, absent = absent)
    }

    private fun act(step: Step, deadline: StepDeadline) {
        val selector = requireNotNull(step.selector)
        val typing = step.action == "type"
        val resolved = requireNotNull(selectors.await(selector, deadline, typing))
        val target = resolved.interaction
        val node = resolved.node
        if (step.action == "tap" && node.config.contains(SemanticsProperties.EditableText)) {
            throw PlaytestError("script", "Use type instead of tap on an editable node; a second click can start the magnifier")
        }
        enabled(node)
        when (step.action) {
            "tap" -> {
                requireAction(node.config.contains(SemanticsActions.OnClick), "not-clickable", "Node has no click action")
                deadline.call { target.performClick() }
            }
            "longPress" -> {
                requireAction(node.config.contains(SemanticsActions.OnLongClick), "not-clickable", "Node has no long-click action")
                deadline.call { target.performTouchInput { longClick() } }
            }
            "type" -> {
                requireAction(node.config.contains(SemanticsActions.SetText) && node.config.contains(SemanticsActions.RequestFocus),
                    "not-editable", "Node has no text replacement or focus action")
                deadline.call { target.requestFocus() }
                val payload = step.payload as JsonObject
                deadline.call { target.performTextReplacement(payload.string("value")) }
                if ("ime" in payload) {
                    requireAction(node.config.contains(SemanticsActions.OnImeAction), "not-editable", "Node has no IME action")
                    deadline.call { target.performImeAction() }
                }
            }
            "swipe" -> deadline.call {
                target.performTouchInput {
                    when ((step.payload as JsonObject).string("direction")) {
                        "left" -> swipeLeft()
                        "right" -> swipeRight()
                        "up" -> swipeUp()
                        "down" -> swipeDown()
                    }
                }
            }
        }
    }

    private fun scrollTo(selector: Selector, deadline: StepDeadline) {
        val target = requireNotNull(selectors.await(selector, deadline, search = { searchLazy(selector, deadline) }))
        enabled(target.node)
        requireAction(generateSequence(target.node.parent) { it.parent }.any { hasScrollAction().matches(it) },
            "not-scrollable", "Node has no scrollable ancestor")
        deadline.call { target.interaction.performScrollTo() }
    }

    private fun searchLazy(selector: Selector, deadline: StepDeadline) {
        val containers = compose.onAllNodes(hasScrollAction())
        val containerIndex = selector.index ?: 0
        val nodes = deadline.call { containers.fetchSemanticsNodes(atLeastOneRootRequired = false) }
        if (containerIndex >= nodes.size) return
        val node = nodes[containerIndex]
        if (!node.config.contains(SemanticsActions.ScrollToIndex)) return
        val container = containers[containerIndex]
        enabled(node)
        try {
            deadline.call { container.performScrollToNode(selectors.matcher(selector, deadline = deadline)) }
        } catch (error: AssertionError) {
            if (error.isWaitTimeout()) throw deadline.timeout(error)
            // A lazy list can be populated by storage after this search. Retry the selector.
        }
    }

    private fun enabled(node: SemanticsNode) {
        requireAction(!node.config.contains(SemanticsProperties.Disabled), "disabled", "Matching node is disabled")
    }

    private fun requireAction(condition: Boolean, kind: String, message: String) {
        if (!condition) throw PlaytestError(kind, message, buildJsonObject { put("matches", lastMatches) })
    }

    private fun advance(duration: Duration, deadline: StepDeadline) {
        if (duration > Duration.ofHours(1)) {
            val safe = deadline.io {
                settings.phoneRestFlow.first() == null && database.runDao().openRun() == null
            }
            scriptCheck(safe, "Advances over one hour require no open workout and no armed rest; finish the workout first")
        }
        time.advance(duration)
        var remaining = duration
        while (!remaining.isZero) {
            val chunk = remaining.coerceAtMost(Duration.ofSeconds(1))
            deadline.call { shadowOf(Looper.getMainLooper()).idleFor(chunk) }
            remaining = remaining.minus(chunk)
        }
        deadline.call { compose.runOnUiThread { cardioAlarm.fireDue(SystemClock.elapsedRealtime()) } }
        deadline.call { compose.mainClock.advanceTimeBy(duration.toMillis().coerceAtMost(5_000)) }
    }

    private fun settle(deadline: StepDeadline) {
        var last = ""
        var stable = 0
        while (!deadline.expired) {
            deadline.call { compose.mainClock.advanceTimeByFrame() }
            deadline.call { shadowOf(Looper.getMainLooper()).idle() }
            val now = deadline.call { compose.dumpSemantics(compose.density.density) }
            stable = if (now == last) stable + 1 else 0
            last = now
            if (stable >= 6 && now != "[\n\n]\n") {
                screen = (Json.parseToJsonElement(now) as JsonArray).asSequence()
                    .mapNotNull { ((it as JsonObject)["text"] as? JsonPrimitive)?.content }
                    .firstOrNull { it.isNotBlank() } ?: "unknown"
                return
            }
            if (!deadline.pause(compose)) break
        }
        throw deadline.timeout()
    }

    private fun capture(name: String, png: Boolean) {
        val file = File(artifacts.directory, "steps/$name.png")
        if (png) compose.writeShot(file, compose.density.density) else {
            check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
            File(file.parentFile, "$name.json").writeText(compose.dumpSemantics(compose.density.density))
        }
    }

    private fun captureFailure(name: String, error: Throwable) {
        val deadline = StepDeadline(1_000)
        try {
            deadline.run(compose) { capture(name, png = true) }
        } catch (captureError: Throwable) {
            error.addSuppressed(captureError)
            try {
                deadline.run(compose) { capture(name, png = false) }
            } catch (dumpError: Throwable) {
                error.addSuppressed(dumpError)
            }
        }
    }
}
