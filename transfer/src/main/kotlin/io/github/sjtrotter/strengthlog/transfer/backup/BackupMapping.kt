package io.github.sjtrotter.strengthlog.transfer.backup

import io.github.sjtrotter.strengthlog.data.FullSnapshot
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramDayEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * Maps between `:data`'s [FullSnapshot] (raw entities + settings) and the portable
 * [BackupDocument]. The document nests exercises under days and sets under
 * sessions, and drops row positions (they are the list order), so this boundary is
 * where that structure is folded and unfolded.
 *
 * Enum-valued *settings* are parsed leniently (an unknown name falls back to the
 * domain default), matching `SettingsStore`'s contract that a value from a newer
 * build never crashes a read. Enum values that would instead throw deeper in the
 * stack — a custom exercise's pattern/equipment — are checked up front by
 * [BackupCodec] and so are safe to parse strictly here.
 */

fun FullSnapshot.toDocument(): BackupDocument {
    val exercisesByDay = exercises.groupBy { it.dayId }
    val setsBySession = sessionSets.groupBy { it.sessionId }
    return BackupDocument(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        settings = SettingsBackup(
            bodyweightLb = answers.config.bodyweightLb,
            age = answers.config.age,
            level = answers.config.level.name,
            emphasis = answers.config.emphasis.name,
            cardioMode = answers.cardio.mode.name,
            cardioPlacement = answers.cardio.placement.name,
            fiveKGoal = answers.cardio.fiveKGoal,
            daysPerWeek = answers.daysPerWeek,
            split = answers.split.name,
            anchorScheme = answers.anchorScheme.name,
            deadliftVariant = answers.deadliftVariant.name,
            equipment = answers.equipment.map { it.name }.sorted(),
            weightUnit = unit.name,
            wizardComplete = wizardComplete,
            suggestedDay = suggestedDay,
        ),
        customExercises = customExercises.map {
            CustomExerciseBackup(
                id = it.id,
                name = it.name,
                pattern = it.pattern,
                equipmentCsv = it.equipmentCsv,
                perHand = it.perHand,
                goalStartLb = it.goalStartLb,
                tracking = it.tracking,
                targetReps = it.targetReps,
                targetSeconds = it.targetSeconds,
            )
        },
        program = days.map { day ->
            ProgramDayBackup(
                dayId = day.dayId,
                title = day.title,
                emphasisLine = day.emphasisLine,
                cardioJson = day.cardioJson,
                exercises = exercisesByDay[day.dayId].orEmpty().map { ex ->
                    ProgramExerciseBackup(
                        id = ex.id,
                        exerciseId = ex.exerciseId,
                        isMain = ex.isMain,
                        targetSets = ex.targetSets,
                        repSchemeLabel = ex.repSchemeLabel,
                        hasWarmupHint = ex.hasWarmupHint,
                        supersetExerciseId = ex.supersetExerciseId,
                        note = ex.note,
                    )
                },
            )
        },
        liveLogs = logs.map {
            LiveLogBackup(
                dayId = it.dayId,
                programExerciseId = it.programExerciseId,
                slot = it.slot,
                setsJson = it.setsJson,
                checkDate = it.checkDate,
                updatedAt = it.updatedAt,
            )
        },
        sessions = sessions.map { s ->
            SessionBackup(
                id = s.id,
                dayId = s.dayId,
                dayTitle = s.dayTitle,
                startedAt = s.startedAt,
                completedAt = s.completedAt,
                bodyweightLb = s.bodyweightLb,
                sets = setsBySession[s.id].orEmpty().map { set ->
                    SessionSetBackup(
                        id = set.id,
                        exerciseId = set.exerciseId,
                        exerciseName = set.exerciseName,
                        slot = set.slot,
                        setIndex = set.setIndex,
                        kind = set.kind,
                        weightLb = set.weightLb,
                        reps = set.reps,
                        done = set.done,
                        seconds = set.seconds,
                    )
                },
            )
        },
    )
}

fun BackupDocument.toSnapshot(): FullSnapshot {
    val config = LifterConfig(
        bodyweightLb = settings.bodyweightLb,
        age = settings.age,
        level = enumOrDefault(settings.level, ExperienceLevel.INTERMEDIATE),
        emphasis = enumOrDefault(settings.emphasis, GoalEmphasis.BALANCED),
    )
    val cardio = CardioPrefs(
        mode = enumOrDefault(settings.cardioMode, CardioMode.OUTDOOR_RUN),
        placement = enumOrDefault(settings.cardioPlacement, CardioPlacement.FINISHERS),
        fiveKGoal = settings.fiveKGoal,
    )
    val answers = WizardAnswers(
        daysPerWeek = settings.daysPerWeek,
        split = enumOrDefault(settings.split, SplitTemplate.FULL_BODY),
        anchorScheme = enumOrDefault(settings.anchorScheme, AnchorScheme.PROTOTYPE),
        deadliftVariant = enumOrDefault(settings.deadliftVariant, DeadliftVariant.TRAP_BAR),
        cardio = cardio,
        config = config,
        equipment = settings.equipment.mapNotNull { name ->
            Equipment.entries.firstOrNull { it.name == name }
        }.toSet(),
    )

    val dayEntities = program.mapIndexed { index, day ->
        ProgramDayEntity(
            dayId = day.dayId,
            position = index,
            title = day.title,
            emphasisLine = day.emphasisLine,
            cardioJson = day.cardioJson,
        )
    }
    val exerciseEntities = program.flatMap { day ->
        day.exercises.mapIndexed { pos, ex ->
            ProgramExerciseEntity(
                id = ex.id,
                dayId = day.dayId,
                position = pos,
                exerciseId = ex.exerciseId,
                isMain = ex.isMain,
                targetSets = ex.targetSets,
                repSchemeLabel = ex.repSchemeLabel,
                hasWarmupHint = ex.hasWarmupHint,
                supersetExerciseId = ex.supersetExerciseId,
                note = ex.note,
            )
        }
    }
    val logEntities = liveLogs.map {
        ExerciseLogEntity(
            dayId = it.dayId,
            programExerciseId = it.programExerciseId,
            slot = it.slot,
            setsJson = it.setsJson,
            checkDate = it.checkDate,
            updatedAt = it.updatedAt,
        )
    }
    val sessionEntities = sessions.map {
        WorkoutSessionEntity(
            id = it.id,
            dayId = it.dayId,
            dayTitle = it.dayTitle,
            startedAt = it.startedAt,
            completedAt = it.completedAt,
            bodyweightLb = it.bodyweightLb,
        )
    }
    val sessionSetEntities = sessions.flatMap { s ->
        s.sets.map { set ->
            SessionSetEntity(
                id = set.id,
                sessionId = s.id,
                exerciseId = set.exerciseId,
                exerciseName = set.exerciseName,
                slot = set.slot,
                setIndex = set.setIndex,
                kind = set.kind,
                weightLb = set.weightLb,
                reps = set.reps,
                done = set.done,
                seconds = set.seconds,
            )
        }
    }

    return FullSnapshot(
        answers = answers,
        unit = enumOrDefault(settings.weightUnit, WeightUnit.LB),
        wizardComplete = settings.wizardComplete,
        suggestedDay = settings.suggestedDay,
        customExercises = customExercises.map {
            CustomExerciseEntity(
                id = it.id,
                name = it.name,
                pattern = it.pattern,
                equipmentCsv = it.equipmentCsv,
                perHand = it.perHand,
                goalStartLb = it.goalStartLb,
                tracking = it.tracking,
                targetReps = it.targetReps,
                targetSeconds = it.targetSeconds,
            )
        },
        days = dayEntities,
        exercises = exerciseEntities,
        logs = logEntities,
        sessions = sessionEntities,
        sessionSets = sessionSetEntities,
    )
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
