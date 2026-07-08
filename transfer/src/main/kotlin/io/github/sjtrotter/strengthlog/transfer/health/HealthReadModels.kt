package io.github.sjtrotter.strengthlog.transfer.health

/**
 * Plain, androidx.health-free intermediates for the Health Connect read path
 * (#17). [HealthConnectReader] extracts these from the provider's records so the
 * formatting/decision logic ([ExternalSessionFormatter], [BodyweightPrompt])
 * stays pure and unit-testable, and so no androidx.health type crosses into
 * `:app`.
 */

/** One strength session read from another app, before display formatting. */
data class ExternalWorkout(
    val title: String?,
    val startMillis: Long,
    val endMillis: Long,
    /** The app that owns the record (its Android package). Never our own — the
     *  reader filters those out so the Log doesn't echo our own writes back. */
    val sourcePackage: String,
)

/** A display row for an external session in the Log screen. */
data class ExternalSessionRow(
    val title: String,
    val dateDisplay: String,
    val sourceLabel: String,
)

/** The bodyweight prompt's data, once [BodyweightPrompt] has decided to show it. */
data class BodyweightPromptData(
    /** Latest bodyweight from Health Connect, canonical pounds. */
    val healthConnectLb: Double,
    /** The app's current configured bodyweight, pounds. */
    val currentConfigLb: Int,
)
