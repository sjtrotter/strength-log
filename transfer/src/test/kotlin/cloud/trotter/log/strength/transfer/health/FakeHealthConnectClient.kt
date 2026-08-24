package cloud.trotter.log.strength.transfer.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import kotlin.reflect.KClass

/**
 * A minimal in-memory [HealthConnectClient] for the publisher's degrade-path
 * tests — no real provider (the emulator has none, D10). It records the records
 * handed to [insertRecords], can be told which permissions are "granted", and
 * can be told to throw from the insert so the swallow-and-log path is exercised.
 * Only the surface the publisher touches is implemented; everything else throws
 * if a test ever reaches for it.
 *
 * [storedRecords] models the one provider behavior the backfill leans on (#159):
 * a record carrying a client record id settles under that id instead of landing
 * beside the record already there, so re-publishing a session can never leave
 * the user holding two copies of the same workout.
 */
class FakeHealthConnectClient(
    grantedPermissions: Set<String> = emptySet(),
    private val insertThrows: Boolean = false,
) : HealthConnectClient {

    val insertedRecords = mutableListOf<Record>()

    /** What the provider would hold afterwards, keyed by client record id. */
    val storedRecords = linkedMapOf<String, Record>()
    val deletedClientRecordIds = mutableListOf<String>()

    var insertCallCount = 0
        private set

    private val fakePermissionController = FakePermissionController(grantedPermissions)

    override val permissionController: PermissionController get() = fakePermissionController

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
        insertCallCount++
        if (insertThrows) throw RuntimeException("provider insert failed")
        insertedRecords += records
        records.forEach { record ->
            record.metadata.clientRecordId?.let { id ->
                // Health Connect's contract: one record per client id, HIGHER
                // version wins; an equal version is NOT promised to replace.
                // Model the weaker guarantee so tests can't claim more.
                val existing = storedRecords[id]
                if (existing == null ||
                    record.metadata.clientRecordVersion > existing.metadata.clientRecordVersion
                ) {
                    storedRecords[id] = record
                }
            }
        }
        return InsertRecordsResponse(records.map { "rec-${insertedRecords.size}" })
    }

    override suspend fun updateRecords(records: List<Record>) = notUsed()
    override suspend fun deleteRecords(
        recordType: KClass<out Record>,
        recordIdsList: List<String>,
        clientRecordIdsList: List<String>,
    ) {
        deletedClientRecordIds += clientRecordIdsList
        clientRecordIdsList.forEach(storedRecords::remove)
    }

    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) = notUsed()
    override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> = notUsed()
    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> = notUsed()
    override suspend fun aggregate(request: AggregateRequest): AggregationResult = notUsed()
    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> = notUsed()
    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> = notUsed()
    override suspend fun getChangesToken(request: ChangesTokenRequest): String = notUsed()
    override suspend fun getChanges(changesToken: String): ChangesResponse = notUsed()

    private fun notUsed(): Nothing = throw UnsupportedOperationException("not used by these tests")
}

/** Grants exactly the permission set it's constructed with. */
class FakePermissionController(private val granted: Set<String>) : PermissionController {
    override suspend fun getGrantedPermissions(): Set<String> = granted
    override suspend fun revokeAllPermissions() = Unit
}
