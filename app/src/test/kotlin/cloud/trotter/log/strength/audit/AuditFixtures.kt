package cloud.trotter.log.strength.audit

import androidx.compose.runtime.Composable
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayActions
import cloud.trotter.log.strength.ui.day.DayEditSlotState
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt
import cloud.trotter.log.strength.ui.day.workoutActions
import cloud.trotter.log.strength.ui.day.workoutEditActions
import cloud.trotter.log.strength.ui.day.workoutLiftSheetFixture
import cloud.trotter.log.strength.ui.log.JournalBuilder
import cloud.trotter.log.strength.ui.log.JournalUiState
import cloud.trotter.log.strength.ui.log.LogScreenBuilder
import cloud.trotter.log.strength.ui.log.LogUiState
import cloud.trotter.log.strength.ui.log.logSessionFixture
import cloud.trotter.log.strength.ui.preview.PreviewLift
import cloud.trotter.log.strength.ui.preview.PreviewPreviousResult
import cloud.trotter.log.strength.ui.preview.PreviewUiState
import cloud.trotter.log.strength.ui.today.TodayUiState
import cloud.trotter.log.strength.ui.today.todayFirstFixture
import cloud.trotter.log.strength.ui.today.todaySecondFixture
import cloud.trotter.log.strength.ui.wizard.WizardStateBuilder
import cloud.trotter.log.strength.ui.wizard.WizardStep
import java.time.LocalDate
import java.time.ZoneOffset

internal const val LONG_PLAN = "Progressive strength and endurance cycle"
internal const val LONG_DAY = "Posterior chain strength and conditioning"
internal val longestExercise get() = ExerciseCatalog.CODE_ONLY.entries.maxBy { it.name.length }

internal fun auditTodayFixture() = TodayUiState(
    hasProgram = true, upNext = listOf(todayFirstFixture()),
    otherDays = listOf(todaySecondFixture()), fact = "Oct 28",
)

internal fun auditPreviewFixture(day: DayUiState = workoutLiftSheetFixture()) = PreviewUiState(
    loading = false, selection = DaySelection(1, day.viewDayId.orEmpty()),
    planName = "Four-day strength", dayIndex = day.dayIndex, title = day.dayTitle,
    emphasis = day.emphasisLine,
    lifts = day.exercises.map { card ->
        val row = card.rows.first { it.kind != SetKind.RAMP }
        PreviewLift(
            listOfNotNull(card.title, card.partnerTitle).joinToString(" + "),
            listOfNotNull(
                SetFormatter.display(card.tracking, day.unit.toLb(row.weightDisplay), row.reps, row.seconds, day.unit),
                row.partner?.let { partner -> SetFormatter.display(requireNotNull(card.partnerTracking),
                    day.unit.toLb(partner.weightDisplay), partner.reps, partner.seconds, day.unit) },
            ).joinToString(" + "),
            listOf(PreviewPreviousResult(card.lastTimeDisplay?.let {
                SetFormatter.display(card.tracking, day.unit.toLb(row.weightDisplay), row.reps, row.seconds, day.unit)
            }, exerciseName = card.title)) + if (card.partnerExerciseId != null)
                listOf(PreviewPreviousResult(null, exerciseName = card.partnerTitle.orEmpty())) else emptyList(),
        )
    },
)

internal fun auditEditFixture(state: DayUiState) = DayEditUiState(
    dayId = state.viewDayId, planId = state.planId, runOpen = state.runOpen,
    cardioBlock = state.cardio?.block,
    canRemove = cloud.trotter.log.strength.ui.day.DayEditRules.canRemove(state.exercises.size, state.cardio?.block != null),
    slots = state.exercises.map { card ->
        DayEditSlotState(
            card.programExerciseId, card.position, card.exerciseId, card.title,
            ExerciseCatalog.CODE_ONLY.find(card.exerciseId)?.pattern, card.isSuperset,
            partnerExerciseId = card.partnerExerciseId, partnerTitle = card.partnerTitle,
            targetSets = card.rows.size, canMoveUp = card.position > 0,
            canMoveDown = card.position < state.exercises.lastIndex,
            rows = card.rows.map { LoggedSet(state.unit.toLb(it.weightDisplay), it.reps, it.kind, seconds = it.seconds) },
        )
    },
)

@Composable
internal fun AuditDay(
    state: DayUiState,
    edit: DayEditUiState = auditEditFixture(state),
    actions: DayActions = workoutActions(),
    receipt: WorkoutReceipt? = null,
) {
    DayScreen(state, actions, edit, workoutEditActions(), receipt = receipt, onBack = {}, nowMillis = { 1_000L })
}

internal fun auditHistoryFixture(): LogUiState {
    val today = LocalDate.of(2026, 11, 3)
    val dates = List(6) { today.minusDays((it * 3).toLong()) }
    val sessions = dates.mapIndexed { index, date ->
        val completedAt = date.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        logSessionFixture(false).copy(
            sessionId = index + 1L, completedAt = completedAt,
            dateDisplay = LogScreenBuilder.dateDisplay(completedAt), dayTitle = "Squat focus", planName = "Four-day strength", planId = 1,
        )
    }
    val main = JournalBuilder.MainLift("bb_back_squat", "Barbell Back Squat", 0, 235.0)
    return LogUiState(
        sessions = sessions,
        journal = JournalUiState(
            trajectories = JournalBuilder.trajectories(listOf(main), sessions.reversed().mapIndexed { index, item ->
                TopSetRow(item.sessionId, item.completedAt, main.exerciseId, 210.0 + index * 5)
            }, WeightUnit.LB, ZoneOffset.UTC),
            volume = JournalBuilder.volume(sessions.map { SessionTonnageRow(it.sessionId, it.completedAt, 12_400.0) },
                WeightUnit.LB, today, ZoneOffset.UTC),
            calendar = JournalBuilder.calendar(sessions.map { SessionSummaryRow(
                WorkoutSessionEntity(id = it.sessionId, dayId = it.dayLetter, dayTitle = it.dayTitle,
                    startedAt = null, completedAt = it.completedAt, bodyweightLb = 185.0), it.setCount,
            ) }, 0, today, ZoneOffset.UTC),
        ),
    )
}

internal fun auditWizardFixture(step: WizardStep) = WizardAnswers(
    cardioFirst = if (step in listOf(WizardStep.CARDIO_ACTIVITY, WizardStep.CARDIO_START,
            WizardStep.CARDIO_SESSIONS, WizardStep.ALSO_LIFT)) CardioFirst() else null,
).let { answers ->
    WizardStateBuilder.buildUiState(WizardStateBuilder.steps(answers).indexOf(step), answers, false,
        previewProgram = ProgramGenerator.generate(answers))
}

/** The selected real entry also supplies tracking and equipment, not just a long label. */
internal fun auditLongWorkoutFixture(): DayUiState {
    val entry = longestExercise
    val state = workoutLiftSheetFixture()
    return state.copy(dayTitle = LONG_DAY, exercises = state.exercises.map { card -> card.copy(
        title = entry.name, exerciseId = entry.id, tracking = entry.tracking, equipment = entry.equipment,
        perHand = entry.perHand,
    ) })
}
