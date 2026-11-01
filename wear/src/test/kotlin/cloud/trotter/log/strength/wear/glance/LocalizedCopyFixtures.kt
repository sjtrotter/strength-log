package cloud.trotter.log.strength.wear.glance

private val TEST_GLANCE_COPY = DayGlanceCopy(
    noProgram = "No program", setUpOnPhone = "Set up on your phone",
    nothingOnToday = "Nothing on Today",
    dayTitle = { day, title -> "Day $day, $title" }, dayOnly = { "Day $it" },
    liftsSets = { lifts, sets ->
        "$lifts ${if (lifts == 1) "lift" else "lifts"}, $sets ${if (sets == 1) "set" else "sets"}"
    },
    progressSets = { done, total -> "$done of $total sets" },
    doneSets = { "Done, $it ${if (it == 1) "set" else "sets"}" },
    noProgramDescription = "No program yet",
    doneDescription = { day, total -> "Day $day done, $total sets" },
    progressDescription = { day, done, total -> "Day $day, $done of $total sets done" },
    emptyShortText = "—", ratio = { done, total -> "$done/$total" },
)

val DayGlance.titleLine: String get() = titleLine(TEST_GLANCE_COPY)
val DayGlance.setLine: String get() = setLine(TEST_GLANCE_COPY)
val DayGlance.contentDescription: String get() = contentDescription(TEST_GLANCE_COPY)
val DayGlance.shortText: String get() = shortText(TEST_GLANCE_COPY)
val DayGlance.ratioText: String get() = ratioText(TEST_GLANCE_COPY)
