package cloud.trotter.log.strength.playtest

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class Selectors(private val nodes: ComposeTestRule, private val density: Float) {
    var lastMatches: JsonArray = JsonArray(emptyList())
        private set

    fun clear() {
        lastMatches = JsonArray(emptyList())
    }

    fun matcher(selector: Selector, typing: Boolean = false, deadline: StepDeadline? = null): SemanticsMatcher = SemanticsMatcher(selector.json.toString()) { node ->
        deadline?.checkTime()
        val texts = node.selectorTexts(typing)
        (selector.text("text")?.let { wanted -> texts.any { it.trim() == wanted.trim() } } ?: true) &&
            (selector.text("textContains")?.let { wanted -> texts.any { wanted in it } } ?: true) &&
            (selector.text("desc")?.let { it in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } ?: true) &&
            (selector.text("tag")?.let { it == node.config.getOrNull(SemanticsProperties.TestTag) } ?: true) &&
            (selector.text("role")?.let { it == node.config.getOrNull(SemanticsProperties.Role)?.toString() } ?: true) &&
            (!(typing || selector.flag("editable") == true) || node.config.contains(SemanticsProperties.EditableText)) &&
            (selector.flag("inDialog")?.let { wanted ->
                wanted == generateSequence(node) { it.parent }.any { isDialog().matches(it) }
            } ?: true)
    }

    data class Target(val interaction: SemanticsNodeInteraction, val node: SemanticsNode)

    fun await(
        selector: Selector,
        deadline: StepDeadline,
        typing: Boolean = false,
        absent: Boolean = false,
        search: (() -> Unit)? = null,
    ): Target? {
        var lastFailure: PlaytestError? = null
        while (true) {
            if (deadline.expired) throw lastFailure ?: deadline.timeout()
            val collection = nodes.onAllNodes(matcher(selector, typing, deadline), useUnmergedTree = typing)
            val all = try {
                deadline.call {
                    nodes.onAllNodes(SemanticsMatcher("all nodes") { true }, useUnmergedTree = typing)
                        .fetchSemanticsNodes(atLeastOneRootRequired = false)
                }
            } catch (error: PlaytestError) {
                if (error.kind == "timeout" && error.cause == null && lastFailure != null) throw lastFailure
                throw error
            }
            val match = matcher(selector, typing)
            val matches = all.filter { match.matches(it) }
            lastMatches = describe(matches)
            val index = selector.index ?: 0
            val present = index < matches.size
            if (absent && !present) return null
            if (!absent && present && (selector.index != null || matches.size == 1)) {
                return Target(collection[index], matches[index])
            }
            val failure = when {
                absent -> PlaytestError("ambiguous", "Expected no matches for ${selector.json}", buildJsonObject {
                    put("matches", lastMatches)
                })
                present -> PlaytestError("ambiguous", "${matches.size} matches for ${selector.json}; add index or narrow the selector", buildJsonObject {
                    put("matches", lastMatches)
                })
                else -> {
                    val nonEditable = typing && all.any { matcher(selector).matches(it) }
                    if (nonEditable) PlaytestError("not-editable", "Use type on a text field with EditableText semantics")
                    else PlaytestError("not-found", "No match at index $index for ${selector.json}", buildJsonObject {
                        put("candidates", candidates(selector, typing, all))
                    })
                }
            }
            lastFailure = failure
            try {
                if (!present && !absent) search?.invoke()
                if (!deadline.pause(nodes)) throw failure
            } catch (error: PlaytestError) {
                // A deadline reached between attempts reports the final selector result.
                // A timeout inside Compose/Espresso remains a timeout with its original cause.
                if (error.kind == "timeout" && error.cause == null) throw failure
                throw error
            }
        }
    }

    fun describe(matches: List<SemanticsNode>): JsonArray = JsonArray(matches.map { node ->
        val bounds = node.boundsInRoot
        buildJsonObject {
            put("text", node.selectorTexts(false).joinToString("\n"))
            put("role", node.config.getOrNull(SemanticsProperties.Role)?.toString())
            put("tag", node.config.getOrNull(SemanticsProperties.TestTag))
            put("disabled", node.config.contains(SemanticsProperties.Disabled))
            put("bounds", buildJsonObject {
                put("left", bounds.left / density)
                put("top", bounds.top / density)
                put("width", bounds.width / density)
                put("height", bounds.height / density)
            })
            put("ancestorText", generateSequence(node.parent) { it.parent }
                .map { it.selectorTexts(false).joinToString("\n") }.firstOrNull { it.isNotBlank() })
        }
    })

    private fun candidates(selector: Selector, typing: Boolean, all: List<SemanticsNode>): JsonArray {
        val words = (selector.text("text") ?: selector.text("textContains") ?: selector.text("desc")).orEmpty()
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        return describe(all.filter { it.selectorTexts(typing).isNotEmpty() }.sortedByDescending { node ->
            words.count { word -> node.selectorTexts(typing).any { it.contains(word, ignoreCase = true) } }
        }.distinctBy { it.selectorTexts(typing) }.take(15))
    }
}

private fun SemanticsNode.ownTexts(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)

private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

private fun SemanticsNode.selectorTexts(typing: Boolean): List<String> {
    if (!typing) return ownTexts()
    // OutlinedTextField can put its floating label beside the editable child.
    // Never borrow labels from a form containing several editable fields.
    val parentField = parent?.takeIf { parent ->
        parent.descendants().count { it.config.contains(SemanticsProperties.EditableText) } == 1
    }
    return (ownTexts() + descendants().flatMap { it.ownTexts() } +
        parent?.ownTexts().orEmpty() + parentField?.descendants().orEmpty().flatMap { it.ownTexts() }).distinct()
}

