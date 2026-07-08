package io.github.sjtrotter.strengthlog.transfer.csv

/**
 * Every way a CSV history import can refuse a file, as a typed hierarchy
 * (mirrors [io.github.sjtrotter.strengthlog.transfer.backup.BackupError]).
 * Every case is thrown while building the pure preview, strictly before
 * [io.github.sjtrotter.strengthlog.data.TrackerRepository.importSessionHistory]
 * is ever called, so a rejected file always leaves the device untouched.
 */
sealed class CsvImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The file is larger than the accepted ceiling; refused before parsing. */
    class TooLarge(val bytes: Long, val maxBytes: Long) :
        CsvImportError("CSV is $bytes bytes, over the $maxBytes-byte limit")

    /** The file has a header row (or none at all) but no data rows. */
    class Empty : CsvImportError("CSV file has no data rows")

    /** One or more columns this import needs weren't found by header name. */
    class MissingColumns(val missing: List<String>) :
        CsvImportError("Missing required column(s): ${missing.joinToString(", ")}")

    /** A weight column's unit can't be determined from either its own header
     *  spelling (e.g. "Weight (kg)") or a companion "Weight Unit" column. */
    class AmbiguousWeightUnit(val header: String) :
        CsvImportError("Cannot determine the weight unit for column '$header'")

    /** A data row is malformed: wrong field count, an unparsable date/number,
     *  or a "Weight Unit" cell that isn't lb/kg. [line] is 1-based and counts
     *  the header, matching what a spreadsheet's row number would show. */
    class MalformedRow(val line: Int, val detail: String) :
        CsvImportError("Row $line: $detail")

    /** [CsvHistoryImporter.commit] was called without an approved pattern for
     *  every name [CsvImportPreview.unmatchedNames] listed. */
    class MissingApproval(val names: List<String>) :
        CsvImportError("Missing an approved pattern for: ${names.joinToString(", ")}")
}
