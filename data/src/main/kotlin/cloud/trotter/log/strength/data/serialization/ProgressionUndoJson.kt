package cloud.trotter.log.strength.data.serialization

import kotlinx.serialization.Serializable

/** What one Finish wrote. Never included in a backup. */
@Serializable internal data class ProgressionUndoDto(val logs: List<UndoLogDto> = emptyList(), val rules: List<UndoRuleDto> = emptyList())

/** Null [afterSetsJson] represents a deletion; null [before] represents an inserted row. */
@Serializable internal data class UndoLogDto(val programExerciseId: Long, val slot: String, val before: UndoRowDto? = null,
                                             val afterSetsJson: String? = null)
@Serializable internal data class UndoRowDto(val setsJson: String, val updatedAt: Long)
@Serializable internal data class UndoRuleDto(val programExerciseId: Long, val beforeJson: String, val afterJson: String)

internal object ProgressionUndoJson {
    fun encode(dto: ProgressionUndoDto): String = SetJson.encode(ProgressionUndoDto.serializer(), dto)
    fun decode(json: String?): ProgressionUndoDto? = json?.let {
        runCatching { SetJson.decode(ProgressionUndoDto.serializer(), it) }.getOrNull()
    }
}
