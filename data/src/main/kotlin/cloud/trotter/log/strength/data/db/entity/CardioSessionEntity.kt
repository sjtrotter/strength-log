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
    /** The plan this session ran under, or null for history recorded before plans
     *  existed (earlier wizard runs deleted programs whose days shared these
     *  letters, so their plan is unknowable) and for CSV imports. */
    val planId: Long? = null,
    /** [PlanEntity.name] copied at completion, so history survives a rename or
     *  archive — the same rule as `exerciseName` on set rows. */
    val planName: String? = null,
    /** CardioActivity.name when recorded by a set-aware caller; unknown for legacy summaries. */
    val activity: String? = null,
)
