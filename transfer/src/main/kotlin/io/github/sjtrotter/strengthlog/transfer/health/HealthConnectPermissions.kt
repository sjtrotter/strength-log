package io.github.sjtrotter.strengthlog.transfer.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord

/**
 * The exact Health Connect permission strings this app asks for (#17, A3, and
 * the session-start-calories brief), requested lazily and individually from
 * the Log/Setup entry points. Four, and no more, so the rationale is honest:
 * write our own workouts, write a session-level calorie estimate alongside
 * them, read other apps' workouts to show them in the Log, read bodyweight to
 * offer a GOAL update. The app stays fully functional with any subset —
 * including none — granted.
 */
object HealthConnectPermissions {

    /** Write our completed sessions out (the publish path). */
    val WRITE_EXERCISE: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

    /** Write the session-level calorie estimate that rides alongside the
     *  exercise session ([CaloriesRecordMapper]) — its own permission because
     *  Health Connect gates every record type independently; a user can grant
     *  workout writes without granting calorie writes. */
    val WRITE_CALORIES: String = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)

    /** Read other apps' strength sessions to list them (marked external) in the Log. */
    val READ_EXERCISE: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

    /** Read the latest bodyweight to offer the "update your GOALs?" prompt. */
    val READ_WEIGHT: String = HealthPermission.getReadPermission(WeightRecord::class)

    /** Everything the app may request, for one lazy permission prompt. */
    val ALL: Set<String> = setOf(WRITE_EXERCISE, WRITE_CALORIES, READ_EXERCISE, READ_WEIGHT)
}
