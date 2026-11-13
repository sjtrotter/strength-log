package cloud.trotter.log.strength.run

import cloud.trotter.log.strength.domain.cardio.Fix
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

fun interface PlayServicesCheck {
    fun available(): Boolean
}

/** The fused provider needs Google Play services, which some phones lack; those phones use GPS alone. */
@Singleton
class PreferredLocationSource @Inject constructor(
    private val platform: PlatformLocationSource,
    private val fused: FusedLocationSource,
    private val playServices: PlayServicesCheck,
) : LocationSource {
    override fun access(): LocationAccess = platform.access()

    override fun updates(): Flow<Fix> = flow {
        emitAll(if (playServices.available()) fused.updates() else platform.updates())
    }
}
