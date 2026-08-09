package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.WearSyncPaths
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * A [PhoneLink] with no Play services behind it — the seam that makes
 * [DataLayerWatchClient]'s reconnect, retry and freshness rules provable on a plain
 * JVM. A test drives the phone by emitting on [snapshotEvents] / [reachability] and
 * reads back what the watch put on the wire.
 *
 * Both flows register on collection (recording the clock, refusing while asked to),
 * which is what a real listener registration does and what the client's retry has to
 * survive. Neither replays, so a test must wait for the client to subscribe
 * (`subscriptionCount`) before emitting.
 */
internal class FakePhoneLink(private val clockMillis: () -> Long = { 0L }) : PhoneLink {

    var cached: WatchSnapshot? = null

    val snapshotEvents = MutableSharedFlow<WatchSnapshot>(extraBufferCapacity = 64)
    val reachability = MutableSharedFlow<Boolean>(extraBufferCapacity = 64)

    /** How many registrations of each flow to refuse before letting one through. */
    var snapshotRegistrationsToRefuse = 0
    var reachabilityRegistrationsToRefuse = 0

    /** The clock at each registration attempt — the retry cadence, observed. */
    val snapshotRegistrations: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val reachabilityRegistrations: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** How long a send takes; non-zero makes an overlapping drain observable. */
    var sendDurationMillis = 0L

    private val wire: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    private val inFlight = AtomicInteger()
    private val peakInFlight = AtomicInteger()

    val sentDeltas: List<SetEditDelta>
        get() = bytesOn(WearSyncPaths.SET_EDIT).map(SyncCodec::decodeDelta)

    val sentSwaps: List<ExerciseSwapDelta>
        get() = bytesOn(WearSyncPaths.EXERCISE_SWAP).map(SyncCodec::decodeSwap)

    /** The most sends ever in flight at once — 1 unless two drains overlapped. */
    val peakConcurrentSends: Int get() = peakInFlight.get()

    override suspend fun cachedSnapshot(): WatchSnapshot? = cached

    override fun snapshotChanges(): Flow<WatchSnapshot> = flow {
        snapshotRegistrations += clockMillis()
        if (snapshotRegistrations.size <= snapshotRegistrationsToRefuse) {
            throw IOException("snapshot registration refused")
        }
        emitAll(snapshotEvents)
    }

    override fun phoneReachability(): Flow<Boolean> = flow {
        reachabilityRegistrations += clockMillis()
        if (reachabilityRegistrations.size <= reachabilityRegistrationsToRefuse) {
            throw IOException("capability registration refused")
        }
        emitAll(reachability)
    }

    override suspend fun send(path: String, bytes: ByteArray) {
        peakInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            if (sendDurationMillis > 0) delay(sendDurationMillis)
            wire += path to bytes
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun bytesOn(path: String): List<ByteArray> =
        synchronized(wire) { wire.filter { it.first == path }.map { it.second } }
}

/** A day the tests can log against: one lift, two rounds, neither done. */
internal fun phoneSnapshot(revision: Long, epoch: Long = 0L) = WatchSnapshot(
    revision = revision,
    epoch = epoch,
    suggestedDayId = "A",
    day = WatchDay(
        dayId = "A",
        title = "Day A",
        accentIndex = 0,
        exercises = listOf(
            WatchExercise(
                programExerciseId = 1L,
                slot = "main",
                name = "Barbell Back Squat",
                goal = 235.0,
                perHand = false,
                supersetPartnerName = null,
                sets = listOf(
                    WatchSet(235.0, 5, "TOP", done = false),
                    WatchSet(175.0, 8, "BACKOFF", done = false),
                ),
                ssSets = emptyList(),
                alternates = listOf(WatchAlternate("front_squat", "Front Squat")),
                exerciseId = "bb_back_squat",
            ),
        ),
    ),
    unit = "lb",
)

internal fun tickDelta(setIndex: Int = 0, done: Boolean? = true, stamp: Long = 1L) = SetEditDelta(
    dayId = "A",
    programExerciseId = 1L,
    slot = "main",
    setIndex = setIndex,
    done = done,
    editedAtMillis = stamp,
)

internal fun swapRequest(stamp: Long = 1L) = ExerciseSwapDelta(
    dayId = "A",
    programExerciseId = 1L,
    exerciseId = "front_squat",
    exerciseName = "Front Squat",
    editedAtMillis = stamp,
)
