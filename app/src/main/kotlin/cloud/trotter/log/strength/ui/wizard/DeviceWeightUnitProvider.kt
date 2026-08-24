package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.os.Build
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
        val metric = if (Build.VERSION.SDK_INT >= 28) {
            LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) == LocaleData.MeasurementSystem.SI
        } else {
            // ICU's measurement data arrived in API 28; below it, the three
            // customary-unit countries are the whole exception list.
            locale.country !in setOf("US", "LR", "MM")
        }
        return if (metric) WeightUnit.KG else WeightUnit.LB
    }
}
