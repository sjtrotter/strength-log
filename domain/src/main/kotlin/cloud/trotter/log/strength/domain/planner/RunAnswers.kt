package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.progression.RecentMaxima

enum class RunExperience { NEW, SOME, RACING }

/** An event's distance. Stored by name; new constants go at the end. */
enum class RunEvent(val metres: Int) {
    M800(800), M1500(1500), MILE(1609), M3000(3000), K5(5000), K10(10_000), HALF(21_097), MARATHON(42_195),
    K50(50_000), MI50(80_467), K100(100_000), MI100(160_934),
}

/** Run sub-goals (design §2.1). [events] lists the Your event choices, the default first; [phases] the readiness
 *  path (§3.1.2), [eventPhase] the phase that readies the event. */
enum class RunGoal(val events: List<RunEvent>, val phases: List<RunPhase>, val eventPhase: RunPhase?) {
    FIRST_5K(listOf(RunEvent.K5), listOf(RunPhase.LADDER, RunPhase.BASE), RunPhase.LADDER),
    FIVE_TO_TEN_K(listOf(RunEvent.K5, RunEvent.K10), listOf(RunPhase.LADDER, RunPhase.BASE), RunPhase.BASE),
    HALF_MARATHON(listOf(RunEvent.HALF), listOf(RunPhase.LADDER, RunPhase.BASE, RunPhase.HALF_BUILD), RunPhase.HALF_BUILD),
    MARATHON(listOf(RunEvent.MARATHON),
        listOf(RunPhase.LADDER, RunPhase.BASE, RunPhase.HALF_BUILD, RunPhase.MARATHON_BUILD), RunPhase.MARATHON_BUILD),
    ULTRA(listOf(RunEvent.K50, RunEvent.MI50, RunEvent.K100, RunEvent.MI100),
        listOf(RunPhase.LADDER, RunPhase.BASE, RunPhase.HALF_BUILD, RunPhase.MARATHON_BUILD, RunPhase.ULTRA_BUILD), RunPhase.ULTRA_BUILD),
    MIDDLE_DISTANCE(listOf(RunEvent.M1500, RunEvent.M800, RunEvent.MILE, RunEvent.M3000), emptyList(), null),
    FITNESS_TEST(emptyList(), emptyList(), null),
    CARDIO_FITNESS(emptyList(), emptyList(), null),
}

/** The field tests (design §2.2). [metres] is the timed tests' distance. */
enum class FitnessTest(val metres: Int?) { TWO_MILE(3_219), ONE_AND_HALF_MILE(2_414), COOPER(null), PACER(null), HAMR(null) }

data class RecentRace(val metres: Int, val seconds: Int) {
    init { require(metres in 1..200_000 && seconds in 1..86_400) }
}

/** Run and Both answers (design §2.2 Your event, Where you are). */
data class RunAnswers(
    val goal: RunGoal = RunGoal.CARDIO_FITNESS,
    val experience: RunExperience = RunExperience.NEW,
    val event: RunEvent? = null,
    val test: FitnessTest = FitnessTest.TWO_MILE,
    val dateEpochDay: Long? = null,
    val goalSeconds: Int? = null,
    val testScore: Int? = null,
    val recent: RecentRace? = null,
    val longest: Measure? = null,
    val marathonBestSeconds: Int? = null,
    val activity: CardioActivity = CardioActivity.ROAD_RUN,
    val runningInjury: Boolean = false,
    val strengthBlocks: Int = 2,
) {
    init {
        require(event == null || event in goal.events)
        require(goalSeconds == null || goalSeconds in 1..86_400)
        require(testScore == null || testScore >= 1)
        require(PlanGoalDate.isValid(dateEpochDay))
        require(when (longest) {
            is Measure.Time -> longest.seconds >= 1
            is Measure.Distance -> longest.metres >= 1
            null -> true
        })
        require(marathonBestSeconds == null || goal == RunGoal.ULTRA)
        require(strengthBlocks in 0..2)
        require(goal == RunGoal.CARDIO_FITNESS || CardioFamily.of(activity) == CardioFamily.RUN)
    }

    val resolvedEvent: RunEvent? get() = event ?: goal.events.firstOrNull()

    /** This answer stands in until there's a log; PR 7 also passes it as CardioHistory.seed. */
    fun seed(): RecentMaxima? = when (val longest = longest) {
        is Measure.Distance -> RecentMaxima(longest.metres, null)
        is Measure.Time -> RecentMaxima(null, longest.seconds)
        null -> null
    }
}
