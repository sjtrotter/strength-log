package cloud.trotter.log.strength.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cardio_set_result", indices = [Index("cardioSessionId")])
data class CardioSetResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardioSessionId: Long,
    val position: Int,
    val specJson: String,
    val status: String,
    val elapsedSeconds: Int,
    val metres: Int? = null,
    val distanceSource: String? = null,
)
