package io.github.sjtrotter.strengthlog.wear.ui

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.wear.OngoingWorkoutChip
import io.github.sjtrotter.strengthlog.wear.data.WatchTrackerClient
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.WearTrackerTheme
import io.github.sjtrotter.strengthlog.wear.theme.accentSoft
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROUTE_DAY_LIST = "dayList"
private const val ROUTE_EXERCISE = "exercise/{id}/{round}"
private const val ROUTE_DONE = "done"
private const val ARG_ID = "id"
private const val ARG_ROUND = "round"
private const val UPDATED_PILL_MILLIS = 2_800L

// An inbound snapshot within this long of the lifter's last local edit is the
// phone echoing that edit back (its cascade/seeding) — not a genuine phone-side
// change — so the "updated from phone" pill stays quiet. Comfortably longer than
// a normal watch->phone->watch round-trip, shorter than any deliberate follow-up.
private const val UPDATED_PILL_SUPPRESS_MILLIS = 3_500L

/**
 * Root composable: owns navigation, keeps the screen on while logging (A8),
 * swaps in [AmbientScreen] while the system reports ambient mode, and
 * translates screen intents into [SetEditDelta]s.
 *
 * Every branch sits on a root [Background]-filled [Box] so no screen — even
 * one whose own Scaffold doesn't fill the round face — can show the system's
 * light default window through a gap (bug 2's other half; the manifest theme
 * fixes the launch flash, this fixes everything after).
 */
@Composable
fun WearApp(client: WatchTrackerClient, isAmbient: Boolean, ambientTick: Int = 0) {
    val snapshot by client.snapshotFlow().collectAsState(initial = null)
    val pendingCount by client.pendingCountFlow().collectAsState(initial = 0)
    val navController = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    KeepScreenOn(enabled = !isAmbient)
    // Runs on every screen (loading/ambient/interactive) so the chip reconciles
    // even when the app relaunches straight into ambient — see OngoingSessionChip.
    OngoingSessionChip(snapshot)

    WearTrackerTheme {
        Box(Modifier.fillMaxSize().background(Background)) {
            val snap = snapshot
            when {
                snap == null -> LoadingScreen()
                isAmbient -> AmbientScreen(snap, ambientTick)
                snap.day.exercises.isEmpty() -> EmptyScreen()
                else -> InteractiveContent(
                    snap = snap,
                    pendingCount = pendingCount,
                    navController = navController,
                    sendEdit = { scope.launch { client.sendEdit(it) } },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.InteractiveContent(
    snap: WatchSnapshot,
    pendingCount: Int,
    navController: NavHostController,
    sendEdit: (SetEditDelta) -> Unit,
) {
    // Stamp when the lifter last sent an edit (a tick). The "updated from phone"
    // pill uses it to tell the phone re-publishing our own edit from a genuine
    // phone-side change. rememberSaveable so a restore right after an edit still
    // suppresses the echo; elapsedRealtime dodges wall-clock jumps.
    var lastLocalEditAtMillis by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE / 2) }
    val trackedSendEdit: (SetEditDelta) -> Unit = { delta ->
        lastLocalEditAtMillis = SystemClock.elapsedRealtime()
        sendEdit(delta)
    }

    WearNavHost(snap, navController, trackedSendEdit)

    val updatedPillVisible = rememberUpdatedFromPhonePill(snap) {
        SystemClock.elapsedRealtime() - lastLocalEditAtMillis
    }
    val syncedPillVisible = rememberSyncedPill(pendingCount)

    // Every sync indicator lives at the TOP of the face, below the "‹ day X" back
    // button, as a compact, non-interactive column. It never sits over the tick
    // button (which used to be covered and un-tappable) and has no `clickable`, so
    // touches fall straight through to the content and the tick beneath it.
    Column(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when {
            pendingCount > 0 -> QueuedPill(pendingCount)
            syncedPillVisible -> SyncedPill()
        }
        if (updatedPillVisible) {
            UpdatedFromPhonePill(
                accentColor = dayAccent(snap.day.accentIndex),
                accentSoftColor = accentSoft(snap.day.accentIndex),
            )
        }
    }
}

/**
 * Flashes true for [UPDATED_PILL_MILLIS] on a *genuine* phone-side content change
 * (design digest §1.1). [elapsedSinceLocalEdit] lets [shouldFlashUpdatedFromPhone]
 * suppress the phone's re-publish of the lifter's own watch edit.
 */
@Composable
private fun rememberUpdatedFromPhonePill(snap: WatchSnapshot, elapsedSinceLocalEdit: () -> Long): Boolean {
    var previous by remember { mutableStateOf<WatchSnapshot?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(snap) {
        if (shouldFlashUpdatedFromPhone(previous, snap, elapsedSinceLocalEdit(), UPDATED_PILL_SUPPRESS_MILLIS)) {
            visible = true
            delay(UPDATED_PILL_MILLIS)
            visible = false
        }
        previous = snap
    }
    return visible
}

/** Tracks the pending-edit count transition and flashes true for ~2s when it settles to zero (digest §3). */
@Composable
private fun rememberSyncedPill(pendingCount: Int): Boolean {
    var previousCount by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCount) {
        val kind = syncPillKind(previousCount, pendingCount)
        previousCount = pendingCount
        if (kind == SyncPillKind.SYNCED) {
            visible = true
            delay(2_000L)
            visible = false
        }
    }
    return visible
}

/**
 * Drives the OngoingActivity re-entry chip off the snapshot (redesign §1.4 / R6).
 *
 * The chip's whole lifecycle is [isSessionActive]: post while a workout is
 * underway, clear otherwise. Because that is a pure function of the snapshot,
 * the effect also **reconciles on launch** — first composition (snapshot still
 * loading ⇒ inactive) cancels any chip a killed process left behind, and DayDone
 * / day-change / all-undone flip it back to `clear()` with no extra bookkeeping.
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

@Composable
private fun WearNavHost(
    snap: WatchSnapshot,
    navController: NavHostController,
    sendEdit: (SetEditDelta) -> Unit,
) {
    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_DAY_LIST) {
        composable(ROUTE_DAY_LIST) {
            DayListScreen(
                state = snap.toDayListUiState(),
                onExerciseClick = { id, round -> navController.navigate("exercise/$id/$round") },
            )
        }
        composable(ROUTE_EXERCISE) { backStackEntry ->
            val id = backStackEntry.arguments?.getString(ARG_ID)?.toLongOrNull()
            val startRound = backStackEntry.arguments?.getString(ARG_ROUND)?.toIntOrNull() ?: 0
            val exercise = snap.day.exercises.firstOrNull { it.programExerciseId == id }
            if (exercise == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Not found")
                }
            } else {
                ExerciseStreamRoute(
                    snap = snap,
                    programExerciseId = exercise.programExerciseId,
                    startRoundIndex = startRound,
                    onBack = { navController.popBackStack() },
                    onDayDone = {
                        navController.navigate(ROUTE_DONE) {
                            popUpTo(ROUTE_DAY_LIST) { inclusive = true }
                        }
                    },
                    sendEdit = sendEdit,
                )
            }
        }
        composable(ROUTE_DONE) {
            DayDoneScreen(state = snap.toDayDoneUiState())
        }
    }
}

/**
 * Owns the exercise-stream screen's local navigation state. [currentIndex] is
 * which round is focused (a pure UI-navigation concept outside [WatchSnapshot]).
 *
 * The watch is read-only (redesign §1.1): the only outbound edit is the tick's
 * `done` delta ([buildDelta]), built and sent immediately on tap — there is no
 * coalescing window, no local pending-edit overlay, and nothing to flush on
 * stop. The client (`DataLayerWatchClient`/`FakeWatchClient`) already echoes
 * the tick into [WatchTrackerClient.snapshotFlow] optimistically via
 * `WatchEditOptimism`, so [exercise] read straight off [snap] is always the
 * value to render.
 */
@Composable
private fun ExerciseStreamRoute(
    snap: WatchSnapshot,
    programExerciseId: Long,
    startRoundIndex: Int,
    onBack: () -> Unit,
    onDayDone: () -> Unit,
    sendEdit: (SetEditDelta) -> Unit,
) {
    val exercise = snap.day.exercises.first { it.programExerciseId == programExerciseId }
    val unit = watchUnit(snap.unit)
    var currentIndex by rememberSaveable(programExerciseId) { mutableIntStateOf(startRoundIndex) }
    val boundedIndex = currentIndex.coerceIn(0, (exercise.sets.size - 1).coerceAtLeast(0))
    val latestSnap by rememberUpdatedState(snap)
    val scope = rememberCoroutineScope()

    val streamState = exercise.toStreamUiState(unit, snap.day.dayId, snap.day.accentIndex)

    ExerciseStreamScreen(
        state = streamState,
        currentIndex = boundedIndex,
        onBack = onBack,
        onTick = {
            val nowDone = !exercise.sets[boundedIndex].done
            sendEdit(buildDelta(snap, programExerciseId, boundedIndex, done = nowDone))
            if (nowDone) {
                scope.launch {
                    delay(380)
                    val ex = latestSnap.day.exercises.first { it.programExerciseId == programExerciseId }
                    when (val advance = decideStreamAdvance(ex.sets.map { it.done }, allExercisesDone(latestSnap))) {
                        is StreamAdvance.NextRound -> currentIndex = advance.index
                        StreamAdvance.BackToList -> onBack()
                        StreamAdvance.DayDone -> onDayDone()
                    }
                }
            }
        },
    )
}

/** The watch's one outbound mutation (redesign §1.1): a done/undone tick on the "main" track. */
private fun buildDelta(
    snap: WatchSnapshot,
    programExerciseId: Long,
    index: Int,
    done: Boolean,
): SetEditDelta = SetEditDelta(
    dayId = snap.day.dayId,
    programExerciseId = programExerciseId,
    slot = "main",
    setIndex = index,
    done = done,
    editedAtMillis = System.currentTimeMillis(),
)
