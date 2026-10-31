package cloud.trotter.log.strength.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Marks the process-lifetime [CoroutineScope] provided by [AppScopeModule]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** The process-wide live device-local date used by cardio snapshots. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CivilDay

/**
 * The one scope that outlives every screen. Work belongs here when finishing it
 * matters more than the UI that started it: a backup restore writes Room and
 * DataStore in sequence, and cancelling it halfway can leave the two
 * disagreeing.
 *
 * A [SupervisorJob] so one failed job doesn't take the others down, and never
 * cancelled — it lives as long as the process.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
