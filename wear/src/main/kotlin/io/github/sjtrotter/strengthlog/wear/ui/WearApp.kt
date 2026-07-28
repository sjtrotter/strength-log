package io.github.sjtrotter.strengthlog.wear.ui

import android.Manifest
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.wear.OngoingWorkoutChip
import io.github.sjtrotter.strengthlog.wear.data.WatchTrackerClient
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.WearTrackerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** No rest, and no rest just ended — the sentinel for both saveable ints. */
private const val NO_REST = -1

/** How often a live screen re-reads the clock: fine enough for a draining arc,
 *  coarse enough to be free. The lifting elapsed timer only needs seconds. */
private const val LIVE_TICK_MILLIS = 200L
private const val LIFTING_TICK_MILLIS = 1_000L

/** Room for the goal buzz to be heard before the auto-tick follows it. */
private const val GOAL_BUZZ_SETTLE_MILLIS = 250L

/** The screens with a clock on them — the only ones that need a repaint ticker. */
private val LIVE_SCREENS = setOf(DialScreen.LIFTING, DialScreen.TIMED_HOLD, DialScreen.REST)

/**
 * Root composable: keeps the screen on while logging (A8), swaps in
 * [AmbientScreen] while the system reports ambient mode, and otherwise shows
 * the dial — the one screen the whole workout happens on (brief §1).
 *
 * There is no navigation. The dial's state changes in place; a swipe dismisses
 * the activity, which is the platform's own behaviour and the only "back" the
 * watch has.
 */
@Composable
fun WearApp(
    client: WatchTrackerClient,
    isAmbient: Boolean,
    ambientTick: Int = 0,
    onDismiss: () -> Unit = {},
) {
    val snapshot by client.snapshotFlow().collectAsState(initial = null)
    val pendingCount by client.pendingCountFlow().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The rest-timer buzz + wake lock live here, at the root, so they outlive the
    // ambient screen swap (the interactive tree is disposed in ambient, but this
    // controller's coroutine must keep running to fire the single haptic on time).
    val restScope = rememberCoroutineScope()
    val restController = remember(context) { RestTimerController(context.applicationContext, restScope) }
    KeepScreenOn(enabled = !isAmbient)
    // Runs on every screen (loading/ambient/interactive) so the chip reconciles
    // even when the app relaunches straight into ambient — see OngoingSessionChip.
    OngoingSessionChip(snapshot)

    WearTrackerTheme {
        Box(Modifier.fillMaxSize().background(Background)) {
            val snap = snapshot
            when {
                snap == null -> LoadingScreen()
                isAmbient -> AmbientScreen(snap, ambientTick, restLabel = restController.activeRest?.nextLabel)
                snap.day.exercises.isEmpty() -> EmptyScreen()
                else -> WorkoutDial(
                    snap = snap,
                    pendingCount = pendingCount,
                    restController = restController,
                    sendEdit = { scope.launch { client.sendEdit(it) } },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/**
 * The workout, as local state over the snapshot. Which exercise and which round
 * the lifter is on are *derived* (the client echoes a tick optimistically, so the
 * ring moves the moment it is tapped); only what the phone cannot know is held
 * here — has this set been started, is a rest running, when did the session begin.
 *
 * All of it is [rememberSaveable] and deadline-anchored, so a process death
 * mid-set or mid-rest restores to exactly the same dial (write-on-mutation).
 */
@Composable
private fun WorkoutDial(
    snap: WatchSnapshot,
    pendingCount: Int,
    restController: RestTimerController,
    sendEdit: (SetEditDelta) -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    // Keyed on the day: a rollover to tomorrow's workout starts a fresh session
    // rather than inheriting yesterday's stamps and a rest nobody is taking.
    val dayId = snap.day.dayId
    var begun by rememberSaveable(dayId) { mutableStateOf(false) }
    var lifting by rememberSaveable(dayId) { mutableStateOf(false) }
    var startedAtWallMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var startedAtElapsedMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var restDeadlineMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var restTotalSeconds by rememberSaveable(dayId) { mutableIntStateOf(0) }
    var restBetweenExercises by rememberSaveable(dayId) { mutableStateOf(false) }
    var restNextLabel by rememberSaveable(dayId) { mutableStateOf("") }
    var restedSeconds by rememberSaveable(dayId) { mutableIntStateOf(NO_REST) }
    var sessionStartedAtMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var sessionEndedAtMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var nowElapsedMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val exerciseIndex = currentExerciseIndex(snap)
    val exercise = snap.day.exercises.getOrNull(exerciseIndex)
    val roundIndex = exercise?.let(::currentRoundIndex) ?: 0
    val stream = exercise?.toStreamUiState(watchUnit(snap.unit), snap.day.dayId, snap.day.accentIndex)
    val holdGoal = exercise?.let { holdGoalSeconds(it, roundIndex) } ?: 0

    fun startRest(seconds: Int, betweenExercises: Boolean, nextLabel: String) {
        restTotalSeconds = seconds
        restBetweenExercises = betweenExercises
        restNextLabel = nextLabel
        restDeadlineMillis = RestTimer.deadlineFrom(SystemClock.elapsedRealtime(), seconds)
    }

    fun clearRest() {
        restDeadlineMillis = 0L
        restTotalSeconds = 0
        restBetweenExercises = false
        restNextLabel = ""
    }

    fun startSet() {
        begun = true
        lifting = true
        restedSeconds = NO_REST
        val wallNow = System.currentTimeMillis()
        startedAtWallMillis = wallNow
        startedAtElapsedMillis = SystemClock.elapsedRealtime()
        if (sessionStartedAtMillis == 0L) sessionStartedAtMillis = wallNow
        nowElapsedMillis = SystemClock.elapsedRealtime()
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun tick() {
        val ex = exercise ?: return
        val set = ex.sets.getOrNull(roundIndex) ?: return
        if (set.done) return
        val completedAtMillis = System.currentTimeMillis()
        // A timed hold's goal buzz belongs to the set that is ending; never let it
        // land during the rest that follows.
        restController.skip()
        sendEdit(
            buildTickDelta(
                dayId = dayId,
                programExerciseId = ex.programExerciseId,
                setIndex = roundIndex,
                startedAtMillis = startedAtWallMillis,
                completedAtMillis = completedAtMillis,
            ),
        )
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        lifting = false
        startedAtWallMillis = 0L
        startedAtElapsedMillis = 0L
        restedSeconds = NO_REST
        if (sessionStartedAtMillis == 0L) sessionStartedAtMillis = completedAtMillis
        sessionEndedAtMillis = completedAtMillis

        // Post-tick flags for this exercise (optimistic: the tick just marked this
        // round done) and for the day, so the advance decision matches what the
        // snapshot is about to say.
        val postTickFlags = ex.sets.mapIndexed { i, s -> i == roundIndex || s.done }
        val othersDone = snap.day.exercises.all { other ->
            other.programExerciseId == ex.programExerciseId || other.sets.all { it.done }
        }
        val advance = decideStreamAdvance(postTickFlags, postTickFlags.all { it } && othersDone)
        when {
            RestTimer.shouldRest(advance, set.restAfterSeconds) -> startRest(
                seconds = set.restAfterSeconds,
                betweenExercises = false,
                nextLabel = stream?.rounds?.getOrNull((advance as StreamAdvance.NextRound).index)
                    ?.let(::roundLabel).orEmpty(),
            )
            RestTimer.shouldRestAfterExercise(advance, set.restAfterSeconds) -> startRest(
                seconds = set.restAfterSeconds,
                betweenExercises = true,
                nextLabel = nextExerciseLabel(snap, ex.programExerciseId),
            )
        }
    }

    val state = dialUiState(
        DialInputs(
            snapshot = snap,
            exerciseIndex = exerciseIndex,
            phase = if (lifting) SetPhase.LIFTING else SetPhase.READY,
            begun = begun,
            pendingCount = pendingCount,
            rest = if (restDeadlineMillis > 0L) {
                RestState(restDeadlineMillis, restTotalSeconds, restBetweenExercises)
            } else {
                null
            },
            restedSeconds = restedSeconds.takeIf { it != NO_REST },
            liftingElapsedMillis = if (lifting) {
                (nowElapsedMillis - startedAtElapsedMillis).coerceAtLeast(0L)
            } else {
                0L
            },
            nowElapsedMillis = nowElapsedMillis,
            session = SessionStamps(sessionStartedAtMillis, sessionEndedAtMillis),
        ),
    )

    // Repaint ticker: only while something on screen is counting.
    LaunchedEffect(state.screen) {
        if (state.screen !in LIVE_SCREENS) return@LaunchedEffect
        val cadence = if (state.screen == DialScreen.LIFTING) LIFTING_TICK_MILLIS else LIVE_TICK_MILLIS
        while (true) {
            nowElapsedMillis = SystemClock.elapsedRealtime()
            delay(cadence)
        }
    }

    // The rest. The controller owns the single buzz (and the wake lock that keeps
    // it punctual in ambient); this effect only re-segments the dial afterwards.
    // Keyed on the deadline so it re-arms from the restored value after process
    // death — and if that value is already past, clears the stale rest at once.
    LaunchedEffect(restDeadlineMillis) {
        if (restDeadlineMillis <= 0L) return@LaunchedEffect
        restController.arm(restDeadlineMillis, restNextLabel)
        while (!RestTimer.isExpired(restDeadlineMillis, SystemClock.elapsedRealtime())) {
            delay(LIVE_TICK_MILLIS)
        }
        // A rest between exercises hands straight over to the next exercise's Ready;
        // a rest between sets earns the "✓ RESTED" badge and the halo bloom (§5.5).
        restedSeconds = if (restBetweenExercises) NO_REST else restTotalSeconds
        clearRest()
        nowElapsedMillis = SystemClock.elapsedRealtime()
    }

    // A timed hold buzzes once at its goal and ticks itself (§5.6). Same controller,
    // so it is the same single-buzz guarantee, and a manual tick cancels it.
    val latestTick by rememberUpdatedState({ tick() })
    LaunchedEffect(lifting, startedAtElapsedMillis, holdGoal) {
        if (!lifting || holdGoal <= 0 || startedAtElapsedMillis <= 0L) return@LaunchedEffect
        val goalDeadline = RestTimer.deadlineFrom(startedAtElapsedMillis, holdGoal)
        restController.arm(goalDeadline, "")
        while (!RestTimer.isExpired(goalDeadline, SystemClock.elapsedRealtime())) {
            delay(LIVE_TICK_MILLIS)
        }
        // Let the goal buzz land before the tick fires its own confirm haptic —
        // the brief's order is one long buzz, *then* the auto-tick (§8).
        delay(GOAL_BUZZ_SETTLE_MILLIS)
        latestTick()
    }

    Dial(
        state = state,
        onTap = {
            when (state.tap) {
                DialTap.BEGIN_EXERCISE -> begun = true
                DialTap.START_SET -> startSet()
                DialTap.TICK -> tick()
                DialTap.SKIP_REST -> {
                    // Skipping is silent by design: cancel the pending buzz, drop
                    // straight to the next set. No "rested" badge — nothing rested.
                    restController.skip()
                    clearRest()
                    restedSeconds = NO_REST
                }
                DialTap.DISMISS -> onDismiss()
                DialTap.NONE -> Unit
            }
        },
    )
}

/**
 * Drives the OngoingActivity re-entry chip off the snapshot (redesign §1.4 / R6).
 *
 * The chip's whole lifecycle is [isSessionActive]: post while a workout is
 * underway, clear otherwise. Because that is a pure function of the snapshot,
 * the effect also **reconciles on launch** — first composition (snapshot still
 * loading ⇒ inactive) cancels any chip a killed process left behind, and a
 * finished day flips it back to `clear()` with no extra bookkeeping.
 *
 * [POST_NOTIFICATIONS][Manifest.permission.POST_NOTIFICATIONS] is requested
 * **contextually** — once, the moment a session first becomes active (API 33+
 * only). Denial is graceful: [OngoingWorkoutChip.show] no-ops without the grant,
 * we never re-ask, and logging is entirely unaffected (re-entry falls back to
 * the launcher).
 */
@Composable
private fun OngoingSessionChip(snapshot: WatchSnapshot?) {
    val context = LocalContext.current
    val chip = remember(context) { OngoingWorkoutChip(context) }
    val sessionActive = isSessionActive(snapshot)

    var hasPermission by remember {
        mutableStateOf(OngoingWorkoutChip.hasPostNotificationsPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(sessionActive) {
        if (sessionActive && !hasPermission && OngoingWorkoutChip.needsRuntimePermission()) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(sessionActive, hasPermission) {
        if (sessionActive) chip.show() else chip.clear()
    }
}

/** Mirrors the phone app's DayScreen.KeepScreenOn — a plain `View.keepScreenOn` toggle. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
