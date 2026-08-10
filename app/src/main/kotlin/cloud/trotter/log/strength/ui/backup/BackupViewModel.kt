package cloud.trotter.log.strength.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.di.ApplicationScope
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.transfer.backup.BackupCodec
import cloud.trotter.log.strength.transfer.backup.BackupError
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.csv.CsvHistoryService
import cloud.trotter.log.strength.transfer.csv.CsvImportError
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import cloud.trotter.log.strength.ui.text.BackupStatusKind
import cloud.trotter.log.strength.ui.text.UiText

/**
 * Backs the Data/Backup screen (PLAN.md A2; brief D9 — the `:transfer` cores
 * stay Uri-free, so *this* class is where a SAF [Uri] becomes a stream: every
 * function here resolves one via [Context.getContentResolver] and hands the
 * opened stream/string to [BackupService] or [CsvHistoryService]). All four
 * flows run on [Dispatchers.IO] off the main thread; nothing here touches the
 * database directly — the `:transfer` services own every write.
 *
 * A restore is read and fully validated ([BackupCodec.decode], which throws
 * before writing anything) *before* [pendingRestoreConfirm] is set, so the
 * confirm-overwrite dialog only ever appears for a file this build can
 * actually restore. A CSV import goes through the same shape: [CsvHistoryService.preview]
 * builds a read-only preview, and only [confirmCsvImport] commits it.
 *
 * The validated backup text and the CSV preview live in a plain [MutableStateFlow],
 * not [androidx.lifecycle.SavedStateHandle]: unlike a hand-typed wizard/custom-
 * exercise draft, this is derived data re-read from the same file on retry, it
 * can be many megabytes (a [androidx.lifecycle.SavedStateHandle] entry rides in
 * a Bundle and risks `TransactionTooLargeException`), and losing it to process
 * death loses no user data — nothing is written to the device until the user
 * explicitly confirms.
 *
 * A confirmed restore is the one operation here that does not belong to this
 * ViewModel's lifetime. It writes Room and then DataStore, and this class is
 * scoped to a nav entry, so a back press used to cancel it mid-write and leave
 * the two stores disagreeing in silence (#172). It runs on [appScope] instead;
 * this ViewModel only waits for it and reports.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupService: BackupService,
    private val csvHistoryService: CsvHistoryService,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val codec = BackupCodec()

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private var pendingRestoreText: String? = null

    // --- JSON backup -----------------------------------------------------

    fun exportBackup(uri: Uri) = runBusy {
        openOutput(uri) { out -> backupService.exportTo(out) }
        postMessage(UiText.BackupStatus(BackupStatusKind.BACKUP_EXPORTED), isError = false)
    }

    /** Reads and validates the picked file; only on success does the confirm-
     *  overwrite dialog appear ([BackupUiState.pendingRestoreConfirm]). */
    fun beginImportBackup(uri: Uri) = runBusy {
        val text = openInput(uri) { input -> codec.readCapped(input) }
        codec.decode(text) // validates end-to-end; throws before anything is written
        pendingRestoreText = text
        _uiState.update { it.copy(isBusy = false, pendingRestoreConfirm = true) }
    }

    fun confirmRestore() {
        val text = pendingRestoreText ?: return
        pendingRestoreText = null
        _uiState.update { it.copy(pendingRestoreConfirm = false) }
        runBusy(isRestore = true) {
            // Awaited, not owned: cancelling this await (the screen going away)
            // leaves the import running to completion on the app scope. The
            // screen holds its exits shut while restoreInFlight — see
            // BackupScreen — so the normal case is that we are here to report.
            try {
                appScope.async { backupService.import(text) }.await()
                postMessage(UiText.BackupStatus(BackupStatusKind.BACKUP_RESTORED), isError = false)
            } catch (e: RestoreInterruption.CleanupPending) {
                // Everything the user owns landed; only the journal/marker
                // cleanup didn't, and that replays itself. A success with a
                // footnote, not a failure.
                postMessage(TransferErrorMessages.of(e), isError = false)
            }
        }
    }

    fun cancelRestore() {
        pendingRestoreText = null
        _uiState.update { it.copy(pendingRestoreConfirm = false) }
    }

    // --- CSV history -------------------------------------------------------

    fun exportCsv(uri: Uri) = runBusy {
        openOutput(uri) { out -> csvHistoryService.exportTo(out) }
        postMessage(UiText.BackupStatus(BackupStatusKind.HISTORY_EXPORTED), isError = false)
    }

    fun beginImportCsv(uri: Uri) = runBusy {
        val preview = openInput(uri) { input -> csvHistoryService.preview(input) }
        _uiState.update { it.copy(isBusy = false, csvImport = CsvImportUiState.of(preview)) }
    }

    fun setUnmatchedPattern(name: String, pattern: MovementPattern) {
        _uiState.update { state ->
            val csvImport = state.csvImport ?: return@update state
            state.copy(csvImport = csvImport.copy(approvedPatterns = csvImport.approvedPatterns + (name to pattern)))
        }
    }

    fun confirmCsvImport() {
        val csvImport = _uiState.value.csvImport ?: return
        if (!csvImport.canCommit) return
        _uiState.update { it.copy(csvImport = null) }
        runBusy {
            csvHistoryService.commit(csvImport.preview, csvImport.approvedPatterns)
            postMessage(UiText.BackupStatus(BackupStatusKind.HISTORY_IMPORTED, csvImport.sessionCount, csvImport.matchedSetCount), isError = false)
        }
    }

    fun cancelCsvImport() {
        _uiState.update { it.copy(csvImport = null) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // --- plumbing ----------------------------------------------------------

    private fun postMessage(text: UiText, isError: Boolean) {
        _uiState.update {
            it.copy(isBusy = false, restoreInFlight = false, message = StatusMessage(text, isError))
        }
    }

    /** Runs [block] on [Dispatchers.IO], marking [BackupUiState.isBusy] for its
     *  duration and turning every typed core error (plus a raw I/O failure —
     *  a revoked SAF grant, a provider that vanished) into a [StatusMessage]
     *  instead of a crash.
     *
     *  [isRestore] additionally raises [BackupUiState.restoreInFlight], which is
     *  what shuts the screen's exits (#172). Only the full restore sets it: it
     *  is the one operation here that writes two stores with no transaction
     *  across them. An export writes a file, and the CSV import commits in a
     *  single Room transaction that rolls back cleanly if it is cut — leaving
     *  those free to be backed out of.
     *
     *  The busy flag is checked and set *before* launching, synchronously on
     *  the caller's thread — every entry point here runs on the main thread,
     *  so this check-and-set is atomic with respect to a second call arriving
     *  while the first is still in flight. Setting it from inside the launched
     *  coroutine instead would let two overlapping SAF results race: the first
     *  result's `finally` could clear the flag while the second was still
     *  running. */
    private fun runBusy(isRestore: Boolean = false, block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, restoreInFlight = isRestore, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: BackupError) {
                postMessage(TransferErrorMessages.of(e), isError = true)
            } catch (e: CsvImportError) {
                postMessage(TransferErrorMessages.of(e), isError = true)
            } catch (e: RestoreInterruption) {
                // The picked file was fine; the restore was cut part-way. Saying
                // "couldn't access that file" would send the user after entirely
                // the wrong problem (#172).
                postMessage(TransferErrorMessages.of(e), isError = true)
            } catch (e: IOException) {
                postMessage(UiText.FileAccessFailure(e.message), isError = true)
            } catch (e: SecurityException) {
                // A revoked/expired SAF grant surfaces here, not as a crash.
                postMessage(UiText.FilePermissionLost, isError = true)
            } finally {
                _uiState.update { it.copy(isBusy = false, restoreInFlight = false) }
            }
        }
    }

    private inline fun <T> openOutput(uri: Uri, block: (java.io.OutputStream) -> T): T =
        context.contentResolver.openOutputStream(uri)?.use(block)
            ?: throw IOException("no output stream for $uri")

    private inline fun <T> openInput(uri: Uri, block: (java.io.InputStream) -> T): T =
        context.contentResolver.openInputStream(uri)?.use(block)
            ?: throw IOException("no input stream for $uri")
}
