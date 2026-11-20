package cloud.trotter.log.strength.playtest

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import kotlin.math.roundToInt

internal fun SemanticsNodeInteractionsProvider.screenListing(header: String, after: List<String>): String = buildString {
    appendLine("## $header")
    if (after.isNotEmpty()) appendLine("after: ${after.joinToString("; ")}")
    val layers = onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
        .mapIndexed { index, root ->
            val nodes = root.listingDescendants()
            val kind = when {
                nodes.any { isPopup().matches(it) } -> 0
                nodes.any { isDialog().matches(it) } -> 1
                else -> 2
            }
            Triple(kind, index, nodes)
        }.sortedWith(compareBy({ it.first }, { -it.second }))
    layers.forEach { (kind, _, nodes) ->
        appendLine(when (kind) { 0 -> "[popup]"; 1 -> "[dialog]"; else -> "[screen]" })
        nodes.sortedWith(compareBy(
            { (it.positionInRoot.y / it.layoutInfo.density.density).roundToInt() },
            { (it.positionInRoot.x / it.layoutInfo.density.density).roundToInt() },
        )).forEach { node ->
            val offscreen = !node.visibleForTouch()
            val inScroll = generateSequence(node.parent) { it.parent }
                .any { it.config.contains(SemanticsActions.ScrollBy) }
            if (!offscreen || inScroll) {
                node.listingLine(offscreen)?.let { appendLine("  $it") }
                node.scrollListing()?.let { appendLine("  $it") }
            }
        }
    }
}

private fun SemanticsNode.listingDescendants(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.listingDescendants() }

private fun List<String>.listingParts(): List<String> = flatMap { it.lines() }
    .map { it.trim() }.filter { it.isNotEmpty() }

private fun List<String>.listingText(): String = joinToString(" / ") {
    if (it.length > 160) it.take(157) + "…" else it
}.quoted()

private fun SemanticsNode.listingLine(offscreen: Boolean): String? {
    val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }.listingParts()
    val editable = config.getOrNull(SemanticsProperties.EditableText)
    val descriptions = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().listingParts()
    val tag = config.getOrNull(SemanticsProperties.TestTag)
    val role = config.getOrNull(SemanticsProperties.Role)
    val clickable = config.contains(SemanticsActions.OnClick) || config.contains(SemanticsActions.OnLongClick)
    if (!config.contains(SemanticsProperties.Text) && editable == null &&
        !config.contains(SemanticsProperties.ContentDescription) && tag == null && role == null && !clickable) return null
    val roleKind = when (role) {
        Role.Button -> "button"
        Role.Checkbox -> "checkbox"
        Role.Switch -> "switch"
        Role.RadioButton -> "radio"
        Role.Tab -> "tab"
        Role.DropdownList -> "dropdown"
        Role.Image -> "image"
        else -> null
    }
    val kind = when {
        editable != null -> "field"
        roleKind != null -> roleKind
        clickable -> "row"
        config.contains(SemanticsProperties.Heading) -> "heading"
        config.contains(SemanticsProperties.Text) -> "text"
        else -> "area"
    }
    val flags = buildList {
        if (config.contains(SemanticsProperties.Disabled)) add("disabled")
        if (config.getOrNull(SemanticsProperties.Selected) == true) add("selected")
        config.getOrNull(SemanticsProperties.ToggleableState)?.let {
            add(when (it) {
                ToggleableState.On -> "checked"
                ToggleableState.Off -> "unchecked"
                ToggleableState.Indeterminate -> "indeterminate"
            })
        }
        if (offscreen) add("offscreen")
    }
    return buildString {
        append(kind)
        if (editable != null) append(" ${texts.listingText()} = ${listOf(editable.text).listingParts().listingText()}")
        else if (texts.isNotEmpty()) append(" ${texts.listingText()}")
        if (descriptions.isNotEmpty() && descriptions != texts) append(" desc ${descriptions.listingText()}")
        if (tag != null) append(" tag $tag")
        if (flags.isNotEmpty()) append(" (${flags.joinToString(", ")})")
    }
}

private fun SemanticsNode.scrollListing(): String? {
    if (!config.contains(SemanticsActions.ScrollBy)) return null
    val directions = buildList {
        listOf(
            SemanticsProperties.VerticalScrollAxisRange to ("above" to "below"),
            SemanticsProperties.HorizontalScrollAxisRange to ("left" to "right"),
        ).forEach { (key, words) ->
            config.getOrNull(key)?.let { axis ->
                val (before, after) = if (axis.reverseScrolling) words.second to words.first else words
                if (axis.value() > 0f) add("more $before")
                if (axis.value() < axis.maxValue()) add("more $after")
            }
        }
    }
    return directions.takeIf { it.isNotEmpty() }?.joinToString(", ", "scroll area (", ")")
}
