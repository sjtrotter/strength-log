package cloud.trotter.log.strength.domain.model

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.serialization.Serializable

enum class ExperienceLevel { NOVICE, INTERMEDIATE, ADVANCED }

enum class GoalEmphasis { STRENGTH, BALANCED, PHYSIQUE }

enum class StandardLift { SQUAT, BENCH, DEADLIFT, OHP, INCLINE, ROW }

enum class MovementPattern {
    SQUAT_BILATERAL, SINGLE_LEG, HINGE, KNEE_FLEXION, KNEE_EXTENSION,
    H_PUSH, V_PUSH, H_PULL, V_PULL,
    SIDE_DELT, REAR_DELT, BICEPS, TRICEPS,
    CALF_GASTROC, CALF_SOLEUS,
    CORE_ANTI_EXT, CORE_ANTI_ROT, CORE_FLEX,
    CARDIO
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
        fun defaultFor(entry: ExerciseEntry): SetStructure =
            if (entry.tracking == TrackingType.WEIGHTED && entry.goal is GoalSource.Std) RAMP_TOP_BACKOFF else STRAIGHT

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
)

data class SupersetPartner(val exerciseId: String) // refers into ExerciseLibrary

/** One exercise slot inside a day of the user's (editable) program. */
data class ProgramExercise(
    val exerciseId: String, // ExerciseLibrary key
    val structure: SetStructure = SetStructure.STRAIGHT,
    val targetSets: Int = 3,
    val superset: SupersetPartner? = null,
    val note: String = "",
    val prescription: Prescription? = null,
    val keepToPlan: Boolean = false,
)

enum class ProgramDayKind { STRENGTH, CARDIO }

data class ProgramDay(
    val id: String, // "A", "B", ...
    val title: String,
    val emphasisLine: String, // muscle-angle emphasis, shown under title
    val exercises: List<ProgramExercise>,
    val kind: ProgramDayKind = ProgramDayKind.STRENGTH,
    val cardioBlock: CardioBlock? = null,
)

data class Program(val days: List<ProgramDay>) {
    val strengthDays: List<ProgramDay> get() = days.filter { it.kind == ProgramDayKind.STRENGTH }
    val cardioDays: List<ProgramDay> get() = days.filter { it.kind == ProgramDayKind.CARDIO }

    fun dayToShow(suggested: String?): ProgramDay? =
        days.firstOrNull { it.id == suggested } ?: strengthDays.firstOrNull() ?: days.firstOrNull()

    companion object {
        /** History can outlive its program; the letter cycles through [DayAccentColors.count] accents. */
        fun accentIndex(dayId: String): Int {
            val letter = dayId.firstOrNull()?.uppercaseChar() ?: return 0
            return if (letter in 'A'..'Z') (letter - 'A') % DayAccentColors.count else 0
        }
    }
}

data class LifterConfig(
    val bodyweightLb: Double = 235.0,
    val age: Int = 40,
    val level: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
    val emphasis: GoalEmphasis = GoalEmphasis.BALANCED,
    val weightUnit: WeightUnit = WeightUnit.LB,
) {
    constructor(
        bodyweightLb: Int,
        age: Int = LifterConfig().age,
        level: ExperienceLevel = LifterConfig().level,
        emphasis: GoalEmphasis = LifterConfig().emphasis,
        weightUnit: WeightUnit = LifterConfig().weightUnit,
    ) : this(bodyweightLb.toDouble(), age, level, emphasis, weightUnit)
}

enum class CardioMode { OUTDOOR_RUN, TREADMILL, LOW_IMPACT, NONE }

enum class CardioPlacement { FINISHERS, SEPARATE_DAYS, BOTH, NONE }

data class CardioPrefs(
    val mode: CardioMode = CardioMode.OUTDOOR_RUN,
    val placement: CardioPlacement = CardioPlacement.FINISHERS,
    val fiveKGoal: Boolean = true, // loose target, shapes suggestion copy
)

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
