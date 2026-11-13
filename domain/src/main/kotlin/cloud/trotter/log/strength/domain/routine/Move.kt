package cloud.trotter.log.strength.domain.routine

import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.library.Muscle.*
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.routine.MoveDose.Reps
import cloud.trotter.log.strength.domain.routine.MoveDose.Time
import cloud.trotter.log.strength.domain.routine.MoveKind.*
import cloud.trotter.log.strength.domain.routine.RoutineDay.*

/** Which kind of day a move suits. A day with any lift is LIFT; a cardio-only day is RUN for the run and walk
 *  families, else CARDIO. */
enum class RoutineDay { LIFT, RUN, CARDIO }

enum class MoveKind { GENERAL, DYNAMIC, STRETCH }

/** A move's amount. [perSide] says "each side" and, for a time, doubles the time it takes. */
sealed interface MoveDose {
    val perSide: Boolean
    data class Reps(val count: Int, override val perSide: Boolean = false) : MoveDose
    data class Time(val seconds: Int, override val perSide: Boolean = false) : MoveDose
}

/**
 * The warm-up and cool-down catalog (#574). [equipment] empty means none. Declaration order is the selection
 * tie-break, so reordering entries changes which moves a day gets. Names and cues are app copy in strings.xml.
 */
enum class Move(val kind: MoveKind, val muscles: Set<Muscle>, val equipment: Set<Equipment>,
                val dose: MoveDose, val days: Set<RoutineDay>) {
    BIKE_OR_ROWER(GENERAL, emptySet(), setOf(Equipment.MACHINE), Time(120), setOf(LIFT)),
    JACKS_OR_MARCH(GENERAL, emptySet(), emptySet(), Time(120), setOf(LIFT)),
    EASY_JOG_OR_WALK(GENERAL, emptySet(), emptySet(), Time(120), setOf(RUN)),
    EASY_CARDIO(GENERAL, emptySet(), emptySet(), Time(120), setOf(CARDIO)),
    LEG_SWINGS(DYNAMIC, setOf(HAMSTRINGS, GLUTES), emptySet(), Reps(10, perSide = true), setOf(LIFT, RUN, CARDIO)),
    BODYWEIGHT_SQUATS(DYNAMIC, setOf(QUADS, GLUTES), emptySet(), Reps(10), setOf(LIFT, CARDIO)),
    HIP_HINGES(DYNAMIC, setOf(HAMSTRINGS, GLUTES), emptySet(), Reps(10), setOf(LIFT, CARDIO)),
    HIGH_KNEES(DYNAMIC, setOf(QUADS, CALVES), emptySet(), Time(20), setOf(RUN)),
    BUTT_KICKS(DYNAMIC, setOf(HAMSTRINGS, QUADS), emptySet(), Time(20), setOf(RUN)),
    REVERSE_LUNGES(DYNAMIC, setOf(QUADS, GLUTES), emptySet(), Reps(6, perSide = true), setOf(LIFT, RUN, CARDIO)),
    CALF_RAISES(DYNAMIC, setOf(CALVES), emptySet(), Reps(15), setOf(LIFT, RUN, CARDIO)),
    Y_T_RAISES(DYNAMIC, setOf(REAR_DELT, SIDE_DELT, UPPER_BACK), emptySet(), Reps(8), setOf(LIFT, RUN, CARDIO)),
    ARM_CIRCLES(DYNAMIC, setOf(FRONT_DELT, SIDE_DELT, REAR_DELT), emptySet(), Reps(20), setOf(LIFT, RUN, CARDIO)),
    PUSH_UPS(DYNAMIC, setOf(CHEST, TRICEPS, FRONT_DELT), emptySet(), Reps(8), setOf(LIFT)),
    ARM_SWINGS(DYNAMIC, setOf(CHEST, BICEPS), emptySet(), Reps(10), setOf(LIFT, RUN, CARDIO)),
    SCAP_PUSH_UPS(DYNAMIC, setOf(UPPER_BACK, CHEST), emptySet(), Reps(10), setOf(LIFT)),
    UPPER_BACK_ROTATIONS(DYNAMIC, setOf(UPPER_BACK, LATS), emptySet(), Reps(6, perSide = true), setOf(LIFT, RUN, CARDIO)),
    CAT_COW(DYNAMIC, setOf(CORE, UPPER_BACK), emptySet(), Reps(8), setOf(LIFT, RUN, CARDIO)),
    DEAD_BUGS(DYNAMIC, setOf(CORE), emptySet(), Reps(6, perSide = true), setOf(LIFT)),
    QUAD_STRETCH(STRETCH, setOf(QUADS), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    HIP_FLEXOR_STRETCH(STRETCH, setOf(QUADS), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    HAMSTRING_STRETCH(STRETCH, setOf(HAMSTRINGS), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    FIGURE_FOUR(STRETCH, setOf(GLUTES), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    CALF_STRETCH(STRETCH, setOf(CALVES), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    DOORWAY_CHEST(STRETCH, setOf(CHEST, FRONT_DELT), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    WALL_BICEPS(STRETCH, setOf(BICEPS, FRONT_DELT), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    CROSS_BODY(STRETCH, setOf(REAR_DELT, SIDE_DELT, UPPER_BACK), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    HANG(STRETCH, setOf(LATS, UPPER_BACK), setOf(Equipment.PULLUP_BAR), Time(30), setOf(LIFT, RUN, CARDIO)),
    CHILDS_POSE(STRETCH, setOf(LATS, UPPER_BACK), emptySet(), Time(30), setOf(LIFT, RUN, CARDIO)),
    OVERHEAD_TRICEPS(STRETCH, setOf(TRICEPS, LATS), emptySet(), Time(30, perSide = true), setOf(LIFT, RUN, CARDIO)),
    COBRA(STRETCH, setOf(CORE), emptySet(), Time(30), setOf(LIFT, RUN, CARDIO)),
}
