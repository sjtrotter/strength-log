package cloud.trotter.log.strength.data.db.entity

import androidx.room.ColumnInfo
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
    /** Plan settings serialized as PlanRecipeDto; also used to regenerate generated templates. */
    val recipeJson: String? = null,
    /** The plan's rotation position. */
    val suggestedDayId: String? = null,
    @ColumnInfo(defaultValue = "0") val onToday: Boolean = false,
    @ColumnInfo(defaultValue = "1") val offerStrengthIncreases: Boolean = true,
    @ColumnInfo(defaultValue = "0") val offerCardioIncreases: Boolean = false,
    /** The plan's optional cardio goal as PlanGoalJson. */
    val goalJson: String? = null,
    /** PlanSettingsJson; null means the defaults (see [PlanSettings]). */
    val settingsJson: String? = null,
    /** Next time default: true is Automatic, false is Ask. Existing plans migrate to Ask. */
    @ColumnInfo(defaultValue = "0") val autoApply: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lightCycleRemaining: Int = 0,
    val deloadEveryCycles: Int? = null,
    @ColumnInfo(defaultValue = "0") val cyclesCompleted: Int = 0,
) {
    companion object {
        /** The plan a pre-plans program (Room v7, or a backup older than plans)
         *  becomes: exactly one generated plan with this id. */
        const val LEGACY_ID = 1L
        const val LEGACY_NAME = "My plan"
    }
}

enum class PlanOrigin { GENERATED, AUTHORED }
