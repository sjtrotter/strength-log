package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.Equipment.BARBELL
import cloud.trotter.log.strength.domain.model.Equipment.BENCH
import cloud.trotter.log.strength.domain.model.Equipment.BODYWEIGHT
import cloud.trotter.log.strength.domain.model.Equipment.CABLE
import cloud.trotter.log.strength.domain.model.Equipment.DUMBBELL
import cloud.trotter.log.strength.domain.model.Equipment.KETTLEBELL
import cloud.trotter.log.strength.domain.model.Equipment.MACHINE
import cloud.trotter.log.strength.domain.model.Equipment.RACK
import cloud.trotter.log.strength.domain.model.Equipment.TRAP_BAR
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.MovementPattern.HINGE
import cloud.trotter.log.strength.domain.model.MovementPattern.H_PULL
import cloud.trotter.log.strength.domain.model.MovementPattern.H_PUSH
import cloud.trotter.log.strength.domain.model.MovementPattern.SQUAT_BILATERAL
import cloud.trotter.log.strength.domain.model.MovementPattern.V_PUSH

/** The old wizard's split answer. Read only from v1 recipes, pre-plan DataStore keys and pre-v9 backups. */
enum class V1Split { FULL_BODY, UPPER_LOWER, PPL, PPLUL }

/** The old wizard's main-lift anchors. Read only from v1 recipes, pre-plan DataStore keys and pre-v9 backups. */
enum class V1Anchors { PROTOTYPE, BIG_4, FIVE_THREE_ONE }

/** The old wizard's deadlift anchor. Read only from v1 recipes, pre-plan DataStore keys and pre-v9 backups. */
enum class V1Deadlift { TRAP_BAR, CONVENTIONAL, SUMO }

/**
 * The old wizard's main lifts for its answers, in day order: its anchor rule frozen at 6d with its own copy of the
 * library's weighted entries as they were then (design §4.4). Conversion intersects these with a plan's own slots,
 * so an old backup converts the same on every later build. The tables are history, not catalog data: never edit them.
 */
internal object V1Mains {
    private class Lift(val id: String, val needs: Set<Equipment>)

    private fun Lift.availableWith(equipment: Set<Equipment>) = needs.all { it in equipment }

    private val RANKED: Map<MovementPattern, List<Lift>> = mapOf(
        SQUAT_BILATERAL to listOf(
            Lift("bb_back_squat", setOf(BARBELL, RACK)),
            Lift("hack_squat", setOf(MACHINE)),
            Lift("leg_press", setOf(MACHINE)),
            Lift("goblet_squat", setOf(DUMBBELL)),
            Lift("front_squat", setOf(BARBELL, RACK)),
            Lift("smith_squat", setOf(MACHINE)),
            Lift("ssb_squat", setOf(BARBELL, RACK)),
            Lift("box_squat", setOf(BARBELL, RACK, BENCH)),
            Lift("belt_squat", setOf(MACHINE)),
            Lift("pendulum_squat", setOf(MACHINE)),
            Lift("kb_goblet_squat", setOf(KETTLEBELL)),
            Lift("kb_front_squat", setOf(KETTLEBELL)),
            Lift("zercher_squat", setOf(BARBELL, RACK)),
        ),
        HINGE to listOf(
            Lift("trap_dl", setOf(TRAP_BAR)),
            Lift("conv_dl", setOf(BARBELL)),
            Lift("sumo_dl", setOf(BARBELL)),
            Lift("rdl", setOf(BARBELL)),
            Lift("stiff_dl", setOf(BARBELL)),
            Lift("good_morning", setOf(BARBELL, RACK)),
            Lift("db_rdl", setOf(DUMBBELL)),
            Lift("kb_rdl", setOf(KETTLEBELL)),
            Lift("sl_rdl", setOf(DUMBBELL)),
            Lift("hip_thrust", setOf(BARBELL, BENCH)),
            Lift("glute_bridge", setOf(BARBELL)),
            Lift("machine_hip_thrust", setOf(MACHINE)),
            Lift("cable_pullthrough", setOf(CABLE)),
            Lift("kb_swing", setOf(KETTLEBELL)),
            Lift("kb_deadlift", setOf(KETTLEBELL)),
            Lift("back_ext_45", setOf(MACHINE)),
            Lift("reverse_hyper", setOf(MACHINE)),
            Lift("block_pull", setOf(BARBELL)),
            Lift("smith_rdl", setOf(MACHINE)),
        ),
        H_PUSH to listOf(
            Lift("bb_bench", setOf(BARBELL, BENCH, RACK)),
            Lift("db_bench", setOf(DUMBBELL, BENCH)),
            Lift("incline_db", setOf(DUMBBELL, BENCH)),
            Lift("incline_bb", setOf(BARBELL, BENCH, RACK)),
            Lift("machine_chest", setOf(MACHINE)),
            Lift("pec_deck", setOf(MACHINE)),
            Lift("dips", setOf(BODYWEIGHT)),
            Lift("smith_bench", setOf(MACHINE, BENCH)),
            Lift("decline_bb", setOf(BARBELL, BENCH, RACK)),
            Lift("incline_machine", setOf(MACHINE)),
            Lift("smith_incline", setOf(MACHINE, BENCH)),
            Lift("floor_press", setOf(BARBELL, RACK)),
            Lift("kb_floor_press", setOf(KETTLEBELL)),
            Lift("db_fly", setOf(DUMBBELL, BENCH)),
            Lift("cable_fly", setOf(CABLE)),
            Lift("low_cable_fly", setOf(CABLE)),
            Lift("weighted_pushup", setOf(BODYWEIGHT)),
        ),
        H_PULL to listOf(
            Lift("bb_row", setOf(BARBELL)),
            Lift("cs_row", setOf(MACHINE)),
            Lift("cable_row", setOf(CABLE)),
            Lift("db_row", setOf(DUMBBELL, BENCH)),
            Lift("pendlay_row", setOf(BARBELL)),
            Lift("tbar_row", setOf(BARBELL)),
            Lift("db_cs_row", setOf(DUMBBELL, BENCH)),
            Lift("machine_row", setOf(MACHINE)),
            Lift("seal_row", setOf(BARBELL, BENCH)),
            Lift("single_cable_row", setOf(CABLE)),
            Lift("kb_row", setOf(KETTLEBELL)),
        ),
        V_PUSH to listOf(
            Lift("ohp", setOf(BARBELL, RACK)),
            Lift("db_shoulder", setOf(DUMBBELL, BENCH)),
            Lift("machine_shoulder", setOf(MACHINE)),
            Lift("landmine_press", setOf(BARBELL)),
            Lift("seated_bb_ohp", setOf(BARBELL, BENCH, RACK)),
            Lift("push_press", setOf(BARBELL, RACK)),
            Lift("standing_db_press", setOf(DUMBBELL)),
            Lift("kb_press", setOf(KETTLEBELL)),
            Lift("arnold_press", setOf(DUMBBELL, BENCH)),
            Lift("smith_shoulder", setOf(MACHINE, BENCH)),
            Lift("z_press", setOf(BARBELL, RACK)),
        ),
    )

    private fun patternOf(id: String): MovementPattern = RANKED.entries.first { (_, lifts) ->
        lifts.any { it.id == id }
    }.key

    private enum class Kind { FULL_BODY, UPPER, LOWER, PUSH, PULL, LEGS }

    private fun kinds(split: V1Split, days: Int): List<Kind> = when (split) {
        V1Split.FULL_BODY -> List(days) { Kind.FULL_BODY }
        V1Split.UPPER_LOWER -> List(days) { if (it % 2 == 0) Kind.UPPER else Kind.LOWER }
        V1Split.PPL -> List(days) { listOf(Kind.PUSH, Kind.PULL, Kind.LEGS)[it % 3] }
        V1Split.PPLUL -> listOf(Kind.PUSH, Kind.PULL, Kind.LEGS, Kind.UPPER, Kind.LOWER)
    }

    fun of(days: Int, split: V1Split, anchors: V1Anchors, deadlift: V1Deadlift, equipment: Set<Equipment>): List<String> {
        val dl = when (deadlift) {
            V1Deadlift.TRAP_BAR -> "trap_dl"
            V1Deadlift.CONVENTIONAL -> "conv_dl"
            V1Deadlift.SUMO -> "sumo_dl"
        }
        val ids = when (anchors) {
            V1Anchors.PROTOTYPE -> listOf("bb_back_squat", "bb_bench", dl, "incline_db")
            V1Anchors.BIG_4 -> listOf("bb_back_squat", "bb_bench", dl, "bb_row")
            V1Anchors.FIVE_THREE_ONE -> listOf("bb_back_squat", "bb_bench", dl, "ohp")
        }
        val available = mutableListOf<String>()
        for (id in ids) {
            val ranked = RANKED.getValue(patternOf(id))
            val lift = ranked.first { it.id == id }
            val chosen = if (lift.availableWith(equipment)) id else ranked.firstOrNull {
                it.availableWith(equipment) && it.id !in available
            }?.id
            if (chosen != null) available += chosen
        }
        fun anchorFor(pattern: MovementPattern) = available.firstOrNull { patternOf(it) == pattern }
        fun pickMain(pattern: MovementPattern) = RANKED.getValue(pattern).firstOrNull { it.availableWith(equipment) }?.id
        val used = if (split == V1Split.FULL_BODY && days <= available.size) available.take(days) else available
        val occurrences = mutableMapOf<Kind, Int>()
        return kinds(split, days).mapIndexedNotNull { index, kind ->
            val occ = occurrences.getOrDefault(kind, 0)
            occurrences[kind] = occ + 1
            when (kind) {
                Kind.FULL_BODY -> if (used.isEmpty()) null else used[index % used.size]
                Kind.UPPER, Kind.PUSH -> anchorFor(H_PUSH) ?: pickMain(H_PUSH)
                Kind.PULL -> anchorFor(H_PULL) ?: pickMain(H_PULL)
                Kind.LOWER, Kind.LEGS -> {
                    val pattern = if (occ % 2 == 0) HINGE else SQUAT_BILATERAL
                    anchorFor(pattern) ?: pickMain(pattern)
                }
            }
        }.distinct()
    }
}
