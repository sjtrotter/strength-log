package cloud.trotter.log.strength.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * A [DataStore] that can be made to fail or park on a chosen write.
 *
 * A restore's writes are ordered and countable, which is what makes precise
 * failure injection possible: on the journal's store, update 1 is the staged
 * payload, 2 is the nonce and 3 is the post-success clear; on the settings
 * store, update 1 is the restore itself. Naming the update by number is how a
 * test says "die exactly here" without reaching inside the code under test.
 *
 * Both knobs are 1-based and one-shot per store instance.
 */
class FlakyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {

    override val data: Flow<Preferences> get() = delegate.data

    private val updates = AtomicInteger(0)

    /** Which update throws [IOException] instead of committing. */
    @Volatile var failOnUpdate: Int? = null

    /** Which update parks until [gate] completes, after signalling [gateReached]. */
    @Volatile var gateOnUpdate: Int? = null

    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** Completes when [gateOnUpdate] is entered, so a test can act mid-write. */
    val gateReached = CompletableDeferred<Unit>()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val n = updates.incrementAndGet()
        if (n == gateOnUpdate) {
            gateReached.complete(Unit)
            gate?.await()
        }
        if (n == failOnUpdate) throw IOException("store is down (update $n)")
        return delegate.updateData(transform)
    }
}
