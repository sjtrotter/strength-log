package io.github.sjtrotter.strengthlog.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.transfer.backup.BackupCodec
import io.github.sjtrotter.strengthlog.transfer.backup.BackupError
import io.github.sjtrotter.strengthlog.transfer.backup.BackupService
import io.github.sjtrotter.strengthlog.transfer.csv.CsvHistoryService
import io.github.sjtrotter.strengthlog.transfer.csv.CsvImportError
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * explicitly confirms, at which point it's one atomic transaction either way.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupService: BackupService,
    private val csvHistoryService: CsvHistoryService,
) : ViewModel() {

    private val codec = BackupCodec()

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private var pendingRestoreText: String? = null

    // --- JSON backup -----------------------------------------------------

    fun exportBackup(uri: Uri) = runBusy {
        openOutput(uri) { out -> backupService.exportTo(out) }
        postMessage("Backup exported.", isError = false)
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
        runBusy {
            backupService.import(text)
            postMessage("Backup restored.", isError = false)
        }
    }

    fun cancelRestore() {
        pendingRestoreText = null
        _uiState.update { it.copy(pendingRestoreConfirm = false) }
    }

    // --- CSV history -------------------------------------------------------

    fun exportCsv(uri: Uri) = runBusy {
        openOutput(uri) { out -> csvHistoryService.exportTo(out) }
        postMessage("History exported.", isError = false)
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
            postMessage("Imported ${csvImport.sessionCount} session(s), ${csvImport.matchedSetCount} set(s).", isError = false)
        }
    }

    fun cancelCsvImport() {
        _uiState.update { it.copy(csvImport = null) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // --- plumbing ----------------------------------------------------------

    private fun postMessage(text: String, isError: Boolean) {
        _uiState.update { it.copy(isBusy = false, message = StatusMessage(text, isError)) }
    }

    /** Runs [block] on [Dispatchers.IO], marking [BackupUiState.isBusy] for its
     *  duration and turning every typed core error (plus a raw I/O failure —
     *  a revoked SAF grant, a provider that vanished) into a [StatusMessage]
     *  instead of a crash. */
    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true, message = null) }
            try {
                block()
            } catch (e: BackupError) {
                postMessage(TransferErrorMessages.of(e), isError = true)
            } catch (e: CsvImportError) {
                postMessage(TransferErrorMessages.of(e), isError = true)
            } catch (e: IOException) {
                postMessage("Couldn't access that file: ${e.message}", isError = true)
            } catch (e: SecurityException) {
                // A revoked/expired SAF grant surfaces here, not as a crash.
                postMessage("No permission to access that file anymore.", isError = true)
            } finally {
                _uiState.update { it.copy(isBusy = false) }
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
