package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.LoggedSlot
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.standards.GoalCalculator
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
 * Projects the phone's live state for the *suggested* day into the wire
 * [WatchSnapshot] (m5-wear.md wire protocol: only the suggested day rides the
 * wrist). Pure and JVM-testable — it mirrors the same name/goal/track derivations
 * [cloud.trotter.log.strength.ui.day.DayViewModel.buildCard] renders, so what
 * the watch shows is exactly what the phone shows, never a second computation of
 * derived sets (those stay phone-side, spec §9).
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
     * Returns the snapshot, or null when there is nothing glanceable to publish
     * (no program, or the suggested day resolves to no real day). A null result
     * means "don't publish", not "publish empty".
     * [equipment] is the lifter's gear, so the wrist is never offered a machine
     * they don't own — the same filter the phone's swap sheet applies.
     */
    fun build(
        program: Program,
        suggestedDayId: String?,
        slots: List<ProgramSlot>,
        logs: List<LoggedSlot>,
        cfg: LifterConfig,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
        revision: Long,
        restSettings: RestSettings = RestSettings(),
        equipment: Set<Equipment> = Equipment.entries.toSet(),
    ): WatchSnapshot? {
        val dayId = suggestedDayId ?: return null
        val dayIndex = program.days.indexOfFirst { it.id == dayId }
        if (dayIndex < 0) return null
        val day = program.days[dayIndex]

        val logsByKey = logs.associateBy { it.programExerciseId to it.slot }
        val previewSeeds = DayScreenBuilder.seedPlan(
            slots = slots,
            existing = logsByKey.mapValues { it.value.sets.size },
            cfg = cfg,
            catalog = catalog,
        ).associateBy { it.programExerciseId to it.slot }
        val exercises = slots.map { slot ->
            buildExercise(slot, logsByKey, previewSeeds, cfg, catalog, unit, restSettings, equipment)
        }

        return WatchSnapshot(
            revision = revision,
            suggestedDayId = dayId,
            day = WatchDay(
                dayId = dayId,
                title = day.title,
                accentIndex = dayIndex,
                exercises = exercises,
                emphasisLine = day.emphasisLine,
            ),
            unit = unit.name.lowercase(),
            cycle = program.days.map { it.toCycleDay(catalog) },
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
        slot: ProgramSlot,
        logsByKey: Map<Pair<Long, String>, LoggedSlot>,
        previewSeeds: Map<Pair<Long, String>, DayScreenBuilder.SeedWrite>,
        cfg: LifterConfig,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
        restSettings: RestSettings,
        equipment: Set<Equipment>,
    ): WatchExercise {
        val pe = slot.exercise
        val id = slot.programExerciseId
        val entry = catalog.find(pe.exerciseId)
        val tracking = entry?.tracking ?: TrackingType.WEIGHTED
        // Routed through targetFor so a reclassified REPS/TIMED slot never hits
        // goalFor's error() branch. The numeric goal stays canonical lb (0 for
        // rep/time targets, which carry no weight); the watch still renders from
        // it for weighted slots. goalLabel is the type-safe display groundwork.
        val target = entry?.let { GoalCalculator.targetFor(it, cfg) }
        val goalLb = when (target) {
            is GoalTarget.Weight -> target.lb
            is GoalTarget.Time -> target.addedLb
            is GoalTarget.Reps, null -> 0.0
        }
        val partnerEntry = pe.superset?.let { catalog.find(it.exerciseId) }
        val main = logsByKey[id to Slot.MAIN]?.sets
            ?: previewSeeds[id to Slot.MAIN]?.sets
            ?: DayScreenBuilder.previewMainSets(slot, cfg, catalog)
        val ss = pe.superset?.let {
            logsByKey[id to Slot.SS]?.sets ?: previewSeeds[id to Slot.SS]?.sets
        }.orEmpty()

        return WatchExercise(
            programExerciseId = id,
            // The slot a WatchExercise renders is always the day's main track; the
            // partner rides in ssSets. The watch sends deltas tagged "main"/"ss"
            // itself, so this field is currently informational only — kept (not
            // dropped) because the DTO shape is frozen; a future schemaVersion can
            // retire it (see report).
            slot = Slot.MAIN,
            name = entry?.watchName ?: pe.exerciseId,
            goal = goalLb,
            goalLabel = target?.let { GoalFormatter.label(it, unit) }.orEmpty(),
            // Enum name lowercased; the watch parses it back to pick a per-type control.
            // Unknown/missing entry falls back to "weighted" — the only pre-P5 behavior.
            tracking = tracking.name.lowercase(),
            // Same encoding, for the superset partner (meaningless without one).
            ssTracking = (partnerEntry?.tracking ?: TrackingType.WEIGHTED).name.lowercase(),
            perHand = entry?.perHand == true,
            supersetPartnerName = partnerEntry?.watchName,
            // Main track carries the round's rest; the partner rows carry 0 so the
            // wire holds exactly one rest per round (the watch never tie-breaks).
            sets = main.map { it.toWatchSet(restAfterSecondsFor(it, tracking, restSettings)) },
            ssSets = ss.map { it.toWatchSet(restAfterSeconds = 0) },
            alternates = alternatesFor(pe.exerciseId, catalog, equipment),
            // The slot's content identity, which is what a swap is acked by — never
            // its display name (see WatchExercise.exerciseId).
            exerciseId = pe.exerciseId,
        )
    }

    /**
     * The replacements the watch may offer for a slot (#90): the same ranked,
     * equipment-filtered list the phone's own swap sheet shows, capped at
     * [MAX_ALTERNATES].
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
    private fun restAfterSecondsFor(set: LoggedSet, tracking: TrackingType, restSettings: RestSettings): Int =
        if (!restSettings.enabled) 0
        else RestPolicy.effectiveRestSeconds(set.kind, tracking, restSettings.overrides)

    private fun LoggedSet.toWatchSet(restAfterSeconds: Int): WatchSet =
        WatchSet(weightLb = weightLb, reps = reps, kind = kind.name, done = done, seconds = seconds, restAfterSeconds = restAfterSeconds)
}
