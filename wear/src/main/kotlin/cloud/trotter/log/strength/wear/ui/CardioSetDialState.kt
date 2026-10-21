package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchCardioSetSpec

/** Structured cardio uses the existing dial states, rings and single disc. */
internal fun cardioSetsDial(inputs: DialInputs, run: CardioSetState): DialUiState {
    val snapshot = inputs.snapshot
    val spec = run.block.sets.getOrNull(run.position)
    val running = run.active != null
    val entering = run.entries.isNotEmpty()
    fun line(text: String, role: DialTextRole = DialTextRole.DISC_LABEL) = DiscLine(text, role, DialTone.PRIMARY)
    val rounds = run.block.sets.indices.map { index ->
        when {
            index == run.peekIndex -> RoundState.PEEKED
            run.results.getOrNull(index)?.status == "FINISHED" -> RoundState.DONE
            run.results.getOrNull(index)?.status == "SHORTENED" -> RoundState.SHORTENED
            run.results.getOrNull(index)?.status == "SKIPPED" -> RoundState.SKIPPED
            index == run.position -> RoundState.CURRENT
            else -> RoundState.UPCOMING
        }
    }
    val elapsed = run.elapsed(inputs.nowElapsedMillis, inputs.nowWallMillis, inputs.bootCount)
    val remaining = if (spec?.endKind == "TIME") (spec.endValue - elapsed).coerceAtLeast(0) else 0
    val clockArc = if (running && spec?.endKind == "TIME") remaining.toFloat() / spec.endValue else null
    var top = cardioActivityLabel(run.block.activity)
    var bottom = spec?.let { "${cardioRoleLabel(it.role)} · Set ${run.position + 1} of ${run.block.sets.size}" }
    var tap = DialTap.CARDIO_SET
    var crown = DialCrown.CARDIO_SET
    var hold: DialHold? = null
    val lines = when {
        entering -> {
            val index = run.entries.first()
            top = if (run.results[index].status == "SHORTENED") "Set stopped" else "Time’s up"
            bottom = null
            listOf(line("${cardioActivityLabel(run.block.activity)} · Set ${index + 1}", DialTextRole.BAND),
                line("Distance (m)", DialTextRole.BAND),
                line(run.draftMetres?.toString().orEmpty(), DialTextRole.NUMERAL), line("Done"))
        }
        run.confirmingEnd -> {
            top = "End block?"
            bottom = null
            listOf(line("Log completed work and skip remaining sets?", DialTextRole.BAND),
                line(if (run.selection == 0) "Keep going" else "End and log"))
        }
        run.options -> {
            top = "Set options"
            bottom = null
            listOf(line(listOf("Skip set", "End block", "Back")[run.selection]))
        }
        run.complete -> {
            top = cardioActivityLabel(run.block.activity)
            bottom = "${run.results.count { it.status == "FINISHED" }} finished · " +
                "${run.results.count { it.status == "SHORTENED" }} shortened · " +
                "${run.results.count { it.status == "SKIPPED" }} skipped"
            tap = if (run.startedAt == null || (run.queuedStamp != null && snapshot.cardioAckStamp >= run.queuedStamp))
                DialTap.DISMISS else DialTap.NONE
            crown = DialCrown.NONE
            listOf(line(if (run.startedAt == null) "Back to Today" else run.receipt(snapshot.cardioAckStamp)))
        }
        run.peekIndex != null -> {
            val index = run.peekIndex
            val result = run.results[index]
            top = "Set ${index + 1}, ${cardioRoleLabel(result.spec.role)}, ${cardioStatusLabel(result.status)}"
            bottom = null
            listOf(line(cardioPrescription(result.spec), DialTextRole.BAND),
                line(cardioSetTime(result.elapsedSeconds), DialTextRole.NUMERAL),
                line(cardioAimResult(result.spec, result.elapsedSeconds, result.metres, result.status), DialTextRole.BAND))
        }
        spec != null -> {
            hold = DialHold(if (running) DialHoldAction.STOP_CARDIO_SET else DialHoldAction.CARDIO_SET_OPTIONS,
                DiscContent(DiscStyle.FLAT, listOf(line(if (running) "Stop" else "Set options"))),
                cardioSetAttempt = run)
            if (running) crown = DialCrown.NONE
            if (running && spec.endKind == "TIME") tap = DialTap.NONE
            if (spec.endKind == "DISTANCE") {
                listOf(line(cardioPrescription(spec), DialTextRole.BAND),
                    line("Target", DialTextRole.BAND), line("${spec.endValue} m", DialTextRole.NUMERAL),
                    line("Elapsed ${cardioSetTime(elapsed)}", DialTextRole.BAND),
                    line(if (running) "Reached ${spec.endValue} m" else "Start"))
            } else {
                listOf(line(cardioPrescription(spec), DialTextRole.BAND),
                    line(cardioSetTime(if (running) remaining else spec.endValue), DialTextRole.NUMERAL),
                    line(if (running) "Stop" else "Start"))
            }
        }
        else -> emptyList()
    }
    return DialUiState(
        screen = if (running) DialScreen.CARDIO_EXECUTING else DialScreen.CARDIO_OFFER,
        accentIndex = snapshot.day.accentIndex, cycle = cycleSegments(snapshot),
        dayProgress = run.results.size.toFloat() / run.block.sets.size,
        rounds = rounds, arc = clockArc,
        topBand = BandContent(top, DialTone.ACCENT_BRIGHT),
        bottomBand = bottom?.let { BandContent(it, DialTone.SECONDARY) },
        disc = DiscContent(DiscStyle.FLAT, lines), bloom = false, tap = tap, crown = crown, hold = hold,
        roundDescriptions = run.block.sets.mapIndexed { index, set ->
            val status = run.results.getOrNull(index)?.status?.let(::cardioStatusLabel)
                ?: if (index == run.position && running) "Running" else "Ready"
            "Set ${index + 1}, ${cardioRoleLabel(set.role)}, $status"
        },
    )
}

private fun cardioRoleLabel(role: String): String = when (role) {
    "WARMUP" -> "Warm-up"
    "RECOVERY" -> "Recovery"
    "COOLDOWN" -> "Cool-down"
    else -> "Work"
}

private fun cardioStatusLabel(status: String): String = when (status) {
    "FINISHED" -> "Finished"
    "SHORTENED" -> "Shortened"
    else -> "Skipped"
}

private fun cardioMeasure(kind: String, value: Int): String = if (kind == "TIME") cardioSetTime(value) else "$value m"

private fun cardioPrescription(spec: WatchCardioSetSpec): String = cardioMeasure(spec.endKind, spec.endValue) +
    (spec.aimKind?.let { " · aim ${cardioMeasure(it, requireNotNull(spec.aimValue))}" } ?: "")

private fun cardioAimResult(spec: WatchCardioSetSpec, seconds: Int, metres: Int?, status: String): String {
    val kind = spec.aimKind ?: return cardioStatusLabel(status)
    val value = requireNotNull(spec.aimValue)
    val met = if (status == "SKIPPED") null else if (kind == "TIME") seconds <= value else metres?.let { it >= value }
    return when (met) {
        true -> "Aim met"
        false -> "Aim missed"
        null -> "Aim unmeasured"
    }
}
