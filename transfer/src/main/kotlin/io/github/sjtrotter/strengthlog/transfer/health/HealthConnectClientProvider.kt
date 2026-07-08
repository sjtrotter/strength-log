package io.github.sjtrotter.strengthlog.transfer.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/**
 * The availability seam. Production checks the SDK status and hands back a live
 * [HealthConnectClient]; when Health Connect isn't installed or needs an update
 * it returns `null`, which every caller treats as "feature absent" (A3). Tests
 * supply a fake client — or `null` — without a real provider, which is why the
 * static `HealthConnectClient.getSdkStatus` call is behind this interface rather
 * than inside the publisher/reader.
 */
fun interface HealthConnectClientProvider {
    fun get(): HealthConnectClient?
}

/** The real provider: [HealthConnectClient] only when the SDK reports available. */
class DefaultHealthConnectClientProvider(private val context: Context) : HealthConnectClientProvider {
    override fun get(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
}
