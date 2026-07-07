package io.github.sjtrotter.strengthlog.transfer.backup

/**
 * Every way a restore can refuse a file, as a typed hierarchy. The import path
 * throws one of these *before* it touches the database, so a rejected backup
 * always leaves the device exactly as it was — there is no partial import. The
 * later UI can map each case to a specific message; the tests assert on the type.
 */
sealed class BackupError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The file is larger than the accepted ceiling; refused before parsing. */
    class TooLarge(val bytes: Long, val maxBytes: Long) :
        BackupError("Backup is $bytes bytes, over the $maxBytes-byte limit")

    /** Not valid JSON, or valid JSON whose shape isn't a backup document. */
    class Malformed(detail: String, cause: Throwable? = null) :
        BackupError("Malformed backup: $detail", cause)

    /** A backup written by a version this build doesn't know how to read. */
    class UnsupportedSchemaVersion(val found: Int?, val supported: Int) :
        BackupError("Unsupported backup schema version $found (this build reads $supported)")

    /** A program slot names an exercise id that resolves to neither the code
     *  catalog nor a custom exercise carried by this same backup. */
    class DanglingExerciseReference(val exerciseId: String) :
        BackupError("Backup references unknown exercise id '$exerciseId'")

    /** A custom exercise is itself unusable (bad id prefix, id collision,
     *  duplicate, or an unparseable pattern/equipment value). */
    class InvalidCustomExercise(detail: String) :
        BackupError("Invalid custom exercise: $detail")

    /** The backup is internally contradictory (e.g. the rotation pointer names a
     *  day it doesn't contain, or a live log attaches to a non-existent slot). */
    class Inconsistent(detail: String) :
        BackupError("Inconsistent backup: $detail")
}
