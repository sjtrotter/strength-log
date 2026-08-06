package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit

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
    val restSettings: RestSettings,
    val customExercises: List<CustomExerciseEntity>,
    val days: List<ProgramDayEntity>,
    val exercises: List<ProgramExerciseEntity>,
    val logs: List<ExerciseLogEntity>,
    val sessions: List<WorkoutSessionEntity>,
    val sessionSets: List<SessionSetEntity>,
)
