package cloud.trotter.log.strength.run

import android.Manifest
import cloud.trotter.log.strength.di.RunDispatcher
import cloud.trotter.log.strength.domain.cardio.DistanceTrack
import cloud.trotter.log.strength.domain.cardio.SetMeter
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.RoutePoint
import cloud.trotter.log.strength.ui.day.CardioClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val lastFix: StateFlow<Long?>
    val reached: SharedFlow<String>
    fun watch(runId: String, setStartElapsed: Long?, metres: Int?)
    fun keepRoute(runId: String, on: Boolean)
    fun route(runId: String): List<RoutePoint>
    fun access(): LocationAccess
    fun refreshAccess()
    fun warmUp(activity: CardioActivity)
    fun coolDown()
    fun begin(runId: String, activity: CardioActivity, startedAtWall: Long)
    fun end(runId: String)
    fun meter(runId: String): SetMeter?
    fun live(runId: String?, setStartElapsed: Long?, nowElapsed: Long): GpsLive?

    object None : GpsDistance {
        override val state: StateFlow<GpsState> = MutableStateFlow(GpsState.Off)
        override val lastFix: StateFlow<Long?> = MutableStateFlow(null)
        override val reached: SharedFlow<String> = MutableSharedFlow()
        override fun watch(runId: String, setStartElapsed: Long?, metres: Int?) = Unit
        override fun keepRoute(runId: String, on: Boolean) = Unit
        override fun route(runId: String): List<RoutePoint> = emptyList()
        override fun access() = LocationAccess.DENIED
        override fun refreshAccess() = Unit
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
 *  keeps the process alive while it runs. With Save route on, the run's anchor points are kept in memory
 *  until the session is logged; nothing is written here. */
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
    private val mutableLastFix = MutableStateFlow<Long?>(null)
    override val lastFix = mutableLastFix.asStateFlow()
    private val mutableReached = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val reached = mutableReached.asSharedFlow()
    private data class Watch(val runId: String, val start: Long, val metres: Int, var fired: Boolean = false)
    private var watch: Watch? = null
    private var routeRunId: String? = null
    private var keeping = false
    private val points = mutableListOf<RoutePoint>()
    private var track: DistanceTrack? = null
    private var runId: String? = null
    private var activity: CardioActivity? = null
    private var startedAtWall = 0L
    private var startElapsed = 0L
    private var collection: Job? = null
    private var foregroundStarted = false

    override fun access(): LocationAccess = source.access()

    override fun refreshAccess() {
        val currentActivity = activity ?: return
        when (access()) {
            LocationAccess.LOCATION_OFF -> mutableState.value = GpsState.LocationOff(runId)
            LocationAccess.PRECISE -> if (state.value is GpsState.LocationOff) {
                val currentRun = runId
                if (track != null) {
                    mutableState.value = GpsState.Tracking(currentRun)
                    updateNotice()
                } else if (currentRun != null) begin(currentRun, currentActivity, startedAtWall)
                else warmUp(currentActivity)
            }
            else -> Unit
        }
    }

    override fun warmUp(activity: CardioActivity) {
        if (runId != null || !activity.usesGps) return
        if (this.activity == activity && state.value == GpsState.Tracking(null)) return
        val access = access()
        if (access == LocationAccess.LOCATION_OFF) {
            coolDown()
            this.activity = activity
            mutableState.value = GpsState.LocationOff(null)
            return
        }
        if (access != LocationAccess.PRECISE) { coolDown(); return }
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
        mutableState.value = GpsState.Off
    }

    override fun begin(runId: String, activity: CardioActivity, startedAtWall: Long) {
        if (!activity.usesGps) return
        if (runId != routeRunId) {
            points.clear()
            routeRunId = null
            keeping = false
        }
        val sameTrack = this.runId == runId && track != null
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
                if (!sameTrack) stopUpdates()
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
                    val current = track ?: return@collect
                    val before = current.anchorMoves
                    if (current.add(fix)) {
                        if (current.anchorMoves > before && keeping && routeRunId == runId &&
                            fix.elapsedMillis >= startElapsed && points.size < 100_000) {
                            points += RoutePoint(((fix.elapsedMillis - startElapsed) / 1000).toInt(),
                                (fix.latitude * 1e5).roundToInt(), (fix.longitude * 1e5).roundToInt())
                        }
                        watch?.takeIf { it.runId == runId && !it.fired }?.let { target ->
                            if (requireNotNull(current.metresAt(fix.elapsedMillis)) -
                                requireNotNull(current.metresAt(target.start)) >= target.metres) {
                                target.fired = true
                                mutableReached.emit(target.runId)
                            }
                        }
                        mutableLastFix.value = fix.elapsedMillis
                    }
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

    override fun watch(runId: String, setStartElapsed: Long?, metres: Int?) {
        if (setStartElapsed == null || metres == null) {
            watch = null
        } else if (watch?.runId != runId || watch?.start != setStartElapsed || watch?.metres != metres) {
            watch = Watch(runId, setStartElapsed, metres)
        }
    }

    override fun keepRoute(runId: String, on: Boolean) {
        if (on) {
            if (routeRunId != runId) {
                points.clear()
                routeRunId = runId
            }
            keeping = true
        } else if (routeRunId == runId) {
            points.clear()
            routeRunId = null
            keeping = false
        }
    }

    override fun route(runId: String): List<RoutePoint> = if (routeRunId == runId) points.toList() else emptyList()

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
