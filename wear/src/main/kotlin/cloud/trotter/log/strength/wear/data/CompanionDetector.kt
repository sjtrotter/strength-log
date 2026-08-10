package cloud.trotter.log.strength.wear.data

import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.NodeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** One-shot companion discovery, kept behind a JVM-friendly seam for the loading dial. */
fun interface CompanionDetector {
    suspend fun detect(): CompanionPresence
}

enum class CompanionPresence {
    INSTALLED,
    INSTALL_NEEDED,
    UNREACHABLE,
}

/**
 * Distinguishes a connected phone from a connected phone advertising our app.
 * Exceptions deliberately escape: inability to read Play services is transient, not
 * evidence that either the phone or companion app is absent.
 */
class DataLayerCompanionDetector(
    private val nodeClient: NodeClient,
    private val capabilityClient: CapabilityClient,
) : CompanionDetector {
    override suspend fun detect(): CompanionPresence = withContext(Dispatchers.IO) {
        val capableNodes = capabilityClient
            .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        if (capableNodes.isNotEmpty()) return@withContext CompanionPresence.INSTALLED

        if (nodeClient.connectedNodes.await().isNotEmpty()) {
            CompanionPresence.INSTALL_NEEDED
        } else {
            CompanionPresence.UNREACHABLE
        }
    }
}
