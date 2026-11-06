package cloud.trotter.log.strength.playtest

internal data class CaptureText(val text: String, val inDialog: Boolean = false)

internal fun formatCompactDiff(step: String, previous: List<CaptureText>, current: List<CaptureText>): String {
    fun lines(texts: List<CaptureText>): List<CaptureText> = texts.flatMap { entry ->
        entry.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { CaptureText(it, entry.inDialog) }.toList()
    }
    val before = lines(previous)
    val after = lines(current)
    fun changes(from: List<CaptureText>, against: List<CaptureText>): List<CaptureText> {
        val remaining = against.groupingBy { it }.eachCount().toMutableMap()
        return from.filter { entry ->
            val count = remaining[entry] ?: 0
            if (count > 0) remaining[entry] = count - 1
            count == 0
        }
    }
    return buildString {
        appendLine(step)
        listOf("- " to changes(before, after), "+ " to changes(after, before)).forEach { (prefix, entries) ->
            entries.forEach { entry ->
                append(prefix)
                if (entry.inDialog) append("[dialog] ")
                appendLine(entry.text)
            }
        }
    }
}
