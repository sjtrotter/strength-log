package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.WorkingRows

import cloud.trotter.log.strength.data.PerformedDay
import cloud.trotter.log.strength.data.PerformedSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.sync.toWatch
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.ui.day.activityLabel
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.standards.GoalFormatter
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchCycleDay
import cloud.trotter.log.strength.domain.sync.WatchCycleExercise
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.day.ExercisePicker

/**
 * Formats the performed *suggested* day into the wire
 * [WatchSnapshot] (m5-wear.md wire protocol: only the suggested day rides the
 * wrist). [PerformedDay] supplies performed identities, goals and rows; this
 * projection supplies watch names, labels, rest durations and alternates.
 *
 * Weights stay canonical lb on the wire; the watch converts for display via
 * [unit]. The [revision] is stamped by the publisher, not derived here.
 */
object WatchSnapshotBuilder {

    /** How many alternates ride the wire per lift. A wrist is not a picker: three
     *  is reachable in three flicks and keeps "n OF 3" honest, and the list is
     *  already ranked best-first so a fourth candidate is one nobody would pick. */
    const val MAX_ALTERNATES = 3

    /**
     * Returns a snapshot even for an empty day, retaining its identity. Returns
     * null only when the suggested day is missing; the publisher then sends nothing.
     * [PerformedDay.equipment] is the lifter's gear, so the wrist is never offered a machine
     * they don't own — the same filter the phone's swap sheet applies.
     * [cardioOpening] is the block the day opens with ([DayScreenBuilder.cardioOpening]); it defaults to the stored block.
     */
    fun build(
        day: PerformedDay,
        unit: WeightUnit,
        revision: Long,
        restSettings: RestSettings = RestSettings(),
        loggedCardio: CardioSessionEntity? = null,
        cardioAckStamp: Long = 0L,
        planName: String? = null,
        cardioAckedStamps: List<Long> = emptyList(),
        runStartedAt: Long? = null,
        processedEditStamps: List<Long>? = null,
        liftGoal: LiftGoal = LiftGoal.STRENGTH,
        cardioOpening: CardioBlock? = day.day?.cardioToRun,
    ): WatchSnapshot? {
        val dayId = day.dayId
        val programDay = day.day ?: return null
        val watchDays = day.program.days.filter {
            it.kind == ProgramDayKind.STRENGTH || it.cardioBlock != null || it.exercises.isNotEmpty()
        }
        if (watchDays.none { it.id == dayId }) return null
        val exercises = day.slots.map { slot ->
            buildExercise(slot, day.catalog, unit, restSettings, day.equipment, liftGoal)
        }

        return WatchSnapshot(
            revision = revision,
            suggestedDayId = dayId,
            day = WatchDay(
                dayId = dayId,
                title = programDay.title,
                accentIndex = Program.accentIndex(dayId),
                exercises = exercises,
                emphasisLine = programDay.emphasisLine,
            ),
            unit = unit.name.lowercase(),
            cycle = watchDays.map { it.toCycleDay(day.catalog) },
            cardioBlock = cardioOpening?.toWatch(),
            cardioAckStamp = cardioAckStamp,
            cardioAckedStamps = cardioAckedStamps,
            planId = day.planId,
            planName = planName,
            runId = day.tracks.runId,
            runStartedAt = runStartedAt,
            retiredRoundsDone = day.tracks.retiredRoundsDone.takeIf { it > 0 },
            processedEditStamps = processedEditStamps,
            cardio = cardioOpening?.let { block ->
                CardioSuggestion(
                    label = activityLabel(block.activity),
                    detail = "",
                    hard = block.sets.any { it.hard },
                    loggedStartedAt = loggedCardio?.takeIf { it.dayId == dayId }?.startedAt,
                    loggedSeconds = loggedCardio?.takeIf { it.dayId == dayId }?.seconds,
                )
            },
        )
    }

    /**
     * A day as the watch's cycle ring reads it (dial v3 §1): its identity plus the
     * lifts it holds, from the *program*, not from logged slots — the watch previews
     * every day but only ever logs against the suggested one, so a preview needs no
     * sets and must not cost a query per day.
     */
    private fun ProgramDay.toCycleDay(catalog: ExerciseCatalog): WatchCycleDay = WatchCycleDay(
        dayId = id,
        title = title,
        exercises = exercises.map { pe ->
            WatchCycleExercise(
                name = catalog.find(pe.exerciseId)?.watchName ?: pe.exerciseId,
                setCount = pe.targetSets,
            )
        },
    )

    private fun buildExercise(
        slot: PerformedSlot,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
        restSettings: RestSettings,
        equipment: Set<Equipment>,
        liftGoal: LiftGoal,
    ): WatchExercise {
        val id = slot.slot.programExerciseId
        val performed = slot.main.performed
        val pe = slot.slot.exercise
        val entry = performed.entry
        val tracking = slot.main.tracking
        val target = slot.main.goal
        val prescribedTarget = performed.prescription?.target
        val workingRow = (slot.main.opening ?: slot.main.baseRows)?.let { rows ->
            WorkingRows.workingIndices(rows).firstOrNull()?.let { rows[it] }
        }
        val labelPrescription = performed.prescription?.let { prescription ->
            prescription.copy(target = when (val authored = prescription.target) {
                is PrescribedTarget.LoadReps -> workingRow?.let {
                    authored.copy(lb = it.weightLb.takeIf { load -> load > 0.0 } ?: authored.lb, reps = it.reps)
                } ?: authored
                is PrescribedTarget.Reps -> workingRow?.let { authored.copy(reps = it.reps) } ?: authored
                is PrescribedTarget.Hold -> workingRow?.let { authored.copy(seconds = it.seconds, addedLb = it.weightLb) } ?: authored
                is PrescribedTarget.RepsInTime -> authored
            })
        }
        val header = DayScreenBuilder.headerTarget(pe.copy(prescription = labelPrescription), target ?: GoalTarget.Weight(0.0, false), unit)
        val repWindow = prescribedTarget as? PrescribedTarget.RepsInTime
        val partner = slot.partner?.performed
        val partnerEntry = partner?.entry
        // The wire keeps authored-count blank rows for an unseedable slot.
        val main = slot.main.opening ?: slot.main.baseRows ?: List(pe.targetSets) { LoggedSet(0.0, 0, SetKind.WORK) }
        val ss = slot.partner?.sets.orEmpty()

        return WatchExercise(
            programExerciseId = id,
            // The slot a WatchExercise renders is always the day's main track; the
            // partner rides in ssSets. The watch sends deltas tagged "main"/"ss"
            // itself, so this field is currently informational only — kept (not
            // dropped) because the DTO shape is frozen; a future schemaVersion can
            // retire it (see report).
            slot = Slot.MAIN,
            name = entry?.watchName ?: performed.exerciseId,
            goal = slot.main.loadLb,
            goalLabel = if (performed.prescription != null) {
                DayScreenBuilder.headerTargetLabel(header)
            } else target?.let { GoalFormatter.label(it, unit) }.orEmpty(),
            targetDetail = DayScreenBuilder.headerTargetDetail(header),
            repWindowSeconds = repWindow?.seconds ?: 0,
            repAim = repWindow?.repAim,
            prescribed = performed.prescription != null,
            // Enum name lowercased; the watch parses it back to pick a per-type control.
            // Unknown/missing entry falls back to "weighted", which older
            // snapshots also assume.
            tracking = tracking.name.lowercase(),
            // Same encoding, for the superset partner (meaningless without one).
            ssTracking = (partner?.tracking ?: TrackingType.WEIGHTED).name.lowercase(),
            perHand = entry?.perHand == true,
            supersetPartnerName = partnerEntry?.watchName,
            // Main track carries the round's rest; the partner rows carry 0 so the
            // wire holds exactly one rest per round (the watch never tie-breaks).
            sets = main.map { it.toWatchSet(restAfterSecondsFor(it, tracking, restSettings, pe.rule, liftGoal)) },
            ssSets = ss.map { it.toWatchSet(restAfterSeconds = 0) },
            alternates = (listOfNotNull(pe.exerciseId.takeIf { it != performed.exerciseId }?.let { plannedId ->
                WatchAlternate(plannedId, catalog.find(plannedId)?.watchName ?: plannedId)
            }) + alternatesFor(performed.exerciseId, catalog, equipment)).distinctBy { it.exerciseId }.take(MAX_ALTERNATES),
            // The slot's content identity, which is what a swap is acked by — never
            // its display name (see WatchExercise.exerciseId).
            exerciseId = performed.exerciseId,
        )
    }

    /**
     * The replacements the watch may offer for a slot
     * (docs/briefs/wear-dial-v3.md §5): the same ranked, equipment-filtered
     * list the phone's own swap sheet shows, capped at [MAX_ALTERNATES].
     *
     * Public because [SetEditApplier] re-derives it to *check* an incoming swap
     * against what this phone would have prescribed. Prescribing the list and
     * enforcing it have to be the same function, or the wire could be talked into a
     * substitution the phone would never have offered.
     */
    fun alternatesFor(
        exerciseId: String,
        catalog: ExerciseCatalog,
        equipment: Set<Equipment>,
    ): List<WatchAlternate> {
        catalog.find(exerciseId) ?: return emptyList()
        return ExercisePicker
            .filter(catalog.substitutionsFor(exerciseId), query = "", equipment = equipment)
            .take(MAX_ALTERNATES)
            .map { WatchAlternate(exerciseId = it.id, name = it.watchName) }
    }

    /**
     * The name the watch shows — the colloquial one where the catalog has it,
     * because a band on a round face is an arc a few words wide and a lifter says
     * "deadlift", not "conventional deadlift" (curved-bands brief §2). Both stamp
     * points go through here; the phone and every export keep the full name, and
     * custom exercises have no short form and fall back to theirs.
     */
    private val ExerciseEntry.watchName: String get() = shortName ?: name

    /** The gated rest for one main-track set: 0 when the master toggle is off,
     *  else the one [RestPolicy] resolver (no rest is computed anywhere else). */
    private fun restAfterSecondsFor(set: LoggedSet, tracking: TrackingType, restSettings: RestSettings, rule: SlotRule?, goal: LiftGoal): Int =
        if (!restSettings.enabled) 0
        else RestPolicy.effectiveRestSeconds(set.kind, tracking, restSettings.overrides, rule, goal)

    private fun LoggedSet.toWatchSet(restAfterSeconds: Int): WatchSet =
        WatchSet(weightLb = weightLb, reps = reps, kind = kind.name, done = done, seconds = seconds, restAfterSeconds = restAfterSeconds)
}
