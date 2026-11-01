package cloud.trotter.log.strength.domain.model

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
    val isMain: Boolean = false,
    val targetSets: Int = 3,
    val repSchemeLabel: String = "", // display only, e.g. "8-12"
    val hasWarmupHint: Boolean = false, // "+1 warm-up set" badge
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
}

data class LifterConfig(
    val bodyweightLb: Int = 235,
    val age: Int = 40,
    val level: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
    val emphasis: GoalEmphasis = GoalEmphasis.BALANCED,
)

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
