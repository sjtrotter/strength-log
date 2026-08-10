package cloud.trotter.log.strength.wear.glance

private val TEST_GLANCE_COPY = DayGlanceCopy(
    noProgram = "no program", setUpOnPhone = "set up on your phone",
    dayTitle = { day, title -> "day $day · $title" }, dayOnly = { "day $it" },
    liftsSets = { lifts, sets ->
        "$lifts ${if (lifts == 1) "lift" else "lifts"} · $sets ${if (sets == 1) "set" else "sets"}"
    },
    progressSets = { done, total -> "$done / $total sets" },
    doneSets = { "done · $it ${if (it == 1) "set" else "sets"}" },
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
