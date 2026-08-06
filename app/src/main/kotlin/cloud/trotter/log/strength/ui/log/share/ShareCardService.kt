package cloud.trotter.log.strength.ui.log.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import cloud.trotter.log.strength.data.TrackerRepository
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Builds the session share card end to end (docs/briefs/session-share.md §3):
 * reads the session off [TrackerRepository], renders it with
 * [ShareCardPainter], writes the PNG under `cache/shares/`, and wraps it in an
 * `ACTION_SEND` chooser intent. This is the *only* place a share [Intent] gets
 * built (§4 acceptance #1) — [cloud.trotter.log.strength.ui.log
 * .LogViewModel] calls [buildShareIntent] on a SHARE tap and nothing else
 * triggers it. Everything here runs off the caller's thread ([Dispatchers.IO]):
 * a DB read, a Canvas render and a file write, none of which belong on main.
 */
class ShareCardService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: TrackerRepository,
) {

    /** Null if [sessionId] no longer resolves to a session — nothing to share. */
    suspend fun buildShareIntent(sessionId: Long): Intent? = withContext(Dispatchers.IO) {
        val session = repo.session(sessionId) ?: return@withContext null
        val sets = repo.sessionSets(sessionId)
        val unit = repo.unitFlow.first()

        val content = ShareCardContentBuilder.build(session, sets, unit)
        val bitmap = ShareCardPainter.render(context, content)
        val file = writePng(sessionId, bitmap)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Session share card", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(sendIntent, "Share workout")
    }

    /** One render lives at a time: every file already in `cache/shares/` is
     *  cleared before the new one is written (§3), so a share sheet backed out
     *  of never leaves a previous session's card behind for the next tap to
     *  reuse by accident. */
    private fun writePng(sessionId: Long, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "share-$sessionId.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out) }
        return file
    }

    private companion object {
        const val SHARE_DIR = "shares"
        const val PNG_QUALITY = 100
    }
}
