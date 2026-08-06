package cloud.trotter.log.strength.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.transfer.health.DefaultHealthConnectClientProvider
import cloud.trotter.log.strength.transfer.health.HealthConnectClientProvider
import cloud.trotter.log.strength.transfer.health.HealthConnectPublisher
import cloud.trotter.log.strength.transfer.health.HealthConnectReader
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import javax.inject.Singleton

/**
 * The Health Connect object graph (#17, brief D5 — `:transfer` stays
 * framework-free, so the Hilt wiring lives here). The client provider is the
 * one availability seam; the publisher (write, D7 trigger) and reader (Log
 * screen) are plain constructors over it. Binding [SessionPublisher] to the
 * real [HealthConnectPublisher] is safe on any device: it self-checks
 * availability and permission and no-ops when Health Connect is absent (A3).
 */
@Module
@InstallIn(SingletonComponent::class)
object HealthModule {

    @Provides
    @Singleton
    fun healthConnectClientProvider(@ApplicationContext context: Context): HealthConnectClientProvider =
        DefaultHealthConnectClientProvider(context)

    @Provides
    @Singleton
    fun sessionPublisher(
        clientProvider: HealthConnectClientProvider,
        repository: TrackerRepository,
    ): SessionPublisher = HealthConnectPublisher(clientProvider, repository)

    @Provides
    @Singleton
    fun healthConnectReader(
        @ApplicationContext context: Context,
        clientProvider: HealthConnectClientProvider,
    ): HealthConnectReader = HealthConnectReader(clientProvider, ownPackageName = context.packageName)
}
