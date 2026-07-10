package io.github.sjtrotter.strengthlog.transfer.health

import android.util.Log
import androidx.health.connect.client.records.Record
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import java.time.ZoneId

/**
 * Writes a completed session to Health Connect (#17, brief D7). Every failure
 * path is silent-with-log so the feature degrades invisibly (A3): if the
 * provider is absent, the write permission was never granted, the session has
 * no sets, or the insert itself throws, [publish] returns without surfacing
 * anything to the UI.
 *
 * The pure session → record mapping is [SessionRecordMapper] (exercise) and
 * [CaloriesRecordMapper] (calories); this class only adds the availability
 * check, the permission gates, and the swallow-and-log. The calorie record
 * rides alongside the exercise record in the same [insertRecords][
 * androidx.health.connect.client.HealthConnectClient.insertRecords] call when
 * both its own permission is granted and [CaloriesRecordMapper] doesn't
 * refuse the session (no real start, or an insane duration) — the exercise
 * record is written either way.
 */
class HealthConnectPublisher(
    private val clientProvider: HealthConnectClientProvider,
    private val repository: TrackerRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : SessionPublisher {

    override suspend fun publish(sessionId: Long) {
        try {
            val client = clientProvider.get() ?: return
            val granted = client.permissionController.getGrantedPermissions()
            if (HealthConnectPermissions.WRITE_EXERCISE !in granted) return

            val session = repository.session(sessionId) ?: return
            val sets = repository.sessionSets(sessionId)
            // Nothing checked off means nothing was performed — don't write an
            // empty/all-zero session into the user's shared health record.
            if (sets.none { it.done }) return

            val records = mutableListOf<Record>(SessionRecordMapper.toExerciseSession(session, sets, zone))
            if (HealthConnectPermissions.WRITE_CALORIES in granted) {
                CaloriesRecordMapper.toActiveCalories(session, zone)?.let { records += it }
            }
            client.insertRecords(records)
        } catch (t: Throwable) {
            // Degrade invisibly (A3): a workout the user already completed and
            // saved locally must never fail because of an optional export.
            Log.w(TAG, "Health Connect publish for session $sessionId failed; skipping", t)
        }
    }

    private companion object {
        const val TAG = "HealthConnectPublish"
    }
}
