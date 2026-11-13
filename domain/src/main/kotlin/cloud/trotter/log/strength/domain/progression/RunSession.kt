package cloud.trotter.log.strength.domain.progression

/** What a run day is for; titles are placeholders until PR 7's copy. Stored by name in [CardioRule]; new constants
 *  go at the end. */
enum class RunSession(val title: String) {
    LADDER("Run/walk"), EASY("Easy"), LONG("Long"),
    THRESHOLD("Threshold"), VO2MAX("Intervals"), RACE_PACE("Race pace"),
    TEST_INTERVALS("Test intervals"), TEMPO("Tempo"),
    SHARPENING("Race pace");

    val quality: Boolean get() = RunRange.of(this) != null
}
