package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.planner.BALANCE_DAYS_A_WEEK
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.PlanDraft
import cloud.trotter.log.strength.domain.planner.PlanReason
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.RecentNumber
import cloud.trotter.log.strength.domain.planner.SessionBudget
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SlotKey
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.text.UiText
import kotlin.math.ceil

/** The setup decisions in one place: steps, answer mapping and screen state.
 *  The planner does the planning; nothing here recomputes it. */
object SetupFlowStateBuilder {
    val minutesOptions = listOf(30, 45, 60, 75, 90)

    fun steps(draft: SetupDraft): List<SetupStep> = buildList {
        addAll(listOf(SetupStep.GOAL, SetupStep.SUB_GOAL, SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT))
        if (draft.goal != LiftGoal.GENERAL && Planner.mainChoices(draft.equipment).any { it.isNotEmpty() }) add(SetupStep.LIFTS)
        if (draft.experience != LiftExperience.NEW && currentMains(draft).any {
            ExerciseLibrary.get(it).tracking == TrackingType.WEIGHTED
        }) add(SetupStep.RECENT)
        add(SetupStep.PLAN)
    }

    fun currentStep(draft: SetupDraft): SetupStep = steps(draft).last { it.ordinal <= draft.step.ordinal }

    fun currentMains(draft: SetupDraft): List<String> = draft.mains ?: Planner.defaultMains(draft.equipment)

    fun answers(draft: SetupDraft): SetupAnswers = SetupAnswers(
        goal = draft.goal, days = draft.days, minutes = draft.minutes, equipment = draft.equipment, unit = draft.unit,
        steps = LoadSteps(stepOptionsLb(StepRow.BAR, draft.unit)[draft.barStep],
            stepOptionsLb(StepRow.DUMBBELL, draft.unit)[draft.dumbbellStep],
            stepOptionsLb(StepRow.STACK, draft.unit)[draft.stackStep]),
        experience = draft.experience, olderAdult = draft.olderAdult, mains = draft.mains,
        recent = if (draft.experience == LiftExperience.NEW) emptyMap() else draft.recent.mapNotNull { (id, row) ->
            val lb = row.weightLb
            val reps = row.reps
            if (id in currentMains(draft) && lb != null && lb.isFinite() && lb > 0 &&
                lb <= PlanRecipeJson.MAX_GOAL_LB && reps != null && reps in 1..10) {
                id to RecentNumber(lb, reps, row.effort)
            } else null
        }.toMap(), balanceDays = draft.balanceDays,
    )

    fun preset(equipment: Set<Equipment>): EquipmentPreset? = EquipmentPreset.entries.firstOrNull { it.equipment == equipment }

    fun stepOptionsLb(row: StepRow, unit: WeightUnit): List<Double> {
        val values = when (row) {
            StepRow.BAR -> if (unit == WeightUnit.LB) listOf(5.0, 2.5, 1.0) else listOf(2.5, 1.25, 0.5)
            StepRow.DUMBBELL -> if (unit == WeightUnit.LB) listOf(5.0, 2.5) else listOf(2.0, 1.0)
            StepRow.STACK -> if (unit == WeightUnit.LB) listOf(10.0, 5.0) else listOf(5.0, 2.5)
        }
        return values.map(unit::toLb)
    }

    fun draftFrom(answers: SetupAnswers): SetupDraft {
        fun index(row: StepRow, lb: Double) = stepOptionsLb(row, answers.unit).indexOf(lb).coerceAtLeast(0)
        val choices = Planner.mainChoices(answers.equipment, answers.excluded).flatten().toSet()
        return SetupDraft(goal = answers.goal, days = answers.days, minutes = answers.minutes,
            equipment = answers.equipment, unit = answers.unit, barStep = index(StepRow.BAR, answers.steps.barLb),
            dumbbellStep = index(StepRow.DUMBBELL, answers.steps.dumbbellLb), stackStep = index(StepRow.STACK, answers.steps.stackLb),
            experience = answers.experience, olderAdult = answers.olderAdult,
            mains = answers.mains?.takeIf { mains -> mains.all(choices::contains) },
            recent = answers.recent.mapValues { (_, row) -> RecentDraft(row.weightLb, row.reps, row.effort) },
            balanceDays = answers.balanceDays)
    }

    fun planPreview(draft: PlanDraft, answers: SetupAnswers): PlanPreviewUi {
        val days = draft.program.days
        if (days.none { it.kind == ProgramDayKind.STRENGTH || it.kind == ProgramDayKind.CARDIO }) return PlanPreviewUi.Empty
        val over = draft.reasons.filterIsInstance<PlanReason.OverBudget>()
        val strengthDays = days.count { it.kind == ProgramDayKind.STRENGTH }
        val rows = days.map { day ->
            val index = day.exercises.indexOfFirst { it.rule?.role == SlotRole.MAIN }
            val main = day.exercises.getOrNull(index)?.let { ExerciseLibrary.get(it.exerciseId) }
            val working = draft.working[SlotKey(day.id, index)].orEmpty()
            val load = if (main?.tracking == TrackingType.WEIGHTED) {
                (working.firstOrNull { it.kind == SetKind.TOP } ?: working.firstOrNull { it.kind == SetKind.WORK })?.weightLb
            } else null
            PlanDayRow(day.id, day.title, day.kind == ProgramDayKind.REST, main?.let { it.shortName ?: it.name }, load,
                day.exercises.sumOf { if (it.superset == null) 1 else 2 },
                ceil(SessionBudget.seconds(day, answers.goal) / 60.0).toInt(),
                over.firstOrNull { it.dayId == day.id }?.let { ceil(it.seconds / 60.0).toInt() })
        }
        val rules = days.flatMap { day -> day.exercises.flatMap { listOfNotNull(it.rule, it.superset?.rule) } }
        val why = buildList {
            draft.reasons.filterIsInstance<PlanReason.ShortSession>().forEach { reason ->
                add(when (reason.step) {
                    0 -> PlanWhy.Floor(reason.dayId, answers.minutes)
                    in 1..3 -> PlanWhy.Trimmed(reason.dayId, answers.minutes)
                    else -> PlanWhy.OneSet(reason.dayId, answers.minutes)
                })
            }
            over.forEach { add(PlanWhy.Over(it.dayId, ceil(it.seconds / 60.0).toInt())) }
            if (PlanReason.BalanceDays in draft.reasons) add(PlanWhy.BalanceDays)
            if (PlanReason.BalanceDaysOffered in draft.reasons) add(PlanWhy.BalanceOffered(strengthDays))
            if (rules.all { it.rir >= 1 }) add(PlanWhy.RepsLeft)
            if (rules.any { it.state.calibrating }) add(PlanWhy.Calibrating)
            draft.deloadEveryCycles?.let { add(PlanWhy.LightWeek(it)) }
            add(PlanWhy.Minutes)
        }
        val mains = answers.mains ?: Planner.defaultMains(answers.equipment)
        return PlanPreviewUi.Ready("", rows,
            droppable = if (mains.size < 2) emptyList() else days.filter { day -> over.any { it.dayId == day.id } }
                .flatMap { it.exercises }.filter { it.rule?.role == SlotRole.MAIN }.map { it.exerciseId }.distinct(),
            longerAvailable = answers.minutes < minutesOptions.last(), over = over.isNotEmpty(),
            balanceOffer = if (PlanReason.BalanceDaysOffered in draft.reasons) BALANCE_DAYS_A_WEEK - strengthDays else null,
            balanceAdded = if (PlanReason.BalanceDays in draft.reasons) days.count { day ->
                day.exercises.isNotEmpty() && day.exercises.all { it.rule?.role == SlotRole.BALANCE }
            } else null, why = why)
    }

    fun build(draft: SetupDraft, initialized: Boolean, firstRun: Boolean, plan: PlanPreviewUi,
        restore: SetupRestoreState, finishInFlight: Boolean, finishError: UiText?, isComplete: Boolean): SetupFlowUiState {
        val steps = steps(draft)
        val step = currentStep(draft)
        val gear = draft.equipment
        val rows = buildList {
            if (gear.any { it in setOf(Equipment.BARBELL, Equipment.TRAP_BAR, Equipment.EZ_BAR) }) add(StepRow.BAR to draft.barStep)
            if (Equipment.DUMBBELL in gear) add(StepRow.DUMBBELL to draft.dumbbellStep)
            if (Equipment.MACHINE in gear || Equipment.CABLE in gear) add(StepRow.STACK to draft.stackStep)
        }.map { (row, index) -> StepRowState(row, stepOptionsLb(row, draft.unit), index) }
        val mains = currentMains(draft)
        return SetupFlowUiState(initialized, firstRun, draft, step, steps.indexOf(step) + 1, steps.size,
            preset(gear), draft.chooseExactly || preset(gear) == null, rows,
            Planner.mainChoices(gear).mapIndexedNotNull { index, choices ->
                choices.takeIf { it.isNotEmpty() }?.let { LiftRowState(index, it, mains.firstOrNull(choices::contains)) }
            }, Planner.mainChoices(gear).flatMap { choices -> mains.filter(choices::contains) }.mapNotNull { id ->
                val entry = ExerciseLibrary.get(id)
                if (entry.tracking != TrackingType.WEIGHTED) null else {
                    val recent = draft.recent[id]
                    RecentRowState(id, entry.name, entry.perHand, recent?.weightLb, recent?.reps, recent?.effort ?: Effort.GOOD)
                }
            }, plan, restore, finishInFlight, finishError, isComplete)
    }
}
