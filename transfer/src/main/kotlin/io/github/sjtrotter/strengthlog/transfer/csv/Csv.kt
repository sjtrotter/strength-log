package io.github.sjtrotter.strengthlog.transfer.csv

/**
 * A minimal RFC 4180 CSV codec: no third-party dependency (mirrors
 * [io.github.sjtrotter.strengthlog.transfer.backup.BackupCodec]'s hand-rolled
 * approach) and no domain knowledge — just the byte-correct grammar every
 * spreadsheet and Strong/Hevy's exporters share.
 *
 * A field is quoted only when it needs to be (contains a comma, a quote, or a
 * newline); an embedded quote is escaped by doubling it. [parse] is the
 * inverse: it reads the whole document character-by-character rather than
 * splitting lines on commas, because a quoted field may itself contain a
 * comma or a newline — the exact case an exercise name ("Bench, Close-Grip")
 * or a workout note can trigger.
 */
object Csv {

    fun writeRow(fields: List<String>): String = fields.joinToString(",") { quoteIfNeeded(it) }

    private fun quoteIfNeeded(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /**
     * Parses [text] into rows of fields, honoring quoted fields that span
     * embedded commas/newlines and doubled-quote escaping. Accepts `\n`,
     * `\r\n`, or bare `\r` line endings (Strong/Hevy exports vary). A
     * completely blank trailing line at EOF does not produce a phantom empty
     * row.
     */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        // Whether anything has been consumed into the current (not-yet-ended)
        // row, so EOF flushes a genuine trailing row but never a phantom empty
        // one after a final newline.
        var pending = false

        fun endField() {
            row.add(field.toString())
            field.clear()
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
            pending = false
        }

        while (i < text.length) {
            val c = text[i]
            pending = true
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> endField()
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        endRow()
                    }
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (pending) endRow()
        return rows
    }
}
