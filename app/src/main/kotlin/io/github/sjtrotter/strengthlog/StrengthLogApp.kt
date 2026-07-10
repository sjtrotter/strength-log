package io.github.sjtrotter.strengthlog

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import io.github.sjtrotter.strengthlog.sync.WearSyncPublisher

/** Hilt's application root. The object graph is defined in [io.github.sjtrotter.strengthlog.di]. */
@HiltAndroidApp
class StrengthLogApp : Application() {

    /**
     * The wear-sync publisher observes state for the whole process lifetime (D6,
     * m5-wear.md #20) — an app-scope observer, not a foreground service. Started
     * here from the singleton graph; it stays a cheap idle observer on devices with
     * no watch paired (Data Layer writes just go unheard).
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WearSyncEntryPoint {
        fun wearSyncPublisher(): WearSyncPublisher
    }

    override fun onCreate() {
        super.onCreate()
        EntryPointAccessors.fromApplication(this, WearSyncEntryPoint::class.java)
            .wearSyncPublisher()
            .start()
    }
}
