package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramDayEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * A complete, in-memory copy of everything a user owns — the whole DataStore
 * surface plus every Room row — read or written as one unit. It is the data-layer
 * currency for the A2 full backup: [TrackerRepository.exportSnapshot] reads one,
 * [TrackerRepository.importSnapshot] replaces the device's state with one.
 *
 * Rows are held as raw entities (surrogate ids and all) so a restore is bit-exact
 * — live logs and session sets key on those ids, so preserving them verbatim keeps
 * every cross-row reference intact. The `:transfer` module owns the portable
 * on-disk JSON shape and maps to/from this type at the boundary; `:data` stays out
 * of the serialization business.
 *
 * [answers] carries the lifter config and cardio prefs (it embeds both), so the
 * config is not stored twice — SSOT.
 */
data class FullSnapshot(
    val answers: WizardAnswers,
    val unit: WeightUnit,
    val wizardComplete: Boolean,
    val suggestedDay: String?,
    val customExercises: List<CustomExerciseEntity>,
    val days: List<ProgramDayEntity>,
    val exercises: List<ProgramExerciseEntity>,
    val logs: List<ExerciseLogEntity>,
    val sessions: List<WorkoutSessionEntity>,
    val sessionSets: List<SessionSetEntity>,
)
