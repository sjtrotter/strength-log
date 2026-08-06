package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit

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
            restTimerEnabled = restSettings.enabled,
            restRampSeconds = restSettings.overrides[RestCategory.RAMP],
            restTopSeconds = restSettings.overrides[RestCategory.TOP],
            restBackoffSeconds = restSettings.overrides[RestCategory.BACKOFF],
            restWorkSeconds = restSettings.overrides[RestCategory.WORK],
            restLightSeconds = restSettings.overrides[RestCategory.LIGHT],
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
                        startedAtMillis = set.startedAtMillis,
                        completedAtMillis = set.completedAtMillis,
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
                startedAtMillis = set.startedAtMillis,
                completedAtMillis = set.completedAtMillis,
            )
        }
    }

    return FullSnapshot(
        answers = answers,
        unit = enumOrDefault(settings.weightUnit, WeightUnit.LB),
        wizardComplete = settings.wizardComplete,
        suggestedDay = settings.suggestedDay,
        restSettings = RestSettings(
            enabled = settings.restTimerEnabled,
            overrides = settings.restOverrides(),
        ),
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

/**
 * The document's five nullable rest fields as the domain's override map. A null
 * field is simply omitted — absent means "use the [cloud.trotter.log.strength.domain.standards.RestPolicy]
 * default", the same rule `SettingsStore` reads and writes — so this is the one
 * place the field↔category pairing is spelled out.
 */
fun SettingsBackup.restOverrides(): Map<RestCategory, Int> = buildMap {
    restRampSeconds?.let { put(RestCategory.RAMP, it) }
    restTopSeconds?.let { put(RestCategory.TOP, it) }
    restBackoffSeconds?.let { put(RestCategory.BACKOFF, it) }
    restWorkSeconds?.let { put(RestCategory.WORK, it) }
    restLightSeconds?.let { put(RestCategory.LIGHT, it) }
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
