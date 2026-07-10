package io.github.sjtrotter.strengthlog.transfer.health

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure formatting for the external-session list (#17 read path). Sorts other
 * apps' strength sessions newest-first and gives each a clear "external" label
 * so the Log never blurs them with the user's own logged history. Android-free
 * (java.time is plain JDK), so the whole contract is JVM-unit-testable — the
 * device-only bit is [HealthConnectReader] pulling the raw [ExternalWorkout]s.
 */
object ExternalSessionFormatter {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

    fun format(workouts: List<ExternalWorkout>, zone: ZoneId = ZoneId.systemDefault()): List<ExternalSessionRow> =
        workouts
            .sortedByDescending { it.endMillis }
            .map { workout ->
                ExternalSessionRow(
                    title = workout.title?.takeIf { it.isNotBlank() } ?: "Strength session",
                    dateDisplay = DATE_FORMAT.format(Instant.ofEpochMilli(workout.endMillis).atZone(zone)),
                    sourceLabel = "External · ${sourceName(workout.sourcePackage)}",
                )
            }

    /** The bare app name from a package id ("com.google.android.apps.fitness" →
     *  "fitness"), a readable-enough attribution without a PackageManager lookup. */
    private fun sourceName(packageName: String): String =
        packageName.substringAfterLast('.').ifBlank { packageName }
}
