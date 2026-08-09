package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *
 * A restore that *starts* is journaled rather than assumed atomic: see
 * [RestoreJournal] and [reconcilePendingRestore] (#172).
 */
class BackupService(
    private val repository: TrackerRepository,
    private val journal: RestoreJournal,
    private val codec: BackupCodec = BackupCodec(),
) {

    /** Restore and startup reconciliation drive the same journal, so they take
     *  turns: a reconcile running mid-restore would replay a payload the restore
     *  is still in the middle of writing. */
    private val restoreLock = Mutex()

    /** The current device state as a deterministic backup string. */
    suspend fun export(): String = codec.encode(repository.exportSnapshot().toDocument())

    suspend fun exportTo(out: OutputStream) {
        out.write(export().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Validates [text] and, only if fully valid, replaces all device state with it. */
    suspend fun import(text: String) {
        val document = codec.decode(text)
        restoreLock.withLock { repository.importSnapshot(document.toSnapshot(), journal) }
    }

    suspend fun importFrom(input: InputStream) = import(codec.readCapped(input))

    /**
     * Finishes a restore that was cut between its Room and settings halves,
     * returning whether one was outstanding (#172). Called once per launch; a
     * no-op on every normal start.
     */
    suspend fun reconcilePendingRestore(): Boolean = restoreLock.withLock { journal.reconcile() }
}
