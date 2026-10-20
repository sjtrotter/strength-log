package cloud.trotter.log.strength.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plan")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val name: String,
    /** [PlanOrigin.name]. */
    val origin: String,
    val position: Int,
    val createdAt: Long,
    val archived: Boolean = false,
) {
    companion object {
        /** The plan a pre-plans program (Room v7, or a backup older than plans)
         *  becomes: exactly one generated plan with this id. */
        const val LEGACY_ID = 1L
        const val LEGACY_NAME = "My plan"
    }
}

enum class PlanOrigin { GENERATED, AUTHORED }
