package cloud.trotter.log.strength.transfer.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import cloud.trotter.log.strength.data.TrackerRepository
import java.time.ZoneId

/**
 * Writes completed sessions to Health Connect (#17, brief D7; backfill #159).
 * [publish] — the completion trigger — is silent on every failure path so the
 * feature degrades invisibly (A3): if the provider is absent, the write
 * permission was never granted, the session has no sets, or the insert itself
 * throws, it returns without surfacing anything. [publishAll] runs the same
 * per-session path but reports whether every session made it, because its
 * caller has to decide whether the one-shot backfill may be marked done.
 *
 * The pure session → record mapping is [SessionRecordMapper] (exercise) and
 * [CaloriesRecordMapper] (calories); this class only adds the availability
 * check, the permission gates, and the swallow-and-log. The calorie record
 * rides alongside the exercise record in the same [insertRecords][
 * androidx.health.connect.client.HealthConnectClient.insertRecords] call when
 * both its own permission is granted and [CaloriesRecordMapper] doesn't
 * refuse the session (no real start, an insane duration, or no recorded
 * bodyweight) — the exercise record is written either way.
 *
 * A backfill inserts one session per call rather than batching the whole
 * history into one request: the records are then identical to what live
 * publishing writes, and one unmappable session can't take the rest of the
 * history down with it.
 */
class HealthConnectPublisher(
    private val clientProvider: HealthConnectClientProvider,
    private val repository: TrackerRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : SessionPublisher {

    override suspend fun publish(sessionId: Long) {
        publishAll(listOf(sessionId))
    }

    override suspend fun publishCardio(cardioSessionId: Long) {
        publishAllCardio(listOf(cardioSessionId))
    }

    override suspend fun publishAllCardio(cardioSessionIds: List<Long>): Boolean =
        withExerciseClient { client ->
            var allPublished = true
            for (id in cardioSessionIds) {
                try {
                    repository.cardioSession(id)?.let { session ->
                        CardioRecordMapper.toExerciseSession(session, zone)?.let { client.insertRecords(listOf(it)) }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Health Connect publish for cardio session $id failed; skipping", t)
                    allPublished = false
                }
            }
            allPublished
        }

    override suspend fun publishAll(sessionIds: List<Long>): Boolean {
        return withExerciseClient { client, granted ->
            val withCalories = HealthConnectPermissions.WRITE_CALORIES in granted
            var allPublished = true
            for (sessionId in sessionIds) {
                if (!publishSession(client, sessionId, withCalories)) allPublished = false
            }
            allPublished
        }
    }

    private suspend fun withExerciseClient(block: suspend (HealthConnectClient, Set<String>) -> Boolean): Boolean {
        val client = try {
            clientProvider.get()
        } catch (t: Throwable) {
            Log.w(TAG, "Health Connect unavailable; skipping publish", t)
            null
        } ?: return false

        // Read the grant on every publish: it is per-package and the user can
        // revoke it in the Health Connect app at any time.
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (t: Throwable) {
            Log.w(TAG, "Health Connect permission check failed; skipping publish", t)
            return false
        }
        if (HealthConnectPermissions.WRITE_EXERCISE !in granted) return false
        return block(client, granted)
    }

    private suspend fun withExerciseClient(block: suspend (HealthConnectClient) -> Boolean): Boolean =
        withExerciseClient { client, _ -> block(client) }

    /**
     * True when [sessionId] was written **or** had nothing to write — a session
     * that no longer exists, or one with nothing checked off, represents no
     * performed work and must never land in the user's shared health record as
     * an all-zero entry. Only a provider refusal returns false, so a history
     * containing such a session can still complete a backfill.
     */
    private suspend fun publishSession(
        client: HealthConnectClient,
        sessionId: Long,
        withCalories: Boolean,
    ): Boolean = try {
        val session = repository.session(sessionId)
        val sets = if (session == null) emptyList() else repository.sessionSets(sessionId)
        if (session != null && sets.any { it.done }) {
            val records = mutableListOf<Record>(SessionRecordMapper.toExerciseSession(session, sets, zone))
            if (withCalories) {
                CaloriesRecordMapper.toActiveCalories(session, zone)?.let { records += it }
            }
            client.insertRecords(records)
        }
        true
    } catch (t: Throwable) {
        // Degrade invisibly (A3): a workout the user already completed and
        // saved locally must never fail because of an optional export.
        Log.w(TAG, "Health Connect publish for session $sessionId failed; skipping", t)
        false
    }

    private companion object {
        const val TAG = "HealthConnectPublish"
    }
}
