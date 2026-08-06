package cloud.trotter.log.strength.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import cloud.trotter.log.strength.sync.TodaySnapshotSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull

/** How long a broadcast-triggered render waits for the first value before giving
 *  up; the observer's next emission repaints anyway, so a slow cold read costs a
 *  frame of the initial layout, never a stuck receiver. */
private const val FIRST_READ_TIMEOUT_MS = 2_000L

/**
 * Keeps the home-screen widget honest, from the same [TodaySnapshotSource] the
 * watch publisher mirrors (glance-surfaces.md §4.2). An app-scope observer —
 * [start] is called once from [cloud.trotter.log.strength.StrengthLogApp],
 * next to [cloud.trotter.log.strength.sync.WearSyncPublisher.start] — so a
 * set ticked on the phone repaints the widget without polling or WorkManager.
 */
class TodayWidgetUpdater(
    private val context: Context,
    private val source: TodaySnapshotSource,
    parentScope: CoroutineScope,
) {

    private val scope = parentScope + SupervisorJob()
    private var started = false

    fun start() {
        if (started) return
        started = true
        source.snapshots
            .map(::todayWidgetContent)
            .distinctUntilChanged()
            .onEach(::render)
            .launchIn(scope)
    }

    /**
     * One render for [TodayWidgetProvider]'s broadcasts (placement, boot, host
     * restore). Runs off the receiver's main thread via [pending], which is
     * finished in every outcome so the broadcast window always closes.
     */
    fun renderOnce(pending: BroadcastReceiver.PendingResult?) {
        scope.launch {
            try {
                withTimeoutOrNull(FIRST_READ_TIMEOUT_MS) {
                    render(todayWidgetContent(source.snapshots.first()))
                }
            } finally {
                pending?.finish()
            }
        }
    }

    private fun render(content: TodayWidgetContent) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, content.toRemoteViews(context))
    }
}
