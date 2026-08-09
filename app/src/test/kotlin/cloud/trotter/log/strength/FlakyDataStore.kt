package cloud.trotter.log.strength

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * A [DataStore] that can be made to fail or park on a chosen write, so a test
 * can hold a restore open at a known point and drive the UI while it is in
 * flight. Counts are 1-based: on the settings store, update 1 of a restore is
 * the restore's own write; on the journal's store, 1 is the staged payload, 2
 * the nonce and 3 the post-success clear.
 *
 * (`:data` and `:transfer` keep their own copies for their own tests — a
 * fixture, not a type worth exporting between modules.)
 */
class FlakyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {

    override val data: Flow<Preferences> get() = delegate.data

    private val updates = AtomicInteger(0)

    @Volatile var failOnUpdate: Int? = null

    @Volatile var gateOnUpdate: Int? = null

    @Volatile var gate: CompletableDeferred<Unit>? = null

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
