package cloud.trotter.log.strength.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

const val AUTO_BACKUP_FILE_NAME = "strengthlog-auto-backup.json"
const val AUTO_BACKUP_TEMP_FILE_NAME = "$AUTO_BACKUP_FILE_NAME.tmp"

/** A permanent SAF failure that requires the user to choose the folder again. */
open class AutoBackupFolderUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
class AutoBackupPermissionAbsentException :
    AutoBackupFolderUnavailableException("Backup folder permission is absent")
class AutoBackupTreeMissingException(cause: Throwable? = null) :
    AutoBackupFolderUnavailableException("Selected backup folder is unavailable", cause)
class AutoBackupProviderGoneException :
    AutoBackupFolderUnavailableException("Backup folder provider is unavailable")

/**
 * A persisted tree grant only lets strength.log write through the selected
 * DocumentsProvider. Provider apps such as Drive, Nextcloud, Syncthing, and
 * OneDrive perform any remote syncing; strength.log only writes to that
 * provider and has no network permission of its own.
 */
fun interface AutoBackupDocumentStore {
    suspend fun replace(treeUri: String, fileName: String, write: suspend (OutputStream) -> Unit)
}

class SafAutoBackupDocumentStore(private val context: Context) : AutoBackupDocumentStore {
    override suspend fun replace(
        treeUri: String,
        fileName: String,
        write: suspend (OutputStream) -> Unit,
    ) {
        val uri = Uri.parse(treeUri)
        requireUsableTree(uri)
        val tree = try {
            DocumentFile.fromTreeUri(context, uri)
                ?: throw AutoBackupTreeMissingException()
        } catch (missing: FileNotFoundException) {
            throw AutoBackupTreeMissingException(missing)
        }
        try {
            if (!tree.exists()) throw AutoBackupTreeMissingException()
        } catch (missing: FileNotFoundException) {
            throw AutoBackupTreeMissingException(missing)
        }

        val tempName = "$fileName.tmp"
        tree.findFile(tempName)?.let { orphan ->
            val bytes = read(orphan)
            if (bytes.isCompleteJsonDocument()) promote(tree, orphan, fileName, bytes)
            else if (!orphan.delete()) throw IOException("Could not discard incomplete automatic backup")
        }

        val temp = tree.createFile("application/json", tempName)
            ?: throw IOException("Could not create temporary automatic backup")
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        val out = context.contentResolver.openOutputStream(temp.uri, "wt")
            ?: throw IOException("Could not open temporary automatic backup")
        DigestOutputStream(out, expectedDigest).use { write(it) }

        // Closing is part of the provider contract. Read the document back after
        // close so promotion never knowingly replaces a good snapshot with a
        // truncated or otherwise different provider write.
        val bytes = read(temp)
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (!expectedDigest.digest().contentEquals(actualDigest) || !bytes.isCompleteJsonDocument()) {
            throw IOException("Temporary automatic backup did not verify")
        }
        promote(tree, temp, fileName, bytes)
    }

    private fun requireUsableTree(uri: Uri) {
        val hasGrant = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!hasGrant) throw AutoBackupPermissionAbsentException()
        val authority = uri.authority
        val client = authority?.let(context.contentResolver::acquireUnstableContentProviderClient)
        if (client == null) {
            throw AutoBackupProviderGoneException()
        }
        client.close()
    }

    private fun read(file: DocumentFile): ByteArray =
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw IOException("Could not verify temporary automatic backup")

    /**
     * SAF has no portable atomic replace operation. We first delete the old
     * document, then ask the provider to rename the verified sibling temp with
     * [DocumentsContract.renameDocument]. If rename is unsupported, we recreate
     * the final name from the already verified bytes and delete the temp. Both
     * paths therefore have a short, inherently non-atomic interval after the old
     * snapshot is deleted; retaining the verified temp makes interrupted runs
     * recoverable.
     */
    private fun promote(tree: DocumentFile, temp: DocumentFile, fileName: String, bytes: ByteArray) {
        val old = tree.findFile(fileName)
        if (old != null && !old.delete()) throw IOException("Could not replace automatic backup")
        val renamed = try {
            DocumentsContract.renameDocument(context.contentResolver, temp.uri, fileName)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (renamed != null) return

        val final = tree.createFile("application/json", fileName)
            ?: throw IOException("Could not recreate automatic backup")
        context.contentResolver.openOutputStream(final.uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Could not rewrite automatic backup")
        if (!read(final).contentEquals(bytes)) {
            throw IOException("Recreated automatic backup did not verify")
        }
        if (!temp.delete()) throw IOException("Could not remove promoted temporary backup")
    }
}

/** Strict enough to reject every truncated JSON prefix without depending on Android's JSON parser. */
internal fun ByteArray.isCompleteJsonDocument(): Boolean {
    val text = toString(Charsets.UTF_8)
    var depth = 0
    var inString = false
    var escaped = false
    var sawRoot = false
    var finished = false
    val nesting = ArrayDeque<Char>()
    for (char in text) {
        if (finished) {
            if (!char.isWhitespace()) return false
            continue
        }
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{', '[' -> {
                if (depth == 0 && sawRoot) return false
                sawRoot = true
                depth++
                nesting.addLast(char)
            }
            '}', ']' -> {
                val expected = if (char == '}') '{' else '['
                if (depth == 0 || nesting.removeLast() != expected) return false
                depth--
                if (depth == 0) finished = true
            }
            else -> if (!sawRoot && !char.isWhitespace()) return false
        }
    }
    return sawRoot && finished && !inString && depth == 0
}

sealed interface AutoBackupRunResult {
    data object Success : AutoBackupRunResult
    data object Retry : AutoBackupRunResult
    data object Disabled : AutoBackupRunResult
}

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
            documents.replace(treeUri, AUTO_BACKUP_FILE_NAME, exportBackup)
            settings.recordAutoBackupSuccess(nowMillis())
            AutoBackupRunResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            settings.markAutoBackupPermissionLost()
            AutoBackupRunResult.Disabled
        } catch (_: AutoBackupFolderUnavailableException) {
            settings.markAutoBackupPermissionLost()
            AutoBackupRunResult.Disabled
        } catch (_: Exception) {
            settings.recordAutoBackupFailure()
            AutoBackupRunResult.Retry
        }
    }
}

class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun settingsStore(): SettingsStore
        fun backupService(): BackupService
    }

    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        return when (AutoBackupRunner(
            dependencies.settingsStore(), dependencies.backupService()::exportTo,
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
        ).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
