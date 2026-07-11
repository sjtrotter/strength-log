package io.github.sjtrotter.strengthlog.sync

import io.github.sjtrotter.strengthlog.data.LoggedSlot
import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.standards.GoalFormatter
import io.github.sjtrotter.strengthlog.domain.standards.GoalTarget
import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * Projects the phone's live state for the *suggested* day into the wire
 * [WatchSnapshot] (m5-wear.md wire protocol: only the suggested day rides the
 * wrist). Pure and JVM-testable — it mirrors the same name/goal/track derivations
 * [io.github.sjtrotter.strengthlog.ui.day.DayViewModel.buildCard] renders, so what
 * the watch shows is exactly what the phone shows, never a second computation of
 * derived sets (those stay phone-side, spec §9).
 *
 * Weights stay canonical lb on the wire; the watch converts for display via
 * [unit]. The [revision] is stamped by the publisher, not derived here.
 */
object WatchSnapshotBuilder {

    /**
     * Returns the snapshot, or null when there is nothing glanceable to publish
     * (no program, or the suggested day resolves to no real day). A null result
     * means "don't publish", not "publish empty".
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
    ): WatchSnapshot? {
        val dayId = suggestedDayId ?: return null
        val dayIndex = program.days.indexOfFirst { it.id == dayId }
        if (dayIndex < 0) return null
        val day = program.days[dayIndex]

        val logsByKey = logs.associateBy { it.programExerciseId to it.slot }
        val exercises = slots.map { slot ->
            buildExercise(slot, logsByKey, cfg, catalog, unit)
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
        )
    }

    private fun buildExercise(
        slot: ProgramSlot,
        logsByKey: Map<Pair<Long, String>, LoggedSlot>,
        cfg: LifterConfig,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
    ): WatchExercise {
        val pe = slot.exercise
        val id = slot.programExerciseId
        val entry = catalog.find(pe.exerciseId)
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
        val main = logsByKey[id to Slot.MAIN]?.sets.orEmpty()
        val ss = pe.superset?.let { logsByKey[id to Slot.SS]?.sets }.orEmpty()

        return WatchExercise(
            programExerciseId = id,
            // The slot a WatchExercise renders is always the day's main track; the
            // partner rides in ssSets. The watch sends deltas tagged "main"/"ss"
            // itself, so this field is currently informational only — kept (not
            // dropped) because the DTO shape is frozen; a future schemaVersion can
            // retire it (see report).
            slot = Slot.MAIN,
            name = entry?.name ?: pe.exerciseId,
            goal = goalLb,
            goalLabel = target?.let { GoalFormatter.label(it, unit) }.orEmpty(),
            perHand = entry?.perHand == true,
            supersetPartnerName = partnerEntry?.name,
            sets = main.map { it.toWatchSet() },
            ssSets = ss.map { it.toWatchSet() },
        )
    }

    private fun LoggedSet.toWatchSet(): WatchSet =
        WatchSet(weightLb = weightLb, reps = reps, kind = kind.name, done = done)
}
