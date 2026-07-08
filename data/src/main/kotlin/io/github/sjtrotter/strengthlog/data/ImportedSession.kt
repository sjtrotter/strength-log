package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity

/**
 * One workout to append to history via [TrackerRepository.importSessionHistory]
 * (CSV import, issue #16). [session]'s id and each set's `id`/`sessionId` are
 * ignored on the way in — Room assigns the session its id on insert and that
 * id is stamped onto every set before the sets are written, so the caller
 * (`:transfer`) never has to invent a row id.
 */
data class ImportedSession(
    val session: WorkoutSessionEntity,
    val sets: List<SessionSetEntity>,
)
