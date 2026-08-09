package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * The Wearable Data Layer as [DataLayerWatchClient] needs it: four verbs, no
 * Play-services types.
 *
 * The seam is here because everything the client has to get *right* — retrying a
 * failed listener registration instead of crashing (#173), draining the queue when
 * the phone comes back, refusing a stale revision — is logic that has to be provable
 * in a plain JVM test, and a `DataClient`/`CapabilityClient` cannot be constructed
 * off-device. [DataLayerPhoneLink] is the only production implementation; the fakes
 * live in the tests.
 */
interface PhoneLink {

    /** The snapshot the Data Layer cached on this node, or null when none ever landed. */
    suspend fun cachedSnapshot(): WatchSnapshot?

    /**
     * Snapshots as the phone publishes them. Collecting *registers a listener*, so
     * this flow can fail at collection time and the caller is expected to retry it —
     * an unhandled registration failure used to take the watch app down (#173).
     */
    fun snapshotChanges(): Flow<WatchSnapshot>

    /**
     * Whether a node advertising [PHONE_CAPABILITY] is reachable: the state at
     * collection time, then every change.
     *
     * This is the watch's only reconnect signal. The phone republishes its snapshot
     * only on a content change, so coming back into range on its own produces no data
     * event, and offline edits used to sit queued until the lifter opened the app
     * (#173). Same registration-failure contract as [snapshotChanges].
     */
    fun phoneReachability(): Flow<Boolean>

    /**
     * Fires one message at the phone. Never throws: an unreachable phone is the normal
     * case on a wrist, and the delta stays queued for the next drain (§11.4).
     */
    suspend fun send(path: String, bytes: ByteArray)
}

/**
 * The capability the phone app advertises, and the watch's answer to "is the phone
 * there?".
 *
 * Declared statically on the phone side in `app/src/main/res/values/wear.xml` — Play
 * services reads that resource at install time, so the capability exists whenever the
 * phone app is installed, with no code running and no INTERNET anywhere. The literal
 * is duplicated in that XML because a resource cannot reference a Kotlin constant;
 * both sides carry a comment pointing at the other.
 *
 * A phone build older than that declaration advertises nothing, which is why every
 * send falls back to broadcasting at the connected nodes.
 */
const val PHONE_CAPABILITY = "strengthlog_phone"
