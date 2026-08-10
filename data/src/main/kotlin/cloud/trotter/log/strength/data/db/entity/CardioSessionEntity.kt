package cloud.trotter.log.strength.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cardio_session")
data class CardioSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: String?, // always set in C1; nullable for future ad-hoc
    val mode: String, // CardioMode.name at log time
    val hard: Boolean,
    val label: String, // the plan's label at log time ("Easy Zone 2")
    val startedAt: Long,
    val completedAt: Long,
    val seconds: Int, // ACTUAL elapsed, not the plan's total
    val stepsCompleted: Int, // how many plan steps fully elapsed
)
