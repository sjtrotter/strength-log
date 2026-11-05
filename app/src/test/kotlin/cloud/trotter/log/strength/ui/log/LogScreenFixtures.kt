package cloud.trotter.log.strength.ui.log

internal fun logSessionFixture(expanded: Boolean) = SessionListItem(
    sessionId = 7L,
    dateDisplay = "Jul 6, 2026",
    dayLetter = "A",
    dayIndex = 0,
    dayTitle = "DAY A · LOWER",
    setCount = 21,
    bodyweightDisplay = "185 lb",
    expanded = expanded,
    exerciseGroups = if (expanded) {
        listOf(SessionExerciseGroup("Back Squat", listOf(SessionSetSummary("TOP", "225×5"))))
    } else {
        null
    },
)
