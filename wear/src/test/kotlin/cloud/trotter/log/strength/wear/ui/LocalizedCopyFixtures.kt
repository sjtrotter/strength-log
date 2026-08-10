package cloud.trotter.log.strength.wear.ui

private val TEST_DIAL_COPY = DialCopy(
    continueText = "continue", start = "start", day = { "day $it" },
    setOf = { current, total -> "set $current of $total" },
    exerciseKind = { exercise, kind -> "$exercise · $kind" },
    waitingOnPhone = "waiting on phone", swapping = "swapping", useThis = "use this",
    alternateOf = { current, total -> "$current of $total alternates" },
    tapToLog = "tap to log", goal = { "goal $it" }, rest = "rest",
    tapToSkip = "tap to skip", next = { "next $it" }, rested = { "✓ rested $it" },
    done = "done", minutes = { "$it min" }, volume = { value, unit -> "$value $unit" },
    sets = { "$it sets" }, undo = "undo", set = { "set $it" }, took = { "took $it" },
    thenPartner = { name, summary -> "then $name $summary" },
    releaseToReturn = "↺ release to return", queued = { "$it queued" },
    noProgram = "no program", setUpOnPhone = "set up on your phone",
    rampLabel = { "R$it" }, topLabel = "TOP", backoffLabel = "B/O",
    cardio = "cardio", cardioStart = "start finisher",
    cardioHoldToStop = "hold to stop", cardioStop = "stop + log",
)

fun dialUiState(inputs: DialInputs): DialUiState = dialUiState(inputs, TEST_DIAL_COPY)

fun cloud.trotter.log.strength.domain.sync.WatchExercise.toStreamUiState(
    unit: cloud.trotter.log.strength.domain.units.WeightUnit,
    dayId: String,
    accentIndex: Int,
): ExerciseStreamUiState = toStreamUiState(unit, dayId, accentIndex, TEST_DIAL_COPY)

fun ambientDialState(
    snapshot: cloud.trotter.log.strength.domain.sync.WatchSnapshot,
    timeText: String,
    restRemainingSeconds: Int? = null,
): AmbientDialState = ambientDialState(
    snapshot, timeText, restRemainingSeconds,
    dayText = { "day $it" },
    dayProgressText = { day, done, total -> "day $day · $done/$total" },
    restingText = "resting",
)
