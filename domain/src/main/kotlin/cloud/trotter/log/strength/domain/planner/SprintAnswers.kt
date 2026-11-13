package cloud.trotter.log.strength.domain.planner

/** The sprint events (design §2.1). Stored by name; new constants go at the end. 40 yd is 36.576 m, stored whole. */
enum class SprintEvent(val metres: Int) { YD40(37), M60(60), M100(100), M200(200), M400(400) }

/** Sprint answers (design §2.2 Your event; §2.1 About your training's hamstring strain). Read on Sprint and on Both
 *  with [BothAnswers.sprint]. Timing method and best time arrive with PR 7's timer. */
data class SprintAnswers(
    val event: SprintEvent = SprintEvent.M100,
    val sprintedRecently: Boolean = false,
    val hamstringStrain: Boolean = false,
) {
    /** Design §3.1.3 Intro: every range opens at its low end. */
    val intro: Boolean get() = !sprintedRecently || hamstringStrain
}
