package cloud.trotter.log.strength.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A cardio session's saved route (Save route on). No foreign key, like `cardio_set_result`: deletes are explicit. */
@Entity(tableName = "cardio_route")
data class CardioRouteEntity(@PrimaryKey val cardioSessionId: Long, val pointsJson: String)
