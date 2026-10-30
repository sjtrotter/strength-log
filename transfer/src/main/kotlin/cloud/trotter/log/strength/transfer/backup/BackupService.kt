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
 * The public surface is Uri-free (strings and streams only) so it runs and tests
 * without Android's SAF; the `:app` layer supplies the share-sheet /
 * document-picker plumbing on top.
 *
 * Restore is an explicit, unconditional replace — the confirm-overwrite dialog is
 * the UI's responsibility. [import] validates the file end-to-end via [BackupCodec]
 * and throws a typed [BackupError] *before* any database write, so a bad file
 * leaves the device untouched.
 *
 * A restore that *starts* is journaled rather than assumed atomic: see
 * [RestoreJournal] and [reconcilePendingRestore].
 */
class BackupService(
    private val repository: TrackerRepository,
    private val journal: RestoreJournal,
    private val codec: BackupCodec = BackupCodec(),
) {

    /**
     * One restore at a time, and nothing that depends on its outcome running
     * beside it. Restore and startup reconciliation drive the same journal, so a
     * reconcile landing mid-restore would replay a payload the restore is still
     * writing.
     */
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
     * returning whether one was outstanding. Called once per launch; a no-op
     * on every normal start.
     */
    suspend fun reconcilePendingRestore(): Boolean =
        restoreLock.withLock { repository.reconcilePendingRestore(journal) }

    /**
     * Finishes any half-done restore before running [block], so callers see a
     * settled device. No restore or reconciliation can start until it returns;
     * if recovery fails, the exception propagates without running [block].
     *
     * For callers whose *decision* depends on what a restore has already
     * written, not just their own writes. The first-run wizard's finish is the
     * one that matters: it reads `wizardComplete` to decide whether the device
     * was restored underneath it, and outside this lock the startup
     * reconciliation can flip that flag between the read and the
     * `replaceProgram` it authorizes — deleting the program that was just
     * restored. Inside it, check and use are one critical section.
     *
     * Keep [block] short; it holds up restore.
     */
    suspend fun <T> withRestoreLock(block: suspend () -> T): T = restoreLock.withLock {
        repository.reconcilePendingRestore(journal)
        block()
    }
}
