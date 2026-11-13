package cloud.trotter.log.strength.domain.progression

/** What a run day is for; titles are placeholders until PR 7's copy. Stored by name in [CardioRule]; new constants
 *  go at the end. */
enum class RunSession(val title: String, val quality: Boolean) {
    LADDER("Run/walk", false), EASY("Easy", false), LONG("Long", false),
    THRESHOLD("Threshold", true), VO2MAX("Intervals", true), RACE_PACE("Race pace", true),
    TEST_INTERVALS("Test intervals", true), TEMPO("Tempo", true),
    SHARPENING("Race pace", true),
}
