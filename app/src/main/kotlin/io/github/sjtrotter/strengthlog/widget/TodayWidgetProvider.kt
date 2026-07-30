package io.github.sjtrotter.strengthlog.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The home-screen widget's receiver (glance-surfaces.md §1). It holds no state
 * and derives nothing: every broadcast it cares about means the same thing —
 * "repaint from [TodaySnapshotSource][io.github.sjtrotter.strengthlog.sync.TodaySnapshotSource]".
 * Steady-state updates arrive from [TodayWidgetUpdater]'s observer instead, so
 * the provider declares `updatePeriodMillis="0"` and is never woken on a timer.
 */
class TodayWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface UpdaterEntryPoint {
        fun todayWidgetUpdater(): TodayWidgetUpdater
    }

    /** Placement, boot and host restore all arrive here (the ENABLED broadcast is
     *  always followed by this one, so it needs no handler of its own). The process
     *  may have started for this broadcast alone, so render rather than wait for a
     *  state change that might be days away. */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) = updater(context).renderOnce(goAsync())

    private fun updater(context: Context): TodayWidgetUpdater =
        EntryPointAccessors
            .fromApplication(context.applicationContext, UpdaterEntryPoint::class.java)
            .todayWidgetUpdater()
}
