package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.domain.model.MovementPattern

/**
 * Pure preview/confirm model for a CSV history import (issue #16; PLAN.md A2:
 * "unmatched names ... preview/confirm ... never silent guessing"). Built by
 * [HistoryCsvReader.preview] and consumed by [CsvHistoryImporter.commit] —
 * nothing is written to the device between the two; the caller renders this,
 * lets the user edit each [UnmatchedExerciseName.suggestedPattern], and only
 * then calls commit.
 *
 * [sessions] already groups every CSV row into workouts (by Date + Workout
 * Name, PLAN.md A2). Each [PreviewSet.exerciseId] is filled in when its
 * [PreviewSet.exerciseName] matched the catalog or an existing custom
 * exercise (case/whitespace-insensitive exact match); it is `null` exactly
 * when that name also appears in [unmatchedNames], which lists each *distinct*
 * unmatched name once with an editable pattern suggestion.
 */
data class CsvImportPreview(
    val sessions: List<PreviewSession>,
    val unmatchedNames: List<UnmatchedExerciseName>,
) {
    val isFullyMatched: Boolean get() = unmatchedNames.isEmpty()
}

/** One workout grouped from CSV rows sharing the same (Date, Workout Name). */
data class PreviewSession(
    val dayTitle: String,
    val completedAt: Long,
    val sets: List<PreviewSet>,
)

/** One CSV row, resolved as far as it can be without user input. */
data class PreviewSet(
    val exerciseName: String,
    val exerciseId: String?,
    val setIndex: Int,
    val weightLb: Double,
    val reps: Int,
)

/** A CSV exercise name with no catalog/custom match, and a best-effort
 *  pattern guess ([PatternGuesser]) the user can accept or change before a
 *  custom exercise is created for it. */
data class UnmatchedExerciseName(
    val name: String,
    val suggestedPattern: MovementPattern,
)
