package io.github.sjtrotter.strengthlog.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.sjtrotter.strengthlog.sync.TodaySnapshotSource
import io.github.sjtrotter.strengthlog.widget.TodayWidgetUpdater
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Home-screen widget wiring (glance-surfaces.md §1). One binding: the observer
 *  that pushes RemoteViews when the shared today-state changes. */
@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {

    @Provides
    @Singleton
    fun todayWidgetUpdater(
        @ApplicationContext context: Context,
        source: TodaySnapshotSource,
    ): TodayWidgetUpdater =
        TodayWidgetUpdater(
            context = context,
            source = source,
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
