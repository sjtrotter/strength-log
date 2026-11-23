package cloud.trotter.log.strength.domain.model

import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import kotlinx.serialization.Serializable

enum class MovementPattern {
    SQUAT_BILATERAL, SINGLE_LEG, HINGE, KNEE_FLEXION, KNEE_EXTENSION,
    H_PUSH, V_PUSH, H_PULL, V_PULL,
    SIDE_DELT, REAR_DELT, BICEPS, TRICEPS,
    CALF_GASTROC, CALF_SOLEUS,
    CORE_ANTI_EXT, CORE_ANTI_ROT, CORE_FLEX,
    CARDIO, PLYOMETRIC, BALANCE
}

enum class Equipment {
    BARBELL, TRAP_BAR, DUMBBELL, MACHINE, CABLE, BODYWEIGHT,
    BENCH, RACK, PULLUP_BAR, EZ_BAR, KETTLEBELL
}

enum class SetKind { RAMP, TOP, BACKOFF, WORK, EXTRA }

/** Stored by name; new constants go at the end. */
enum class SetStructure {
    STRAIGHT,
    RAMP_TOP_BACKOFF,
    RAMP_TOP;

    val hasTopSet: Boolean get() = this != STRAIGHT

    companion object {
        /** Data from before structures existed: a main lift ramps, everything else is straight. A main
         *  lift with a written load ramps around that load as its top set (owner decision, #415). */
        fun fromLegacy(isMain: Boolean): SetStructure = if (isMain) RAMP_TOP_BACKOFF else STRAIGHT
    }
}

data class LoggedSet(
    val weightLb: Double,
    val reps: Int,
    val kind: SetKind,
    val done: Boolean = false, // per-set checkmark, reset daily
    val seconds: Int = 0, // timed hold duration; otherwise 0
    // When the set was started and ticked, in wall-clock millis, as observed by
    // whichever surface logged it: the watch dial, or phone ticks when the
    // one-set workout screen is on. Null = not observed, including older ticks.
    // Active time and actual rest are *derived* from these, never stored.
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    /** The planned row this actual answers; run tracks only. */
    val plannedIndex: Int? = null,
    /** The newest watch stamp applied to this row. */
    val watchEditedAt: Long? = null,
    /** Reported reps in reserve; null = not reported, read as on target. */
    val rir: Int? = null,
    /** Null is side unknown; the single values are the weaker side ([sidesAgree]). */
    val sides: Sides? = null,
)

/** [rule] is the partner's own rule (`program_exercise.partnerRuleJson`); null until a partner path or conversion
 *  writes one. */
data class SupersetPartner(val exerciseId: String, val rule: SlotRule? = null) // refers into ExerciseLibrary

/** One exercise slot inside a day of the user's (editable) program. */
data class ProgramExercise(
    val exerciseId: String, // ExerciseLibrary key
    val structure: SetStructure = SetStructure.STRAIGHT,
    val targetSets: Int = 3,
    val superset: SupersetPartner? = null,
    val note: String = "",
    val prescription: Prescription? = null,
    val keepToPlan: Boolean = false,
    /** Null until conversion writes one (PR 2a). */
    val rule: SlotRule? = null,
)

/** Stored by name; new constants go at the end. */
enum class ProgramDayKind { STRENGTH, CARDIO, REST }

/** A day's Progress block stays parked while Plan is chosen; null uses the plan's block. */
data class CardioProgress(
    val keepToPlan: Boolean = false,
    val measure: ProgressMeasure? = null,
    val increasePercent: Int? = null,
    val working: CardioBlock? = null,
)

data class ProgramDay(
    val id: String, // "A", "B", ...
    val title: String,
    val emphasisLine: String, // muscle-angle emphasis, shown under title
    val exercises: List<ProgramExercise>,
    val kind: ProgramDayKind = ProgramDayKind.STRENGTH,
    val cardioBlock: CardioBlock? = null,
    val cardioProgress: CardioProgress = CardioProgress(),
    /** The day's cardio progression state; null on lift-only and converted days. */
    val cardioRule: CardioRule? = null,
) {
    /** A working block with different activity or set roles cannot replace the plan's block. */
    val cardioToRun: CardioBlock?
        get() {
            val plan = cardioBlock ?: return null
            if (cardioProgress.keepToPlan) return plan
            return cardioProgress.working?.takeIf {
                it.activity == plan.activity && it.sets.map { set -> set.role } == plan.sets.map { set -> set.role }
            } ?: plan
        }
}

data class Program(val days: List<ProgramDay>) {
    val strengthDays: List<ProgramDay> get() = days.filter { it.kind == ProgramDayKind.STRENGTH }
    val cardioDays: List<ProgramDay> get() = days.filter { it.kind == ProgramDayKind.CARDIO }

    /** C1/C2 marked CARDIO are generated extras, not training days in the rotation.
     *  Keep the legacy fallback for programs containing only those extras. */
    val rotationDays: List<ProgramDay> get() = days.filterNot {
        it.kind == ProgramDayKind.CARDIO && (it.id == "C1" || it.id == "C2")
    }.ifEmpty { days }

    /** The day to train: a Rest suggestion resolves to the training day after it. */
    fun dayToShow(suggested: String?): ProgramDay? {
        val day = days.firstOrNull { it.id == suggested }
            ?: rotationDays.firstOrNull { it.kind != ProgramDayKind.REST }
        return if (day?.kind == ProgramDayKind.REST) {
            val trainingId = Rotation.trainingDayFrom(this, day.id)
            days.firstOrNull { it.id == trainingId }
        } else day
    }

    companion object {
        /** History can outlive its program; the letter cycles through [DayAccentColors.count] accents. */
        fun accentIndex(dayId: String): Int {
            val letter = dayId.firstOrNull()?.uppercaseChar() ?: return 0
            return if (letter in 'A'..'Z') (letter - 'A') % DayAccentColors.count else 0
        }
    }
}

enum class CardioMode { OUTDOOR_RUN, TREADMILL, LOW_IMPACT, NONE }

@Serializable
data class CardioSuggestion(
    val label: String,
    val detail: String,
    val hard: Boolean,
    /** Snapshot-only acknowledgement facts. Program suggestions leave these null. */
    val loggedStartedAt: Long? = null,
    val loggedSeconds: Int? = null,
    /** Snapshot-only execution facts: without them a watch cannot build the
     *  plan or the delta, so a null mode suppresses the wrist offer (an old
     *  phone degrades to lifts-only rather than logging a guess). */
    val mode: String? = null,
    val fiveK: Boolean? = null,
)
