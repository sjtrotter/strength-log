package cloud.trotter.log.strength.playtest

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isDialog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class Selectors(private val nodes: SemanticsNodeInteractionsProvider, private val density: Float) {
    fun matcher(selector: Selector, typing: Boolean = false): SemanticsMatcher = SemanticsMatcher(selector.json.toString()) { node ->
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

    fun matches(selector: Selector, typing: Boolean = false): List<SemanticsNode> =
        nodes.onAllNodes(matcher(selector, typing), useUnmergedTree = typing).fetchSemanticsNodes()

    fun resolve(selector: Selector, typing: Boolean = false): SemanticsNodeInteraction {
        val matches = matches(selector, typing)
        val index = selector.index ?: 0
        if (matches.isEmpty() || index >= matches.size) {
            throw PlaytestError("not-found", "No match at index $index for ${selector.json}", buildJsonObject {
                put("candidates", candidates(selector, typing))
            })
        }
        if (matches.size > 1 && selector.index == null) {
            throw PlaytestError("ambiguous", "${matches.size} matches for ${selector.json}; add index or narrow the selector", buildJsonObject {
                put("matches", describe(matches))
            })
        }
        return nodes.onAllNodes(matcher(selector, typing), useUnmergedTree = typing)[index]
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

    private fun candidates(selector: Selector, typing: Boolean): JsonArray {
        val words = (selector.text("text") ?: selector.text("textContains") ?: selector.text("desc")).orEmpty()
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        val all = nodes.onAllNodes(SemanticsMatcher("text candidates") {
            it.selectorTexts(typing).isNotEmpty()
        }, useUnmergedTree = typing).fetchSemanticsNodes()
        return describe(all.sortedByDescending { node ->
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

