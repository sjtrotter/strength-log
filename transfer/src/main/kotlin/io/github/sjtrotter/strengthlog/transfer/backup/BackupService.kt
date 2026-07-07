package io.github.sjtrotter.strengthlog.transfer.backup

import io.github.sjtrotter.strengthlog.data.TrackerRepository
import java.io.InputStream
import java.io.OutputStream

/**
 * The A2 full backup, wired to the data layer. Everything the caller needs:
 * produce a backup string/stream from the current device state, or restore the
 * device from one — replacing all existing data.
 *
 * The public surface is deliberately Uri-free (strings and streams only) so it
 * runs and tests without Android's SAF; the `:app` layer supplies the share-sheet
 * / document-picker plumbing on top later.
 *
 * Restore is an explicit, unconditional replace — the confirm-overwrite dialog is
 * the UI's responsibility. [import] validates the file end-to-end via [BackupCodec]
 * and throws a typed [BackupError] *before* any database write, so a bad file
 * leaves the device untouched.
 */
class BackupService(
    private val repository: TrackerRepository,
    private val codec: BackupCodec = BackupCodec(),
) {

    /** The current device state as a deterministic backup string. */
    suspend fun export(): String = codec.encode(repository.exportSnapshot().toDocument())

    suspend fun exportTo(out: OutputStream) {
        out.write(export().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Validates [text] and, only if fully valid, replaces all device state with it. */
    suspend fun import(text: String) {
        val document = codec.decode(text)
        repository.importSnapshot(document.toSnapshot())
    }

    suspend fun importFrom(input: InputStream) = import(codec.readCapped(input))
}
