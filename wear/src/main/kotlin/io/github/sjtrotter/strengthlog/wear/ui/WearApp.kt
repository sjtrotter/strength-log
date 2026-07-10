package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
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
private const val SYNCED_PILL_MILLIS = 2_000L

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
    WearNavHost(snap, navController, sendEdit)

    val updatedPillVisible = rememberUpdatedFromPhonePill(snap)
    val syncedPillVisible = rememberSyncedPill(pendingCount)

    if (updatedPillVisible) {
        UpdatedFromPhonePill(
            accentColor = dayAccent(snap.day.accentIndex),
            accentSoftColor = accentSoft(snap.day.accentIndex),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp),
        )
    }

    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
        when {
            pendingCount > 0 -> QueuedPill(pendingCount)
            syncedPillVisible -> SyncedPill()
        }
    }
}

/** Tracks the last rendered snapshot and flashes true for [UPDATED_PILL_MILLIS] on a real content delta (digest §1.1). */
@Composable
private fun rememberUpdatedFromPhonePill(snap: WatchSnapshot): Boolean {
    var previous by remember { mutableStateOf<WatchSnapshot?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(snap) {
        if (isUpdatedFromPhone(previous, snap)) {
            visible = true
            delay(UPDATED_PILL_MILLIS)
            visible = false
        }
        previous = snap
    }
    return visible
}

/** Tracks the pending-edit count transition and flashes true for [SYNCED_PILL_MILLIS] when it settles to zero (digest §3). */
@Composable
private fun rememberSyncedPill(pendingCount: Int): Boolean {
    var previousCount by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCount) {
        val kind = syncPillKind(previousCount, pendingCount)
        previousCount = pendingCount
        if (kind == SyncPillKind.SYNCED) {
            visible = true
            delay(SYNCED_PILL_MILLIS)
            visible = false
        }
    }
    return visible
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
 * Owns the exercise-stream screen's local navigation state — [currentIndex] is
 * "which round is focused", a pure UI-navigation concept that lives outside
 * [WatchSnapshot] (the round *data* itself is snapshot-sourced, so back/forth
 * navigation never loses progress; only which round is on-screen is local).
 * [rememberSaveable] survives rotation/process death without a ViewModel —
 * this module has no Hilt/DI, so that's the idiomatic no-DI equivalent.
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

    fun send(slot: String, index: Int, weightLb: Double? = null, reps: Int? = null, done: Boolean? = null) {
        sendEdit(
            SetEditDelta(
                dayId = snap.day.dayId,
                programExerciseId = programExerciseId,
                slot = slot,
                setIndex = index,
                weightLb = weightLb,
                reps = reps,
                done = done,
                editedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    ExerciseStreamScreen(
        state = exercise.toStreamUiState(unit, snap.day.dayId, snap.day.accentIndex),
        currentIndex = boundedIndex,
        onBack = onBack,
        onWeightStep = { i, up -> send("main", i, weightLb = unit.toLb(steppedWeight(exercise.sets[i].weightLb, unit, up))) },
        onRepsStep = { i, up -> send("main", i, reps = steppedReps(exercise.sets[i].reps, up)) },
        onPartnerWeightStep = { i, up -> send("ss", i, weightLb = unit.toLb(steppedWeight(exercise.ssSets[i].weightLb, unit, up))) },
        onPartnerRepsStep = { i, up -> send("ss", i, reps = steppedReps(exercise.ssSets[i].reps, up)) },
        onTick = {
            val nowDone = !exercise.sets[boundedIndex].done
            send("main", boundedIndex, done = nowDone)
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

/** Steps a canonical-lb weight by one display-unit increment and rounds it (spec §5). */
private fun steppedWeight(currentLb: Double, unit: WeightUnit, up: Boolean): Double {
    val display = unit.fromLb(currentLb)
    val step = WeightStepper.increment(display, unit)
    return WeightStepper.round(if (up) display + step else display - step, unit)
}

private fun steppedReps(current: Int, up: Boolean): Int = (current + if (up) 1 else -1).coerceAtLeast(1)
