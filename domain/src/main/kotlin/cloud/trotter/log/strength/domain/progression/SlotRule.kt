package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetStructure

enum class SlotRole { MAIN, SECONDARY, ACCESSORY, ISOLATION, CORE, BALANCE, PLYO }

/** The plan's "Defaults for" goal and the lift branches' goal. Stored by name; new constants go at the end. */
enum class LiftGoal { STRENGTH, MUSCLE, GENERAL }

enum class NextTimeMode { AUTO, ASK }

sealed interface RepTarget {
    /** The reps every load-anchor row is prescribed this time. */
    val reps: Int
    data class Fixed(override val reps: Int) : RepTarget
    data class Range(val lo: Int, val hi: Int, val current: Int) : RepTarget {
        init { require(lo in 1..hi && current in lo..hi) }
        override val reps: Int get() = current
    }
}

/** Progression state; Finish also grants matching slots shared success credit. */
data class SlotState(
    val calibrating: Boolean = false,
    /** Successes toward cadence; consuming a pending increase starts a fresh cadence. */
    val successStreak: Int = 0,
    val repMissStreak: Int = 0,
    val effortMissStreak: Int = 0,
    /** Epoch days of the last two stalls, oldest first. */
    val resetEpochDays: List<Long> = emptyList(),
    val exposures: Int = 0,
    /** Shared credit earned an increase to apply on this slot's next successful Finish. */
    val pendingIncrease: Boolean = false,
)

data class SlotRule(
    val role: SlotRole,
    val target: RepTarget,
    /** Target reps in reserve on working sets. */
    val rir: Int,
    val incrementPct: Double,
    /** 1..3 successes between increases. */
    val everyExposures: Int,
    /** 0 means misses never lower the load. */
    val dropPct: Double = 0.05,
    val scheme: Scheme,
    /** Set only by a user edit in Lift options. */
    val restSeconds: Int? = null,
    /** Null follows the plan. */
    val nextTime: NextTimeMode? = null,
    val state: SlotState = SlotState(),
) {
    companion object {
        /** Evidence defaults for a slot the user adds by hand or that conversion creates. [role] is the caller's guess
         *  (ramped or main-capable → MAIN, else ACCESSORY); [structure] picks Scheme.PROTOTYPE for ramped slots and
         *  Scheme.STRENGTH otherwise; [reps] is the first working row's count (the TOP for a ramped slot), or null for
         *  the goal's default; [pattern] decides the body half. Rir follows the §3.1.1 table for [goal]; increments
         *  by body half at the A-year-or-more values (2.5 % lower body, 1.25 % upper and small); everyExposures 1;
         *  dropPct 0.05; no rest override. */
        fun defaults(role: SlotRole, goal: LiftGoal, structure: SetStructure, reps: Int?, pattern: MovementPattern): SlotRule =
            SlotRule(
                role = role,
                target = RepTarget.Fixed(reps ?: defaultReps(role, goal)),
                rir = defaultRir(role, goal),
                incrementPct = if (pattern in lowerBody) 0.025 else 0.0125,
                everyExposures = 1,
                scheme = if (structure.hasTopSet) Scheme.PROTOTYPE else Scheme.STRENGTH,
            )
    }
}

private fun defaultRir(role: SlotRole, goal: LiftGoal): Int = when (goal) {
    LiftGoal.STRENGTH -> if (role == SlotRole.SECONDARY) 3 else 2
    LiftGoal.MUSCLE -> if (role == SlotRole.ISOLATION) 1 else 2
    LiftGoal.GENERAL -> if (role == SlotRole.MAIN) 3 else 2
}

private fun defaultReps(role: SlotRole, goal: LiftGoal): Int = when (goal) {
    LiftGoal.STRENGTH -> if (role == SlotRole.MAIN || role == SlotRole.SECONDARY) 5 else 10
    LiftGoal.MUSCLE -> if (role == SlotRole.MAIN || role == SlotRole.SECONDARY) 8 else 12
    LiftGoal.GENERAL -> if (role == SlotRole.MAIN) 10 else 12
}

private val lowerBody = setOf(
    MovementPattern.SQUAT_BILATERAL, MovementPattern.SINGLE_LEG, MovementPattern.HINGE,
    MovementPattern.KNEE_FLEXION, MovementPattern.KNEE_EXTENSION, MovementPattern.CALF_GASTROC, MovementPattern.CALF_SOLEUS,
)
