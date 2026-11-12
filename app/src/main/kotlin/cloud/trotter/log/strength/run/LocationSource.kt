package cloud.trotter.log.strength.run

import cloud.trotter.log.strength.domain.cardio.Fix
import kotlinx.coroutines.flow.Flow

interface LocationSource {
    fun access(): LocationAccess
    fun updates(): Flow<Fix>
}
