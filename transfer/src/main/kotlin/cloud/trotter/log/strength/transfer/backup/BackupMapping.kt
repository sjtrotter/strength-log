package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.run.LegacyLog
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.preferredPlan
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
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
import cloud.trotter.log.strength.domain.theme.ThemePreference

/**
 * Maps between `:data`'s [FullSnapshot] (raw entities + settings) and the portable
 * [BackupDocument]. The document nests days under plans, exercises under days,
 * and sets under sessions, and drops row positions (they are the list order), so
 * this boundary is where that structure is folded and unfolded.
 *
 * Enum-valued *settings* are parsed leniently (an unknown name falls back to the
 * domain default), matching `SettingsStore`'s contract that a value from a newer
 * build never crashes a read. Enum values that would instead throw deeper in the
 * stack — a custom exercise's pattern/equipment — are checked up front by
 * [BackupCodec] and so are safe to parse strictly here.
 */

fun FullSnapshot.toDocument(): BackupDocument {
    val exercisesByDay = exercises.groupBy { it.planId to it.dayId }
    val daysByPlan = days.groupBy { it.planId }
    val setsBySession = sessionSets.groupBy { it.sessionId }
    val cardioSetsBySession = cardioSetResults.groupBy { it.cardioSessionId }
    return BackupDocument(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        settings = SettingsBackup(
            bodyweightLb = answers.config.bodyweightLb,
            age = answers.config.age,
            level = answers.config.level.name,
            weightUnit = unit.name,
            wizardComplete = wizardComplete,
            selectedPlanId = selectedPlanId,
            restTimerEnabled = restSettings.enabled,
            restRampSeconds = restSettings.overrides[RestCategory.RAMP],
            restTopSeconds = restSettings.overrides[RestCategory.TOP],
            restBackoffSeconds = restSettings.overrides[RestCategory.BACKOFF],
            restWorkSeconds = restSettings.overrides[RestCategory.WORK],
            restLightSeconds = restSettings.overrides[RestCategory.LIGHT],
            keepScreenOn = keepScreenOn,
            topSetHelperSeen = topSetHelperSeen,
            supersetHelperSeen = supersetHelperSeen,
            theme = themePreference.name,
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
        plans = plans.sortedWith(compareBy({ it.position }, { it.id })).map { plan ->
            PlanBackup(
                id = plan.id,
                name = plan.name,
                origin = plan.origin,
                createdAt = plan.createdAt,
                archived = plan.archived,
                onToday = plan.onToday,
                recipeJson = plan.recipeJson,
                suggestedDay = plan.suggestedDayId,
                days = daysByPlan[plan.id].orEmpty().sortedBy { it.position }.map { day ->
                    ProgramDayBackup(
                        dayId = day.dayId,
                        title = day.title,
                        emphasisLine = day.emphasisLine,
                        cardioJson = day.cardioJson,
                        kind = day.kind,
                        cardioBlockJson = day.cardioBlockJson,
                        nextRunId = day.nextRunId,
                        closedAt = day.closedAt,
                        exercises = exercisesByDay[plan.id to day.dayId].orEmpty().sortedBy { it.position }.map { ex ->
                            ProgramExerciseBackup(
                                id = ex.id,
                                exerciseId = ex.exerciseId,
                                isMain = ex.isMain,
                                targetSets = ex.targetSets,
                                repSchemeLabel = ex.repSchemeLabel,
                                hasWarmupHint = ex.hasWarmupHint,
                                supersetExerciseId = ex.supersetExerciseId,
                                note = ex.note,
                                prescriptionJson = ex.prescriptionJson,
                                keepToPlan = ex.keepToPlan,
                            )
                        },
                    )
                },
            )
        },
        runs = runs.map { run ->
            RunBackup(run.id, run.planId, run.dayId, run.dayTitle, run.planName, run.startedAt,
                runTracks.filter { it.runId == run.id }.sortedWith(compareBy({ it.position }, { it.slot }, { it.id })).map {
                    RunTrackBackup(it.id, it.position, it.programExerciseId, it.slot, it.exerciseId,
                        it.retired, it.plannedJson, it.setsJson, it.updatedAt)
                },
            )
        },
        program = emptyList(),
        liveLogs = logs.map {
            LiveLogBackup(
                planId = it.planId,
                dayId = it.dayId,
                programExerciseId = it.programExerciseId,
                slot = it.slot,
                setsJson = it.setsJson,
                checkDate = null,
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
                note = s.note,
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
                        trackPosition = set.trackPosition,
                        plannedIndex = set.plannedIndex,
                    )
                },
                planId = s.planId,
                planName = s.planName,
                runId = s.runId,
                plannedJson = s.plannedJson,
            )
        },
        cardioSessions = cardioSessions.map {
            CardioSessionBackup(
                id = it.id, dayId = it.dayId, mode = it.mode, hard = it.hard,
                label = it.label, startedAt = it.startedAt, completedAt = it.completedAt,
                seconds = it.seconds, stepsCompleted = it.stepsCompleted,
                planId = it.planId, planName = it.planName,
                activity = it.activity,
                sets = cardioSetsBySession[it.id].orEmpty().sortedWith(compareBy({ it.position }, { it.id })).map { set ->
                    CardioSetResultBackup(
                        id = set.id, specJson = set.specJson, status = set.status,
                        elapsedSeconds = set.elapsedSeconds, metres = set.metres, distanceSource = set.distanceSource,
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
        emphasis = if (schemaVersion >= 9) {
            WizardAnswers().config.emphasis
        } else {
            enumOrDefault(settings.emphasis, GoalEmphasis.BALANCED)
        },
    )
    val answers = if (schemaVersion >= 9) {
        WizardAnswers(config = config)
    } else {
        WizardAnswers(
            daysPerWeek = checkNotNull(settings.daysPerWeek),
            split = enumOrDefault(settings.split, SplitTemplate.FULL_BODY),
            anchorScheme = enumOrDefault(settings.anchorScheme, AnchorScheme.PROTOTYPE),
            deadliftVariant = enumOrDefault(settings.deadliftVariant, DeadliftVariant.TRAP_BAR),
            cardio = CardioPrefs(
                mode = enumOrDefault(settings.cardioMode, CardioMode.OUTDOOR_RUN),
                placement = enumOrDefault(settings.cardioPlacement, CardioPlacement.FINISHERS),
                fiveKGoal = checkNotNull(settings.fiveKGoal),
            ),
            config = config,
            equipment = checkNotNull(settings.equipment).mapNotNull { name ->
                Equipment.entries.firstOrNull { it.name == name }
            }.toSet(),
        )
    }

    val programs = if (schemaVersion >= 8) {
        plans.map { it.id to it.days }
    } else {
        listOf(PlanEntity.LEGACY_ID to program)
    }
    val dayEntities = programs.flatMap { (planId, days) ->
        days.mapIndexed { index, day ->
            ProgramDayEntity(
                planId = planId,
                dayId = day.dayId,
                position = index,
                title = day.title,
                emphasisLine = day.emphasisLine,
                cardioJson = day.cardioJson,
                kind = day.kind,
                cardioBlockJson = if (schemaVersion >= 11) day.cardioBlockJson else null,
                nextRunId = if (schemaVersion >= 13) day.nextRunId else "",
                closedAt = if (schemaVersion >= 13) day.closedAt else null,
            )
        }
    }
    val exerciseEntities = programs.flatMap { (planId, days) ->
        days.flatMap { day ->
            day.exercises.mapIndexed { pos, ex ->
                ProgramExerciseEntity(
                    id = ex.id,
                    planId = planId,
                    dayId = day.dayId,
                    position = pos,
                    exerciseId = ex.exerciseId,
                    isMain = ex.isMain,
                    targetSets = ex.targetSets,
                    repSchemeLabel = ex.repSchemeLabel,
                    hasWarmupHint = ex.hasWarmupHint,
                    supersetExerciseId = ex.supersetExerciseId,
                    note = ex.note,
                    prescriptionJson = if (schemaVersion >= 10) ex.prescriptionJson else null,
                    keepToPlan = ex.keepToPlan ?: (schemaVersion >= 10 && ex.prescriptionJson != null),
                )
            }
        }
    }
    val logEntities = liveLogs.filter { it.checkDate == null }.map {
        ExerciseLogEntity(
            planId = if (schemaVersion >= 8) checkNotNull(it.planId) else PlanEntity.LEGACY_ID,
            dayId = it.dayId,
            programExerciseId = it.programExerciseId,
            slot = it.slot,
            setsJson = it.setsJson,
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
            note = it.note.ifEmpty { settings.sessionNotes[it.id].orEmpty() }.trim().take(120),
            planId = it.planId,
            planName = it.planName,
            runId = if (schemaVersion >= 13) it.runId else null,
            plannedJson = if (schemaVersion >= 13) it.plannedJson else null,
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
                trackPosition = if (schemaVersion >= 13) set.trackPosition else null,
                plannedIndex = if (schemaVersion >= 13) set.plannedIndex else null,
            )
        }
    }

    val restoredPlans = if (schemaVersion >= 8) plans.mapIndexed { index, plan ->
        PlanEntity(
            id = plan.id,
            name = plan.name,
            origin = plan.origin,
            position = index,
            createdAt = plan.createdAt,
            archived = plan.archived,
            onToday = schemaVersion >= 15 && plan.onToday == true,
            recipeJson = if (schemaVersion >= 9) plan.recipeJson else null,
            suggestedDayId = if (schemaVersion >= 9) plan.suggestedDay else null,
        )
    } else emptyList()

    return FullSnapshot(
        answers = answers,
        unit = enumOrDefault(settings.weightUnit, WeightUnit.LB),
        wizardComplete = settings.wizardComplete,
        suggestedDay = if (schemaVersion >= 9) null else settings.suggestedDay,
        answersCarryRecipe = schemaVersion < 9,
        selectedPlanId = if (schemaVersion >= 8) settings.selectedPlanId else null,
        restSettings = RestSettings(
            enabled = settings.restTimerEnabled,
            overrides = settings.restOverrides(),
        ),
        keepScreenOn = settings.keepScreenOn,
        topSetHelperSeen = settings.topSetHelperSeen,
        supersetHelperSeen = settings.supersetHelperSeen,
        themePreference = enumOrDefault(settings.theme, ThemePreference.SYSTEM),
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
        plans = if (schemaVersion >= 15) restoredPlans else {
            val preferredId = preferredPlan(restoredPlans, settings.selectedPlanId)?.id
            restoredPlans.map { it.copy(onToday = it.id == preferredId) }
        },
        runs = if (schemaVersion >= 13) runs.map {
            WorkoutRunEntity(it.id, it.planId, it.dayId, it.dayTitle, it.planName, it.startedAt)
        } else emptyList(),
        runTracks = if (schemaVersion >= 13) runs.flatMap { run ->
            run.tracks.map {
                RunTrackEntity(it.id, run.id, it.position, it.programExerciseId, it.slot, it.exerciseId,
                    it.retired, it.plannedJson, it.setsJson, it.updatedAt)
            }
        } else emptyList(),
        days = dayEntities,
        exercises = exerciseEntities,
        logs = logEntities,
        legacyLogs = liveLogs.mapNotNull { log ->
            val date = log.checkDate ?: return@mapNotNull null
            LegacyLog(if (schemaVersion >= 8) checkNotNull(log.planId) else PlanEntity.LEGACY_ID,
                log.dayId, log.programExerciseId, log.slot, SetJson.decodeSets(log.setsJson), date, log.updatedAt)
        },
        sessions = sessionEntities,
        sessionSets = sessionSetEntities,
        cardioSessions = cardioSessions.map {
            CardioSessionEntity(
                id = it.id, dayId = it.dayId, mode = it.mode, hard = it.hard,
                label = it.label, startedAt = it.startedAt, completedAt = it.completedAt,
                seconds = it.seconds, stepsCompleted = it.stepsCompleted,
                planId = it.planId, planName = it.planName,
                activity = if (schemaVersion >= 12) it.activity else null,
            )
        },
        cardioSetResults = if (schemaVersion >= 12) cardioSessions.flatMap { session ->
            session.sets.mapIndexed { position, set ->
                CardioSetResultEntity(
                    id = set.id, cardioSessionId = session.id, position = position,
                    specJson = set.specJson, status = set.status, elapsedSeconds = set.elapsedSeconds,
                    metres = set.metres, distanceSource = set.distanceSource,
                )
            }
        } else emptyList(),
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

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
