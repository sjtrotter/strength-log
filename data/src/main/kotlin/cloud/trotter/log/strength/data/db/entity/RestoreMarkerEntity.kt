package cloud.trotter.log.strength.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The one durable fact a full restore's two stores can agree on (#172).
 *
 * A restore replaces Room and then rewrites the settings DataStore, and no
 * transaction spans the two. The recovery journal carries the settings half, but
 * replaying it is only correct if the destructive Room transaction actually
 * committed — and nothing outside Room can record that atomically with it. So
 * this row is written *inside* that transaction: its presence, carrying the same
 * [nonce] the journal staged beforehand, is the commit itself saying "the data
 * half landed". No row (or a different nonce) means it did not, and the staged
 * payload is discarded rather than replayed onto data that was never replaced.
 *
 * At most one row: [id] is pinned to [SINGLETON], so a restore overwrites the
 * previous marker instead of accumulating them. The table is empty at rest —
 * both halves clear it once the restore is complete.
 */
@Entity(tableName = "restore_marker")
data class RestoreMarkerEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val nonce: String,
) {
    companion object {
        const val SINGLETON = 0
    }
}
