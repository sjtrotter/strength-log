package cloud.trotter.log.strength.domain.progression

/** The cardio Finish word (design §3.1.2), a talk-test band the user never sees as a number: Easy speaks in full
 *  sentences, Good in short phrases, Hard in a few words. Null at Finish means not reported. Stored by name; new
 *  constants go at the end. */
enum class CardioEffort { EASY, GOOD, HARD }
