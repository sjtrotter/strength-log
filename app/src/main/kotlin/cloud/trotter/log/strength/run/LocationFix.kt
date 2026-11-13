package cloud.trotter.log.strength.run

import android.location.Location
import cloud.trotter.log.strength.domain.cardio.Fix

internal fun Location.toFix() = Fix(
    elapsedRealtimeNanos / 1_000_000, latitude, longitude,
    if (hasAccuracy()) accuracy.toDouble() else Double.MAX_VALUE,
)
