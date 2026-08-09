package cloud.trotter.log.strength.transfer.health

import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The Health Connect read path (#17): other apps' strength sessions for the Log
 * screen, the latest bodyweight for the "update your GOALs?" prompt, and the
 * Log section's connection status (#158). Reads are permission-gated and every
 * provider call is wrapped so a denial or a provider error degrades to "nothing
 * to show" rather than an error — the app stays fully functional when Health
 * Connect is absent or its permissions are denied (A3).
 *
 * The pure formatting/decision logic lives in [ExternalSessionFormatter] and
 * [BodyweightPrompt]; this class only pulls raw values off the provider.
 *
 * `open` so a module that can't name an androidx.health type can subclass it as
 * a test double (see the no-provider constructor below) — the same reason
 * `TrackerRepository`'s slow reads are open.
 */
open class HealthConnectReader(
    private val clientProvider: HealthConnectClientProvider,
    private val ownPackageName: String,
) {

    /**
     * A reader with no provider: [isAvailable] is false and every read degrades
     * to empty. For callers that must supply a reader on a platform without
     * Health Connect — notably JVM/Robolectric ViewModel tests, which then need
     * no androidx.health reference of their own, neither to construct one nor to
     * subclass it.
     */
    constructor(ownPackageName: String = "unavailable") : this(HealthConnectClientProvider { null }, ownPackageName)

    /** Everything the app may ask for, for the lazy one-shot permission request. */
    val requestedPermissions: Set<String> get() = HealthConnectPermissions.ALL

    /** The Health Connect permission-request contract for the Log/Setup launcher.
     *  A plain androidx.activity type, so `:app` drives the request without ever
     *  importing an androidx.health class. */
    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** True when a Health Connect provider is installed and usable. Any provider
     *  exception (e.g. a throwing getSdkStatus) degrades to "unavailable" (A3). */
    open fun isAvailable(): Boolean = runCatching { clientProvider.get() != null }.getOrDefault(false)

    /**
     * Whether the workout-write permission is granted — the single grant that
     * decides whether [SessionPublisher] exports anything at all (#158). The Log
     * screen asks on every refresh and never caches the answer: the grant is
     * per-package, an applicationId change resets it, and the user can revoke it
     * in the Health Connect app while this process is alive.
     */
    open suspend fun publishesWorkouts(): Boolean =
        HealthConnectPermissions.WRITE_EXERCISE in grantedPermissions()

    /** Which of [requestedPermissions] are currently granted (empty on any error).
     *  Asked fresh at every gate below rather than cached: the grant is the
     *  user's to change at any moment, in another app. */
    private suspend fun grantedPermissions(): Set<String> =
        runCatching { client()?.permissionController?.getGrantedPermissions().orEmpty() }
            .getOrDefault(emptySet())

    /**
     * Other apps' STRENGTH_TRAINING sessions from the last [LOOKBACK_DAYS] days,
     * our own writes excluded. Empty when the read permission isn't granted, the
     * provider is absent, or anything at all fails — the whole body, including
     * acquiring the client, is inside the swallow so nothing reaches the UI (A3).
     */
    suspend fun externalWorkouts(): List<ExternalWorkout> = runCatching {
        val client = client() ?: return@runCatching emptyList()
        if (HealthConnectPermissions.READ_EXERCISE !in grantedPermissions()) return@runCatching emptyList()
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS)),
            ),
        )
        response.records
            .filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING }
            .filter { it.metadata.dataOrigin.packageName != ownPackageName }
            .map {
                ExternalWorkout(
                    title = it.title,
                    startMillis = it.startTime.toEpochMilli(),
                    endMillis = it.endTime.toEpochMilli(),
                    sourcePackage = it.metadata.dataOrigin.packageName,
                )
            }
    }.getOrDefault(emptyList())

    /**
     * The latest bodyweight in canonical pounds, or null when the read permission
     * isn't granted, no weight is recorded, the provider is absent, or anything
     * at all fails — the whole body, including acquiring the client, is inside
     * the swallow so nothing reaches the UI (A3).
     */
    suspend fun latestBodyweightLb(): Double? = runCatching {
        val client = client() ?: return@runCatching null
        if (HealthConnectPermissions.READ_WEIGHT !in grantedPermissions()) return@runCatching null
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.after(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS)),
                ascendingOrder = false,
                pageSize = 1,
            ),
        )
        response.records.firstOrNull()?.weight?.inPounds
    }.getOrNull()

    private fun client() = clientProvider.get()

    private companion object {
        const val LOOKBACK_DAYS = 365L
    }
}
