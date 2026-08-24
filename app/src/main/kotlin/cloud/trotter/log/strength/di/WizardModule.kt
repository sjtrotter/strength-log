package cloud.trotter.log.strength.di

import cloud.trotter.log.strength.ui.wizard.AndroidDeviceWeightUnitProvider
import cloud.trotter.log.strength.ui.wizard.DeviceWeightUnitProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WizardModule {
    @Binds
    abstract fun deviceWeightUnitProvider(impl: AndroidDeviceWeightUnitProvider): DeviceWeightUnitProvider
}
