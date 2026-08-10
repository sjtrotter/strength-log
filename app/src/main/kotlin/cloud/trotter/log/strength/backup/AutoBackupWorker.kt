package cloud.trotter.log.strength.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.trotter.log.strength.data.prefs.AUTO_BACKUP_DAILY_HOURS
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.backup.BackupService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

const val AUTO_BACKUP_FILE_NAME = "strengthlog-auto-backup.json"

/**
 * A persisted tree grant only lets strength.log write through the selected
 * DocumentsProvider. Provider apps such as Drive, Nextcloud, Syncthing, and
 * OneDrive perform any remote syncing; strength.log only writes to that
 * provider and has no network permission of its own.
 */
fun interface AutoBackupDocumentStore {
    suspend fun overwrite(treeUri: String, fileName: String, write: suspend (OutputStream) -> Unit)
}

class SafAutoBackupDocumentStore(private val context: Context) : AutoBackupDocumentStore {
    override suspend fun overwrite(
        treeUri: String,
        fileName: String,
        write: suspend (OutputStream) -> Unit,
    ) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw IOException("Selected backup folder is unavailable")
        // A stable rolling name is intentional: automatic backup is a recovery
        // safety net, not an archive. Reusing one document prevents silent,
        // unbounded storage growth in the user's provider.
        val file = tree.findFile(fileName)
            ?: tree.createFile("application/json", fileName)
            ?: throw IOException("Could not create automatic backup")
        val out = context.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IOException("Could not open automatic backup")
        out.use { write(it) }
    }
}

sealed interface AutoBackupRunResult {
    data object Success : AutoBackupRunResult
    data object Retry : AutoBackupRunResult
    data object Disabled : AutoBackupRunResult
}

/** Android-free decision seam apart from its output abstraction; tests supply a
 * fake store to prove replacement, bytes, retry, and revoked-grant behavior. */
class AutoBackupRunner(
    private val settings: SettingsStore,
    private val exportBackup: suspend (OutputStream) -> Unit,
    private val documents: AutoBackupDocumentStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): AutoBackupRunResult {
        val preference = settings.autoBackupSettingsFlow.first()
        val treeUri = preference.treeUri
        if (!preference.enabled || treeUri == null) return AutoBackupRunResult.Disabled
        return try {
            documents.overwrite(treeUri, AUTO_BACKUP_FILE_NAME, exportBackup)
            settings.recordAutoBackupSuccess(nowMillis())
            AutoBackupRunResult.Success
        } catch (_: SecurityException) {
            // The provider grant was revoked. Stop scheduling quietly; the next
            // Backup-screen visit explains the disabled state without a crash or
            // notification.
            settings.markAutoBackupPermissionLost()
            AutoBackupRunResult.Disabled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            settings.recordAutoBackupFailure()
            AutoBackupRunResult.Retry
        }
    }
}

class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun settingsStore(): SettingsStore
        fun backupService(): BackupService
    }

    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        return when (AutoBackupRunner(
            dependencies.settingsStore(),
            dependencies.backupService()::exportTo,
            SafAutoBackupDocumentStore(applicationContext),
        ).run()) {
            AutoBackupRunResult.Success -> Result.success()
            AutoBackupRunResult.Disabled -> {
                AutoBackupScheduler.disable(applicationContext)
                Result.success()
            }
            AutoBackupRunResult.Retry -> Result.retry()
        }
    }
}

object AutoBackupScheduler {
    const val UNIQUE_WORK_NAME = "daily-saf-auto-backup"

    fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            AUTO_BACKUP_DAILY_HOURS.toLong(), TimeUnit.HOURS,
        )
            // No storage/network requirement: SAF providers decide how writes
            // are persisted or synced. Avoid low-battery execution.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
