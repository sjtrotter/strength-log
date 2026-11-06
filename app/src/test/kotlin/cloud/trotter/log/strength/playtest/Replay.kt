package cloud.trotter.log.strength.playtest

import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.test.espresso.Espresso
import androidx.test.core.app.ActivityScenario
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.audit.dumpSemantics
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.backup.BackupService
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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
    private var backupError: JsonObject? = null

    init {
        check(directory.mkdirs() || directory.isDirectory)
        listOf("summary.json", "error.json", "backup.json", "backup.json.tmp", "log.jsonl").forEach { File(directory, it).delete() }
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
        // Written only after the export returns, then moved into place, so a timeout never
        // leaves a truncated backup.json behind.
        val json = waitForStorage(60_000) { backup.export() }
        val partial = File(directory, "backup.json.tmp")
        try {
            partial.writeText(json)
            Files.move(partial.toPath(), File(directory, "backup.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            partial.delete()
        }
    }

    fun backupFailure(error: PlaytestError) {
        backupError = failureJson(error, null)
    }

    fun writeSummary(time: PlaytestTime?) {
        File(directory, "summary.json").writeText(buildJsonObject {
            put("seat", script?.seat)
            put("steps_total", script?.steps?.size ?: 0)
            put("steps_run", stepsRun)
            put("failed_step", failedStep?.let(::JsonPrimitive) ?: JsonNull)
            put("error_class", errorClass)
            backupError?.let { put("backup_error", it) }
            put("clock_at_exit", time?.clock?.instant()?.toString())
            put("clock_at_exit_local", time?.clock?.let { LocalDateTime.ofInstant(it.instant(), it.zone).toString() })
            put("clock_zone", script?.start?.zone?.id)
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
    private val crashes: AppCrashes,
) {
    private val selectors = Selectors(compose, compose.density.density)
    private var screen = "launch"
    private var imeLog: JsonObject? = null
    private var settledFrames = 0
    private val lastMatches: JsonArray get() = selectors.lastMatches

    fun run(script: Script) {
        var previousTexts = emptyList<CaptureText>()
        try {
            crashes.step(compose) {
                val deadline = StepDeadline(20_000)
                deadline.run(compose) {
                    settle(deadline)
                    deadline.call { capture("000-launch", png = true) }
                    if (script.compactCapture) previousTexts = deadline.call { snapshotTexts() }
                }
            }
        } catch (error: Throwable) {
            captureFailure("000-FAILED-launch", error)
            throw error
        }
        script.steps.forEachIndexed { index, step ->
            val started = System.nanoTime()
            artifacts.stepsRun++
            selectors.clear()
            imeLog = null
            settledFrames = 0
            val deadline = StepDeadline(step.timeoutMs, index)
            deadline.withWatchdog(onTimeout = { error ->
                try {
                    artifacts.failure(error, index, step.json, replace = true)
                } finally {
                    artifacts.writeSummary(time)
                }
            }) {
                try {
                    crashes.step(compose) {
                        if (step.action != "note" || script.compactCapture) {
                            deadline.run(compose) {
                                if (step.action != "note") {
                                    step.assertExpectedError {
                                        perform(step, deadline)
                                        if (step.action in INTERACTION_ACTIONS) settleInteraction(deadline) else settle(deadline)
                                        if (step.action == "advance") {
                                            deadline.call { shadowOf(Looper.getMainLooper()).idle() }
                                            settle(deadline)
                                        }
                                    }
                                }
                                val name = "%03d-%s".format(index, step.slug)
                                if (script.compactCapture) {
                                    val texts = deadline.call { snapshotTexts() }
                                    if (step.action == "checkpoint" || index == script.steps.lastIndex) {
                                        deadline.call { capture(name, png = true) }
                                    } else if (index >= artifacts.captureFrom) {
                                        deadline.call {
                                            val file = File(artifacts.directory, "steps/$name.txt")
                                            file.writeText(formatCompactDiff(name, previousTexts, texts))
                                        }
                                    }
                                    previousTexts = texts
                                } else if (index >= artifacts.captureFrom || step.action == "checkpoint") {
                                    deadline.call {
                                        capture(name, png = !script.checkpointsOnly || step.action == "checkpoint")
                                    }
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
            put("settle_frames", settledFrames)
            put("matches", lastMatches)
            put("screen", screen)
            step.expectedError?.let { put("expected_error", it) }
            if (step.action == "note") put("note", step.payload)
            imeLog?.let { put("ime", it) }
        })
    }

    private fun perform(step: Step, deadline: StepDeadline) {
        when (step.action) {
            // Like the system key: the focused window (an open sheet or dialog) gets it first.
            "back" -> deadline.call { Espresso.pressBackUnconditionally() }
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
        val resolved = requireNotNull(selectors.await(selector, deadline, typing, requireEnabled = true))
        val target = resolved.interaction
        val node = resolved.node
        if (step.action == "tap" || step.action == "longPress") {
            requireAction(node.visibleForTouch(), "not-visible", "Node has empty or off-screen bounds; use scrollTo first")
        }
        if (step.action == "tap" && node.config.contains(SemanticsProperties.EditableText) && !node.dropdownAnchor()) {
            throw PlaytestError("script", "Use type instead of tap on an editable node; a second click can start the magnifier")
        }
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
                    val declared = node.config.getOrNull(SemanticsProperties.ImeAction)
                    // Compose refuses performImeAction on Default; such a field has no action key and commits as it is typed.
                    val perform = declared != null && declared != ImeAction.Default && declared != ImeAction.None
                    imeLog = buildJsonObject {
                        put("declared", declared?.toString() ?: "none")
                        put("performed", perform)
                    }
                    if (perform) deadline.call { target.performImeAction() }
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
        requireAction(target.node.scrollContainer() != null, "not-scrollable", "Node has no scrollable ancestor")
        var moves = 0
        while (true) {
            val node = deadline.call { target.interaction.fetchSemanticsNode() }
            val container = node.scrollContainer() ?: return
            val (dx, dy) = scrollDelta(node, container)
            if (dx == 0f && dy == 0f) return
            if (moves++ >= MAX_SCROLL_MOVES) {
                throw PlaytestError("not-scrollable", "Target stayed outside its scroll container after $MAX_SCROLL_MOVES scrolls")
            }
            val before = node.positionInRoot
            scrollBy(container, dx, dy, deadline)
            val after = deadline.call { target.interaction.fetchSemanticsNode() }.positionInRoot
            requireAction(after != before, "not-scrollable", "The scroll container did not move toward the target")
        }
    }

    /** Pages each lazy container on screen until one composes the target; the Compose clock is advanced
     *  by hand, so an endless animation cannot stall it. */
    private fun searchLazy(selector: Selector, deadline: StepDeadline) {
        val matcher = selectors.matcher(selector)
        val ids = deadline.call { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes(atLeastOneRootRequired = false) }
            .filter { it.config.contains(SemanticsActions.ScrollToIndex) }.map { it.id }
        ids.forEach { id ->
            val container = compose.onNode(SemanticsMatcher("node $id") { it.id == id })
            fun current() = deadline.call { container.fetchSemanticsNode() }
            if (current().containsMatch(matcher)) return
            val start = current().let { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                ?: it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) }
            // ScrollToIndex(0) throws on an empty list, which is also already at its start.
            if (start == null || start.value() > 0f) {
                deadline.call { compose.runOnUiThread { current().config[SemanticsActions.ScrollToIndex].action?.invoke(0) } }
                settleScroll(deadline)
            }
            repeat(MAX_LAZY_PAGES) {
                val node = current()
                if (node.containsMatch(matcher)) return
                val vertical = node.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                val axisKey = if (vertical) SemanticsProperties.VerticalScrollAxisRange else SemanticsProperties.HorizontalScrollAxisRange
                val axis = node.config.getOrNull(axisKey) ?: return@forEach
                if (axis.value() >= axis.maxValue()) return@forEach
                val page = (if (vertical) node.size.height else node.size.width).toFloat() * (if (axis.reverseScrolling) -1f else 1f)
                val before = axis.value()
                scrollBy(node, if (vertical) 0f else page, if (vertical) page else 0f, deadline)
                if (current().config.getOrNull(axisKey)?.value() == before) return@forEach
            }
        }
    }

    private fun scrollBy(container: SemanticsNode, dx: Float, dy: Float, deadline: StepDeadline) {
        deadline.call { compose.runOnUiThread { container.config[SemanticsActions.ScrollBy].action?.invoke(dx, dy) } }
        settleScroll(deadline)
    }

    /** Scroll semantics animate with a spring, which moves only while the paused Compose clock advances. */
    private fun settleScroll(deadline: StepDeadline) {
        deadline.call { compose.mainClock.advanceTimeBy(SCROLL_SETTLE_MS) }
        deadline.call { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun SemanticsNode.scrollContainer(): SemanticsNode? =
        generateSequence(parent) { it.parent }.firstOrNull { hasScrollAction().matches(it) }

    private fun SemanticsNode.containsMatch(matcher: SemanticsMatcher): Boolean =
        matcher.matches(this) || children.any { it.containsMatch(matcher) }

    /** Compose's scrolling rule: bring the nearer edge into the viewport, or nothing when the target
     *  already fits or is larger than the viewport. */
    private fun scrollDelta(target: SemanticsNode, container: SemanticsNode): Pair<Float, Float> {
        val viewport = container.boundsInRoot
        val bounds = Rect(target.positionInRoot, target.size.toSize())
        fun delta(start: Float, end: Float): Float =
            if (sign(start) == sign(end)) (if (abs(start) < abs(end)) start else end) else 0f
        val horizontal = container.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        val vertical = container.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        var dx = if (horizontal != null) delta(bounds.left - viewport.left, bounds.right - viewport.right) else 0f
        var dy = if (vertical != null) delta(bounds.top - viewport.top, bounds.bottom - viewport.bottom) else 0f
        if (horizontal?.reverseScrolling == true) dx = -dx
        if (container.layoutInfo.layoutDirection == LayoutDirection.Rtl) dx = -dx
        if (vertical?.reverseScrolling == true) dy = -dy
        return dx to dy
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
            settledFrames++
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
            val paused = deadline.pause(compose)
            settledFrames++
            if (!paused) break
        }
        throw deadline.timeout()
    }

    private fun settleInteraction(deadline: StepDeadline) {
        val shapes = compose.settleSemantics(deadline, awaitPendingWork = {
            val locks = deadline.call {
                var pending = emptyList<Mutex>()
                scenario.onActivity { pending = it.viewModelStore.workoutMutationLocks() }
                pending
            }
            deadline.io { awaitWorkoutMutations(locks) }
        }) { settledFrames++ }
        screen = shapes.asSequence().flatMap { it.texts.asSequence() }
            .firstOrNull { it.isNotBlank() } ?: "unknown"
    }

    private fun snapshotTexts(): List<CaptureText> = compose.semanticsSnapshot(compose.density.density).captureTexts()

    private fun capture(name: String, png: Boolean) {
        val file = File(artifacts.directory, "steps/$name.png")
        if (png) compose.writePlaytestShot(file, compose.density.density) else {
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

private const val SCROLL_SETTLE_MS = 1_000L
private const val MAX_SCROLL_MOVES = 20
private const val MAX_LAZY_PAGES = 50
private val INTERACTION_ACTIONS = setOf("tap", "longPress", "swipe", "back", "scrollTo")

internal fun SemanticsNode.dropdownAnchor(): Boolean =
    !config.contains(SemanticsActions.SetText) && config.contains(SemanticsActions.OnClick) &&
        (config.getOrNull(SemanticsProperties.Role) == Role.DropdownList ||
            config.contains(SemanticsActions.Expand) || config.contains(SemanticsActions.Collapse))

internal fun SemanticsNode.visibleForTouch(): Boolean {
    val bounds = boundsInWindow
    val window = generateSequence(this) { it.parent }.last().boundsInWindow
    return !bounds.isEmpty && bounds.overlaps(window)
}
