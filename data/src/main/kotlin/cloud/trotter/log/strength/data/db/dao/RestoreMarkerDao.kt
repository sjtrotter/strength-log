package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity

/** The restore commit marker (#172) — see [RestoreMarkerEntity] for why it lives
 *  in Room rather than beside the journal it belongs to. */
@Dao
interface RestoreMarkerDao {

    /** Written inside the restore's own transaction, never on its own. */
    @Upsert
    suspend fun put(marker: RestoreMarkerEntity)

    /** The committed nonce, or null when no restore is outstanding. No `WHERE`:
     *  the entity pins its own primary key, so there is only ever the one row. */
    @Query("SELECT nonce FROM restore_marker LIMIT 1")
    suspend fun nonce(): String?

    @Query("DELETE FROM restore_marker")
    suspend fun clear()
}
