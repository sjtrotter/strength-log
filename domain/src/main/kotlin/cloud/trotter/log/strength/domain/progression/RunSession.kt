package cloud.trotter.log.strength.domain.progression

/** What a run or sprint day is for; titles are placeholders until PR 7's copy. Stored by name in [CardioRule];
 *  new constants go at the end. */
enum class RunSession(val title: String) {
    LADDER("Run/walk"), EASY("Easy"), LONG("Long"),
    THRESHOLD("Threshold"), VO2MAX("Intervals"), RACE_PACE("Race pace"),
    TEST_INTERVALS("Test intervals"), TEMPO("Tempo"),
    SHARPENING("Race pace"),
    ACCELERATION("Acceleration"), MAX_VELOCITY("Max velocity"),
    SPRINT_ENDURANCE("Sprint endurance"), SPRINT_TEMPO("Tempo");

    /** A sprint branch day (design §3.1.3): stepped by [SprintRules], exempt from the run cap. */
    val sprint: Boolean get() = this == ACCELERATION || this == MAX_VELOCITY ||
        this == SPRINT_ENDURANCE || this == SPRINT_TEMPO

    /** §3.1.2's quality sessions: a range of their own, not a sprint day. */
    val quality: Boolean get() = RunRange.of(this) != null && !sprint
}
