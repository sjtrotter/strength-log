package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.domain.model.LoggedSet

/**
 * The live log for one exercise slot's set track, as read out for a day. [sets]
 * already has the daily checkmark reset applied, so [checkDate] is informational
 * (the date the persisted `done` flags were last valid for).
 */
data class LoggedSlot(
    val programExerciseId: Long,
    val slot: String,
    val sets: List<LoggedSet>,
    val checkDate: String,
)
