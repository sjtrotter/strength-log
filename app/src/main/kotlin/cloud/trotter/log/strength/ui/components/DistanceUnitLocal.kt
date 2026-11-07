package cloud.trotter.log.strength.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.units.DistanceUnit

/** The lifter's distance setting; read through [distanceUnitFor] so track and rower stay in metres. */
val LocalDistanceUnit = staticCompositionLocalOf { DistanceUnit.MI }

@Composable
fun distanceUnitFor(activity: CardioActivity?): DistanceUnit =
    DistanceUnit.forActivity(LocalDistanceUnit.current, activity)
