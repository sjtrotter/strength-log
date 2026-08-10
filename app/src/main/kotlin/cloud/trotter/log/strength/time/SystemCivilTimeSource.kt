package cloud.trotter.log.strength.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device's civil day, off the real system clock and the *current* system
 * zone (#176).
 *
 * Three things can end a civil day, and this listens for all three:
 *  - it simply ends — a delay to the next local midnight, recomputed from every
 *    reading, so a DST-short day or a zone hop reschedules itself;
 *  - the user or the network moves the clock or the zone — `ACTION_DATE_CHANGED`,
 *    `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED`;
 *  - [refresh], which the screen calls on resume.
 *
 * The flow is cold: the receiver exists only while something is collecting, so
 * a backgrounded app runs no timer and holds no receiver. That is deliberate,
 * and it is why [refresh] exists — a day that turned while nobody was watching
 * is healed by the resume reading rather than by work done in the background.
 * A coroutine `delay` is also not a wakeup: it can run late across doze, and
 * the resume reading covers that too.
 */
@Singleton
class SystemCivilTimeSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : CivilTimeSource {

    /** Bumped by every wake-up signal. A counter, not a `Unit` signal: the
     *  collector samples it *before* reading the clock, so a broadcast that
     *  lands while the reading is being emitted still wakes the next wait
     *  instead of falling into the gap between them. */
    private val wakeups = MutableStateFlow(0)

    override val civilTime: Flow<CivilTime> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivingContext: Context?, intent: Intent?) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Protected system broadcasts only: nothing outside the platform can
        // reach this receiver, and it needs no permission of its own.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        launch {
            while (true) {
                val seen = wakeups.value
                val reading = CivilTime.now()
                send(reading)
                withTimeoutOrNull(reading.millisUntilNextDay()) { wakeups.first { it != seen } }
            }
        }

        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged { old, new -> old.date == new.date && old.zone == new.zone }

    override fun refresh() {
        wakeups.update { it + 1 }
    }
}
