package cloud.trotter.log.strength.run

import android.Manifest
import cloud.trotter.log.strength.di.RunDispatcher
import cloud.trotter.log.strength.domain.cardio.DistanceTrack
import cloud.trotter.log.strength.domain.cardio.SetMeter
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.ui.day.CardioClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LocationAccess { PRECISE, APPROXIMATE, DENIED, LOCATION_OFF }
enum class LocationAnswer {
    PRECISE, APPROXIMATE, DENIED, BLOCKED;
    companion object {
        fun from(grants: Map<String, Boolean>, rationale: Boolean): LocationAnswer = when {
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true -> PRECISE
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> APPROXIMATE
            rationale -> DENIED
            else -> BLOCKED
        }
    }
}

sealed interface GpsState {
    data object Off : GpsState
    data class NeedsPermission(val runId: String) : GpsState
    data class LocationOff(val runId: String?) : GpsState
    data class Tracking(val runId: String?) : GpsState
}
enum class GpsLiveStatus { FINDING, LIVE, NO_SIGNAL, PARTIAL }
data class GpsLive(val status: GpsLiveStatus, val metres: Int?, val metresPerSecond: Double?)
enum class GpsNotice { OFF, NEEDS_PRECISE }
data class RunNotice(val activity: CardioActivity, val startedAtWall: Long, val live: GpsLive)

interface GpsDistance {
    val state: StateFlow<GpsState>
    fun access(): LocationAccess
    fun warmUp(activity: CardioActivity)
    fun coolDown()
    fun begin(runId: String, activity: CardioActivity, startedAtWall: Long)
    fun end(runId: String)
    fun meter(runId: String): SetMeter?
    fun live(runId: String?, setStartElapsed: Long?, nowElapsed: Long): GpsLive?

    object None : GpsDistance {
        override val state: StateFlow<GpsState> = MutableStateFlow(GpsState.Off)
        override fun access() = LocationAccess.DENIED
        override fun warmUp(activity: CardioActivity) = Unit
        override fun coolDown() = Unit
        override fun begin(runId: String, activity: CardioActivity, startedAtWall: Long) = Unit
        override fun end(runId: String) = Unit
        override fun meter(runId: String): SetMeter? = null
        override fun live(runId: String?, setStartElapsed: Long?, nowElapsed: Long): GpsLive? = null
    }
}

internal val CardioActivity.usesGps: Boolean get() = when (this) {
    CardioActivity.ROAD_RUN, CardioActivity.TRAIL_RUN, CardioActivity.TRACK_RUN,
    CardioActivity.OUTDOOR_WALK, CardioActivity.OUTDOOR_RIDE -> true
    else -> false
}

interface TrackingForeground {
    fun start(): Boolean
    fun stop()
}

/** Holds the run's track in memory only; nothing here is written to disk. The foreground service
 *  keeps the process alive while it runs. */
@Singleton
class RunTracker @Inject constructor(
    private val source: LocationSource,
    private val foreground: TrackingForeground,
    private val clock: CardioClock,
    @RunDispatcher dispatcher: CoroutineDispatcher,
) : GpsDistance {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<GpsState>(GpsState.Off)
    override val state = mutableState.asStateFlow()
    private val mutableNotice = MutableStateFlow<RunNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private var track: DistanceTrack? = null
    private var runId: String? = null
    private var activity: CardioActivity? = null
    private var startedAtWall = 0L
    private var startElapsed = 0L
    private var collection: Job? = null
    private var foregroundStarted = false

    override fun access(): LocationAccess {
        val access = source.access()
        if (access == LocationAccess.LOCATION_OFF) mutableState.value = GpsState.LocationOff(runId)
        else if (access == LocationAccess.PRECISE && state.value == GpsState.LocationOff(null)) mutableState.value = GpsState.Off
        return access
    }

    override fun warmUp(activity: CardioActivity) {
        if (runId != null || !activity.usesGps) return
        if (access() == LocationAccess.LOCATION_OFF) {
            coolDown()
            mutableState.value = GpsState.LocationOff(null)
            return
        }
        if (access() != LocationAccess.PRECISE) { coolDown(); return }
        if (this.activity != activity) endAll()
        this.activity = activity
        if (track == null) track = DistanceTrack.forActivity(activity)
        collectFixes()
        if (track == null) return
        mutableState.value = GpsState.Tracking(null)
    }

    override fun coolDown() {
        if (runId != null) return
        stopUpdates()
        activity = null
        mutableState.value = if (source.access() == LocationAccess.LOCATION_OFF) GpsState.LocationOff(null) else GpsState.Off
    }

    override fun begin(runId: String, activity: CardioActivity, startedAtWall: Long) {
        if (!activity.usesGps) return
        if (this.runId != null && this.runId != runId || this.activity != null && this.activity != activity) endAll()
        this.runId = runId
        this.activity = activity
        this.startedAtWall = startedAtWall
        when (access()) {
            LocationAccess.PRECISE -> {
                if (track == null) track = DistanceTrack.forActivity(activity)
                startElapsed = clock.elapsedRealtimeMillis() - (clock.wallMillis() - startedAtWall).coerceAtLeast(0)
                collectFixes()
                if (track == null) return
                if (!foregroundStarted) {
                    if (!foreground.start()) { endAll(); return }
                    foregroundStarted = true
                }
                mutableState.value = GpsState.Tracking(runId)
                updateNotice()
            }
            LocationAccess.LOCATION_OFF -> {
                stopUpdates()
                mutableState.value = GpsState.LocationOff(runId)
            }
            else -> {
                stopUpdates()
                mutableState.value = GpsState.NeedsPermission(runId)
            }
        }
    }

    private fun collectFixes() {
        if (collection?.isActive == true) return
        collection = scope.launch {
            try {
                source.updates().collect { fix ->
                    track?.add(fix)
                    updateNotice()
                }
                foregroundRefused()
            } catch (_: SecurityException) {
                foregroundRefused()
            }
        }
    }

    private fun stopUpdates() {
        collection?.cancel()
        collection = null
        if (foregroundStarted) foreground.stop()
        foregroundStarted = false
        track = null
        mutableNotice.value = null
    }

    override fun end(runId: String) {
        if (this.runId == runId) endAll()
    }

    fun endAll() {
        stopUpdates()
        runId = null
        activity = null
        mutableState.value = GpsState.Off
    }

    fun foregroundRefused() = endAll()

    override fun meter(runId: String): SetMeter? = track.takeIf {
        this.runId == runId && state.value == GpsState.Tracking(runId)
    }

    override fun live(runId: String?, setStartElapsed: Long?, nowElapsed: Long): GpsLive? {
        if (state.value != GpsState.Tracking(runId)) return null
        val current = track ?: return null
        val last = current.lastFixMillis
        if (last == null) return GpsLive(GpsLiveStatus.FINDING, null, null)
        val recent = nowElapsed - last <= DistanceTrack.FRESH_MILLIS
        if (setStartElapsed == null) return GpsLive(if (recent) GpsLiveStatus.LIVE else GpsLiveStatus.FINDING, null, null)
        val metres = floor((requireNotNull(current.metresAt(nowElapsed)) - requireNotNull(current.metresAt(setStartElapsed))).coerceAtLeast(0.0) + 1e-7).toInt()
        val status = when {
            !recent -> GpsLiveStatus.NO_SIGNAL
            !current.covered(setStartElapsed, nowElapsed) -> GpsLiveStatus.PARTIAL
            else -> GpsLiveStatus.LIVE
        }
        return GpsLive(status, metres, if (recent) current.metresPerSecond(nowElapsed) else null)
    }

    fun updateNotice() {
        val currentActivity = activity ?: return
        if (runId == null) return
        val current = live(runId, startElapsed, clock.elapsedRealtimeMillis()) ?: return
        mutableNotice.value = RunNotice(currentActivity, startedAtWall, current)
    }
}
