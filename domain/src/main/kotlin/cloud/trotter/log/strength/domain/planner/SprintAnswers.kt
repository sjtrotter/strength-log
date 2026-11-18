package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.units.DistanceUnit

/** The sprint events (design §2.1). Stored by name; new constants go at the end. 40 yd is 36.576 m, stored whole. */
enum class SprintEvent(val metres: Int, val yards: Int? = null) {
    YD40(37, yards = 40), M60(60), M100(100), M200(200), M400(400);

    companion object {
        /** The yard length a sprint event of [metres] is run in; null for metric events. */
        fun yardsFor(metres: Int): Int? = entries.firstOrNull { it.metres == metres }?.yards

        /** Sprint surfaces only: 40 yd is stored as 37 m (revision 31). Typed distances keep their unit. */
        fun label(metres: Int, unit: DistanceUnit): String = yardsFor(metres)?.let { "$it yd" } ?: unit.format(metres)
    }
}

/** Sprint answers (design §2.2 Your event; §2.1 About your training's hamstring strain). Read on Sprint and on Both
 *  with [BothAnswers.sprint]. Timing method and best time arrive with PR 7's timer. */
data class SprintAnswers(
    val event: SprintEvent = SprintEvent.M100,
    val sprintedRecently: Boolean = false,
    val hamstringStrain: Boolean = false,
    /** 40 or older (owner Q2, practice cutoff); PR 7 asks it on sprint setup. */
    val fortyOrOlder: Boolean = false,
) {
    /** Design §3.1.3 Intro: every range opens at its low end. */
    val intro: Boolean get() = !sprintedRecently || hamstringStrain
}
