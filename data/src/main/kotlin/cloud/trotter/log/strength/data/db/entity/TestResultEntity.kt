package cloud.trotter.log.strength.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.log.strength.domain.standards.TestStop

/** Written when the test ends, before Finish. [rulesJson] keeps the catalog rules as they were (#646). */
@Entity(tableName = "test_result", indices = [Index(value = ["eventId", "eventVersion"])])
data class TestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val completedAt: Long,
    val planId: Long?,
    val dayId: String?,
    val runId: String?,
    val programExerciseId: Long?,
    val setIndex: Int?,
    val exerciseId: String,
    val eventId: String,
    val eventVersion: Int,
    val windowSeconds: Int?,
    val count: Int?,
    val seconds: Int?,
    /** [TestStop] name. */
    val stop: String,
    val rulesJson: String,
)
