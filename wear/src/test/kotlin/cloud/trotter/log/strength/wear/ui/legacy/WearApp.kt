@file:JvmName("MainWorkoutDialFixture")

package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

internal var mainWorkoutDialFixtureState: Map<String, Any?> = emptyMap()

// Frozen main saveable position: Compose keys include the source basename and
// function signature. JvmName only keeps this fixture's facade separate.
@Suppress("UNUSED_PARAMETER", "unused")
@Composable
private fun WorkoutDial(
    snap: WatchSnapshot,
    cardioAnchors: CardioAnchors?,
    cardioSets: CardioSetState?,
    onCardioSetTap: () -> Unit,
    onCardioSetDismiss: () -> Unit,
    onCardioSetHold: (DialHold) -> Unit,
    onCardioSetTurn: (Int) -> Int,
    timedRep: TimedRepState?,
    onTimedRepChanged: (TimedRepState?) -> Unit,
    bootCount: Int,
    onStartCardio: () -> Unit,
    onStopCardio: () -> Unit,
    pendingCount: Int,
    pendingExercises: Set<Long>,
    pendingSwaps: Set<Long>,
    restController: RestTimerController,
    scheduleTimedHoldTick: (RestTimerController.Firing, () -> Unit) -> Unit,
    sendEdit: (SetEditDelta) -> Unit,
    sendSwap: (ExerciseSwapDelta) -> Unit,
    sendCardio: (CardioDelta) -> Unit,
    onSessionStarted: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val dayId = snap.day.dayId
    rememberSaveable(snap.planId, dayId, snap.runId,
        saver = mapSaver(save = { state: Map<String, Any?> -> state }, restore = { it })) {
        mainWorkoutDialFixtureState
    }
}
