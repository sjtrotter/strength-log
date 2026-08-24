package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import cloud.trotter.log.strength.domain.units.WeightUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

fun interface DeviceWeightUnitProvider {
    fun defaultUnit(): WeightUnit
}

class AndroidDeviceWeightUnitProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceWeightUnitProvider {
    override fun defaultUnit(): WeightUnit {
        val locale = context.resources.configuration.locales[0]
        return if (LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) == LocaleData.MeasurementSystem.SI) {
            WeightUnit.KG
        } else {
            WeightUnit.LB
        }
    }
}
