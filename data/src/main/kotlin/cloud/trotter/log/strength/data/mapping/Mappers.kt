package cloud.trotter.log.strength.data.mapping

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner

/** Room-entity ⇆ pure-domain conversions, kept in one place. */

fun ProgramExerciseEntity.toDomain(): ProgramExercise =
    ProgramExercise(
        exerciseId = exerciseId,
        isMain = isMain,
        targetSets = targetSets,
        repSchemeLabel = repSchemeLabel,
        hasWarmupHint = hasWarmupHint,
        superset = supersetExerciseId?.let(::SupersetPartner),
        note = note,
    )

fun ProgramExercise.toEntity(dayId: String, position: Int): ProgramExerciseEntity =
    ProgramExerciseEntity(
        id = 0,
        dayId = dayId,
        position = position,
        exerciseId = exerciseId,
        isMain = isMain,
        targetSets = targetSets,
        repSchemeLabel = repSchemeLabel,
        hasWarmupHint = hasWarmupHint,
        supersetExerciseId = superset?.exerciseId,
        note = note,
    )

/** Assembles a domain [ProgramDay] from its day row and its ordered exercise rows. */
fun ProgramDayEntity.toDomain(exercises: List<ProgramExerciseEntity>): ProgramDay =
    ProgramDay(
        id = dayId,
        title = title,
        emphasisLine = emphasisLine,
        exercises = exercises.sortedBy { it.position }.map { it.toDomain() },
        cardio = CardioDto.decode(cardioJson),
        kind = ProgramDayKind.entries.firstOrNull { it.name == kind } ?: ProgramDayKind.STRENGTH,
    )

fun ProgramDay.toEntity(): ProgramDayEntity =
    ProgramDayEntity(
        dayId = id,
        position = 0, // caller overwrites with the day's index
        title = title,
        emphasisLine = emphasisLine,
        cardioJson = CardioDto.encode(cardio),
        kind = kind.name,
    )

/** Custom-exercise overlay row → the catalog's [ExerciseEntry] shape (PLAN.md A4).
 *  [CustomExerciseEntity.tracking] selects the GOAL source: WEIGHTED→flat load,
 *  REPS→rep target, TIMED→time target with [goalStartLb] as any added load. An
 *  unrecognized tracking name (a row from a newer build) falls back to WEIGHTED
 *  rather than crashing the catalog read. */
fun CustomExerciseEntity.toEntry(): ExerciseEntry =
    ExerciseEntry(
        id = id,
        name = name,
        pattern = MovementPattern.valueOf(pattern),
        equipment = equipmentCsv.split(",").filter { it.isNotBlank() }.map { Equipment.valueOf(it) },
        perHand = perHand,
        goal = when (TrackingType.entries.firstOrNull { it.name == tracking }) {
            TrackingType.REPS -> GoalSource.Reps(targetReps ?: 0)
            TrackingType.TIMED -> GoalSource.Time(targetSeconds ?: 0, goalStartLb)
            else -> GoalSource.Flat(goalStartLb)
        },
        subRank = ExerciseCatalog.CUSTOM_SUBRANK,
    )
