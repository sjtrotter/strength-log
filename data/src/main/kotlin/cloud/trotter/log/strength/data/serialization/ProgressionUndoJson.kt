package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.LadderHold
import cloud.trotter.log.strength.domain.progression.RunSession
import kotlinx.serialization.Serializable

/** What one Finish wrote. Never included in a backup. */
@Serializable internal data class ProgressionUndoDto(val logs: List<UndoLogDto> = emptyList(), val rules: List<UndoRuleDto> = emptyList(),
                                                        val plan: UndoPlanDto? = null, val roughLight: UndoPlanDto? = null)

/** Null [afterSetsJson] represents a deletion; null [before] represents an inserted row. */
@Serializable internal data class UndoLogDto(val programExerciseId: Long, val slot: String, val before: UndoRowDto? = null,
                                             val afterSetsJson: String? = null)
@Serializable internal data class UndoRowDto(val setsJson: String, val updatedAt: Long)
/** [slot] names the column: `ruleJson` for main, `partnerRuleJson` for ss. A record from before this field reads as main. */
@Serializable internal data class UndoRuleDto(val programExerciseId: Long, val beforeJson: String, val afterJson: String,
                                              val slot: String = Slot.MAIN)

/** The plan's light counters before and after the Finish. */
@Serializable data class UndoPlanDto(val beforeRemaining: Int, val beforeCycles: Int, val afterRemaining: Int, val afterCycles: Int,
                                     val replan: UndoReplanDto? = null, val replanOffer: UndoReplanOfferDto? = null)

@Serializable data class UndoReplanOfferDto(val phase: String?)

@Serializable data class UndoReplanDto(val recipeBefore: String, val recipeAfter: String, val days: List<UndoReplanDayDto>)
@Serializable data class UndoReplanDayDto(val dayId: String, val before: UndoDayColumnsDto?, val after: UndoDayColumnsDto)
@Serializable data class UndoDayColumnsDto(val kind: String, val title: String, val position: Int,
                                         val block: String?, val rule: String?, val working: String?)

internal object ProgressionUndoJson {
    fun encode(dto: ProgressionUndoDto): String = SetJson.encode(ProgressionUndoDto.serializer(), dto)
    fun decode(json: String?): ProgressionUndoDto? = json?.let {
        runCatching { SetJson.decode(ProgressionUndoDto.serializer(), it) }.getOrNull()
    }
}

@Serializable internal data class CardioUndoDto(val block: CardioBlockUndoDto? = null, val plan: UndoPlanDto? = null,
                                               val rule: CardioRuleUndoDto? = null, val days: List<CardioDayUndoDto> = emptyList(),
                                               val result: CardioResultDto? = null)
@Serializable internal data class CardioDayUndoDto(val dayId: String, val blockBefore: String?, val blockAfter: String?,
                                                 val workingBefore: String? = null, val ruleBefore: String?, val ruleAfter: String?)
@Serializable internal data class CardioRuleUndoDto(val before: String, val after: String, val cycle: Int)
@Serializable internal data class CardioBlockUndoDto(val before: String?, val after: String?)

internal object CardioUndoJson {
    fun encode(dto: CardioUndoDto): String = SetJson.encode(CardioUndoDto.serializer(), dto)
    fun decode(json: String?): CardioUndoDto? = json?.let {
        runCatching { SetJson.decode(CardioUndoDto.serializer(), it) }.getOrNull()
    }
}

/** One ruled run Finish's judged change and block, held state, ladder credit and explicit chip choice. */
@Serializable internal data class CardioResultDto(
    val change: String, val level: Int? = null, val to: String? = null, val capped: Boolean = false,
    val from: Int? = null, val hold: String? = null, val credit: Boolean = false, val held: Boolean = false,
    val choice: Boolean? = null, val block: String? = null,
)

/** One ruled run Finish's result; null without a readable record (rule-less, sprint, cleared). */
data class RunResult(val change: CardioChange, val from: Int?, val hold: LadderHold?, val credit: Boolean,
                     val held: Boolean, val block: CardioBlock?, val replanned: Boolean = false)

fun CardioSessionEntity.runResult(): RunResult? = runCatching {
    val undo = CardioUndoJson.decode(progressionUndoJson) ?: return@runCatching null
    val record = undo.result ?: return@runCatching null
    val block = CardioBlockJson.decode(record.block)
    if (record.block != null && block == null) return@runCatching null
    val change = when (record.change) {
        "REPEAT" -> CardioChange.Repeat
        "INCREASE" -> {
            val end = block?.sets?.firstOrNull { it.role == CardioRole.WORK }?.end ?: return@runCatching null
            CardioChange.Increase(if (end is Measure.Distance) ProgressMeasure.DISTANCE else ProgressMeasure.TIME, record.capped)
        }
        "LEVEL_TO" -> CardioChange.LevelTo(requireNotNull(record.level))
        "LADDER_DONE" -> CardioChange.LadderDone
        "STEP" -> CardioChange.Step
        "ALTERNATE" -> CardioChange.Alternate(RunSession.valueOf(requireNotNull(record.to)))
        "RESTART" -> CardioChange.Restart
        else -> return@runCatching null
    }
    RunResult(change, record.from, record.hold?.let(LadderHold::valueOf), record.credit, record.held, block,
        undo.plan?.replan != null)
}.getOrNull()
