package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first

/**
 * The #16 CSV history export/import, wired to the data layer — the CSV
 * counterpart of [io.github.sjtrotter.strengthlog.transfer.backup.BackupService].
 * The public surface is Uri-free (streams and strings only, D9); `:app`
 * supplies the share-sheet/SAF plumbing later.
 *
 * Import is two calls by design: [preview] never touches the database — it
 * only builds the read-only preview/confirm model — and [commit] writes only
 * after the caller (the eventual UI) has let the user review and, if needed,
 * correct each unmatched exercise name's pattern (PLAN.md A2).
 */
class CsvHistoryService(private val repository: TrackerRepository) {

    suspend fun exportTo(out: OutputStream) {
        val history = repository.exportSessionHistory()
        val csv = HistoryCsvWriter.export(history.sessions, history.sessionSets, history.unit)
        out.write(csv.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Parses and validates [input] end-to-end and returns the preview/confirm
     *  model. Throws a typed [CsvImportError] on any problem; nothing is
     *  written to the device by this call. */
    suspend fun preview(input: InputStream): CsvImportPreview {
        val text = HistoryCsvReader.readCapped(input)
        val catalog = repository.catalogFlow.first()
        return HistoryCsvReader.preview(text, catalog)
    }

    /**
     * Commits a [preview] the caller has reviewed: creates a custom exercise
     * for each unmatched name using [approvedPatterns], then appends every
     * session in one `:data` staging transaction ([TrackerRepository.importSessionHistory]).
     * Throws [CsvImportError.MissingApproval] — writing nothing — if
     * [approvedPatterns] doesn't cover every unmatched name.
     */
    suspend fun commit(preview: CsvImportPreview, approvedPatterns: Map<String, MovementPattern> = emptyMap()) {
        val plan = CsvHistoryImporter.commit(preview, approvedPatterns)
        repository.importSessionHistory(plan.sessions, plan.newCustomExercises)
    }
}
