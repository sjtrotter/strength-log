package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
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
import io.github.sjtrotter.strengthlog.wear.theme.WearTrackerTheme
import kotlinx.coroutines.launch

private const val ROUTE_DAY_LIST = "dayList"
private const val ROUTE_EXERCISE = "exercise/{id}"
private const val ARG_ID = "id"

/**
 * Root composable: owns navigation, keeps the screen on while logging (A8),
 * swaps in [AmbientScreen] while the system reports ambient mode, and
 * translates screen intents into [SetEditDelta]s.
 */
@Composable
fun WearApp(client: WatchTrackerClient, isAmbient: Boolean) {
    val snapshot by client.snapshotFlow().collectAsState(initial = null)
    val navController = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    KeepScreenOn(enabled = !isAmbient)

    WearTrackerTheme {
        val snap = snapshot
        when {
            snap == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "strength.log")
            }
            isAmbient -> AmbientScreen(snap)
            else -> WearNavHost(snap, navController, sendEdit = { scope.launch { client.sendEdit(it) } })
        }
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
                onExerciseClick = { id -> navController.navigate("exercise/$id") },
            )
        }
        composable(ROUTE_EXERCISE) { backStackEntry ->
            val id = backStackEntry.arguments?.getString(ARG_ID)?.toLongOrNull()
            val exercise = snap.day.exercises.firstOrNull { it.programExerciseId == id }
            if (exercise == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Not found") }
            } else {
                val unit = watchUnit(snap.unit)

                fun send(slot: String, index: Int, weightLb: Double? = null, reps: Int? = null, done: Boolean? = null) {
                    sendEdit(
                        SetEditDelta(
                            dayId = snap.day.dayId,
                            programExerciseId = exercise.programExerciseId,
                            slot = slot,
                            setIndex = index,
                            weightLb = weightLb,
                            reps = reps,
                            done = done,
                            editedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }

                ExerciseDetailScreen(
                    state = exercise.toDetailUiState(unit, snap.day.accentIndex),
                    onWeightStep = { index, up ->
                        send("main", index, weightLb = unit.toLb(steppedWeight(exercise.sets[index].weightLb, unit, up)))
                    },
                    onRepsStep = { index, up ->
                        send("main", index, reps = steppedReps(exercise.sets[index].reps, up))
                    },
                    onMainDoneToggle = { index, done -> send("main", index, done = done) },
                    onPartnerWeightStep = { index, up ->
                        send("ss", index, weightLb = unit.toLb(steppedWeight(exercise.ssSets[index].weightLb, unit, up)))
                    },
                    onPartnerRepsStep = { index, up ->
                        send("ss", index, reps = steppedReps(exercise.ssSets[index].reps, up))
                    },
                )
            }
        }
    }
}

/** Steps a canonical-lb weight by one display-unit increment and rounds it (spec §5). */
private fun steppedWeight(currentLb: Double, unit: WeightUnit, up: Boolean): Double {
    val display = unit.fromLb(currentLb)
    val step = WeightStepper.increment(display, unit)
    return WeightStepper.round(if (up) display + step else display - step, unit)
}

private fun steppedReps(current: Int, up: Boolean): Int = (current + if (up) 1 else -1).coerceAtLeast(1)
