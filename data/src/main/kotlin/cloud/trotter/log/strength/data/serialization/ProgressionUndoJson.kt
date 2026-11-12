package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.data.db.entity.Slot
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
@Serializable data class UndoPlanDto(val beforeRemaining: Int, val beforeCycles: Int, val afterRemaining: Int, val afterCycles: Int)

internal object ProgressionUndoJson {
    fun encode(dto: ProgressionUndoDto): String = SetJson.encode(ProgressionUndoDto.serializer(), dto)
    fun decode(json: String?): ProgressionUndoDto? = json?.let {
        runCatching { SetJson.decode(ProgressionUndoDto.serializer(), it) }.getOrNull()
    }
}

@Serializable internal data class CardioUndoDto(val block: CardioBlockUndoDto? = null, val plan: UndoPlanDto? = null)
@Serializable internal data class CardioBlockUndoDto(val before: String?, val after: String?)

internal object CardioUndoJson {
    fun encode(dto: CardioUndoDto): String = SetJson.encode(CardioUndoDto.serializer(), dto)
    fun decode(json: String?): CardioUndoDto? = json?.let {
        runCatching { SetJson.decode(CardioUndoDto.serializer(), it) }.getOrNull()
    }
}
