package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind

internal object CardioFirstPlanner {
    private enum class Session(val title: String) { EASY("Easy"), INTERVALS("Intervals"), LONG("Long") }

    fun program(answers: WizardAnswers, cardio: CardioFirst): Program {
        val sessions = when (cardio.sessions) {
            1 -> listOf(Session.EASY)
            2 -> listOf(Session.EASY, Session.LONG)
            3 -> listOf(Session.EASY, Session.INTERVALS, Session.LONG)
            4 -> listOf(Session.EASY, Session.INTERVALS, Session.EASY, Session.LONG)
            5 -> listOf(Session.EASY, Session.INTERVALS, Session.EASY, Session.EASY, Session.LONG)
            else -> listOf(Session.EASY, Session.INTERVALS, Session.EASY, Session.INTERVALS, Session.EASY, Session.LONG)
        }
        val lifts = if (cardio.liftDays == 0) emptyList() else ProgramGenerator.generate(answers.copy(
            cardioFirst = null,
            cardio = CardioPrefs(mode = CardioMode.NONE, placement = CardioPlacement.NONE),
        )).days
        val days = buildList {
            sessions.forEachIndexed { index, session ->
                val block = when (session) {
                    Session.INTERVALS -> CardioBlocks.fromLegacy(CardioSuggestion("", "", hard = true), fiveK = true, cardio.activity)
                    Session.EASY -> CardioBlock(cardio.activity, listOf(CardioSetSpec(CardioRole.WORK, cardio.comfortable)))
                    Session.LONG -> CardioBlock(cardio.activity, listOf(CardioSetSpec(CardioRole.WORK, longMeasure(cardio.comfortable))))
                }
                add(ProgramDay("", session.title, "", emptyList(), ProgramDayKind.STRENGTH, block))
                lifts.getOrNull(index)?.let { add(it) }
            }
            addAll(lifts.drop(sessions.size))
        }
        val firstRestAfter = days.indexOfFirst { it.title == Session.LONG.title }
            .takeIf { it >= 0 } ?: days.lastIndex
        val secondRestAfter = days.indexOfFirst { it.title == Session.INTERVALS.title }
            .takeIf { it >= 0 } ?: days.indexOfFirst { it.title == Session.EASY.title }
        val withRest = buildList {
            days.forEachIndexed { index, day ->
                add(day)
                if (cardio.restDays >= 1 && index == firstRestAfter) {
                    add(ProgramDay("", "Rest", "", emptyList(), kind = ProgramDayKind.REST))
                }
                if (cardio.restDays == 2 && index == secondRestAfter) {
                    add(ProgramDay("", "Rest", "", emptyList(), kind = ProgramDayKind.REST))
                }
            }
        }
        return Program(withRest.mapIndexed { index, day -> day.copy(id = ('A' + index).toString()) })
    }

    private fun longMeasure(comfortable: Measure): Measure = when (comfortable) {
        is Measure.Time -> Measure.Time((Math.round(comfortable.seconds * 1.5 / 60.0).toInt() * 60).coerceIn(60, 86_400))
        is Measure.Distance -> Measure.Distance(Math.round(comfortable.metres * 1.5).toInt().coerceAtMost(200_000))
    }
}
