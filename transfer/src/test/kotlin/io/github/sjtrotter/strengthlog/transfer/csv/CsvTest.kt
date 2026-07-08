package io.github.sjtrotter.strengthlog.transfer.csv

import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 4180 quoting/parsing edge cases (#16): the primitives everything else
 *  in this package is built on. */
class CsvTest {

    @Test
    fun `plain fields need no quoting`() {
        assertEquals("a,b,c", Csv.writeRow(listOf("a", "b", "c")))
    }

    @Test
    fun `a field containing a comma is quoted`() {
        assertEquals("""a,"b, and c",d""", Csv.writeRow(listOf("a", "b, and c", "d")))
    }

    @Test
    fun `a field containing a quote is quoted and the quote is doubled`() {
        val fieldWithQuote = "say \"hi\""
        assertEquals("a,\"say \"\"hi\"\"\",c", Csv.writeRow(listOf("a", fieldWithQuote, "c")))
    }

    @Test
    fun `a field containing a newline is quoted`() {
        assertEquals("a,\"line1\nline2\",c", Csv.writeRow(listOf("a", "line1\nline2", "c")))
    }

    @Test
    fun `write then parse round-trips fields with commas quotes and newlines`() {
        val fields = listOf("plain", "with, comma", "with \"quote\"", "with\nnewline", "")
        val line = Csv.writeRow(fields)
        assertEquals(listOf(fields), Csv.parse(line))
    }

    @Test
    fun `parse splits multiple rows on newlines`() {
        val text = "a,b\nc,d\n"
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse(text))
    }

    @Test
    fun `parse handles CRLF line endings`() {
        val text = "a,b\r\nc,d\r\n"
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse(text))
    }

    @Test
    fun `parse does not add a phantom row after a trailing newline`() {
        assertEquals(2, Csv.parse("a,b\nc,d\n").size)
    }

    @Test
    fun `parse handles a final row with no trailing newline`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse("a,b\nc,d"))
    }

    @Test
    fun `parse keeps a genuinely blank line as an empty row`() {
        assertEquals(listOf(listOf("a"), listOf("")), Csv.parse("a\n\n"))
    }

    @Test
    fun `a quoted field may itself contain a newline`() {
        val text = "\"multi\nline\",b\n"
        assertEquals(listOf(listOf("multi\nline", "b")), Csv.parse(text))
    }

    @Test
    fun `empty input parses to no rows`() {
        assertEquals(emptyList(), Csv.parse(""))
    }
}
