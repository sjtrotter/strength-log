package io.github.sjtrotter.strengthlog.wear.data

import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Standalone stand-in for the real Data Layer client (#20). It seeds one
 * canned day — Squat's pinned seeded ramp (spec §11.1) plus a superset pair —
 * and applies edits in memory the same shape the phone will: a delta touches
 * one round of one track, a `done` on the "main" track also flips the aligned
 * "ss" round (one-tick-per-round, spec §8.2), and every applied edit bumps
 * `revision` so the screens' reconcile-on-next-snapshot logic has something
 * real to reconcile against even with no phone attached.
 */
class FakeWatchClient : WatchTrackerClient {

    private val state = MutableStateFlow(CANNED)

    override fun snapshotFlow(): StateFlow<WatchSnapshot> = state

    override suspend fun sendEdit(delta: SetEditDelta) {
        state.update { snapshot ->
            val exercises = snapshot.day.exercises.map { exercise ->
                if (exercise.programExerciseId != delta.programExerciseId) return@map exercise
                applyDelta(exercise, delta)
            }
            snapshot.copy(revision = snapshot.revision + 1, day = snapshot.day.copy(exercises = exercises))
        }
    }

    private fun applyDelta(exercise: WatchExercise, delta: SetEditDelta): WatchExercise {
        val editingMain = delta.slot == "main"
        val track = if (editingMain) exercise.sets else exercise.ssSets
        if (delta.setIndex !in track.indices) return exercise

        val updatedTrack = track.mapIndexed { i, set ->
            if (i != delta.setIndex) set else set.applying(delta)
        }

        // One-tick-per-round: a done edit on the main track dims the aligned
        // partner round too — there is no independent tick on the sub-row.
        val doneEdit = delta.done
        val updatedSsSets = if (editingMain && doneEdit != null && exercise.ssSets.isNotEmpty()) {
            exercise.ssSets.mapIndexed { i, set -> if (i == delta.setIndex) set.copy(done = doneEdit) else set }
        } else {
            exercise.ssSets
        }

        return if (editingMain) {
            exercise.copy(sets = updatedTrack, ssSets = updatedSsSets)
        } else {
            exercise.copy(ssSets = updatedTrack)
        }
    }

    private fun WatchSet.applying(delta: SetEditDelta): WatchSet = copy(
        weightLb = delta.weightLb ?: weightLb,
        reps = delta.reps ?: reps,
        done = delta.done ?: done,
    )

    private companion object {
        /** Squat's pinned seeded ramp (spec §11.1) — R130/R165/R190/R210/TOP235/B175. */
        val SQUAT = WatchExercise(
            programExerciseId = 1L,
            slot = "main",
            name = "Barbell Back Squat",
            goal = 235.0,
            perHand = false,
            supersetPartnerName = null,
            sets = listOf(
                WatchSet(130.0, 5, "RAMP", done = false),
                WatchSet(165.0, 5, "RAMP", done = false),
                WatchSet(190.0, 5, "RAMP", done = false),
                WatchSet(210.0, 3, "RAMP", done = false),
                WatchSet(235.0, 5, "TOP", done = false),
                WatchSet(175.0, 8, "BACKOFF", done = false),
            ),
            ssSets = emptyList(),
        )

        /** A superset pair, to exercise the sub-row / one-tick-per-round layout. */
        val INCLINE_PRESS = WatchExercise(
            programExerciseId = 2L,
            slot = "main",
            name = "Incline DB Press",
            goal = 75.0,
            perHand = true,
            supersetPartnerName = "Rope Pushdown",
            sets = listOf(
                WatchSet(75.0, 8, "WORK", done = false),
                WatchSet(75.0, 8, "WORK", done = false),
                WatchSet(75.0, 8, "WORK", done = false),
            ),
            ssSets = listOf(
                WatchSet(50.0, 12, "WORK", done = false),
                WatchSet(50.0, 12, "WORK", done = false),
                WatchSet(50.0, 12, "WORK", done = false),
            ),
        )

        val CANNED = WatchSnapshot(
            revision = 1L,
            suggestedDayId = "A",
            day = WatchDay(
                dayId = "A",
                title = "Day A — Squat Focus",
                accentIndex = 0,
                exercises = listOf(SQUAT, INCLINE_PRESS),
            ),
            unit = "lb",
        )
    }
}
