package cloud.trotter.log.strength.ui.day

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.DistanceUnit
import java.util.Locale

internal data class CardioLegs(val at: String, val past: String) {
    companion object {
        fun of(set: CardioSetResult, unit: DistanceUnit, resources: Resources): CardioLegs? {
            if (set.status != CardioSetStatus.FINISHED || set.readingSource == null) return null
            val metres = set.metres ?: return null
            val endSeconds: Int
            val endMetres: Int
            val at: String
            fun distance(value: Int) = if (unit == DistanceUnit.M) unit.format(value)
                else String.format(Locale.US, "%.2f %s", unit.fromMetres(value), unit.symbol)
            when (val end = set.spec.end) {
                is Measure.Time -> {
                    endSeconds = end.seconds
                    endMetres = set.metresAtEnd ?: return null
                    if (set.elapsedSeconds <= endSeconds) return null
                    at = resources.getString(R.string.cardio_leg_at, distance(endMetres), formatCardioTime(endSeconds))
                }
                is Measure.Distance -> {
                    endMetres = end.metres
                    endSeconds = set.secondsAtEnd ?: return null
                    if (metres <= endMetres) return null
                    at = resources.getString(R.string.cardio_leg_at, formatCardioTime(endSeconds), unit.format(endMetres))
                }
            }
            if (metres < endMetres || set.elapsedSeconds < endSeconds) return null
            return CardioLegs(at, resources.getString(R.string.cardio_leg_past,
                formatCardioTime(set.elapsedSeconds - endSeconds), distance(metres - endMetres)))
        }
    }
}

@Composable
internal fun CardioLegLines(set: CardioSetResult, unit: DistanceUnit, label: String? = null) {
    CardioLegs.of(set, unit, LocalContext.current.resources)?.let {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(it.at, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(it.past, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
